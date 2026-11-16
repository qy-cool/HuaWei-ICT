package cloide.server.websocket;

import java.io.IOException;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.stereotype.Component;

/**
 * 管理单 JVM 内 Agent 会话，并按服务器维度保证新认证连接替换旧连接。
 */
@Component
public class AgentConnectionRegistry {

    private final ConcurrentMap<String, AgentWebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, String> serverSessions = new ConcurrentHashMap<>();

    private AgentConnectionOwnership ownership;

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void configureCluster(AgentConnectionOwnership ownership) { this.ownership = ownership; }

    public boolean clustered() { return ownership != null; }

    public void claim(AgentWebSocketSession session, Runnable reauthenticate) {
        if (clustered()) ownership.claim(session.serverId(), session.sessionId(), reauthenticate);
    }

    public boolean owns(AgentWebSocketSession session) {
        return !clustered() || ownership.owns(session.serverId(), session.sessionId());
    }

    public boolean withOwnership(AgentWebSocketSession session, Runnable action) {
        if (clustered()) return ownership.withOwnership(session.serverId(), session.sessionId(), action);
        action.run();
        return true;
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=5000)
    public void closeDisplacedSessions() {
        if (!clustered()) return;
        for (var session : sessions.values()) {
            if (!owns(session)) {
                try { session.socketSession().close(CloseStatus.NORMAL); }
                catch (IOException ignored) { /* 下次心跳或断开回调会继续收口。 */ }
            }
        }
    }

    /** 注册已认证会话，并返回同服务器旧会话。 */
    public Optional<AgentWebSocketSession> replace(AgentWebSocketSession session) {
        sessions.put(session.sessionId(), session);
        String oldSessionId = serverSessions.put(session.serverId(), session.sessionId());
        if (oldSessionId == null || oldSessionId.equals(session.sessionId())) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.remove(oldSessionId));
    }

    /** 删除会话并清除其服务器索引。 */
    public void remove(AgentWebSocketSession session) {
        sessions.remove(session.sessionId());
        if (session.serverId() != null) {
            serverSessions.remove(session.serverId(), session.sessionId());
            if (clustered()) ownership.release(session.serverId(),session.sessionId());
        }
    }

    /** 返回当前连接快照，供心跳过期扫描使用。 */
    public Collection<AgentWebSocketSession> sessions() {
        return sessions.values();
    }

    /** 向指定服务器当前已认证 Agent 串行发送终端控制帧。 */
    public boolean sendToServer(Long serverId, TextMessage message) throws IOException {
        String sessionId = serverSessions.get(serverId);
        AgentWebSocketSession session = sessionId == null ? null : sessions.get(sessionId);
        return session != null && owns(session) && session.send(message);
    }

    /** 判断连接是否仍是其服务器当前生效的 Agent，供断连收口排除被替换的旧连接。 */
    public boolean isCurrent(AgentWebSocketSession session) {
        return session.authenticated() && current(session.serverId()).filter(session::equals).isPresent();
    }

    public Optional<AgentWebSocketSession> current(long serverId) {
        String sessionId = serverSessions.get(serverId);
        return sessionId == null ? Optional.empty() : Optional.ofNullable(sessions.get(sessionId)).filter(this::owns);
    }

    /** 撤销事务先删除共享归属，提交后仅关闭撤销时仍绑定的本地会话。 */
    public void revokeOnCommit(long serverId) {
        if (clustered()) ownership.revoke(serverId);
        String old = serverSessions.get(serverId);
        if (old == null) return;
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() {
                        if (!serverSessions.remove(serverId,old)) return;
                        var removed = sessions.remove(old);
                        if (removed != null) {
                            try { removed.socketSession().close(CloseStatus.POLICY_VIOLATION); }
                            catch (IOException ignored) { /* 网络已断开 */ }
                        }
                    }
                });
    }

    /** 关闭并移除指定服务器的连接。 */
    public void closeByServerId(Long serverId, CloseStatus closeStatus) {
        if (clustered()) ownership.revoke(serverId);
        String sessionId = serverSessions.remove(serverId);
        if (sessionId == null) {
            return;
        }
        AgentWebSocketSession session = sessions.remove(sessionId);
        if (session != null && session.socketSession().isOpen()) {
            try {
                session.socketSession().close(closeStatus);
            } catch (IOException ignored) {
                // 连接已经进入关闭流程，不再向调用方传播网络关闭异常。
            }
        }
    }
}
