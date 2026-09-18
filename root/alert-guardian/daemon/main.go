package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"

	mqtt "github.com/eclipse/paho.mqtt.golang"
	"github.com/fsnotify/fsnotify"
)

const (
	packageName   = "dev.chanooh.alert"
	componentName = "dev.chanooh.alert/.transport.RootIngressService"
	ingressAction = "dev.chanooh.alert.action.ROOT_DRAIN"
	// One MQTT ping a minute is negligible on Wi-Fi/mobile data, but bounds a
	// silent radio/NAT failure to about 75 seconds.  Five minutes allowed a
	// dead TCP path to look connected for far too long on HyperOS overnight.
	keepAlive         = 60 * time.Second
	pingTimeout       = 15 * time.Second
	initialBackoff    = 5 * time.Second
	maximumBackoff    = 5 * time.Minute
	ingressDrainGrace = 5 * time.Second
	ingressDrainPoll  = 250 * time.Millisecond
)

type config struct {
	Schema     int    `json:"schema"`
	Enabled    bool   `json:"enabled"`
	Generation int64  `json:"generation"`
	Broker     string `json:"broker"`
	Username   string `json:"username"`
	Password   string `json:"password"`
	DeviceID   string `json:"deviceId"`
}

type status struct {
	Schema          int    `json:"schema"`
	Generation      int64  `json:"generation"`
	State           string `json:"state"`
	LastConnectedAt int64  `json:"lastConnectedAt,omitempty"`
	LastEventAt     int64  `json:"lastEventAt,omitempty"`
	PendingInbox    int    `json:"pendingInbox"`
	RejectedInbox   int    `json:"rejectedInbox"`
	LastError       string `json:"lastError,omitempty"`
}

type daemon struct {
	rootDir string
	config  config

	mu        sync.Mutex
	statusMu  sync.Mutex
	lastEvent int64
	wakeLoop  bool
}

func main() {
	if len(os.Args) != 2 {
		fmt.Fprintln(os.Stderr, "usage: alert-root-mqtt <app-files-dir>")
		os.Exit(2)
	}
	d := &daemon{rootDir: filepath.Join(os.Args[1], "root_transport")}
	if err := d.run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

// run only blocks on MQTT callbacks or inotify configuration events. It has
// no periodic health poll while the inbox is empty and the configuration is stable.
func (d *daemon) run() error {
	backoff := initialBackoff
	for {
		cfg, err := readConfig(filepath.Join(d.rootDir, "config.json"))
		if err != nil || !cfg.Enabled {
			d.setConfig(cfg)
			d.writeStatus("disabled", err, 0)
			waitForConfigChange(filepath.Join(d.rootDir, "config.json"))
			continue
		}
		d.setConfig(cfg)
		connected, err := d.runClient(cfg)
		if err != nil {
			d.writeStatus("backoff", err, 0)
			// A socket that reached SUBACK before later dropping gets an
			// immediate fresh attempt; only consecutive initial failures back
			// off exponentially.
			if connected {
				backoff = initialBackoff
			}
			waitForConfigChangeOrTimeout(filepath.Join(d.rootDir, "config.json"), backoff)
			if !connected {
				backoff *= 2
				if backoff > maximumBackoff {
					backoff = maximumBackoff
				}
			}
		} else {
			// Configuration changed.  A newly supplied endpoint should be tried
			// promptly rather than inheriting an old network-failure backoff.
			backoff = initialBackoff
		}
	}
}

// runClient owns exactly one MQTT socket.  Paho automatic reconnect is
// intentionally disabled: it can remain in an opaque reconnect state after a
// radio/NAT failure, while this outer state machine records every failure and
// recreates the socket with bounded exponential backoff.
func (d *daemon) runClient(cfg config) (bool, error) {
	changed := make(chan struct{}, 1)
	connectionLost := make(chan error, 1)
	options := mqtt.NewClientOptions().
		AddBroker(cfg.Broker).
		SetClientID("alert-root-" + cfg.DeviceID).
		SetCleanSession(false).
		SetKeepAlive(keepAlive).
		SetPingTimeout(pingTimeout).
		SetAutoReconnect(false).
		// Initial connections are driven by the outer state machine instead of
		// Paho's unbounded ConnectRetry. A configuration or TLS failure must be
		// surfaced as a backoff error instead of looking "connecting" forever.
		SetConnectRetry(false).
		SetConnectTimeout(10 * time.Second).
		SetOrderMatters(false)
	if cfg.Username != "" {
		options.SetUsername(cfg.Username).SetPassword(cfg.Password)
	}
	options.OnConnectionLost = func(_ mqtt.Client, err error) {
		d.writeStatus("backoff", err, 0)
		select {
		case connectionLost <- err:
		default:
		}
	}
	client := mqtt.NewClient(options)
	d.writeStatus("connecting", nil, 0)
	if token := client.Connect(); !token.WaitTimeout(20 * time.Second) {
		client.Disconnect(250)
		return false, errors.New("connect timeout")
	} else if token.Error() != nil {
		client.Disconnect(250)
		return false, token.Error()
	}
	if token := client.Subscribe("alert/"+cfg.DeviceID+"/events", 1, func(_ mqtt.Client, message mqtt.Message) {
		d.receive(message.Payload())
	}); !token.WaitTimeout(20 * time.Second) {
		client.Disconnect(250)
		return false, errors.New("subscribe timeout")
	} else if token.Error() != nil {
		client.Disconnect(250)
		return false, token.Error()
	}
	d.writeStatus("subscribed", nil, time.Now().UnixMilli())

	// Reconfiguration is detected by file metadata rather than a timer. A
	// running Paho client owns its own socket/reconnect loop in the meantime.
	go func() {
		waitForConfigChange(filepath.Join(d.rootDir, "config.json"))
		changed <- struct{}{}
	}()
	select {
	case <-changed:
		client.Disconnect(250)
		return true, nil
	case err := <-connectionLost:
		client.Disconnect(250)
		return true, err
	}
}

func (d *daemon) setConfig(cfg config) {
	d.mu.Lock()
	d.config = cfg
	d.mu.Unlock()
}

func (d *daemon) receive(payload []byte) {
	if len(payload) == 0 || len(payload) > 32*1024 {
		d.writeStatus("subscribed", errors.New("invalid event payload size"), 0)
		return
	}
	if err := d.writeInbox(payload); err != nil {
		d.writeStatus("subscribed", err, 0)
		return
	}
	d.mu.Lock()
	d.lastEvent = time.Now().UnixMilli()
	d.mu.Unlock()
	d.writeStatus("subscribed", nil, 0)
	d.ensureWakeLoop()
}

func (d *daemon) writeInbox(payload []byte) error {
	inbox := filepath.Join(d.rootDir, "inbox")
	if err := os.MkdirAll(inbox, 0o700); err != nil {
		return err
	}
	name, err := randomName()
	if err != nil {
		return err
	}
	temporary := filepath.Join(inbox, "."+name+".tmp")
	final := filepath.Join(inbox, name+".json")
	// The file remains under Alert's private directory. KernelSU can create
	// files there on HyperOS, but SELinux can reject chown even after creation.
	// A readable leaf is safe because the app-data parent is still private.
	if err := os.WriteFile(temporary, payload, 0o644); err != nil {
		return err
	}
	if err := os.Rename(temporary, final); err != nil {
		_ = os.Remove(temporary)
		return err
	}
	return nil
}

func (d *daemon) ensureWakeLoop() {
	d.mu.Lock()
	if d.wakeLoop {
		d.mu.Unlock()
		return
	}
	d.wakeLoop = true
	d.mu.Unlock()
	go func() {
		defer func() {
			d.mu.Lock()
			d.wakeLoop = false
			d.mu.Unlock()
		}()
		for {
			inbox := filepath.Join(d.rootDir, "inbox")
			if pendingInbox(inbox) == 0 {
				d.writeStatus("subscribed", nil, 0)
				return
			}
			if err := wakeIngress(); err != nil {
				d.writeStatus("subscribed", err, 0)
			} else if waitForInboxDrain(inbox, ingressDrainGrace) {
				// The App drained durable work. Refresh immediately rather than
				// leaving WebUI's pending count stale for a full retry minute.
				d.writeStatus("subscribed", nil, 0)
				return
			}
			time.Sleep(time.Minute) // only while durable work remains undrained
		}
	}()
}

func wakeIngress() error {
	output, err := exec.Command("am", "start-foreground-service", "--user", "0", "-a", ingressAction, "-n", componentName).CombinedOutput()
	if err == nil {
		return nil
	}
	detail := strings.Join(strings.Fields(string(output)), " ")
	if len(detail) > 120 {
		detail = detail[:120]
	}
	if detail == "" {
		return fmt.Errorf("root ingress start failed: %w", err)
	}
	return fmt.Errorf("root ingress start failed: %w: %s", err, detail)
}

func waitForInboxDrain(path string, grace time.Duration) bool {
	deadline := time.Now().Add(grace)
	for pendingInbox(path) > 0 && time.Now().Before(deadline) {
		time.Sleep(ingressDrainPoll)
	}
	return pendingInbox(path) == 0
}

func (d *daemon) writeStatus(state string, cause error, connectedAt int64) {
	d.statusMu.Lock()
	defer d.statusMu.Unlock()
	d.mu.Lock()
	cfg, lastEvent := d.config, d.lastEvent
	d.mu.Unlock()
	previous := readStatus(filepath.Join(d.rootDir, "status.json"))
	if connectedAt == 0 {
		connectedAt = previous.LastConnectedAt
	}
	message := ""
	if cause != nil {
		message = redactError(cause.Error(), cfg)
	}
	value := status{
		Schema: 1, Generation: cfg.Generation, State: state, LastConnectedAt: connectedAt,
		LastEventAt: lastEvent, PendingInbox: pendingInbox(filepath.Join(d.rootDir, "inbox")),
		RejectedInbox: pendingInbox(filepath.Join(d.rootDir, "rejected")), LastError: message,
	}
	encoded, _ := json.Marshal(value)
	path := filepath.Join(d.rootDir, "status.json")
	if err := atomicWriteForApp(path, encoded); err != nil {
		fmt.Fprintf(os.Stderr, "write root status: %v\n", err)
	}
}

func atomicWriteForApp(path string, content []byte) error {
	temporary := path + ".tmp"
	if err := os.WriteFile(temporary, content, 0o644); err != nil {
		return err
	}
	if err := os.Rename(temporary, path); err != nil {
		_ = os.Remove(temporary)
		return err
	}
	return nil
}

func readConfig(path string) (config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return config{}, nil
		}
		return config{}, err
	}
	var cfg config
	if err := json.Unmarshal(data, &cfg); err != nil {
		return config{}, errors.New("invalid root transport config")
	}
	if !cfg.Enabled {
		return cfg, nil
	}
	if cfg.Schema != 1 || cfg.DeviceID == "" {
		return config{}, errors.New("invalid root transport config")
	}
	parsed, err := url.Parse(cfg.Broker)
	if err != nil || (parsed.Scheme != "mqtt" && parsed.Scheme != "mqtts") || parsed.Host == "" {
		return config{}, errors.New("invalid MQTT broker")
	}
	return cfg, nil
}

func readStatus(path string) status {
	data, err := os.ReadFile(path)
	if err != nil {
		return status{}
	}
	var value status
	_ = json.Unmarshal(data, &value)
	return value
}

func pendingInbox(path string) int {
	entries, err := os.ReadDir(path)
	if err != nil {
		return 0
	}
	count := 0
	for _, entry := range entries {
		if !entry.IsDir() && strings.HasSuffix(entry.Name(), ".json") {
			count++
		}
	}
	return count
}

func randomName() (string, error) {
	bytes := make([]byte, 8)
	if _, err := rand.Read(bytes); err != nil {
		return "", err
	}
	return fmt.Sprintf("evt-%d-%s", time.Now().UnixNano(), hex.EncodeToString(bytes)), nil
}

func redactError(message string, cfg config) string {
	message = strings.ReplaceAll(message, cfg.Password, "[redacted]")
	message = strings.ReplaceAll(message, cfg.Username, "[redacted]")
	message = strings.ReplaceAll(message, cfg.Broker, "[broker]")
	message = strings.Join(strings.Fields(message), " ")
	if len(message) > 120 {
		return message[:120]
	}
	return message
}

func waitForConfigChange(path string) {
	waitForConfigChangeOrTimeout(path, 0)
}

func waitForConfigChangeOrTimeout(path string, timeout time.Duration) {
	var timeoutC <-chan time.Time
	if timeout > 0 {
		timeoutC = time.After(timeout)
	}
	watcher, err := fsnotify.NewWatcher()
	if err != nil {
		// A rare kernel/API incompatibility should not create a busy loop. The
		// next low-frequency restart is sufficient to notice a changed config.
		fallback := 5 * time.Minute
		if timeout > 0 {
			fallback = timeout
		}
		time.Sleep(fallback)
		return
	}
	defer watcher.Close()
	if err := watcher.Add(filepath.Dir(path)); err != nil {
		fallback := 5 * time.Minute
		if timeout > 0 {
			fallback = timeout
		}
		time.Sleep(fallback)
		return
	}
	for {
		select {
		case event, ok := <-watcher.Events:
			if !ok {
				return
			}
			if filepath.Clean(event.Name) == filepath.Clean(path) && event.Op&(fsnotify.Write|fsnotify.Create|fsnotify.Rename) != 0 {
				return
			}
		case <-watcher.Errors:
			return
		case <-timeoutC:
			return
		}
	}
}
