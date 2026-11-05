package collector

import (
	"errors"
	"testing"
	"time"

	"github.com/shirou/gopsutil/v3/disk"
	"github.com/shirou/gopsutil/v3/load"
	"github.com/shirou/gopsutil/v3/mem"
	"github.com/shirou/gopsutil/v3/net"
)

func testCollector() *GopsutilCollector {
	return &GopsutilCollector{
		cpuPercent: func(time.Duration, bool) ([]float64, error) { return []float64{35.236}, nil },
		virtualMemory: func() (*mem.VirtualMemoryStat, error) {
			return &mem.VirtualMemoryStat{UsedPercent: 48.126, Used: 481, Total: 1000}, nil
		},
		diskUsage: func(path string) (*disk.UsageStat, error) {
			if path != systemDiskPath() {
				return nil, errors.New("unexpected disk path")
			}
			return &disk.UsageStat{UsedPercent: 61.456, Used: 614, Total: 1000}, nil
		},
		netIOCounters: func(bool) ([]net.IOCountersStat, error) {
			return []net.IOCountersStat{{BytesRecv: 123, BytesSent: 456}}, nil
		},
		sensorsTemperatures: func() ([]sensorTemperature, error) {
			return []sensorTemperature{{Temperature: 42.567}}, nil
		},
		loadAvg: func() (*load.AvgStat, error) { return &load.AvgStat{Load1: 0.756}, nil },
	}
}

func TestGopsutilCollectorCollect(t *testing.T) {
	metrics, err := testCollector().Collect()
	if err != nil {
		t.Fatalf("Collect() error = %v", err)
	}
	if *metrics.CPUPercent != 35.24 || *metrics.MemoryPercent != 48.13 || *metrics.DiskPercent != 61.46 {
		t.Fatalf("percentages were not rounded: %+v", metrics)
	}
	if *metrics.MemoryUsed != 481 || *metrics.MemoryTotal != 1000 || *metrics.DiskUsed != 614 || *metrics.DiskTotal != 1000 {
		t.Fatalf("size fields mismatch: %+v", metrics)
	}
	if *metrics.NetRx != 123 || *metrics.NetTx != 456 || *metrics.Temperature != 42.57 || *metrics.LoadAvg != 0.76 {
		t.Fatalf("optional or network fields mismatch: %+v", metrics)
	}
}

func TestGopsutilCollectorOptionalMetricsMayBeNil(t *testing.T) {
	c := testCollector()
	c.sensorsTemperatures = func() ([]sensorTemperature, error) { return nil, errors.New("unsupported") }
	c.loadAvg = func() (*load.AvgStat, error) { return nil, errors.New("unsupported") }
	metrics, err := c.Collect()
	if err != nil {
		t.Fatalf("Collect() error = %v", err)
	}
	if metrics.Temperature != nil || metrics.LoadAvg != nil {
		t.Fatalf("optional metrics should be nil: %+v", metrics)
	}
}

func TestGopsutilCollectorRequiredMetricErrors(t *testing.T) {
	tests := []struct {
		name string
		set  func(*GopsutilCollector)
	}{
		{"cpu", func(c *GopsutilCollector) {
			c.cpuPercent = func(time.Duration, bool) ([]float64, error) { return nil, errors.New("cpu") }
		}},
		{"memory", func(c *GopsutilCollector) {
			c.virtualMemory = func() (*mem.VirtualMemoryStat, error) { return nil, errors.New("memory") }
		}},
		{"disk", func(c *GopsutilCollector) {
			c.diskUsage = func(string) (*disk.UsageStat, error) { return nil, errors.New("disk") }
		}},
		{"network", func(c *GopsutilCollector) {
			c.netIOCounters = func(bool) ([]net.IOCountersStat, error) { return nil, errors.New("network") }
		}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			c := testCollector()
			tt.set(c)
			if _, err := c.Collect(); err == nil {
				t.Fatalf("Collect() error = nil, want error")
			}
		})
	}
}

func TestGopsutilCollectorCarriesProcessTopWhenSampled(t *testing.T) {
	c := testCollector()
	scanTime := time.Unix(2000, 0)
	scanCount := 0
	c.processSampler = &ProcessSampler{
		topN:   5,
		numCPU: 1,
		listProcesses: func() ([]processReading, error) {
			scanCount++
			// 每个扫描周期多消耗 3 CPU 秒，供第二次采样做差分。
			return []processReading{{pid: 9, name: "java", cpuTotal: float64(3 * (scanCount - 1)), memPercent: 40}}, nil
		},
		now:           func() time.Time { scanTime = scanTime.Add(5 * time.Second); return scanTime },
		lastCPUTotals: make(map[int32]float64),
	}

	// 首次采集只建立差分基线，不携带进程字段。
	first, err := c.Collect()
	if err != nil {
		t.Fatalf("Collect() error = %v", err)
	}
	if first.ProcessCPUTop != nil || first.ProcessMemTop != nil {
		t.Fatalf("first collect should not carry process fields: %+v", first)
	}

	// 1 核、5 秒、消耗 3 CPU 秒 => 3/5*100 = 60%。
	second, err := c.Collect()
	if err != nil {
		t.Fatalf("Collect() error = %v", err)
	}
	if second.ProcessCPUTop == nil || second.ProcessMemTop == nil {
		t.Fatalf("second collect should carry process fields: %+v", second)
	}
	if len(*second.ProcessCPUTop) != 1 || (*second.ProcessCPUTop)[0].Name != "java" ||
		(*second.ProcessCPUTop)[0].CPUPercent != 60 || (*second.ProcessCPUTop)[0].MemPercent != 40 {
		t.Fatalf("unexpected process samples: %+v", *second.ProcessCPUTop)
	}
}

func TestGopsutilCollectorProcessSampleFailureKeepsMetrics(t *testing.T) {
	c := testCollector()
	c.processSampler = &ProcessSampler{
		topN:   5,
		numCPU: 1,
		listProcesses: func() ([]processReading, error) {
			return nil, errors.New("proc walk failed")
		},
		now:           time.Now,
		lastCPUTotals: make(map[int32]float64),
	}

	metrics, err := c.Collect()
	if err != nil {
		t.Fatalf("Collect() error = %v", err)
	}
	if metrics.ProcessCPUTop != nil || metrics.ProcessMemTop != nil {
		t.Fatalf("process fields should stay nil on sampler failure: %+v", metrics)
	}
	if metrics.CPUPercent == nil || *metrics.CPUPercent != 35.24 {
		t.Fatalf("core metrics should be unaffected: %+v", metrics)
	}
}

func TestGopsutilCollectorCarriesExtendedResourcesWhenSampled(t *testing.T) {
	c := testCollector()
	c.extendedSampler = &ExtendedSampler{
		listPartitions: func() ([]partitionReading, error) {
			return []partitionReading{{device: "/dev/sda1", mountPoint: "/", fstype: "ext4"}}, nil
		},
		usage: func(string) (*disk.UsageStat, error) { return &disk.UsageStat{Total: 1000, Free: 250}, nil },
		listNicCounters: func() ([]net.IOCountersStat, error) {
			return []net.IOCountersStat{{Name: "eth0", BytesRecv: 100000, BytesSent: 50000}}, nil
		},
		now:         time.Now,
		lastNicRxTx: make(map[string][2]uint64),
	}
	// 固定时钟推进 5 秒/次，供网卡差分使用。
	fixed := time.Unix(2000, 0)
	c.extendedSampler.now = func() time.Time { fixed = fixed.Add(5 * time.Second); return fixed }

	// 首周期：磁盘点值即携带，网卡仅有基线为 nil。
	first, err := c.Collect()
	if err != nil {
		t.Fatalf("Collect() error = %v", err)
	}
	if first.Disks == nil || len(*first.Disks) != 1 || (*first.Disks)[0].MountPoint != "/" {
		t.Fatalf("first collect should carry disks: %+v", first.Disks)
	}
	if first.Nics != nil {
		t.Fatalf("first collect should not carry nics: %+v", first.Nics)
	}

	second, err := c.Collect()
	if err != nil {
		t.Fatalf("Collect() error = %v", err)
	}
	if second.Nics == nil || len(*second.Nics) != 1 || (*second.Nics)[0].Name != "eth0" {
		t.Fatalf("second collect should carry nics: %+v", second.Nics)
	}
}

func TestGopsutilCollectorExtendedResourcesDisabledStaysNil(t *testing.T) {
	c := testCollector()
	metrics, err := c.Collect()
	if err != nil {
		t.Fatalf("Collect() error = %v", err)
	}
	if metrics.Disks != nil || metrics.Nics != nil {
		t.Fatalf("extended resources should stay nil without sampler: %+v", metrics)
	}
}
