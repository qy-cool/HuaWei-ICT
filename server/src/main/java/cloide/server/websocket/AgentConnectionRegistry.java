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
        return session != null && session.send(message);
    }

    /** 判断连接是否仍是其服务器当前生效的 Agent，供断连收口排除被替换的旧连接。 */
    public boolean isCurrent(AgentWebSocketSession session) {
        return session.authenticated() && session.equals(sessions.get(serverSessions.get(session.serverId())));
    }

    /** 关闭并移除指定服务器的连接。 */
    public void closeByServerId(Long serverId, CloseStatus closeStatus) {
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
