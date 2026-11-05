package cloide.server.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.servlet.ServletException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 验证请求 ID 和客户端关联 ID 的过滤器契约。
 */
class RequestIdFilterTests {

    private final RequestIdFilter requestIdFilter = new RequestIdFilter();

    /**
     * 验证服务端忽略客户端伪造的请求 ID 并生成 UUID。
     */
    @Test
    void shouldGenerateServerRequestIdAndIgnoreClientValue() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "client-controlled-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        requestIdFilter.doFilter(request, response, (servletRequest, servletResponse) -> {
            String requestId = MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY);
            assertEquals(requestId, ((MockHttpServletResponse) servletResponse)
                    .getHeader(RequestIdFilter.REQUEST_ID_HEADER));
            UUID.fromString(requestId);
        });

        assertNotEquals("client-controlled-id", response.getHeader(RequestIdFilter.REQUEST_ID_HEADER));
        assertNull(MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY));
    }

    /**
     * 验证合法关联 ID 会进入响应和 MDC，并在请求结束后清理。
     */
    @Test
    void shouldPropagateValidCorrelationId() throws Exception {
        String correlationId = "apifox-register_flow-1";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.CORRELATION_ID_HEADER, correlationId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> correlationIdInChain = new AtomicReference<>();

        requestIdFilter.doFilter(request, response, (servletRequest, servletResponse) ->
                correlationIdInChain.set(MDC.get(RequestIdFilter.CORRELATION_ID_MDC_KEY)));

        assertEquals(correlationId, correlationIdInChain.get());
        assertEquals(correlationId, response.getHeader(RequestIdFilter.CORRELATION_ID_HEADER));
        assertNull(MDC.get(RequestIdFilter.CORRELATION_ID_MDC_KEY));
    }

    /**
     * 验证非法和超长关联 ID 均被忽略，不进入响应或 MDC。
     */
    @Test
    void shouldIgnoreInvalidCorrelationIds() throws Exception {
        String[] invalidIds = {"contains space", "contains/slash", "中文", "a".repeat(65)};

        for (String invalidId : invalidIds) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(RequestIdFilter.CORRELATION_ID_HEADER, invalidId);
            MockHttpServletResponse response = new MockHttpServletResponse();

            requestIdFilter.doFilter(request, response, (servletRequest, servletResponse) ->
                    assertNull(MDC.get(RequestIdFilter.CORRELATION_ID_MDC_KEY)));

            assertFalse(response.containsHeader(RequestIdFilter.CORRELATION_ID_HEADER));
        }
    }

    /**
     * 验证下游异常不会导致 MDC 残留到复用线程。
     */
    @Test
    void shouldClearMdcWhenChainThrowsException() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.CORRELATION_ID_HEADER, "failed-request");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(ServletException.class, () ->
                requestIdFilter.doFilter(request, response, (servletRequest, servletResponse) -> {
                    throw new ServletException("expected failure");
                }));

        assertNull(MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY));
        assertNull(MDC.get(RequestIdFilter.CORRELATION_ID_MDC_KEY));
    }
}
