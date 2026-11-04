package cloide.server.websocket;

import cloide.server.config.AppProperties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 维护单 JVM Agent 连接和未认证连接配额，保证连接建立与关闭之间的计数不会泄漏。
 */
@Component
public class AgentConnectionLimiter {

    private final int maxConnections;
    private final int maxUnauthenticatedConnections;
    private final Set<String> admittedSessions = ConcurrentHashMap.newKeySet();
    private final Set<String> unauthenticatedSessions = ConcurrentHashMap.newKeySet();

    /** 根据启动期校验后的 Agent 配置初始化固定连接配额。 */
    public AgentConnectionLimiter(AppProperties appProperties) {
        AppProperties.Agent agent = appProperties.getAgent();
        this.maxConnections = agent.getMaxConnections();
        this.maxUnauthenticatedConnections = agent.getMaxUnauthenticatedConnections();
        if (maxUnauthenticatedConnections > maxConnections) {
            throw new IllegalArgumentException("Agent unauthenticated connection limit exceeds total limit");
        }
    }

    /**
     * 原子申请总连接和未认证配额。
     *
     * @param sessionId WebSocket session ID
     * @return 已获配额时为 true
     */
    public synchronized boolean admit(String sessionId) {
        if (admittedSessions.contains(sessionId)) {
            return true;
        }
        if (admittedSessions.size() >= maxConnections || unauthenticatedSessions.size() >= maxUnauthenticatedConnections) {
            return false;
        }
        admittedSessions.add(sessionId);
        unauthenticatedSessions.add(sessionId);
        return true;
    }

    /** 认证完成后释放未认证配额，同时继续占用总连接配额。 */
    public synchronized void authenticate(String sessionId) {
        unauthenticatedSessions.remove(sessionId);
    }

    /** 连接关闭、认证拒绝或超时后释放全部配额。 */
    public synchronized void release(String sessionId) {
        unauthenticatedSessions.remove(sessionId);
        admittedSessions.remove(sessionId);
    }

    /** 返回当前总连接数，仅供边界测试和诊断使用。 */
    int admittedCount() {
        return admittedSessions.size();
    }

    /** 返回当前未认证连接数，仅供边界测试和诊断使用。 */
    int unauthenticatedCount() {
        return unauthenticatedSessions.size();
    }
}
