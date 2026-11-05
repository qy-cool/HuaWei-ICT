package cloide.server.common.limit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 单 JVM 固定窗口限流（Bucket4j 实现）：每个 key 一个令牌桶，窗口起点为该 key
 * 首次访问时间，每窗口整量补充 maxRequests 个令牌，超配额的尝试被拒绝。
 *
 * <p>登录/注册防爆破、AI 诊断/问答与命令配额等按 key 计数场景共用本实现，
 * 仅配置参数与错误码不同；线程安全由 Bucket4j 本地桶保证。桶表带惰性淘汰：
 * key 数量超阈值时清理闲置超 2 个窗口的桶，防扫描流量以伪造 IP 撑爆内存。</p>
 */
public class FixedWindowRateLimiter {

    /** 桶表容量阈值：超过后触发一轮闲置清理（正常业务量级远达不到）。 */
    static final int MAX_TRACKED_KEYS = 8192;

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> lastAccessSeconds = new ConcurrentHashMap<>();
    private final long maxRequests;
    private final Duration window;
    private final Clock clock;

    /** 创建固定窗口限流器；clock 注入统一 UTC 时钟，测试可用可推进时钟。 */
    public FixedWindowRateLimiter(long maxRequests, Duration window, Clock clock) {
        this.maxRequests = maxRequests;
        this.window = window;
        this.clock = clock;
    }

    /**
     * 为 key 记录一次尝试（检查即计数）。
     *
     * @param key 限流维度标识（如客户端 IP 或管理员 ID 字符串）
     * @return 窗口内未超过 maxRequests 时为 true
     */
    public boolean tryAcquire(String key) {
        long nowSeconds = clock.instant().getEpochSecond();
        lastAccessSeconds.put(key, nowSeconds);
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> Bucket.builder()
                .withCustomTimePrecision(new ClockTimeMeter(clock))
                .addLimit(Bandwidth.classic(maxRequests, Refill.intervally(maxRequests, window)))
                .build());
        boolean acquired = bucket.tryConsume(1);
        purgeIdleBucketsIfNeeded(nowSeconds);
        return acquired;
    }

    /** 当前跟踪的 key 数量（测试观测用）。 */
    int trackedKeyCount() {
        return buckets.size();
    }

    /**
     * 键数量超阈值时惰性清理闲置超 2 个窗口的桶：被清理 key 的窗口早已过期，
     * 重建新桶与原语义等价；双键表以"读值-条件删除"配对移除，避免误删刚活跃的 key。
     */
    private void purgeIdleBucketsIfNeeded(long nowSeconds) {
        if (buckets.size() < MAX_TRACKED_KEYS) {
            return;
        }
        long idleThreshold = nowSeconds - Math.max(1, window.toSeconds()) * 2;
        for (Map.Entry<String, Long> entry : lastAccessSeconds.entrySet()) {
            Long access = entry.getValue();
            if (access != null && access < idleThreshold) {
                buckets.remove(entry.getKey());
                lastAccessSeconds.remove(entry.getKey(), access);
            }
        }
    }
}
