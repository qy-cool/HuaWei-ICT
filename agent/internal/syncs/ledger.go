package syncs

import (
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
)

func recordDirectory(base, backend string, server int64) string {
	return filepath.Join(base, fmt.Sprintf("%x", sha256.Sum256([]byte(fmt.Sprintf("source-sync|%s|%d", backend, server)))))
}

func readRecord(dir, id string) (record, error) {
	var r record
	data, err := os.ReadFile(filepath.Join(dir, id+".json"))
	if err != nil {
		return r, err
	}
	if err = json.Unmarshal(data, &r); err != nil {
		return r, err
	}
	if r.Request.ID != id || (r.Result == nil && r.Acked) || (r.Result != nil && r.Result.ID != id) {
		return r, fmt.Errorf("invalid source sync record %s", id)
	}
	return r, nil
}

func saveRecord(dir string, r record) error {
	f, err := os.CreateTemp(dir, ".pending-")
	if err != nil {
		return err
	}
	tmp := f.Name()
	defer os.Remove(tmp)
	if err = json.NewEncoder(f).Encode(r); err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	if err = os.Rename(tmp, filepath.Join(dir, r.Request.ID+".json")); err != nil {
		return err
	}
	if runtime.GOOS == "windows" {
		return nil
	} // Test hosts; production nodes run Linux.
	d, err := os.Open(dir)
	if err != nil {
		return err
	}
	defer d.Close()
	return d.Sync()
}
