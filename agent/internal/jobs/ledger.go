package jobs

import (
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
)

// An empty result reserves the job before the first external effect. Final
// delivery and acknowledgement are separate durable state transitions.
type record struct {
	Request Request `json:"request"`
	Result  *Result `json:"result,omitempty"`
	Acked   bool    `json:"acknowledged,omitempty"`
}

func recordDirectory(base, backend string, server int64) string {
	return filepath.Join(base, fmt.Sprintf("%x", sha256.Sum256([]byte(fmt.Sprintf("%s|%d", backend, server)))))
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
		return r, fmt.Errorf("invalid job record %s", id)
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
	} // Test hosts; the production runner requires Linux.
	d, err := os.Open(dir)
	if err != nil {
		return err
	}
	defer d.Close()
	return d.Sync()
}
