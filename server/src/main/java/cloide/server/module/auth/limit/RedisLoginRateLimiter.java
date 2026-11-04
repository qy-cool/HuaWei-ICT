package cloide.server.module.auth.limit;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import cloide.server.common.limit.RedisFixedWindowRateLimiter;
import cloide.server.config.AppProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 基于 Redis 的登录防爆破（Redis 安全加固一期，2026-08-18）。
 *
 * <p>按客户端 IP 固定窗口原子计数：{@code INCR cloide:login-limit:<ip>}，
 * 首次计数原子设置 {@code EXPIRE}（窗口秒数，TTL 自动清理）；计数超过阈值抛 42905。</p>
 */
@Component
@ConditionalOnProperty(name = "cloide.redis.enabled", havingValue = "true")
public class RedisLoginRateLimiter implements LoginRateLimiter {

    private final RedisFixedWindowRateLimiter window;

    /** 注入 Redis 模板与登录限流参数。 */
    public RedisLoginRateLimiter(StringRedisTemplate redisTemplate, AppProperties appProperties) {
        this.window = new RedisFixedWindowRateLimiter(redisTemplate, "cloide:login-limit:",
                appProperties.getSecurity().getLoginLimitMaxAttempts(),
                appProperties.getSecurity().getLoginLimitWindowSeconds());
    }

    /** 原子递增计数并保证首窗口 TTL；超过阈值抛 42905。 */
    @Override
    public void checkAttempt(String clientIp) {
        if (!window.tryAcquire(clientIp)) {
            throw new BusinessException(ErrorCode.LOGIN_RATE_LIMIT_REACHED);
        }
    }
}
