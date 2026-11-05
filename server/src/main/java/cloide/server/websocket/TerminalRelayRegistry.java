package cloide.server.websocket;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * 保存单 JVM 终端会话到浏览器 Monitor 连接的路由元数据。
 *
 * <p>注册表只保存会话归属和 Socket 引用，不保存 PTY 输入、输出、命令或其他终端内容。
 * 多实例部署前必须以共享状态和消息层替换该内存实现。</p>
 */
@Component
public class TerminalRelayRegistry {

    private final ConcurrentMap<String, TerminalRelayBinding> bindings = new ConcurrentHashMap<>();

    /**
     * 绑定已持久化的终端会话与发起它的 Monitor 连接。
     *
     * @param sessionId Java 生成的终端会话 UUID
     * @param serverId 目标 Agent 所属服务器 ID
     * @param monitorSession 发起终端请求的浏览器连接
     * @return 新建绑定为 true；已有不一致绑定为 false
     */
    public boolean bind(String sessionId, Long serverId, MonitorWebSocketSession monitorSession) {
        TerminalRelayBinding binding = new TerminalRelayBinding(sessionId, serverId, monitorSession);
        TerminalRelayBinding existing = bindings.putIfAbsent(sessionId, binding);
        return existing == null || existing.equals(binding);
    }

    /** 按会话 UUID 查询浏览器路由元数据。 */
    public TerminalRelayBinding get(String sessionId) {
        return bindings.get(sessionId);
    }

    /** 删除指定会话的路由元数据。 */
    public TerminalRelayBinding remove(String sessionId) {
        return bindings.remove(sessionId);
    }

    /** 移除指定浏览器连接创建的全部会话路由。 */
    public List<TerminalRelayBinding> removeByMonitorSession(MonitorWebSocketSession monitorSession) {
        return removeIf(binding -> binding.monitorSession().equals(monitorSession));
    }

    /** 移除指定 Agent 服务器关联的全部会话路由。 */
    public List<TerminalRelayBinding> removeByServerId(Long serverId) {
        return removeIf(binding -> binding.serverId().equals(serverId));
    }

    /** 返回当前内存路由快照，仅供生命周期收口使用。 */
    public Collection<TerminalRelayBinding> bindings() {
        return List.copyOf(bindings.values());
    }

    /** 使用条件删除保证并发路由替换不会移除新绑定。 */
    private List<TerminalRelayBinding> removeIf(java.util.function.Predicate<TerminalRelayBinding> predicate) {
        return bindings.values().stream().filter(predicate).filter(binding -> bindings.remove(
                binding.sessionId(), binding)).toList();
    }

    /** 表示一条会话到浏览器连接的不可变路由记录。 */
    public record TerminalRelayBinding(String sessionId, Long serverId, MonitorWebSocketSession monitorSession) {
    }
}
