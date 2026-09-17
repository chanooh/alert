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
	keepAlive     = 300 * time.Second
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
	for {
		cfg, err := readConfig(filepath.Join(d.rootDir, "config.json"))
		if err != nil || !cfg.Enabled {
			d.setConfig(cfg)
			d.writeStatus("disabled", err, 0)
			waitForConfigChange(filepath.Join(d.rootDir, "config.json"))
			continue
		}
		d.setConfig(cfg)
		if err := d.runClient(cfg); err != nil {
			d.writeStatus("backoff", err, 0)
			waitForConfigChangeOrTimeout(filepath.Join(d.rootDir, "config.json"), 5*time.Second)
		}
	}
}

func (d *daemon) runClient(cfg config) error {
	changed := make(chan struct{}, 1)
	subscriptionFailure := make(chan error, 1)
	options := mqtt.NewClientOptions().
		AddBroker(cfg.Broker).
		SetClientID("alert-root-" + cfg.DeviceID).
		SetCleanSession(false).
		SetKeepAlive(keepAlive).
		SetAutoReconnect(true).
		SetConnectRetry(true).
		SetConnectRetryInterval(5 * time.Second).
		SetOrderMatters(false)
	if cfg.Username != "" {
		options.SetUsername(cfg.Username).SetPassword(cfg.Password)
	}
	options.OnConnectionLost = func(_ mqtt.Client, err error) { d.writeStatus("backoff", err, 0) }
	options.OnReconnecting = func(_ mqtt.Client, _ *mqtt.ClientOptions) { d.writeStatus("connecting", nil, 0) }
	options.OnConnect = func(client mqtt.Client) {
		token := client.Subscribe("alert/"+cfg.DeviceID+"/events", 1, func(_ mqtt.Client, message mqtt.Message) {
			d.receive(message.Payload())
		})
		if !token.WaitTimeout(20 * time.Second) {
			d.writeStatus("backoff", errors.New("subscribe timeout"), 0)
			select {
			case subscriptionFailure <- errors.New("subscribe timeout"):
			default:
			}
			return
		}
		if token.Error() != nil {
			d.writeStatus("backoff", token.Error(), 0)
			select {
			case subscriptionFailure <- token.Error():
			default:
			}
			return
		}
		d.writeStatus("subscribed", nil, time.Now().UnixMilli())
	}
	client := mqtt.NewClient(options)
	d.writeStatus("connecting", nil, 0)
	if token := client.Connect(); !token.WaitTimeout(20 * time.Second) {
		client.Disconnect(250)
		return errors.New("connect timeout")
	} else if token.Error() != nil {
		client.Disconnect(250)
		return token.Error()
	}

	// Reconfiguration is detected by file metadata rather than a timer. A
	// running Paho client owns its own socket/reconnect loop in the meantime.
	go func() {
		waitForConfigChange(filepath.Join(d.rootDir, "config.json"))
		changed <- struct{}{}
	}()
	select {
	case <-changed:
		client.Disconnect(250)
		return nil
	case err := <-subscriptionFailure:
		client.Disconnect(250)
		return err
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
			if pendingInbox(filepath.Join(d.rootDir, "inbox")) == 0 {
				d.writeStatus("subscribed", nil, 0)
				return
			}
			if err := wakeIngress(); err != nil {
				d.writeStatus("subscribed", err, 0)
			}
			time.Sleep(time.Minute) // only while durable work remains undrained
		}
	}()
}

func wakeIngress() error {
	return exec.Command("am", "start-foreground-service", "--user", "0", "-a", ingressAction, "-n", componentName).Run()
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
		LastEventAt: lastEvent, PendingInbox: pendingInbox(filepath.Join(d.rootDir, "inbox")), LastError: message,
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
