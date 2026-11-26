package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestConnectionFileFormats(t *testing.T) {
	const entry = `{"url":"http://127.0.0.1:49273/mcp","headers":{"Authorization":"Bearer test-local-token"}}`
	cases := []struct {
		name, data, wantError string
	}{
		{"plugin file", entry, ""},
		{"copied MCP JSON", `{"mcpServers":{"other":{"headers":[]},"ij-format":` + entry + `}}`, ""},
		{"invalid JSON", `{"mcpServers":`, "invalid JSON"},
		{"wrong server", `{"mcpServers":{"other":` + entry + `}}`, "missing a valid ij-format Bearer token"},
		{"invalid named entry", `{"mcpServers":{"ij-format":[]}}`, "invalid mcpServers.ij-format entry"},
		{"remote address", strings.Replace(entry, "127.0.0.1", "remote.invalid", 1), "must point to http://127.0.0.1:<port>/mcp"},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "connection.json")
			if err := os.WriteFile(path, []byte(test.data), 0600); err != nil {
				t.Fatal(err)
			}
			got, err := loadConfig(path, false)
			if test.wantError != "" {
				if err == nil || !strings.Contains(err.Error(), test.wantError) || strings.Contains(err.Error(), "test-local-token") {
					t.Fatalf("got %v, want safe error containing %q", err, test.wantError)
				}
			} else if err != nil || got.URL != "http://127.0.0.1:49273/mcp" || got.Headers.Authorization != "Bearer test-local-token" {
				t.Fatal("expected a valid local connection file")
			}
		})
	}
}

func TestContainerConnectionRequiresExplicitOptIn(t *testing.T) {
	const config = `{"url":"http://host.docker.internal:49273/mcp","headers":{"Authorization":"Bearer test-local-token"}}`
	path := filepath.Join(t.TempDir(), "connection.json")
	if err := os.WriteFile(path, []byte(config), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := loadConfig(path, false); err == nil {
		t.Fatal("container address accepted without opt-in")
	}
	if _, err := loadConfig(path, true); err != nil {
		t.Fatal(err)
	}
	if _, err := loadConfig("", true); err == nil {
		t.Fatal("container mode accepted automatic connection discovery")
	}
	if err := os.WriteFile(path, []byte(strings.Replace(config, "host.docker.internal", "remote.invalid", 1)), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := loadConfig(path, true); err == nil {
		t.Fatal("arbitrary hostname accepted in container mode")
	}
}
