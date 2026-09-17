package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestReadConfigAcceptsSupportedBrokers(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.json")
	if err := os.WriteFile(path, []byte(`{"schema":1,"enabled":true,"generation":7,"broker":"mqtts://broker.example:8883","username":"user","password":"secret","deviceId":"device-1"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := readConfig(path)
	if err != nil || cfg.DeviceID != "device-1" || cfg.Generation != 7 {
		t.Fatalf("unexpected config: %#v, %v", cfg, err)
	}
}

func TestReadConfigRejectsUnsupportedBroker(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	if err := os.WriteFile(path, []byte(`{"schema":1,"enabled":true,"broker":"https://example.test","deviceId":"device-1"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := readConfig(path); err == nil {
		t.Fatal("expected unsupported broker rejection")
	}
}

func TestRedactErrorNeverKeepsCredentials(t *testing.T) {
	cfg := config{Broker: "mqtt://broker.test:1883", Username: "alice", Password: "super-secret"}
	got := redactError("dial mqtt://broker.test:1883 as alice using super-secret", cfg)
	if got == "" || containsAny(got, cfg.Username, cfg.Password, cfg.Broker) {
		t.Fatalf("credential leaked in %q", got)
	}
}

func TestPendingInboxCountsOnlyDurableEvents(t *testing.T) {
	dir := t.TempDir()
	for _, name := range []string{"one.json", "two.json", ".partial.tmp", "nested"} {
		path := filepath.Join(dir, name)
		if name == "nested" {
			if err := os.Mkdir(path, 0o700); err != nil {
				t.Fatal(err)
			}
		} else if err := os.WriteFile(path, []byte("x"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	if got := pendingInbox(dir); got != 2 {
		t.Fatalf("pendingInbox = %d, want 2", got)
	}
}

func containsAny(value string, values ...string) bool {
	for _, candidate := range values {
		if candidate != "" && len(value) >= len(candidate) {
			for index := 0; index+len(candidate) <= len(value); index++ {
				if value[index:index+len(candidate)] == candidate {
					return true
				}
			}
		}
	}
	return false
}
