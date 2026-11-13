package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"time"

	"cloide/agent/internal/config"
	"cloide/agent/internal/jobs"
	"cloide/agent/internal/wsclient"
)

func openRepositoryJobs(ctx context.Context, cfg *config.Config, client *wsclient.Client, logger *slog.Logger) (*jobs.Manager, error) {
	var registration jobs.Registration
	dir, timeout, run, err := jobs.LoadRunner(cfg.JobManifest, &registration)
	if err != nil {
		return nil, err
	}
	var dynamic jobs.Registration
	var observed time.Time
	return jobs.Open(ctx, dir, cfg.BackendURL, cfg.ServerID, timeout, run, func(ctx context.Context, kind string, body any) error {
		if kind == "job.ready" {
			if observed.IsZero() || time.Since(observed) >= time.Minute {
				dynamic = registration.Snapshot(dir)
				observed = time.Now()
			}
			body = map[string]any{"protocol_version": 1, "capabilities": dynamic}
		}
		return client.SendMessage(ctx, wsclient.NewMessage(kind, body))
	}, logger)
}

func handleRepositoryJob(m *jobs.Manager, msg wsclient.AgentMessage) error {
	if msg.Type == "job.execute" {
		var req jobs.Request
		if err := json.Unmarshal(msg.Payload, &req); err != nil {
			return err
		}
		return m.Accept(req)
	}
	var ref struct {
		ID string `json:"job_id"`
	}
	if err := json.Unmarshal(msg.Payload, &ref); err != nil {
		return err
	}
	switch msg.Type {
	case "job.cancel":
		return m.Cancel(ref.ID)
	case "job.result.ack":
		return m.Acknowledge(ref.ID)
	default:
		return fmt.Errorf("unknown repository job message %q", msg.Type)
	}
}
