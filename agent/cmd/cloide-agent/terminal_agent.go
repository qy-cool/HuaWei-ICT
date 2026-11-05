package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"log/slog"
	"path/filepath"
	"sync"
	"time"

	"cloide/agent/internal/config"
	"cloide/agent/internal/terminal"
	"cloide/agent/internal/wsclient"
)

type terminalAgent struct {
	client     *wsclient.Client
	manager    terminal.Manager
	logger     *slog.Logger
	serverID   int64
	shell      string
	maxBytes   int
	requestIDs sync.Map
}

func newTerminalAgent(cfg *config.Config, client *wsclient.Client, logger *slog.Logger) (*terminalAgent, error) {
	agent := &terminalAgent{client: client, logger: logger, serverID: cfg.ServerID, shell: cfg.TerminalShell, maxBytes: cfg.TerminalMaxInputBytes}
	manager, err := terminal.NewManager(terminal.Config{
		Enabled: cfg.TerminalEnabled, Shell: cfg.TerminalShell, MaxSessions: cfg.TerminalMaxSessions,
		MaxInputBytes: cfg.TerminalMaxInputBytes, MaxOutputBytes: cfg.TerminalMaxOutputBytes,
		OutputRateBytesPerSecond: cfg.TerminalOutputRateBytesPerSecond, OutputBurstBytes: cfg.TerminalOutputBurstBytes,
		OutputQueueSize: cfg.TerminalOutputQueueSize,
		IdleTimeout:     time.Duration(cfg.TerminalIdleTimeoutSeconds) * time.Second,
		MaxLifetime:     time.Duration(cfg.TerminalMaxLifetimeSeconds) * time.Second,
	}, terminal.Callbacks{Output: agent.sendOutput, Closed: agent.sendClosed})
	if err != nil {
		return nil, err
	}
	agent.manager = manager
	return agent, nil
}

func (a *terminalAgent) handle(ctx context.Context, message wsclient.AgentMessage) {
	switch message.Type {
	case "terminal.open":
		var payload wsclient.TerminalOpenPayload
		if json.Unmarshal(message.Payload, &payload) != nil || !a.matchesServer(payload.ServerID) || payload.SessionID == "" || payload.Cols == 0 || payload.Rows == 0 || payload.Cols > 300 || payload.Rows > 100 {
			a.logger.Warn("terminal.open rejected: invalid payload or server mismatch", "server_id", payload.ServerID, "session_id", payload.SessionID)
			a.sendError(ctx, message.MessageID, "", 40003, "terminal invalid payload")
			return
		}
		a.logger.Info("terminal.open received", "session_id", payload.SessionID, "cols", payload.Cols, "rows", payload.Rows)
		a.requestIDs.Store(payload.SessionID, message.MessageID)
		if err := a.manager.Open(ctx, payload.SessionID, payload.Cols, payload.Rows); err != nil {
			a.logger.Warn("terminal pty open failed", "error", err, "session_id", payload.SessionID)
			a.requestIDs.Delete(payload.SessionID)
			a.sendError(ctx, message.MessageID, payload.SessionID, 40903, "terminal session state conflict")
			return
		}
		a.logger.Info("terminal.opened sending", "session_id", payload.SessionID)
		if err := a.send(ctx, wsclient.NewMessageWithID("terminal.opened", message.MessageID,
			wsclient.TerminalOpenedPayload{ServerID: a.serverID, SessionID: payload.SessionID,
				Shell: shellIdentifier(a.shell)})); err != nil {
			_ = a.manager.Close(payload.SessionID, "opened_send_failed")
		}
	case "terminal.input":
		var payload wsclient.TerminalInputPayload
		data, err := decodeTerminalData(message.Payload, &payload, a.maxBytes)
		if err == nil && !a.matchesServer(payload.ServerID) {
			err = terminal.ErrSessionNotFound
		}
		if err == nil {
			err = a.manager.WriteInput(payload.SessionID, data)
		}
		if err != nil {
			a.sendError(ctx, message.MessageID, payload.SessionID, 40903, "terminal session state conflict")
		}
	case "terminal.resize":
		var payload wsclient.TerminalResizePayload
		err := json.Unmarshal(message.Payload, &payload)
		if err == nil && (!a.matchesServer(payload.ServerID) || payload.SessionID == "" || payload.Cols == 0 || payload.Rows == 0 || payload.Cols > 300 || payload.Rows > 100) {
			err = terminal.ErrSessionNotFound
		}
		if err == nil {
			err = a.manager.Resize(payload.SessionID, payload.Cols, payload.Rows)
		}
		if err != nil {
			a.sendError(ctx, message.MessageID, payload.SessionID, 40903, "terminal session state conflict")
		}
	case "terminal.close":
		var payload wsclient.TerminalClosePayload
		err := json.Unmarshal(message.Payload, &payload)
		if err == nil && (!a.matchesServer(payload.ServerID) || payload.SessionID == "") {
			err = terminal.ErrSessionNotFound
		}
		if err == nil {
			a.requestIDs.Store(payload.SessionID, message.MessageID)
			err = a.manager.Close(payload.SessionID, "remote_close")
		}
		if err != nil && err != terminal.ErrSessionNotFound {
			a.sendError(ctx, message.MessageID, payload.SessionID, 40903, "terminal session state conflict")
		}
	default:
		a.logger.Debug("unsupported agent message", "type", message.Type)
	}
}

func decodeTerminalData(raw json.RawMessage, payload *wsclient.TerminalInputPayload, maxBytes int) ([]byte, error) {
	if err := json.Unmarshal(raw, payload); err != nil || payload.SessionID == "" {
		return nil, terminal.ErrSessionNotFound
	}
	data, err := base64.StdEncoding.DecodeString(payload.Data)
	if err != nil || len(data) == 0 || len(data) > maxBytes {
		return nil, terminal.ErrSessionNotFound
	}
	return data, nil
}

func (a *terminalAgent) sendOutput(sessionID string, data []byte) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := a.send(ctx, wsclient.NewMessage("terminal.output", wsclient.TerminalOutputPayload{ServerID: a.serverID, SessionID: sessionID, Data: base64.StdEncoding.EncodeToString(data)})); err != nil {
		a.logger.Warn("terminal output send failed", "session_id", sessionID)
		_ = a.manager.Close(sessionID, "output_send_failed")
	}
}

func (a *terminalAgent) sendClosed(sessionID string, reason string, exitCode *int) {
	requestID, _ := a.requestIDs.LoadAndDelete(sessionID)
	messageID, _ := requestID.(string)
	if messageID == "" {
		messageID = wsclient.NewMessage("terminal.closed", nil).MessageID
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = a.send(ctx, wsclient.NewMessageWithID("terminal.closed", messageID, wsclient.TerminalClosedPayload{ServerID: a.serverID, SessionID: sessionID, Reason: reason, ExitCode: exitCode}))
}

func (a *terminalAgent) sendError(ctx context.Context, messageID string, sessionID string, code int, message string) {
	_ = a.send(ctx, wsclient.NewMessageWithID("terminal.error", messageID, wsclient.TerminalErrorPayload{ServerID: a.serverID, SessionID: sessionID, Code: code, Message: message}))
}

func (a *terminalAgent) matchesServer(serverID int64) bool {
	return serverID == a.serverID
}

// shellIdentifier returns the protocol-safe Shell name without exposing the local executable path.
func shellIdentifier(shellPath string) string {
	return filepath.Base(shellPath)
}

func (a *terminalAgent) send(ctx context.Context, message wsclient.AgentMessage) error {
	return a.client.SendMessage(ctx, message)
}
