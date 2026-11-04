package cloide.server.websocket;

import cloide.server.config.AppProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 注册浏览器监控 WebSocket 通道。
 *
 * <p>Origin 白名单从 AppProperties.Cors 读取，与 REST CORS 共用同一配置源。
 * Spring 的 isSameOrigin 会对缺失 Origin 的原生客户端放行，浏览器场景的
 * CSWSH 防护由白名单保证。</p>
 */
@Configuration
public class MonitorWebSocketConfig implements WebSocketConfigurer {

    private final MonitorWebSocketHandler monitorWebSocketHandler;
    private final MonitorTicketService monitorTicketService;
    private final AppProperties appProperties;

    /** 注入浏览器监控 Handler、Ticket 服务和 CORS 配置。 */
    public MonitorWebSocketConfig(MonitorWebSocketHandler monitorWebSocketHandler,
            MonitorTicketService monitorTicketService, AppProperties appProperties) {
        this.monitorWebSocketHandler = monitorWebSocketHandler;
        this.monitorTicketService = monitorTicketService;
        this.appProperties = appProperties;
    }

    /** 注册 /ws/monitor 地址，使用配置的 Origin 白名单替代通配符。 */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] allowedOrigins = appProperties.getCors().getAllowedOrigins()
                .toArray(String[]::new);
        registry.addHandler(monitorWebSocketHandler, "/ws/monitor")
                .addInterceptors(new MonitorHandshakeInterceptor(monitorTicketService))
                .setAllowedOrigins(allowedOrigins);
    }
}
