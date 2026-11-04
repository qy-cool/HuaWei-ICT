package cloide.server.websocket;

import cloide.server.common.BusinessException;
import cloide.server.security.AuthenticatedUser;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/** 在 WebSocket 握手阶段消费短时 Monitor ticket，并把用户身份传给 Handler。 */
@Slf4j
public class MonitorHandshakeInterceptor implements HandshakeInterceptor {

    private final MonitorTicketService monitorTicketService;

    /** 注入 ticket 服务。 */
    public MonitorHandshakeInterceptor(MonitorTicketService monitorTicketService) {
        this.monitorTicketService = monitorTicketService;
    }

    /** 验证 ticket 并保存认证用户到握手属性，拒绝时记录原因并返回 401。 */
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String ticket = UriComponentsBuilder.fromUri(request.getURI())
                .build().getQueryParams().getFirst("ticket");
        try {
            AuthenticatedUser user = monitorTicketService.consume(ticket);
            attributes.put("authenticated_user", user);
            return true;
        } catch (BusinessException exception) {
            // 拒绝留痕，但不输出 ticket 值本身。
            log.warn("monitor ticket rejected, reason={}", exception.getMessage());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    /** 握手完成后不再附加处理。 */
    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
    }
}
