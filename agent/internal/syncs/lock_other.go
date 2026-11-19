//go:build !linux

package syncs

import "os"

// Production nodes run Linux. This handle permits the platform-independent
// ledger, protocol and executor tests to use a temporary directory.
func lockLedger(dir string) (*os.File, error) { return os.Open(dir) }
