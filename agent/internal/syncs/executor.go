package syncs

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"time"

	"github.com/shirou/gopsutil/v3/disk"
)

/**
 * 节点侧同步执行器：领取短期授权 → 下载 bundle（Range 续传，授权过期自动重领）
 * → 校验摘要 → 临时目录导入并核验 Git 对象 → 同文件系统原子安装。节点凭据与
 * 短期授权都不写入账本、安装目录或日志；中断保留已下载分段供续传。
 */
type Executor struct {
	Origin   string
	Token    string
	ServerID int64
	Root     string
	Client   *http.Client
}

func NewExecutor(origin, token string, server int64, root string) *Executor {
	return &Executor{Origin: origin, Token: token, ServerID: server, Root: root,
		Client: &http.Client{Transport: &http.Transport{
			DialContext:           (&net.Dialer{Timeout: 8 * time.Second}).DialContext,
			ResponseHeaderTimeout: 30 * time.Second,
		}}}
}

type grantData struct {
	GrantID     string `json:"grantId"`
	Secret      string `json:"secret"`
	ExpiresAt   string `json:"expiresAt"`
	BundleSHA   string `json:"bundleSha256"`
	ByteCount   int64  `json:"byteCount"`
	Commit      string `json:"commitSha"`
	Tree        string `json:"treeSha"`
	Snapshot    string `json:"snapshotId"`
	Generation  int64  `json:"generation"`
	ProjectID   int64  `json:"projectId"`
	Repository  int64  `json:"repositoryId"`
}

func (e *Executor) Execute(ctx context.Context, req Request, progress func(string, int64)) Result {
	staging := filepath.Join(e.Root, ".staging", req.ID)
	if err := os.MkdirAll(staging, 0700); err != nil {
		return failed(req, "staging unavailable: "+err.Error())
	}
	// 容量预检：同步根目录所在卷放不下该 bundle 时明确失败，不产生半成品。
	if usage, err := disk.Usage(e.Root); err == nil && usage.Free < uint64(req.Bytes) {
		return failed(req, "capacity: only "+strconv.FormatUint(usage.Free, 10)+" bytes free on sync root")
	}
	progress("transferring", 0)
	bundleFile := filepath.Join(staging, "bundle.git")
	if err := e.download(ctx, req, bundleFile, progress); err != nil {
		if ctx.Err() != nil {
			// 中断保留已下载分段，同一 syncId 下次续传（A2-01）。
			return Result{req.ID, "cancelled", "", "", "", "download interrupted: " + err.Error()}
		}
		return failed(req, "transfer failed: "+err.Error())
	}
	progress("verifying", req.Bytes)
	sha, size, err := fileDigest(bundleFile)
	if err != nil || size != req.Bytes || sha != req.BundleSHA {
		os.Remove(bundleFile)
		return failed(req, fmt.Sprintf("digest_mismatch: got sha=%s bytes=%d", sha, size))
	}
	commit, tree, err := verifyBundle(ctx, staging, req)
	if err != nil {
		if ctx.Err() != nil {
			return Result{req.ID, "cancelled", "", "", "", "verification interrupted: " + err.Error()}
		}
		return failed(req, "verification failed: "+err.Error())
	}
	final := filepath.Join(e.Root, strconv.FormatInt(req.Project, 10),
		strconv.FormatInt(req.Repository, 10), req.Snapshot)
	if err := install(staging, final); err != nil {
		if ctx.Err() != nil {
			return Result{req.ID, "cancelled", "", "", "", "install interrupted: " + err.Error()}
		}
		return failed(req, "install failed: "+err.Error())
	}
	os.RemoveAll(staging)
	return Result{req.ID, "ready", commit, tree, sha, "installed"}
}

func failed(req Request, output string) Result {
	return Result{req.ID, "failed", "", "", "", output}
}

// download 在授权过期或网络中断时重领授权并按已下载字节数续传，最多三轮。
func (e *Executor) download(ctx context.Context, req Request, dest string, progress func(string, int64)) error {
	var lastErr error
	for attempt := 0; attempt < 3; attempt++ {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		grant, err := e.claim(ctx, req.ID)
		if err != nil {
			lastErr = err
			continue
		}
		if grant.BundleSHA != req.BundleSHA || grant.ByteCount != req.Bytes ||
			grant.Commit != req.Commit || grant.Tree != req.Tree || grant.Snapshot != req.Snapshot {
			return errors.New("control plane bundle facts differ from the dispatched envelope")
		}
		if err = e.fetch(ctx, req, grant, dest, progress); err == nil {
			return nil
		}
		lastErr = err
	}
	if ctx.Err() != nil {
		return ctx.Err()
	}
	return lastErr
}

func (e *Executor) claim(ctx context.Context, syncID string) (*grantData, error) {
	url := fmt.Sprintf("%s/api/source-agent/syncs/%s/grant?serverId=%d", e.Origin, syncID, e.ServerID)
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, url, nil)
	if err != nil {
		return nil, err
	}
	request.Header.Set("X-Agent-Token", e.Token)
	response, err := e.Client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("grant returned %s", response.Status)
	}
	var envelope struct {
		Code int       `json:"code"`
		Data *grantData `json:"data"`
	}
	if err = json.NewDecoder(io.LimitReader(response.Body, 1<<20)).Decode(&envelope); err != nil {
		return nil, err
	}
	if envelope.Code != 0 || envelope.Data == nil || envelope.Data.GrantID == "" || envelope.Data.Secret == "" {
		return nil, errors.New("invalid grant response")
	}
	return envelope.Data, nil
}

func (e *Executor) fetch(ctx context.Context, req Request, grant *grantData, dest string, progress func(string, int64)) error {
	info, statErr := os.Stat(dest)
	offset := int64(0)
	switch {
	case statErr == nil && info.Size() > 0 && info.Size() < req.Bytes:
		// 已有分段且小于总量：从当前字节数续传（同一摘要复用）。
		offset = info.Size()
	case statErr == nil:
		// 完整或超长的残留文件：丢弃重新下载。
		if err := os.Truncate(dest, 0); err != nil {
			return err
		}
	case !errors.Is(statErr, os.ErrNotExist):
		return statErr
	}
	url := fmt.Sprintf("%s/api/source-agent/syncs/%s/content?serverId=%d", e.Origin, req.ID, e.ServerID)
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return err
	}
	request.Header.Set("X-Agent-Token", e.Token)
	request.Header.Set("X-Sync-Grant", grant.GrantID+"."+grant.Secret)
	if offset > 0 {
		request.Header.Set("Range", "bytes="+strconv.FormatInt(offset, 10)+"-")
	}
	response, err := e.Client.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK && response.StatusCode != http.StatusPartialContent {
		return fmt.Errorf("content returned %s", response.Status)
	}
	flags := os.O_CREATE | os.O_WRONLY
	if response.StatusCode == http.StatusPartialContent {
		flags |= os.O_APPEND
	} else {
		flags |= os.O_TRUNC
	}
	file, err := os.OpenFile(dest, flags, 0600)
	if err != nil {
		return err
	}
	// 进度按 1 MiB 节流：大 bundle 的每 64 KiB 写入不逐段上报，避免 WSS 帧与
	// 中控逐帧事务风暴；开始值已在 Execute 中上报，终值由 verifying 上报。
	done := offset
	reported := offset
	buffer := make([]byte, 64*1024)
	for {
		count, readErr := response.Body.Read(buffer)
		if count > 0 {
			if _, writeErr := file.Write(buffer[:count]); writeErr != nil {
				file.Close()
				return writeErr
			}
			done += int64(count)
			if done-reported >= 1<<20 {
				progress("transferring", done)
				reported = done
			}
		}
		if readErr == io.EOF {
			break
		}
		if readErr != nil {
			file.Close()
			return readErr
		}
		if ctx.Err() != nil {
			file.Close()
			return ctx.Err()
		}
	}
	return file.Close()
}

// verifyBundle 把 bundle 导入临时裸仓库并核验 commit 与 tree（在无任何访问令牌的
// 子进程内执行），返回实际对象 SHA。
func verifyBundle(ctx context.Context, staging string, req Request) (string, string, error) {
	repo := filepath.Join(staging, "repo.git")
	if out, err := runGit(ctx, "", "init", "-q", "--bare", repo); err != nil {
		return "", "", fmt.Errorf("git init: %v: %s", err, out)
	}
	privateRef := "refs/cloide/snapshots/" + req.Snapshot
	if out, err := runGit(ctx, repo, "fetch", "-q", filepath.Join(staging, "bundle.git"),
		privateRef+":"+privateRef); err != nil {
		return "", "", fmt.Errorf("git fetch from bundle: %v: %s", err, out)
	}
	// The expected commit can exist as an ancestor even when the snapshot ref points elsewhere.
	commit, err := runGit(ctx, repo, "rev-parse", "--verify", privateRef+"^{commit}")
	if err != nil {
		return "", "", fmt.Errorf("commit missing from bundle: %v", err)
	}
	tree, err := runGit(ctx, repo, "rev-parse", "--verify", commit+"^{tree}")
	if err != nil {
		return "", "", fmt.Errorf("tree missing from bundle: %v", err)
	}
	if commit != req.Commit || tree != req.Tree {
		return "", "", fmt.Errorf("object mismatch: commit=%s tree=%s", commit, tree)
	}
	return commit, tree, nil
}

// install 原子更名就绪；目标已存在时视为同快照对象的重复派发去重。
func install(staging, final string) error {
	if info, err := os.Stat(final); err == nil && info.IsDir() {
		return nil // 已就绪的同快照对象：保留唯一有效结果。
	}
	if err := os.MkdirAll(filepath.Dir(final), 0700); err != nil {
		return err
	}
	return os.Rename(filepath.Join(staging, "repo.git"), final)
}

func runGit(ctx context.Context, gitDir string, args ...string) (string, error) {
	var command *exec.Cmd
	if gitDir == "" {
		command = exec.CommandContext(ctx, "git", args...)
	} else {
		command = exec.CommandContext(ctx, "git", append([]string{"--git-dir", gitDir}, args...)...)
	}
	output, err := command.Output()
	if err != nil {
		if exitErr, ok := err.(*exec.ExitError); ok {
			return "", fmt.Errorf("%v: %s", err, exitErr.Stderr)
		}
		return "", err
	}
	return string(trimSpace(output)), nil
}

func trimSpace(b []byte) []byte {
	for len(b) > 0 && (b[len(b)-1] == '\n' || b[len(b)-1] == '\r' || b[len(b)-1] == ' ') {
		b = b[:len(b)-1]
	}
	return b
}

func fileDigest(path string) (string, int64, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", 0, err
	}
	defer file.Close()
	digest := sha256.New()
	size, err := io.Copy(digest, file)
	if err != nil {
		return "", 0, err
	}
	return hex.EncodeToString(digest.Sum(nil)), size, nil
}
