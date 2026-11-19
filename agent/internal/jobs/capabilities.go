package jobs

import (
	"github.com/shirou/gopsutil/v3/disk"
	"github.com/shirou/gopsutil/v3/mem"
	"runtime"
)

type RepositoryAlias struct {
	Project int64  `json:"project_id"`
	Name    string `json:"name"`
}

type Registration struct {
	Repositories    []RepositoryAlias `json:"repositories"`
	Toolchains      []string          `json:"toolchains"`
	Actions         []string          `json:"actions,omitempty"`
	OS              string            `json:"os"`
	Arch            string            `json:"arch"`
	CPUs            int               `json:"cpus"`
	DiskFree        *uint64           `json:"disk_free"`
	MemoryAvailable *uint64           `json:"memory_available"`
}

func (r Registration) Snapshot(state string) Registration {
	r.OS, r.Arch, r.CPUs = runtime.GOOS, runtime.GOARCH, runtime.NumCPU()
	if usage, err := disk.Usage(state); err == nil {
		r.DiskFree = &usage.Free
	}
	if memory, err := mem.VirtualMemory(); err == nil {
		r.MemoryAvailable = &memory.Available
	}
	return r
}
