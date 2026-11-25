package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"slices"
	"strconv"
	"strings"
	"syscall"
	"time"
)

var protocolVersions = []string{"2025-11-25", "2025-06-18", "2025-03-26"}
var bearerPattern = regexp.MustCompile(`^Bearer [A-Za-z0-9_-]+$`)
var cliVersion = "dev" // Set by the linker for release binaries.

type connectionSettings struct {
	URL     string `json:"url"`
	Headers struct {
		Authorization string
	} `json:"headers"`
}

const settingsLocation = "Settings > Tools > ij-format"

func defaultConfig() (string, error) {
	root, err := os.UserConfigDir()
	if runtime.GOOS == "windows" && os.Getenv("APPDATA") == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", fmt.Errorf("cannot locate IDEA connection file: %w. Specify --config", err)
		}
		root = filepath.Join(home, "AppData", "Roaming")
	} else if err != nil {
		return "", fmt.Errorf("cannot locate IDEA connection file: %w. Specify --config", err)
	}

	directory := filepath.Join(root, "JetBrains")
	entries, err := os.ReadDir(directory)
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return "", fmt.Errorf("cannot search for IDEA connection files: %w. Specify --config", err)
	}

	var matches []string
	for _, entry := range entries {
		name := entry.Name()
		if !strings.HasPrefix(name, "IntelliJIdea") && !strings.HasPrefix(name, "IdeaIC") {
			continue
		}
		path := filepath.Join(directory, name, "ij-format-mcp.json")
		if info, err := os.Stat(path); err == nil && info.Mode().IsRegular() {
			matches = append(matches, path)
		}
	}
	switch len(matches) {
	case 0:
		return "", fmt.Errorf("no IDEA connection file found under %q. Start IDEA and enable the MCP server in %s, or pass --config", directory, settingsLocation)
	case 1:
		return matches[0], nil
	default:
		return "", fmt.Errorf("multiple IDEA connection files found. Use --config with the active path from %s:\n  %s", settingsLocation, strings.Join(matches, "\n  "))
	}
}

func loadConfig(path string) (connectionSettings, error) {
	var settings connectionSettings
	if path == "" {
		var err error
		path, err = defaultConfig()
		if err != nil {
			return settings, err
		}
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return settings, fmt.Errorf("cannot read connection file: %w", err)
	}
	var config struct {
		connectionSettings
		MCPServers map[string]json.RawMessage `json:"mcpServers"`
	}
	if json.Unmarshal(data, &config) != nil {
		return settings, fmt.Errorf("invalid JSON in connection file %q. Use the file from %s", path, settingsLocation)
	}
	settings = config.connectionSettings
	if settings.URL == "" && settings.Headers.Authorization == "" {
		if entry := config.MCPServers["ij-format"]; entry != nil {
			if err := json.Unmarshal(entry, &settings); err != nil {
				return settings, fmt.Errorf("connection file %q has an invalid mcpServers.ij-format entry. Use Copy MCP JSON from %s", path, settingsLocation)
			}
		}
	}
	if !bearerPattern.MatchString(settings.Headers.Authorization) {
		return settings, fmt.Errorf("connection file %q is missing a valid ij-format Bearer token. Use the file or Copy MCP JSON from %s", path, settingsLocation)
	}
	invalid := fmt.Errorf("connection file %q must point to http://127.0.0.1:<port>/mcp. Use the file from %s", path, settingsLocation)
	address, err := url.Parse(settings.URL)
	if err != nil || address.Scheme != "http" || address.Hostname() != "127.0.0.1" ||
		address.User != nil || address.EscapedPath() != "/mcp" || address.ForceQuery ||
		address.RawQuery != "" || address.Fragment != "" {
		return settings, invalid
	}
	port, err := strconv.ParseUint(address.Port(), 10, 16)
	if err != nil || port == 0 {
		return settings, invalid
	}
	return settings, nil
}

type mcpClient struct {
	http    *http.Client
	url     string
	headers http.Header
	nextID  int
}

func connect(config string, requireExact bool) (*mcpClient, error) {
	settings, err := loadConfig(config)
	if err != nil {
		return nil, err
	}
	dialer := &net.Dialer{Timeout: 2 * time.Second}
	client := &mcpClient{
		http: &http.Client{
			Transport: &http.Transport{DialContext: dialer.DialContext, DisableCompression: true},
			Timeout:   25 * time.Second,
			CheckRedirect: func(*http.Request, []*http.Request) error {
				return http.ErrUseLastResponse
			},
		},
		url: settings.URL,
		headers: http.Header{
			"Authorization": {settings.Headers.Authorization},
			"Content-Type":  {"application/json"},
			"Accept":        {"application/json, text/event-stream"},
		},
	}
	err = client.initialize()
	if err == nil && requireExact {
		var exact bool
		exact, err = client.formatCapability()
		if err == nil && !exact {
			err = errors.New("the running IDEA plugin does not support --range. Update it or use --lines")
		}
	}
	if err != nil {
		client.close()
		return nil, err
	}
	return client, nil
}

func (c *mcpClient) close() {
	c.http.CloseIdleConnections()
}

type rpcRequest struct {
	Version string `json:"jsonrpc"`
	Method  string `json:"method"`
	ID      int    `json:"id,omitempty"`
	Params  any    `json:"params,omitempty"`
}

type rpcResponse struct {
	Version string          `json:"jsonrpc"`
	ID      int             `json:"id"`
	Result  json.RawMessage `json:"result"`
	Error   *struct {
		Code    int    `json:"code"`
		Message string `json:"message"`
	} `json:"error"`
}

func (c *mcpClient) request(method string, params, result any) error {
	payload := rpcRequest{Version: "2.0", Method: method, Params: params}
	if result != nil {
		c.nextID++
		payload.ID = c.nextID
	}
	data, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	req, err := http.NewRequest(http.MethodPost, c.url, bytes.NewReader(data))
	if err != nil {
		return err
	}
	req.Header = c.headers.Clone()
	response, err := c.http.Do(req)
	if err != nil {
		return connectionError(err)
	}
	defer response.Body.Close()
	body, err := io.ReadAll(response.Body)
	if err != nil {
		return connectionError(err)
	}
	switch response.StatusCode {
	case http.StatusUnauthorized, http.StatusForbidden:
		return fmt.Errorf("MCP authorization rejected (HTTP %d). Use the current configuration from %s", response.StatusCode, settingsLocation)
	case http.StatusTooManyRequests, http.StatusServiceUnavailable:
		return fmt.Errorf("MCP server is busy (HTTP %d)", response.StatusCode)
	}
	if result == nil {
		if response.StatusCode != http.StatusAccepted {
			return fmt.Errorf("MCP notification failed (HTTP %d)", response.StatusCode)
		}
		return nil
	}
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("MCP request failed (HTTP %d)", response.StatusCode)
	}
	invalid := errors.New("invalid MCP response from IDEA")
	var answer rpcResponse
	if json.Unmarshal(body, &answer) != nil || answer.Version != "2.0" || answer.ID != payload.ID {
		return invalid
	}
	if answer.Error != nil {
		return fmt.Errorf("MCP error %d: %s", answer.Error.Code, answer.Error.Message)
	}
	if json.Unmarshal(answer.Result, result) != nil {
		return invalid
	}
	if method == "initialize" {
		if session := response.Header.Get("Mcp-Session-Id"); session != "" {
			c.headers.Set("Mcp-Session-Id", session)
		}
	}
	return nil
}

func connectionError(err error) error {
	var operation *net.OpError
	var timeout net.Error
	switch {
	case errors.Is(err, os.ErrPermission), errors.Is(err, syscall.EPERM):
		return errors.New("local MCP connection denied by the environment")
	case errors.Is(err, syscall.ECONNREFUSED),
		errors.As(err, &operation) && operation.Op == "dial" && operation.Timeout():
		return fmt.Errorf("IDEA MCP server is not reachable. Start IDEA and check the server status in %s", settingsLocation)
	case errors.As(err, &timeout) && timeout.Timeout():
		return errors.New("timed out waiting for IDEA MCP server")
	default:
		return fmt.Errorf("MCP connection failed: %w", err)
	}
}

func (c *mcpClient) initialize() error {
	var result struct{ ProtocolVersion string }
	err := c.request("initialize", map[string]any{
		"protocolVersion": protocolVersions[0],
		"capabilities":    struct{}{},
		"clientInfo":      map[string]string{"name": "ij-format-cli", "version": cliVersion},
	}, &result)
	if err != nil {
		return err
	}
	if !slices.Contains(protocolVersions, result.ProtocolVersion) {
		return errors.New("IDEA returned an unsupported MCP protocol version")
	}
	c.headers.Set("MCP-Protocol-Version", result.ProtocolVersion)
	return c.request("notifications/initialized", nil, nil)
}

func (c *mcpClient) formatCapability() (bool, error) {
	var result struct {
		Tools []struct {
			Name        string
			InputSchema struct {
				Properties map[string]json.RawMessage
			}
		}
	}
	if err := c.request("tools/list", nil, &result); err != nil {
		return false, err
	}
	if result.Tools == nil {
		return false, errors.New("invalid tool list from IDEA")
	}
	for _, tool := range result.Tools {
		if tool.Name == "ij_format_lines" {
			_, exact := tool.InputSchema.Properties["range"]
			return exact, nil
		}
	}
	return false, errors.New("the server does not expose ij_format_lines. Check that --config points to the ij-format plugin")
}

type formatArguments struct {
	Source    string      `json:"source"`
	Language  string      `json:"language"`
	StartLine int         `json:"startLine,omitempty"`
	EndLine   int         `json:"endLine,omitempty"`
	Range     *exactRange `json:"range,omitempty"`
}

func (c *mcpClient) format(arguments formatArguments) (string, error) {
	var result struct {
		Content []struct {
			Type string
			Text *string
		}
		IsError bool
	}
	err := c.request("tools/call", map[string]any{"name": "ij_format_lines", "arguments": arguments}, &result)
	if err != nil {
		return "", err
	}
	if result.Content == nil {
		return "", errors.New("invalid formatting response from IDEA")
	}
	var output strings.Builder
	for _, item := range result.Content {
		if item.Type == "text" {
			if item.Text == nil {
				return "", errors.New("invalid formatting response from IDEA")
			}
			output.WriteString(*item.Text)
		}
	}
	text := output.String()
	if result.IsError {
		if text == "" {
			text = "IDEA rejected the formatting request"
		}
		return "", errors.New(text)
	}
	if text == "" {
		return "", errors.New("IDEA returned no formatted text")
	}
	return text, nil
}
