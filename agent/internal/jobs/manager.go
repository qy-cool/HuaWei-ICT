package jobs

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

type Execute func(context.Context, Request, func(string)) Result
type Send func(context.Context, string, any) error

// ErrLedgerCorrupt 标记账本的记录级损坏（异物文件名、单条解析或补写失败）。
// 调用方应停用作业执行器但保持 Agent 主体运行；目录级错误（锁、目录不可读）不是它的实例。
var ErrLedgerCorrupt = errors.New("job ledger corrupted")

type Manager struct {
	dir     string
	lock    *os.File
	logger  *slog.Logger
	execute Execute
	send    Send
	timeout time.Duration
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

func Open(ctx context.Context, base, backend string, server int64, timeout time.Duration, execute Execute, send Send, logger *slog.Logger) (*Manager, error) {
	dir := recordDirectory(base, backend, server)
	if err := os.MkdirAll(dir, 0700); err != nil {
		return nil, err
	}
	lock, err := lockLedger(dir)
	if err != nil {
		return nil, fmt.Errorf("lock node job ledger: %w", err)
	}
	child, stop := context.WithCancel(ctx)
	m := &Manager{dir: dir, lock: lock, ctx: child, stop: stop, logger: logger, execute: execute, send: send, timeout: timeout, wake: make(chan struct{}, 1), pending: make(map[string]record)}
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
	// 记录级损坏降级：异物文件名、单条解析失败或补写失败只停用执行器（Open 返回
	// ErrLedgerCorrupt，调用方记日志后继续运行），采集与告警不受影响；已收集的
	// pending 随降级一并作废，不从可能损坏的账本做部分重放。
	corrupt := false
	for _, entry := range entries {
		if filepath.Ext(entry.Name()) != ".json" {
			continue
		}
		id := strings.TrimSuffix(entry.Name(), ".json")
		if !jobID.MatchString(id) {
			m.logger.Error("job ledger corrupt entry skipped", "file", entry.Name())
			corrupt = true
			continue
		}
		r, err := readRecord(m.dir, id)
		if err != nil {
			m.logger.Error("job ledger corrupt record skipped", "job_id", id, "error", err)
			corrupt = true
			continue
		}
		if r.Result == nil {
			r.Result = &Result{id, "uncertain", -1, "agent restarted before the test result was durably recorded"}
			if err = saveRecord(m.dir, r); err != nil {
				m.logger.Error("job ledger uncertain rewrite failed", "job_id", id, "error", err)
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
			m.logger.Error("job ledger write failed", "error", err)
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
	// Negotiate on every connection before replay. Polling while active lets
	// the control plane deliver cancellation after a disconnect.
	if err := m.emit("job.ready", map[string]int{"protocol_version": 1}); err != nil {
		m.logger.Debug("job poll deferred", "error", err)
		return
	}
	for _, result := range results {
		if m.emit("job.result", result) != nil {
			return
		}
	}
}

func (m *Manager) Accept(req Request) error {
	if !jobID.MatchString(req.ID) || req.Project <= 0 || !slug.MatchString(req.Repository) || !fullSHA.MatchString(req.Commit) {
		return errors.New("invalid repository test request")
	}
	deadline, err := time.Parse(time.RFC3339, req.Deadline)
	if err != nil {
		return fmt.Errorf("invalid job deadline: %w", err)
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
		if r.Request.Project != 0 && r.Request != req {
			return errors.New("job ID already bound to different inputs")
		}
		if r.Result != nil {
			return m.emit("job.result", *r.Result)
		}
		return m.emit("job.accepted", map[string]string{"job_id": req.ID})
	case !errors.Is(err, os.ErrNotExist):
		m.mu.Unlock()
		return err
	case m.active != "" || m.unsaved != nil:
		m.mu.Unlock()
		return errors.New("another job is running or awaiting durable result")
	}
	remaining := time.Until(deadline)
	if remaining <= 0 {
		r = record{Request: req, Result: &Result{req.ID, "cancelled", -1, "dispatch deadline expired"}}
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
	ctx, cancel := context.WithTimeout(m.ctx, min(m.timeout, remaining))
	m.active, m.cancel = req.ID, cancel
	m.wg.Add(1)
	m.mu.Unlock()
	go func() {
		defer m.wg.Done()
		defer cancel()
		if err := m.emit("job.accepted", map[string]string{"job_id": req.ID}); err != nil {
			m.logger.Debug("job acceptance delivery deferred", "job_id", req.ID)
		}
		seq := 0
		result := m.execute(ctx, req, func(s string) {
			if seq >= 256 {
				return
			}
			seq++
			if err := m.emit("job.log", map[string]any{"job_id": req.ID, "seq": seq, "text": s}); err != nil {
				m.logger.Debug("live job log unavailable", "job_id", req.ID)
			}
		})
		r := record{Request: req, Result: &result}
		m.mu.Lock()
		m.active, m.cancel = "", nil
		if err := saveRecord(m.dir, r); err != nil {
			m.unsaved = &r
			m.logger.Error("test result pending disk retry", "job_id", req.ID, "error", err)
		} else {
			m.pending[req.ID] = r
		}
		m.mu.Unlock()
		m.Wake()
	}()
	return nil
}

func (m *Manager) Cancel(id string) error {
	if !jobID.MatchString(id) {
		return errors.New("invalid job ID")
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.active == id {
		m.cancel()
		return nil
	}
	r, err := readRecord(m.dir, id)
	if errors.Is(err, os.ErrNotExist) {
		r = record{Request: Request{ID: id}, Result: &Result{id, "cancelled", -1, "cancelled before execution"}}
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
	if !jobID.MatchString(id) {
		return errors.New("invalid acknowledgement job ID")
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
