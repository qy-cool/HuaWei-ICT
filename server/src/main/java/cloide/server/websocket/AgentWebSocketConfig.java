package cloide.server.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 注册 Agent 专用 WebSocket 通道；浏览器 Monitor 通道由独立配置负责。
 */
@Configuration
@EnableWebSocket
public class AgentWebSocketConfig implements WebSocketConfigurer {

    private final AgentWebSocketHandler agentWebSocketHandler;
    private final AgentHandshakeInterceptor agentHandshakeInterceptor;

    /** 注入 Agent WebSocket Handler。 */
    public AgentWebSocketConfig(AgentWebSocketHandler agentWebSocketHandler,
            AgentHandshakeInterceptor agentHandshakeInterceptor) {
        this.agentWebSocketHandler = agentWebSocketHandler;
        this.agentHandshakeInterceptor = agentHandshakeInterceptor;
    }

    /**
     * 将 Agent Handler 注册到固定地址。
     *
     * <p>不配置 Origin 白名单：Agent 是原生 Go 客户端，不发 Origin header，
     * 安全由首帧 Agent Token 鉴权和 10 秒超时关闭保证。Spring 的
     * isSameOrigin 会对缺失 Origin 的请求放行，因此 Origin 白名单对
     * Agent 通道无实际拦截效果。</p>
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(agentWebSocketHandler, "/ws/agent")
                .addInterceptors(agentHandshakeInterceptor);
    }
}
