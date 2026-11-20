//go:build linux

package syncs

import (
	"os"
	"syscall"
)

func lockLedger(dir string) (*os.File, error) {
	d, err := os.Open(dir)
	if err != nil {
		return nil, err
	}
	if err = syscall.Flock(int(d.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		d.Close()
		return nil, err
	}
	return d, nil
}
