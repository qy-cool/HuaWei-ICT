package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"path/filepath"
	"strings"

	"cloide/agent/internal/config"
	"cloide/agent/internal/syncs"
	"cloide/agent/internal/wsclient"
)

// httpOrigin derives the control-plane REST origin from the WebSocket backend.
func httpOrigin(backend string) string {
	if strings.HasPrefix(backend, "wss://") {
		return "https://" + strings.TrimPrefix(backend, "wss://")
	}
	return "http://" + strings.TrimPrefix(backend, "ws://")
}

// openSourceSyncs enables the source.sync.v1 protocol when the administrator
// configured a sync_root; the ledger lives under that root, separate from the
// repository-test job ledger. A nil manager without error means "not enabled".
func openSourceSyncs(ctx context.Context, cfg *config.Config, client *wsclient.Client, logger *slog.Logger) (*syncs.Manager, error) {
	root, err := syncs.LoadRoot(cfg.JobManifest)
	if err != nil || root == "" {
		return nil, err
	}
	executor := syncs.NewExecutor(httpOrigin(cfg.BackendURL), cfg.AgentToken, cfg.ServerID, root)
	return syncs.Open(ctx, filepath.Join(root, ".ledger"), cfg.BackendURL, cfg.ServerID,
		executor.Execute, func(ctx context.Context, kind string, body any) error {
			return client.SendMessage(ctx, wsclient.NewMessage(kind, body))
		}, logger)
}

func handleSourceSync(m *syncs.Manager, msg wsclient.AgentMessage) error {
	if msg.Type == "source.sync" {
		var req syncs.Request
		if err := json.Unmarshal(msg.Payload, &req); err != nil {
			return err
		}
		return m.Accept(req)
	}
	var ref struct {
		ID string `json:"sync_id"`
	}
	if err := json.Unmarshal(msg.Payload, &ref); err != nil {
		return err
	}
	switch msg.Type {
	case "source.sync.cancel":
		return m.Cancel(ref.ID)
	case "source.sync.result.ack":
		return m.Acknowledge(ref.ID)
	default:
		return fmt.Errorf("unknown source sync message %q", msg.Type)
	}
}
