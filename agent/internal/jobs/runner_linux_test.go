//go:build linux

package jobs

import (
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"
)

// Opt-in real sandbox acceptance. The primary runner, Git, prlimit and
// bubblewrap all execute under the same unprivileged identity as in production.
func TestLinuxRepositorySandbox(t *testing.T) {
	if os.Getenv("CLOIDE_JOB_SANDBOX_TEST") != "1" {
		t.Skip("set CLOIDE_JOB_SANDBOX_TEST=1 on a Linux development node")
	}
	dir := t.TempDir()
	root := filepath.Join(dir, "rootfs")
	for _, d := range []string{"tmp", "work", "objects", "proc", "dev"} {
		if err := os.MkdirAll(filepath.Join(root, d), 0755); err != nil {
			t.Fatal(err)
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	// Copy the two executables and their dynamic loaders into an otherwise
	// empty filesystem. Never bind the host root, /etc, /home or agent state.
	libs := regexp.MustCompile(`/[^\s()]+`)
	for _, binary := range []string{"/bin/sh", "/usr/bin/git"} {
		data, err := exec.CommandContext(ctx, "ldd", binary).Output()
		if err != nil {
			t.Fatal(err)
		}
		for _, p := range append(libs.FindAllString(string(data), -1), binary) {
			dst := filepath.Join(root, p)
			if err := os.MkdirAll(filepath.Dir(dst), 0755); err != nil {
				t.Fatal(err)
			}
			b, err := os.ReadFile(p)
			if err != nil {
				t.Fatal(err)
			}
			if err = os.WriteFile(dst, b, 0755); err != nil {
				t.Fatal(err)
			}
		}
	}
	source := filepath.Join(dir, "source")
	if err := os.Mkdir(source, 0700); err != nil {
		t.Fatal(err)
	}
	git := func(args ...string) string {
		cmd := exec.CommandContext(ctx, "git", args...)
		cmd.Dir = source
		cmd.Env = []string{"PATH=/usr/bin:/bin", "HOME=" + dir, "GIT_CONFIG_NOSYSTEM=1"}
		out, err := cmd.CombinedOutput()
		if err != nil {
			t.Fatalf("git %s: %s %v", args[0], out, err)
		}
		return strings.TrimSpace(string(out))
	}
	git("init", "-q")
	probe := `set -eu
test "$(/usr/bin/git rev-parse HEAD)" != ""
test -z "${CLOIDE_AGENT_TOKEN:-}"
test ! -e /tmp/agent-secret
test ! -e /etc/shadow
test -f README
printf build-output > "$CLOIDE_BUILD_OUTPUT"
echo "repository test passed"
`
	if err := os.WriteFile(filepath.Join(source, "test.sh"), []byte(probe), 0644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(source, "README"), []byte("fixture"), 0644); err != nil {
		t.Fatal(err)
	}
	git("add", ".")
	git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "fixture")
	req := request()
	req.Commit = git("rev-parse", "HEAD")
	bare := filepath.Join(dir, "source.git")
	git("clone", "--bare", "--no-local", source, bare)
	cfg := manifest{State: filepath.Join(dir, "ledger"), RootFS: root, Timeout: 15, Repositories: []repository{{Project: 1, Name: "orders", GitDir: bare, Command: []string{"/bin/sh", "test.sh"}, ArtifactKind: "static-tar"}}}
	body, _ := json.Marshal(cfg)
	manifestPath := filepath.Join(dir, "config.json")
	if err := os.WriteFile(manifestPath, body, 0600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("CLOIDE_AGENT_TOKEN", "secret-fixture")
	_, _, run, err := LoadRunner(manifestPath, nil)
	if err != nil {
		t.Fatal(err)
	}
	res := run(ctx, req, func(string) {})
	if res.Status != "succeeded" || !strings.Contains(res.Output, "repository test passed") {
		t.Fatalf("sandbox result %+v", res)
	}
	req.Project++
	if got := run(ctx, req, func(string) {}); got.Status != "failed" {
		t.Fatalf("cross-project test: %+v", got)
	}
}
