// Package jobs executes repository tests explicitly registered on a development node.
package jobs

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"time"
)

var (
	slug    = regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}$`)
	fullSHA = regexp.MustCompile(`^[0-9a-f]{40}$`)
	jobID   = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
)

type Request struct {
	ID         string `json:"job_id"`
	Project    int64  `json:"project_id"`
	Repository string `json:"repository"`
	Commit     string `json:"commit_sha"`
	Deadline   string `json:"deadline"`
}

type Result struct {
	ID       string `json:"job_id"`
	Status   string `json:"status"`
	ExitCode int    `json:"exit_code"`
	Output   string `json:"output"`
}

type repository struct {
	Project      int64    `json:"project_id"`
	Name         string   `json:"name"`
	GitDir       string   `json:"bare_git_dir"`
	Command      []string `json:"test_argv"`
	ArtifactKind string   `json:"artifact_kind,omitempty"`
}

type manifest struct {
	Toolchains   []string     `json:"toolchains,omitempty"`
	State        string       `json:"state_directory"`
	RootFS       string       `json:"toolchain_root"`
	SyncRoot     string       `json:"sync_root,omitempty"`
	Timeout      int          `json:"timeout_seconds"`
	Repositories []repository `json:"repositories"`
}

// LoadRunner reads administrator-owned local configuration. The wire protocol
// never supplies executable names, mount paths, environment or an upstream URL.
func LoadRunner(path string, registration *Registration) (string, time.Duration, Execute, error) {
	if runtime.GOOS != "linux" || os.Geteuid() == 0 {
		return "", 0, nil, errors.New("repository tests require a dedicated non-root Linux account")
	}
	file, err := os.Open(path)
	if err != nil {
		return "", 0, nil, err
	}
	defer file.Close()
	var cfg manifest
	decoder := json.NewDecoder(io.LimitReader(file, 1<<20))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&cfg); err != nil {
		return "", 0, nil, err
	}
	if decoder.Decode(new(any)) != io.EOF {
		return "", 0, nil, errors.New("invalid trailing manifest data")
	}
	cleanAbs := func(p string) bool { return filepath.IsAbs(p) && filepath.Clean(p) == p && p != "/" }
	if len(cfg.Repositories) > 128 || len(cfg.Toolchains) > 32 {
		return "", 0, nil, errors.New("too many job registrations")
	}
	for _, label := range cfg.Toolchains {
		if !slug.MatchString(label) {
			return "", 0, nil, errors.New("invalid toolchain label")
		}
	}
	if !cleanAbs(cfg.State) || !cleanAbs(cfg.RootFS) || cfg.Timeout < 1 || cfg.Timeout > 1800 {
		return "", 0, nil, errors.New("invalid job ledger, toolchain root or timeout")
	}
	if len(cfg.Repositories) == 0 && !cleanAbs(cfg.SyncRoot) {
		return "", 0, nil, errors.New("empty repository list requires a clean absolute sync_root")
	}
	resolveDir := func(p string) (string, error) {
		real, err := filepath.EvalSymlinks(p)
		if err != nil {
			return "", err
		}
		info, err := os.Stat(real)
		if err != nil {
			return "", err
		}
		if !info.IsDir() || !cleanAbs(real) {
			return "", fmt.Errorf("expected dedicated directory: %s", p)
		}
		return real, nil
	}
	cfg.RootFS, err = resolveDir(cfg.RootFS)
	if err != nil {
		return "", 0, nil, err
	}
	if err = os.MkdirAll(cfg.State, 0700); err != nil {
		return "", 0, nil, err
	}
	cfg.State, err = resolveDir(cfg.State)
	if err != nil {
		return "", 0, nil, err
	}
	if cfg.State == cfg.RootFS || strings.HasPrefix(cfg.State, cfg.RootFS+"/") {
		return "", 0, nil, errors.New("ledger must be outside the toolchain filesystem")
	}
	for i, repo := range cfg.Repositories {
		if repo.ArtifactKind != "" && repo.ArtifactKind != "java-jar" && repo.ArtifactKind != "static-tar" {
			return "", 0, nil, errors.New("invalid artifact_kind")
		}
		if repo.Project < 1 || !slug.MatchString(repo.Name) || !cleanAbs(repo.GitDir) || len(repo.Command) == 0 || !cleanAbs(repo.Command[0]) {
			return "", 0, nil, errors.New("repository needs project_id, name, bare_git_dir and absolute test executable")
		}
		for _, previous := range cfg.Repositories[:i] {
			if previous.Project == repo.Project && previous.Name == repo.Name {
				return "", 0, nil, errors.New("duplicate project repository")
			}
		}
		objects, err := resolveDir(filepath.Join(repo.GitDir, "objects"))
		if err != nil {
			return "", 0, nil, err
		}
		cfg.Repositories[i].GitDir = objects
	}
	bwrap, err := exec.LookPath("bwrap")
	if err != nil {
		return "", 0, nil, err
	}
	prlimit, err := exec.LookPath("prlimit")
	if err != nil {
		return "", 0, nil, err
	}
	run := func(ctx context.Context, req Request, log func(string)) Result {
		for _, repo := range cfg.Repositories {
			if repo.Project == req.Project && repo.Name == req.Repository {
				return runSandbox(ctx, req, cfg, repo, prlimit, bwrap, log)
			}
		}
		return Result{req.ID, "failed", -1, "repository is not registered for this project"}
	}
	if registration != nil {
		registration.Toolchains = append([]string{}, cfg.Toolchains...)
		if registration.Repositories == nil {
			registration.Repositories = []RepositoryAlias{}
		}
		for _, repo := range cfg.Repositories {
			registration.Repositories = append(registration.Repositories, RepositoryAlias{Project: repo.Project, Name: repo.Name})
		}
		// 配置了同步根目录的节点声明 source.sync.v1；中控据此决定是否派发源码同步。
		if cfg.SyncRoot != "" {
			registration.Actions = []string{"source.sync.v1"}
		}
	}
	return cfg.State, time.Duration(cfg.Timeout) * time.Second, run, nil
}

// The shell program is constant. Only the validated SHA and local administrator's
// argument vector are passed as positional arguments, never interpolated as code.
const checkoutAndTest = `set -eu
git init -q .
commit=$(git rev-parse --verify "$1^{commit}")
test "$commit" = "$1"
git -c advice.detachedHead=false checkout -q --detach "$commit"
shift
exec "$@"`

func runSandbox(ctx context.Context, req Request, cfg manifest, repo repository, prlimit, bwrap string, log func(string)) Result {
	var captured string
	if repo.ArtifactKind != "" {
		var err error
		captured, err = prepareCapture(cfg.State, req.ID)
		if err != nil {
			return Result{req.ID, "failed", -1, "cannot reserve the private build output file"}
		}
	}
	args := []string{"--nproc=128", "--nofile=512", "--as=4294967296", "--fsize=134217728", "--cpu=" + strconv.Itoa(cfg.Timeout), "--", bwrap,
		"--unshare-all", "--die-with-parent", "--new-session", "--cap-drop", "ALL", "--clearenv",
		"--ro-bind", cfg.RootFS, "/", "--ro-bind", repo.GitDir, "/objects", "--dev", "/dev", "--tmpfs", "/proc", "--remount-ro", "/proc",
		"--size", "134217728", "--tmpfs", "/tmp", "--size", "536870912", "--tmpfs", "/work", "--chdir", "/work",
		"--setenv", "HOME", "/tmp", "--setenv", "PATH", "/usr/local/bin:/usr/bin:/bin", "--setenv", "GIT_CONFIG_NOSYSTEM", "1",
		"--setenv", "GIT_ALTERNATE_OBJECT_DIRECTORIES", "/objects"}
	if captured != "" {
		args = append(args, "--bind", captured, "/tmp/cloide-build-output", "--setenv", "CLOIDE_BUILD_OUTPUT", "/tmp/cloide-build-output")
	}
	args = append(args, "--", "/bin/sh", "-c", checkoutAndTest, "job", req.Commit)
	cmd := exec.CommandContext(ctx, prlimit, append(args, repo.Command...)...)
	cmd.Env = []string{"PATH=/usr/bin:/bin", "LANG=C"}
	cmd.WaitDelay = 2 * time.Second
	out := &limitedReport{log: log}
	cmd.Stdout, cmd.Stderr = out, out
	err := cmd.Run()
	result := Result{req.ID, "succeeded", 0, out.String()}
	if err != nil {
		result.Status, result.ExitCode = "failed", -1
		var exit *exec.ExitError
		if errors.As(err, &exit) {
			result.ExitCode = exit.ExitCode()
		}
		if result.Output == "" {
			result.Output = "sandbox/test process could not complete"
		}
	}
	result = classifyCancellation(ctx.Err(), result)
	if captured != "" {
		if result.Status == "succeeded" {
			if err := completeCapture(captured, req, repo.ArtifactKind); err != nil {
				result.Status, result.ExitCode = "failed", -1
				result.Output += "\nmissing or invalid build artifact"
			}
		}
		if result.Status != "succeeded" {
			_ = os.Remove(captured)
		}
	}
	result.Output = limitString(result.Output, 8192)
	return result
}

// classifyCancellation 把执行结束后的上下文取消原因归入终态：deadline 到期为
// failed（输出前缀标注，与 cancelled 区分），其余取消（含主动 cancel）为
// cancelled。抽为纯函数以便跨平台直测，沙箱不可用的环境也能覆盖该分类。
func classifyCancellation(ctxErr error, result Result) Result {
	if ctxErr == nil {
		return result
	}
	result.ExitCode = -1
	if errors.Is(ctxErr, context.DeadlineExceeded) {
		result.Status = "failed"
		result.Output = "test deadline exceeded\n" + result.Output
	} else {
		result.Status = "cancelled"
	}
	return result
}

// os/exec serializes writes when stdout and stderr share this writer.
type limitedReport struct {
	buffer    bytes.Buffer
	log       func(string)
	truncated bool
}

func (w *limitedReport) Write(p []byte) (int, error) {
	n := min(len(p), 8000-w.buffer.Len())
	if n > 0 {
		w.buffer.Write(p[:n])
		for off := 0; off < n; {
			end := min(off+2048, n)
			w.log(strings.ToValidUTF8(string(p[off:end]), "?"))
			off = end
		}
	}
	if n < len(p) {
		w.truncated = true
	}
	return len(p), nil
}
func (w *limitedReport) String() string {
	s := strings.ToValidUTF8(w.buffer.String(), "?")
	if w.truncated {
		s += "\n[output truncated]"
	}
	return s
}
func limitString(s string, n int) string {
	if len(s) > n {
		s = s[:n]
	}
	return strings.ToValidUTF8(s, "?")
}
