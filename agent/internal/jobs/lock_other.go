//go:build !linux

package jobs

import "os"

// LoadRunner rejects non-Linux production execution. This handle permits the
// platform-independent journal and protocol tests to use a temporary directory.
func lockLedger(dir string) (*os.File, error) { return os.Open(dir) }
