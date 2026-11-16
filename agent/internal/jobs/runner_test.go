package jobs

import (
	"context"
	"fmt"
	"strings"
	"testing"
)

func TestClassifyCancellationDistinguishesDeadlineFromCancel(t *testing.T) {
	// deadline 到期：failed + exit -1 + 输出前缀标注（协议终态分类的关键差异点）。
	deadline := classifyCancellation(context.DeadlineExceeded, Result{"j", "succeeded", 0, "tail"})
	if deadline.Status != "failed" || deadline.ExitCode != -1 || !strings.HasPrefix(deadline.Output, "test deadline exceeded\n") {
		t.Fatalf("deadline classification: %+v", deadline)
	}
	// 其余取消（含主动 cancel）：cancelled，不加前缀。
	cancelled := classifyCancellation(context.Canceled, Result{"j", "succeeded", 0, "tail"})
	if cancelled.Status != "cancelled" || cancelled.ExitCode != -1 || strings.Contains(cancelled.Output, "test deadline exceeded") {
		t.Fatalf("cancel classification: %+v", cancelled)
	}
	// 未取消：结果原样返回。
	untouched := classifyCancellation(nil, Result{"j", "succeeded", 0, "tail"})
	if untouched.Status != "succeeded" || untouched.ExitCode != 0 || untouched.Output != "tail" {
		t.Fatalf("untouched classification: %+v", untouched)
	}
	// 包装后的 DeadlineExceeded（errors.Is 语义）同样判 failed。
	wrapped := classifyCancellation(fmt.Errorf("watchdog: %w", context.DeadlineExceeded), Result{"j", "failed", 5, ""})
	if wrapped.Status != "failed" || !strings.HasPrefix(wrapped.Output, "test deadline exceeded\n") {
		t.Fatalf("wrapped classification: %+v", wrapped)
	}
}
