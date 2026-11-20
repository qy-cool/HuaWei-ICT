// Package syncs implements the node side of the source.sync.v1 protocol:
// claim a managed bundle from the control plane, verify the pinned commit,
// and atomically install the snapshot objects. The ledger, staging area and
// installed directories are separate from the repository-test job runtime.
package syncs

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

type Execute func(context.Context, Request, func(string, int64)) Result
type Send func(context.Context, string, any) error

// ErrLedgerCorrupt 标记同步账本的记录级损坏；调用方停用同步执行器但保持
// Agent 主体运行，清理账本目录并重启后恢复。
var ErrLedgerCorrupt = errors.New("source sync ledger corrupted")

var (
	syncID    = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
	snapshot  = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
	fullSHA   = regexp.MustCompile(`^[0-9a-f]{40}$`)
	bundleSHA = regexp.MustCompile(`^[0-9a-f]{64}$`)
)

type Request struct {
	ID          string `json:"sync_id"`
	Project     int64  `json:"project_id"`
	Repository  int64  `json:"repository_id"`
	Snapshot    string `json:"snapshot_id"`
	Generation  int64  `json:"generation"`
	Commit      string `json:"commit_sha"`
	Tree        string `json:"tree_sha"`
	BundleSHA   string `json:"bundle_sha256"`
	Bytes       int64  `json:"byte_count"`
	Deadline    string `json:"deadline"`
}

type Result struct {
	ID      string `json:"sync_id"`
	Status  string `json:"status"`
	Commit  string `json:"verified_commit_sha,omitempty"`
	Tree    string `json:"verified_tree_sha,omitempty"`
	Bundle  string `json:"verified_bundle_sha256,omitempty"`
	Output  string `json:"output"`
}

// An empty result reserves the sync before the first external effect. Final
// delivery and acknowledgement are separate durable state transitions.
type record struct {
	Request Request `json:"request"`
	Result  *Result `json:"result,omitempty"`
	Acked   bool    `json:"acknowledged,omitempty"`
}

type Manager struct {
	dir     string
	lock    *os.File
	logger  *slog.Logger
	execute Execute
	send    Send
	ctx     context.Context
	stop    context.CancelFunc
	wake    chan struct{}
	wg      sync.WaitGroup
	mu      sync.Mutex
	active  string
	cancel  context.CancelFunc
	pending map[string]record
	unsaved *record
}

// LoadRoot reads the administrator-owned manifest and returns the configured
// sync root; an absent field disables the protocol without touching jobs.
func LoadRoot(path string) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()
	var cfg struct {
		State    string `json:"state_directory"`
		SyncRoot string `json:"sync_root"`
	}
	if err := json.NewDecoder(file).Decode(&cfg); err != nil {
		return "", err
	}
	if cfg.SyncRoot == "" {
		return "", nil
	}
	if !filepath.IsAbs(cfg.SyncRoot) || filepath.Clean(cfg.SyncRoot) != cfg.SyncRoot || cfg.SyncRoot == "/" {
		return "", errors.New("sync_root must be a clean absolute path")
	}
	return cfg.SyncRoot, nil
}

func Open(ctx context.Context, base, backend string, server int64, execute Execute, send Send, logger *slog.Logger) (*Manager, error) {
	// 独立于作业账本的哈希子目录：同一节点两套账本互不干扰。
	dir := recordDirectory(base, backend, server)
	if err := os.MkdirAll(dir, 0700); err != nil {
		return nil, err
	}
	lock, err := lockLedger(dir)
	if err != nil {
		return nil, fmt.Errorf("lock source sync ledger: %w", err)
	}
	child, stop := context.WithCancel(ctx)
	m := &Manager{dir: dir, lock: lock, ctx: child, stop: stop, logger: logger, execute: execute, send: send,
		wake: make(chan struct{}, 1), pending: make(map[string]record)}
	if err = m.recover(); err != nil {
		stop()
		lock.Close()
		return nil, err
	}
	return m, nil
}

func (m *Manager) recover() error {
	entries, err := os.ReadDir(m.dir)
	if err != nil {
		return err
	}
	// 记录级损坏降级：与作业账本同构，只停用同步执行器，采集与告警不受影响。
	corrupt := false
	for _, entry := range entries {
		if filepath.Ext(entry.Name()) != ".json" {
			continue
		}
		id := strings.TrimSuffix(entry.Name(), ".json")
		if !syncID.MatchString(id) {
			m.logger.Error("source sync ledger corrupt entry skipped", "file", entry.Name())
			corrupt = true
			continue
		}
		r, err := readRecord(m.dir, id)
		if err != nil {
			m.logger.Error("source sync ledger corrupt record skipped", "sync_id", id, "error", err)
			corrupt = true
			continue
		}
		if r.Result == nil {
			r.Result = &Result{id, "uncertain", "", "", "", "agent restarted before the sync result was durably recorded"}
			if err = saveRecord(m.dir, r); err != nil {
				m.logger.Error("source sync ledger uncertain rewrite failed", "sync_id", id, "error", err)
				corrupt = true
				continue
			}
		}
		if !r.Acked {
			m.pending[id] = r
		}
	}
	if corrupt {
		return fmt.Errorf("%w: %s", ErrLedgerCorrupt, m.dir)
	}
	return nil
}

func (m *Manager) Start() {
	m.wg.Add(1)
	go func() {
		defer m.wg.Done()
		ticker := time.NewTicker(5 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-m.ctx.Done():
				return
			case <-m.wake:
			case <-ticker.C:
			}
			m.poll()
		}
	}()
}

func (m *Manager) Wake() {
	select {
	case m.wake <- struct{}{}:
	default:
	}
}

func (m *Manager) Close() {
	m.mu.Lock()
	m.stop()
	m.mu.Unlock()
	m.wg.Wait()
	m.lock.Close()
}

func (m *Manager) emit(kind string, payload any) error {
	ctx, cancel := context.WithTimeout(m.ctx, 2*time.Second)
	defer cancel()
	return m.send(ctx, kind, payload)
}

func (m *Manager) poll() {
	m.mu.Lock()
	if r := m.unsaved; r != nil {
		if err := saveRecord(m.dir, *r); err != nil {
			m.mu.Unlock()
			m.logger.Error("source sync ledger write failed", "error", err)
			return
		}
		m.pending[r.Request.ID] = *r
		m.unsaved = nil
	}
	results := make([]Result, 0, len(m.pending))
	for _, r := range m.pending {
		results = append(results, *r.Result)
	}
	m.mu.Unlock()
	// Negotiate on every connection before replay, mirroring the job protocol.
	if err := m.emit("source.sync.ready", map[string]int{"protocol_version": 1}); err != nil {
		m.logger.Debug("source sync poll deferred", "error", err)
		return
	}
	for _, result := range results {
		if m.emit("source.sync.result", result) != nil {
			return
		}
	}
}

func (m *Manager) Accept(req Request) error {
	if !syncID.MatchString(req.ID) || !snapshot.MatchString(req.Snapshot) || req.Project <= 0 ||
		req.Repository <= 0 || req.Generation < 1 || !fullSHA.MatchString(req.Commit) ||
		!fullSHA.MatchString(req.Tree) || !bundleSHA.MatchString(req.BundleSHA) || req.Bytes <= 0 {
		return errors.New("invalid source sync request")
	}
	deadline, err := time.Parse(time.RFC3339, req.Deadline)
	if err != nil {
		return fmt.Errorf("invalid sync deadline: %w", err)
	}
	m.mu.Lock()
	if err := m.ctx.Err(); err != nil {
		m.mu.Unlock()
		return err
	}
	r, err := readRecord(m.dir, req.ID)
	switch {
	case err == nil:
		m.mu.Unlock()
		// 相同 syncId 且相同信封摘要（全部派发事实）的重复送达查账；换摘要拒绝。
		if r.Request.Project != 0 && r.Request != req {
			return errors.New("sync ID already bound to different inputs")
		}
		if r.Result != nil {
			return m.emit("source.sync.result", *r.Result)
		}
		return m.emit("source.sync.accepted", map[string]string{"sync_id": req.ID})
	case !errors.Is(err, os.ErrNotExist):
		m.mu.Unlock()
		return err
	case m.active != "" || m.unsaved != nil:
		m.mu.Unlock()
		return errors.New("another source sync is running or awaiting durable result")
	}
	remaining := time.Until(deadline)
	if remaining <= 0 {
		r = record{Request: req, Result: &Result{req.ID, "cancelled", "", "", "", "dispatch deadline expired"}}
		if err = saveRecord(m.dir, r); err == nil {
			m.pending[req.ID] = r
		}
		m.mu.Unlock()
		m.Wake()
		return err
	}
	if err = saveRecord(m.dir, record{Request: req}); err != nil {
		m.mu.Unlock()
		return err
	}
	ctx, cancel := context.WithCancel(m.ctx)
	m.active, m.cancel = req.ID, cancel
	m.wg.Add(1)
	m.mu.Unlock()
	go func() {
		defer m.wg.Done()
		defer cancel()
		if err := m.emit("source.sync.accepted", map[string]string{"sync_id": req.ID}); err != nil {
			m.logger.Debug("source sync acceptance delivery deferred", "sync_id", req.ID)
		}
		result := m.execute(ctx, req, func(stage string, done int64) {
			if err := m.emit("source.sync.progress", map[string]any{"sync_id": req.ID, "stage": stage, "bytes_done": done}); err != nil {
				m.logger.Debug("source sync progress unavailable", "sync_id", req.ID)
			}
		})
		r := record{Request: req, Result: &result}
		m.mu.Lock()
		m.active, m.cancel = "", nil
		if err := saveRecord(m.dir, r); err != nil {
			m.unsaved = &r
			m.logger.Error("source sync result pending disk retry", "sync_id", req.ID, "error", err)
		} else {
			m.pending[req.ID] = r
		}
		m.mu.Unlock()
		m.Wake()
	}()
	return nil
}

func (m *Manager) Cancel(id string) error {
	if !syncID.MatchString(id) {
		return errors.New("invalid sync ID")
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.active == id {
		m.cancel()
		return nil
	}
	r, err := readRecord(m.dir, id)
	if errors.Is(err, os.ErrNotExist) {
		r = record{Request: Request{ID: id}, Result: &Result{id, "cancelled", "", "", "", "cancelled before execution"}}
		if err = saveRecord(m.dir, r); err != nil {
			return err
		}
	} else if err != nil {
		return err
	}
	if r.Result != nil && !r.Acked {
		m.pending[id] = r
	}
	m.Wake()
	return nil
}

func (m *Manager) Acknowledge(id string) error {
	if !syncID.MatchString(id) {
		return errors.New("invalid acknowledgement sync ID")
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	r, ok := m.pending[id]
	if !ok {
		return nil
	}
	r.Acked = true
	if err := saveRecord(m.dir, r); err != nil {
		return err
	}
	delete(m.pending, id)
	return nil
}
