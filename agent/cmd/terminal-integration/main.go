// Package main runs an isolated end-to-end Linux PTY relay verification.
package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"time"

	"cloide/agent/internal/wsclient"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"
)

const (
	backendURL       = "http://127.0.0.1:18081"
	serverNamePrefix = "pty-integration-"
	agentLogPath     = "/tmp/cloide-pty-integration/agent-runtime.log"
)

type apiResponse struct {
	Code    int             `json:"code"`
	Message string          `json:"message"`
	Data    json.RawMessage `json:"data"`
}

type loginData struct {
	Token string `json:"token"`
	User  struct {
		Role         string `json:"role"`
		ReviewStatus string `json:"reviewStatus"`
	} `json:"user"`
}

type serverData struct {
	ID int64 `json:"id"`
}

type agentTokenData struct {
	AgentToken string `json:"agent_token"`
}

type monitorTicketData struct {
	Ticket string `json:"ticket"`
}

// main verifies the Java-to-WSL Linux PTY relay without persisting credentials.
func main() {
	username := os.Getenv("CLOIDE_INTEGRATION_USERNAME")
	password := os.Getenv("CLOIDE_INTEGRATION_PASSWORD")
	agentBinary := os.Getenv("CLOIDE_INTEGRATION_AGENT_BINARY")
	if username == "" || password == "" || agentBinary == "" {
		fail("missing short-lived integration session environment")
	}
	defer unset("CLOIDE_INTEGRATION_PASSWORD")

	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()

	token, err := login(ctx, username, password)
	if err != nil {
		fail(err.Error())
	}
	defer unset("CLOIDE_INTEGRATION_USERNAME")
	if err := verifyAdmin(ctx, token); err != nil {
		fail(err.Error())
	}

	serverID, err := createServer(ctx, token)
	if err != nil {
		fail(err.Error())
	}
	agentToken, err := registerAgent(ctx, token, serverID)
	if err != nil {
		fail(err.Error())
	}
	defer revokeAgent(context.Background(), token, serverID)

	agent := startAgent(ctx, agentBinary, serverID, agentToken)
	defer stopAgent(agent)
	if err := waitForAgent(ctx, token, serverID); err != nil {
		fail(err.Error())
	}

	if err := verifyTerminal(ctx, token, serverID); err != nil {
		fail(err.Error())
	}
	fmt.Println("PTY_RELAY_INTEGRATION_OK")
}

// login obtains a JWT only in process memory.
func login(ctx context.Context, username string, password string) (string, error) {
	var data loginData
	if err := request(ctx, http.MethodPost, "/api/auth/login", "", map[string]string{
		"username": username, "password": password,
	}, &data); err != nil {
		return "", err
	}
	if data.Token == "" {
		return "", errors.New("login response did not contain a token")
	}
	return data.Token, nil
}

// verifyAdmin prevents the harness from creating an isolated server with a non-admin user.
func verifyAdmin(ctx context.Context, token string) error {
	var data struct {
		Role         string `json:"role"`
		ReviewStatus string `json:"reviewStatus"`
	}
	if err := request(ctx, http.MethodGet, "/api/auth/me", token, nil, &data); err != nil {
		return err
	}
	if data.Role != "admin" || data.ReviewStatus != "approved" {
		return errors.New("integration user is not an approved admin")
	}
	return nil
}

// createServer creates a uniquely named isolated metadata record; its SSH credential is intentionally unusable.
func createServer(ctx context.Context, token string) (int64, error) {
	var data serverData
	runID := fmt.Sprint(time.Now().UnixNano())
	unusedCredential, err := randomUnusedCredential()
	if err != nil {
		return 0, err
	}
	requestBody := map[string]interface{}{
		"name":          serverNamePrefix + runID,
		"host":          "wsl-pty-" + runID + ".invalid",
		"description":   "isolated WSL Linux PTY relay integration",
		"ssh_host":      "127.0.0.1",
		"ssh_port":      22,
		"ssh_user":      "unused",
		"ssh_auth_type": "password",
		"ssh_password":  unusedCredential,
	}
	if err := request(ctx, http.MethodPost, "/api/servers", token, requestBody, &data); err != nil {
		return 0, err
	}
	if data.ID <= 0 {
		return 0, errors.New("server creation did not return an ID")
	}
	return data.ID, nil
}

// randomUnusedCredential satisfies the server metadata contract without embedding a reusable secret in source.
func randomUnusedCredential() (string, error) {
	bytes := make([]byte, 24)
	if _, err := rand.Read(bytes); err != nil {
		return "", fmt.Errorf("generate isolated SSH placeholder: %w", err)
	}
	return base64.RawURLEncoding.EncodeToString(bytes), nil
}

// registerAgent creates a temporary Agent Token and returns it only to the current process.
func registerAgent(ctx context.Context, token string, serverID int64) (string, error) {
	var data agentTokenData
	if err := request(ctx, http.MethodPost, fmt.Sprintf("/api/servers/%d/agent/register", serverID), token, nil, &data); err != nil {
		return "", err
	}
	if data.AgentToken == "" {
		return "", errors.New("agent registration did not return a token")
	}
	return data.AgentToken, nil
}

// startAgent launches the already-built Linux Agent with only process-scoped configuration.
func startAgent(ctx context.Context, binary string, serverID int64, token string) *exec.Cmd {
	command := exec.CommandContext(ctx, binary)
	logFile, err := os.OpenFile(agentLogPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0600)
	if err != nil {
		fail("agent log file initialization failed")
	}
	command.Stdout = logFile
	command.Stderr = logFile
	command.Env = append(os.Environ(),
		"CLOIDE_BACKEND_URL=ws://127.0.0.1:18081",
		fmt.Sprintf("CLOIDE_SERVER_ID=%d", serverID),
		"CLOIDE_AGENT_TOKEN="+token,
		"CLOIDE_TERMINAL_ENABLED=true",
		"CLOIDE_TERMINAL_SHELL=/bin/bash",
		"CLOIDE_COLLECT_INTERVAL_SECONDS=60",
		"CLOIDE_HEARTBEAT_INTERVAL_SECONDS=5",
		"CLOIDE_RECONNECT_INITIAL_SECONDS=1",
		"CLOIDE_RECONNECT_MAX_SECONDS=2",
	)
	if err := command.Start(); err != nil {
		_ = logFile.Close()
		fail("agent start failed")
	}
	return command
}

// waitForAgent waits for the authenticated Agent status without exposing Agent Token data.
func waitForAgent(ctx context.Context, token string, serverID int64) error {
	for {
		var data struct {
			AgentStatus string `json:"agent_status"`
		}
		if err := request(ctx, http.MethodGet, fmt.Sprintf("/api/servers/%d", serverID), token, nil, &data); err != nil {
			return err
		}
		if data.AgentStatus == "online" {
			return nil
		}
		select {
		case <-ctx.Done():
			return errors.New("agent did not become online")
		case <-time.After(time.Second):
		}
	}
}

// verifyTerminal exercises the protocol without printing terminal input or output.
func verifyTerminal(ctx context.Context, token string, serverID int64) error {
	ticket, err := issueTicket(ctx, token)
	if err != nil {
		return err
	}
	connection, _, err := websocket.Dial(ctx, "ws://127.0.0.1:18081/ws/monitor?ticket="+ticket, nil)
	if err != nil {
		return fmt.Errorf("monitor websocket dial failed: %w", err)
	}
	defer connection.Close(websocket.StatusNormalClosure, "integration_complete")

	if err := wsjson.Write(ctx, connection, wsclient.NewMessage("terminal.open", map[string]interface{}{
		"server_id": serverID, "cols": 80, "rows": 24,
	})); err != nil {
		return err
	}
	opened, err := readTerminal(ctx, connection, "terminal.opened")
	if err != nil {
		return err
	}
	var openedPayload struct {
		SessionID string `json:"session_id"`
	}
	if err := json.Unmarshal(opened.Payload, &openedPayload); err != nil || openedPayload.SessionID == "" {
		return errors.New("terminal.opened did not contain a session ID")
	}

	command := []byte("printf 'PTY_RELAY_OK\\n'\n")
	if err := wsjson.Write(ctx, connection, wsclient.NewMessage("terminal.input", map[string]interface{}{
		"session_id": openedPayload.SessionID, "data": base64.StdEncoding.EncodeToString(command),
	})); err != nil {
		return err
	}
	output, err := readTerminal(ctx, connection, "terminal.output")
	if err != nil {
		return err
	}
	var outputPayload struct {
		Data string `json:"data"`
	}
	if err := json.Unmarshal(output.Payload, &outputPayload); err != nil {
		return err
	}
	outputBytes, err := base64.StdEncoding.DecodeString(outputPayload.Data)
	if err != nil || !bytes.Contains(outputBytes, []byte("PTY_RELAY_OK")) {
		return errors.New("terminal output marker was not received")
	}
	if err := wsjson.Write(ctx, connection, wsclient.NewMessage("terminal.resize", map[string]interface{}{
		"session_id": openedPayload.SessionID, "cols": 100, "rows": 30,
	})); err != nil {
		return err
	}
	if err := wsjson.Write(ctx, connection, wsclient.NewMessage("terminal.close", map[string]interface{}{
		"session_id": openedPayload.SessionID,
	})); err != nil {
		return err
	}
	_, err = readTerminal(ctx, connection, "terminal.closed")
	return err
}

// issueTicket obtains a one-time Monitor ticket in process memory.
func issueTicket(ctx context.Context, token string) (string, error) {
	var data monitorTicketData
	if err := request(ctx, http.MethodPost, "/api/ws/monitor-ticket", token, nil, &data); err != nil {
		return "", err
	}
	if data.Ticket == "" {
		return "", errors.New("monitor ticket response was empty")
	}
	return data.Ticket, nil
}

// readTerminal waits for the expected terminal frame and rejects server error frames.
func readTerminal(parent context.Context, connection *websocket.Conn, expectedType string) (wsclient.AgentMessage, error) {
	deadline, cancel := context.WithTimeout(parent, 20*time.Second)
	defer cancel()
	for {
		var message wsclient.AgentMessage
		if err := wsjson.Read(deadline, connection, &message); err != nil {
			return wsclient.AgentMessage{}, err
		}
		if message.Type == "error" || message.Type == "terminal.error" {
			return wsclient.AgentMessage{}, errors.New("terminal relay returned an error frame")
		}
		if message.Type == expectedType {
			return message, nil
		}
	}
}

// request sends an API request and keeps non-success response bodies out of terminal output.
func request(ctx context.Context, method string, path string, token string, payload interface{}, destination interface{}) error {
	var body io.Reader
	if payload != nil {
		encoded, err := json.Marshal(payload)
		if err != nil {
			return err
		}
		body = bytes.NewReader(encoded)
	}
	request, err := http.NewRequestWithContext(ctx, method, backendURL+path, body)
	if err != nil {
		return err
	}
	request.Header.Set("Content-Type", "application/json")
	if token != "" {
		request.Header.Set("Authorization", "Bearer "+token)
	}
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	var envelope apiResponse
	if err := json.NewDecoder(io.LimitReader(response.Body, 64*1024)).Decode(&envelope); err != nil {
		return err
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 || envelope.Code != 0 {
		return fmt.Errorf("API request was rejected: %s %s (http_status=%d, business_code=%d)",
			method, path, response.StatusCode, envelope.Code)
	}
	if destination != nil && json.Unmarshal(envelope.Data, destination) != nil {
		return errors.New("API response payload was invalid")
	}
	return nil
}

// revokeAgent invalidates the temporary token even when an earlier relay assertion fails.
func revokeAgent(ctx context.Context, token string, serverID int64) {
	_ = request(ctx, http.MethodDelete, fmt.Sprintf("/api/servers/%d/agent/revoke", serverID), token, nil, nil)
}

// stopAgent terminates the WSL Agent process and waits briefly for all PTYs to close.
func stopAgent(command *exec.Cmd) {
	if command == nil || command.Process == nil {
		return
	}
	_ = command.Process.Signal(os.Interrupt)
	done := make(chan struct{})
	go func() {
		_ = command.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		_ = command.Process.Kill()
	}
}

// unset removes a process-scoped secret environment variable.
func unset(name string) {
	_ = os.Unsetenv(name)
}

// fail reports only a high-level failure reason and never includes secret values.
func fail(message string) {
	message = strings.ReplaceAll(message, "\n", " ")
	fmt.Fprintln(os.Stderr, "PTY_RELAY_INTEGRATION_FAILED:", message)
	os.Exit(1)
}
