package cloide.server.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cloide.server.config.AppProperties;
import cloide.server.module.auth.entity.UserEntity;
import cloide.server.module.auth.mapper.UserMapper;
import cloide.server.module.server.mapper.ServerMapper;
import cloide.server.security.AuthenticatedUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

class ProjectIsolationMonitorTests {
    private final Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
    private final UserMapper users = mock(UserMapper.class);
    private final LegacyMonitorAccess access = new LegacyMonitorAccess(true, users);
    private final MonitorTicketService tickets = new MonitorTicketServiceImpl(clock);
    private final MonitorSubscriptionRegistry registry = new MonitorSubscriptionRegistry();
    private final MonitorWebSocketHandler handler = new MonitorWebSocketHandler(new ObjectMapper(),
            mock(ServerMapper.class), registry, clock, null, null, null, null, new AppProperties(), access);

    @Test
    void previouslyIssuedTicketsRequireBothAdminSnapshotAndCurrentApprovedAdmin() throws Exception {
        String oldUserTicket = ticket("user");
        assertThat(handshake(oldUserTicket, new HashMap<>())).isEqualTo(403);
        assertThat(handshake(oldUserTicket, new HashMap<>())).isEqualTo(401);
        assertThat(handshake(ticket("admin"), new HashMap<>())).isEqualTo(403);
        UserEntity admin = new UserEntity();
        admin.setReviewStatus("approved");
        admin.setRole("admin");
        when(users.selectAuthenticationUserById(1L)).thenReturn(admin);
        Map<String, Object> attributes = new HashMap<>();
        assertThat(handshake(ticket("admin"), attributes)).isEqualTo(200);
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn("admin-session");
        when(socket.getAttributes()).thenReturn(attributes);
        handler.afterConnectionEstablished(socket);
        admin.setRole("user");
        handler.recheckIdleSessions();
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
    }

    private String ticket(String role) {
        return tickets.issue(new AuthenticatedUser(1L, "member", role, "approved", null, null)).ticket();
    }

    private int handshake(String ticket, Map<String, Object> attributes) throws Exception {
        var request = new MockHttpServletRequest("GET", "/ws/monitor");
        request.setQueryString("ticket=" + ticket);
        var response = new MockHttpServletResponse();
        var serverResponse = new ServletServerHttpResponse(response);
        boolean accepted = new MonitorHandshakeInterceptor(tickets, access).beforeHandshake(
                new ServletServerHttpRequest(request), serverResponse, handler, attributes);
        serverResponse.flush();
        assertThat(accepted).isEqualTo(response.getStatus() == 200);
        return response.getStatus();
    }
}
