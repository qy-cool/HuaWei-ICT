package jobs

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
)

func prepareCapture(state, id string) (string, error) {
	if !jobID.MatchString(id) {
		return "", errors.New("invalid job identifier")
	}
	dir := filepath.Join(state, "artifacts", id)
	if err := os.MkdirAll(dir, 0700); err != nil {
		return "", err
	}
	file := filepath.Join(dir, "blob")
	out, err := os.OpenFile(file, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return "", err
	}
	return file, out.Close()
}

// 仅主机在测试进程已退出后生成证明。仓库进程只能访问单个输出文件。
func completeCapture(file string, req Request, kind string) error {
	blob, err := os.OpenFile(file, os.O_RDWR, 0)
	if err != nil {
		return err
	}
	defer blob.Close()
	info, err := blob.Stat()
	if err != nil {
		return err
	}
	if !info.Mode().IsRegular() || info.Size() == 0 || info.Size() > 128<<20 {
		return errors.New("invalid artifact size")
	}
	if err = blob.Sync(); err != nil {
		return err
	}
	md := sha256.New()
	if _, err = io.Copy(md, blob); err != nil {
		return err
	}
	data, err := json.Marshal(struct {
		Job     string `json:"job_id"`
		Project int64  `json:"project_id"`
		Commit  string `json:"commit_sha"`
		Kind    string `json:"kind"`
		Digest  string `json:"sha256"`
	}{req.ID, req.Project, req.Commit, kind, hex.EncodeToString(md.Sum(nil))})
	if err != nil {
		return err
	}
	dir := filepath.Dir(file)
	tmp := filepath.Join(dir, "metadata.tmp")
	out, err := os.OpenFile(tmp, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	if _, err = out.Write(data); err == nil {
		err = out.Sync()
	}
	cerr := out.Close()
	if err != nil {
		return err
	}
	if cerr != nil {
		return cerr
	}
	if err = os.Rename(tmp, filepath.Join(dir, "pending.json")); err != nil {
		return err
	}
	parent, err := os.Open(dir)
	if err != nil {
		return err
	}
	defer parent.Close()
	return parent.Sync()
}
