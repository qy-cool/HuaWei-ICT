package cloide.server.websocket;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/** 管理单 JVM Monitor 会话和服务器订阅索引。 */
@Component
public class MonitorSubscriptionRegistry {

    private final ConcurrentMap<String, MonitorWebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, ConcurrentMap<String, MonitorWebSocketSession>> byServer =
            new ConcurrentHashMap<>();

    /** 注册会话。 */
    public void register(MonitorWebSocketSession session) {
        sessions.put(session.socketSession().getId(), session);
    }

    /** 添加服务器订阅，重复订阅保持幂等。 */
    public void subscribe(MonitorWebSocketSession session, Long serverId) {
        if (session.subscribedServerIds().add(serverId)) {
            byServer.computeIfAbsent(serverId, ignored -> new ConcurrentHashMap<>())
                    .put(session.socketSession().getId(), session);
        }
    }

    /** 移除服务器订阅。 */
    public void unsubscribe(MonitorWebSocketSession session, Long serverId) {
        if (session.subscribedServerIds().remove(serverId)) {
            ConcurrentMap<String, MonitorWebSocketSession> subscribers = byServer.get(serverId);
            if (subscribers != null) {
                subscribers.remove(session.socketSession().getId());
                if (subscribers.isEmpty()) {
                    byServer.remove(serverId, subscribers);
                }
            }
        }
    }

    /** 移除连接的全部订阅。 */
    public void remove(MonitorWebSocketSession session) {
        sessions.remove(session.socketSession().getId(), session);
        for (Long serverId : session.subscribedServerIds().toArray(Long[]::new)) {
            unsubscribe(session, serverId);
        }
    }

    /** 返回指定服务器的订阅会话快照。 */
    public Collection<MonitorWebSocketSession> subscribers(Long serverId) {
        ConcurrentMap<String, MonitorWebSocketSession> subscribers = byServer.get(serverId);
        return subscribers == null ? java.util.List.of() : subscribers.values();
    }
}
