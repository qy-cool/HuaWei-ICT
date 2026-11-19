package syncs

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

/** 回环中控：提供 grant 与 content 端点，可注入半包中断与字节损坏。 */
type fakeControl struct {
	mu            sync.Mutex
	bundle        []byte
	commit, tree  string
	truncateAt    int  // >0 时首次 content 只写这些字节（A2-01 半包）
	interrupted   bool // 半包是否已注入过
	flipped       bool // true 时翻转一个字节（A2-03 损坏）
	rangeRequests []string
	grantCount    int
}

func (f *fakeControl) handler(t *testing.T) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /api/source-agent/syncs/", func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasSuffix(r.URL.Path, "/grant") {
			http.Error(w, "unexpected grant path", http.StatusNotFound)
			return
		}
		if r.Header.Get("X-Agent-Token") != "node-token" {
			http.Error(w, "bad agent token", http.StatusUnauthorized)
			return
		}
		f.mu.Lock()
		f.grantCount++
		grantCount := f.grantCount
		f.mu.Unlock()
		fmt.Fprintf(w, `{"code":0,"message":"success","data":{"grantId":"11111111-1111-1111-1111-111111111111",`+
			`"secret":"grant-secret-%d","expiresAt":"2999-01-01T00:00:00Z","deadline":"2999-01-01T00:00:00Z",`+
			`"projectId":1,"repositoryId":8,"snapshotId":"%s","generation":1,`+
			`"commitSha":"%s","treeSha":"%s","bundleSha256":"%s","byteCount":%d}}`,
			grantCount, testSnapshot, f.commit, f.tree, sha256Of(f.bundle), len(f.bundle))
	})
	mux.HandleFunc("GET /api/source-agent/syncs/", func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasSuffix(r.URL.Path, "/content") {
			http.Error(w, "unexpected content path", http.StatusNotFound)
			return
		}
		if r.Header.Get("X-Agent-Token") != "node-token" {
			http.Error(w, "bad agent token", http.StatusUnauthorized)
			return
		}
		if !strings.HasPrefix(r.Header.Get("X-Sync-Grant"), "11111111-1111-1111-1111-111111111111.") {
			http.Error(w, "bad sync grant", http.StatusUnauthorized)
			return
		}
		f.mu.Lock()
		defer f.mu.Unlock()
		bundle := f.bundle
		if f.flipped {
			bundle = append([]byte(nil), f.bundle...)
			bundle[len(bundle)/2] ^= 0xff
		}
		if rangeHeader := r.Header.Get("Range"); rangeHeader != "" {
			f.rangeRequests = append(f.rangeRequests, rangeHeader)
		}
		var offset int64
		if rangeHeader := r.Header.Get("Range"); strings.HasPrefix(rangeHeader, "bytes=") {
			if _, err := fmt.Sscanf(rangeHeader, "bytes=%d-", &offset); err != nil || offset >= int64(len(bundle)) {
				w.WriteHeader(http.StatusRequestedRangeNotSatisfiable)
				return
			}
		}
		// A2-01：首次全量下载只送达一半字节（响应少于声明长度即断链）。
		if f.truncateAt > 0 && !f.interrupted && offset == 0 {
			f.interrupted = true
			w.Header().Set("Content-Length", fmt.Sprintf("%d", len(bundle)))
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(bundle[:f.truncateAt])
			return
		}
		if offset > 0 {
			w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", offset, len(bundle)-1, len(bundle)))
			w.WriteHeader(http.StatusPartialContent)
			_, _ = w.Write(bundle[offset:])
			return
		}
		w.Header().Set("Content-Length", fmt.Sprintf("%d", len(bundle)))
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(bundle)
	})
	return mux
}

func sha256Of(data []byte) string {
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:])
}

func gitRun(t *testing.T, dir string, args ...string) string {
	t.Helper()
	command := exec.Command("git", args...)
	command.Dir = dir
	command.Env = append(os.Environ(),
		"GIT_AUTHOR_NAME=t", "GIT_AUTHOR_EMAIL=t@example.com",
		"GIT_COMMITTER_NAME=t", "GIT_COMMITTER_EMAIL=t@example.com")
	output, err := command.CombinedOutput()
	if err != nil {
		t.Fatalf("git %v: %v: %s", args, err, output)
	}
	return strings.TrimSpace(string(output))
}

/** 用真实 git 生成含私有 ref 的 bundle，返回完整派发事实。 */
func bundleFixture(t *testing.T) (bundle []byte, commit, tree string) {
	t.Helper()
	work := t.TempDir()
	gitRun(t, work, "init", "-q", "--initial-branch=main", ".")
	if err := os.WriteFile(filepath.Join(work, "orders.txt"), []byte("baseline\n"), 0600); err != nil {
		t.Fatal(err)
	}
	gitRun(t, work, "add", "orders.txt")
	gitRun(t, work, "commit", "-qm", "baseline")
	commit = gitRun(t, work, "rev-parse", "HEAD")
	tree = gitRun(t, work, "rev-parse", "HEAD^{tree}")
	privateRef := "refs/cloide/snapshots/" + testSnapshot
	gitRun(t, work, "update-ref", privateRef, commit)
	bundlePath := filepath.Join(work, "bundle.git")
	gitRun(t, work, "bundle", "create", bundlePath, privateRef)
	data, err := os.ReadFile(bundlePath)
	if err != nil {
		t.Fatal(err)
	}
	return data, commit, tree
}

func dispatchRequest(commit, tree, bundleSHA string, bytes int64) Request {
	return Request{testSyncID, 1, 8, testSnapshot, 1, commit, tree, bundleSHA, bytes, "2999-01-01T00:00:00Z"}
}

func TestExecutorInstallsVerifiedSnapshot(t *testing.T) {
	// 正常路径：领取授权 → 下载 → 摘要与对象核验 → 原子安装；staging 清理，无残留。
	data, commit, tree := bundleFixture(t)
	control := &fakeControl{bundle: data, commit: commit, tree: tree}
	server := httptest.NewServer(control.handler(t))
	defer server.Close()
	root := t.TempDir()
	executor := NewExecutor(server.URL, "node-token", 2, root)
	var stages []string
	result := executor.Execute(context.Background(), dispatchRequest(commit, tree, sha256Of(data), int64(len(data))),
		func(stage string, done int64) { stages = append(stages, stage) })
	if result.Status != "ready" || result.Commit != commit || result.Tree != tree {
		t.Fatalf("unexpected result %+v (commit=%s tree=%s)", result, commit, tree)
	}
	final := filepath.Join(root, "1", "8", testSnapshot)
	// 就绪对象是可独立核验的裸仓库：对象与私有 ref 与派发事实一致（无令牌子进程核验路径）。
	if got := gitRun(t, "", "--git-dir", final, "rev-parse", "--verify", commit+"^{commit}"); got != commit {
		t.Fatalf("installed commit=%s want %s", got, commit)
	}
	if got := gitRun(t, "", "--git-dir", final, "rev-parse", "--verify", commit+"^{tree}"); got != tree {
		t.Fatalf("installed tree=%s want %s", got, tree)
	}
	if pinned := gitRun(t, "", "--git-dir", final, "rev-parse", "refs/cloide/snapshots/"+testSnapshot); pinned != commit {
		t.Fatalf("pinned ref=%s want %s", pinned, commit)
	}
	if _, err := os.Stat(filepath.Join(root, ".staging", testSyncID)); !os.IsNotExist(err) {
		t.Fatal("staging must be cleaned after a ready install")
	}
	if !contains(stages, "verifying") || !contains(stages, "transferring") {
		t.Fatalf("expected transferring and verifying progress, got %v", stages)
	}
}

func TestExecutorResumesInterruptedTransferWithRange(t *testing.T) {
	// A2-01 半包与凭据过期：首次下载中途断链，重试领取新授权并按 Range 续传，
	// 最终只有一个 ready 目录；续传起点与首次已送达字节数一致。
	data, commit, tree := bundleFixture(t)
	half := len(data) / 2
	control := &fakeControl{bundle: data, commit: commit, tree: tree, truncateAt: half}
	server := httptest.NewServer(control.handler(t))
	defer server.Close()
	root := t.TempDir()
	executor := NewExecutor(server.URL, "node-token", 2, root)
	result := executor.Execute(context.Background(), dispatchRequest(commit, tree, sha256Of(data), int64(len(data))),
		func(string, int64) {})
	if result.Status != "ready" {
		t.Fatalf("expected ready after resume, got %+v", result)
	}
	control.mu.Lock()
	defer control.mu.Unlock()
	if !control.interrupted || len(control.rangeRequests) == 0 {
		t.Fatalf("expected interruption then Range resume, interrupted=%v ranges=%v",
			control.interrupted, control.rangeRequests)
	}
	if first := control.rangeRequests[0]; first != fmt.Sprintf("bytes=%d-", half) {
		t.Fatalf("resume must start at the interrupted offset %d, got %s", half, first)
	}
	if control.grantCount < 2 {
		t.Fatalf("expected a fresh grant after the interrupted transfer, got %d", control.grantCount)
	}
}

func TestExecutorRejectsCorruptedBundleWithoutHalfwayState(t *testing.T) {
	// A2-03 单字节损坏：摘要核验失败，返回 digest_mismatch；没有可执行的半成品，
	// 原 ready 快照不受影响（final 不存在），清理后可明确重试。
	data, commit, tree := bundleFixture(t)
	control := &fakeControl{bundle: data, commit: commit, tree: tree, flipped: true}
	server := httptest.NewServer(control.handler(t))
	defer server.Close()
	root := t.TempDir()
	executor := NewExecutor(server.URL, "node-token", 2, root)
	result := executor.Execute(context.Background(), dispatchRequest(commit, tree, sha256Of(data), int64(len(data))),
		func(string, int64) {})
	if result.Status != "failed" || !strings.Contains(result.Output, "digest_mismatch") {
		t.Fatalf("expected digest_mismatch failure, got %+v", result)
	}
	if _, err := os.Stat(filepath.Join(root, "1", "8", testSnapshot)); !os.IsNotExist(err) {
		t.Fatal("no ready directory may exist for a corrupted transfer")
	}
	if _, err := os.Stat(filepath.Join(root, ".staging", testSyncID, "bundle.git")); !os.IsNotExist(err) {
		t.Fatal("corrupted bundle bytes must be removed from staging")
	}
}

func TestExecutorRejectsRequestBeyondDiskCapacity(t *testing.T) {
	// A2-03 容量不足：同步根所在卷放不下该 bundle 时明确失败，不产生半成品。
	data, commit, tree := bundleFixture(t)
	control := &fakeControl{bundle: data, commit: commit, tree: tree}
	server := httptest.NewServer(control.handler(t))
	defer server.Close()
	root := t.TempDir()
	executor := NewExecutor(server.URL, "node-token", 2, root)
	result := executor.Execute(context.Background(), dispatchRequest(commit, tree, sha256Of(data), 1<<62),
		func(string, int64) {})
	if result.Status != "failed" || !strings.Contains(result.Output, "capacity") {
		t.Fatalf("expected capacity failure, got %+v", result)
	}
	if _, err := os.Stat(filepath.Join(root, "1", "8", testSnapshot)); !os.IsNotExist(err) {
		t.Fatal("no ready directory may exist for a capacity failure")
	}
}

func contains(values []string, want string) bool {
	for _, value := range values {
		if value == want {
			return true
		}
	}
	return false
}
