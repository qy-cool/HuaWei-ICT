package cloide.server.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cloide.server.config.AppProperties;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

/** 验证可信代理 IP 解析和 Upgrade 前握手限流边界。 */
class AgentHandshakeInterceptorTests {

    /** 默认不信任 XFF，直连请求必须使用 TCP peer IP。 */
    @Test
    void untrustedPeerShouldIgnoreForwardedFor() throws Exception {
        AgentClientIpResolver resolver = new AgentClientIpResolver(new AppProperties());

        assertEquals("198.51.100.10", resolver.resolve(request("198.51.100.10", "203.0.113.20")));
    }

    /** 可信代理从 XFF 右向左剥离代理地址，取首个非可信客户端地址。 */
    @Test
    void trustedProxyShouldResolveClientFromForwardedFor() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getAgent().setTrustedProxyCidrs(List.of("127.0.0.1/32"));
        AgentClientIpResolver resolver = new AgentClientIpResolver(properties);

        assertEquals("198.51.100.10", resolver.resolve(request("127.0.0.1", "198.51.100.10, 127.0.0.1")));
    }

    /** 同 IP 超过配置的 Upgrade 次数后返回 HTTP 429 和 Retry-After。 */
    @Test
    void shouldRejectExcessiveHandshakes() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getAgent().setHandshakeRatePerMinute(1);
        AgentHandshakeInterceptor interceptor = new AgentHandshakeInterceptor(
                new AgentClientIpResolver(properties), properties, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        HttpHeaders responseHeaders = new HttpHeaders();
        when(response.getHeaders()).thenReturn(responseHeaders);

        assertTrue(interceptor.beforeHandshake(request("198.51.100.10", null), response,
                mock(WebSocketHandler.class), new java.util.HashMap<>()));
        assertFalse(interceptor.beforeHandshake(request("198.51.100.10", null), response,
                mock(WebSocketHandler.class), new java.util.HashMap<>()));
        org.mockito.Mockito.verify(response).setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        assertEquals("60", responseHeaders.getFirst("Retry-After"));
    }

    /** 限流状态到达上限时淘汰最久未使用地址，防止随机 IP 使内存状态无界增长。 */
    @Test
    void shouldEvictLeastRecentlyUsedClientWhenStateLimitIsReached() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getAgent().setMaxTrackedClientIps(1);
        AgentHandshakeInterceptor interceptor = new AgentHandshakeInterceptor(
                new AgentClientIpResolver(properties), properties, Clock.systemUTC());
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        when(response.getHeaders()).thenReturn(new HttpHeaders());

        assertTrue(interceptor.beforeHandshake(request("198.51.100.10", null), response,
                mock(WebSocketHandler.class), new java.util.HashMap<>()));
        assertTrue(interceptor.beforeHandshake(request("198.51.100.11", null), response,
                mock(WebSocketHandler.class), new java.util.HashMap<>()));
        assertEquals(1, interceptor.trackedClientIpCount());
    }

    /** 创建仅包含 peer IP 和可选 XFF 的握手请求替身。 */
    private ServerHttpRequest request(String peerIp, String forwardedFor) throws Exception {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        if (forwardedFor != null) {
            headers.set("X-Forwarded-For", forwardedFor);
        }
        when(request.getHeaders()).thenReturn(headers);
        when(request.getRemoteAddress()).thenReturn(new InetSocketAddress(InetAddress.getByName(peerIp), 12345));
        return request;
    }
}
