package cloide.server.module.metrics.outbox;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * H2 测试辅助：为 H2 注册 MySQL 的 UTC_TIMESTAMP() 等价函数。
 * H2 的 FunctionAlias 通过反射调用，类与方法必须为 public。
 */
public final class H2UtcTimestamp {

    private H2UtcTimestamp() {
    }

    /** 返回当前 UTC 时刻，供 CREATE ALIAS 绑定。 */
    public static Timestamp utcTimestamp() {
        return Timestamp.from(Instant.now());
    }
}
