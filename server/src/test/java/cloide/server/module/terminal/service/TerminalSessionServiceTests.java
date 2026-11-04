package cloide.server.module.terminal.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import cloide.server.config.AppProperties;
import cloide.server.module.auth.entity.UserEntity;
import cloide.server.module.auth.service.UserService;
import cloide.server.module.server.entity.ServerEntity;
import cloide.server.module.server.service.ServerService;
import cloide.server.module.terminal.entity.TerminalSessionEntity;
import cloide.server.module.terminal.enums.TerminalSessionStatus;
import cloide.server.module.terminal.mapper.TerminalSessionMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 验证终端会话创建授权、幂等、会话配额和超时元数据收口。 */
@ExtendWith(MockitoExtension.class)
class TerminalSessionServiceTests {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-26T00:00:00Z"), ZoneOffset.UTC);
    @Mock private TerminalSessionMapper sessionMapper;
    @Mock private UserService userService;
    @Mock private ServerService serverService;
    private TerminalSessionService service;

    /** 创建使用固定时钟和默认资源限制的服务。 */
    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        service = new TerminalSessionServiceImpl(sessionMapper, userService, serverService, properties, CLOCK);
    }

    /** 已审核用户和在线 Agent 可创建 opening 会话。 */
    @Test
    void openSessionShouldPersistOpeningMetadata() {
        when(userService.isApprovedUser(1L)).thenReturn(true);
        when(serverService.existsActive(2L)).thenReturn(true);
        when(serverService.isAgentOnline(2L)).thenReturn(true);
        stubNoActiveSessions();

        TerminalSessionEntity result = service.openSession(1L, 2L, "5e6b9558-9bf1-4b0b-8b93-99fce56f9d19");

        ArgumentCaptor<TerminalSessionEntity> captor = ArgumentCaptor.forClass(TerminalSessionEntity.class);
        verify(sessionMapper).insertSession(captor.capture());
        assertEquals(TerminalSessionStatus.OPENING.value(), captor.getValue().getStatus());
        assertEquals(1L, result.getUserId());
        assertEquals(2L, result.getServerId());
    }

    /** 已有相同用户和 open 消息时返回原会话，不重复做授权或插入。 */
    @Test
    void openSessionShouldBeIdempotentByUserAndMessage() {
        TerminalSessionEntity existing = new TerminalSessionEntity();
        existing.setSessionId("4b9e2c30-1d0e-49ea-a4d7-05e5b41a4d9e");
        when(sessionMapper.selectByUserIdAndOpenMessageId(1L, "message-id")).thenReturn(existing);

        assertEquals(existing, service.openSession(1L, 2L, "message-id"));
    }

    /** 非 approved 用户不可获得 root 终端。 */
    @Test
    void openSessionShouldRejectUnapprovedUser() {
        UserEntity user = approvedUser();
        user.setReviewStatus("pending");
        when(userService.isApprovedUser(1L)).thenReturn(false);

        assertError(ErrorCode.TERMINAL_ACCESS_DENIED,
                () -> service.openSession(1L, 2L, "message-id"));
    }

    /** Agent 离线时不能创建无法中继的会话。 */
    @Test
    void openSessionShouldRejectOfflineAgent() {
        ServerEntity server = onlineServer();
        server.setAgentStatus("offline");
        when(userService.isApprovedUser(1L)).thenReturn(true);
        when(serverService.existsActive(2L)).thenReturn(true);
        when(serverService.isAgentOnline(2L)).thenReturn(false);

        assertError(ErrorCode.TERMINAL_AGENT_OFFLINE,
                () -> service.openSession(1L, 2L, "message-id"));
    }

    /** 已达到用户会话额度时拒绝创建。 */
    @Test
    void openSessionShouldEnforceUserLimit() {
        when(userService.isApprovedUser(1L)).thenReturn(true);
        when(serverService.existsActive(2L)).thenReturn(true);
        when(serverService.isAgentOnline(2L)).thenReturn(true);
        when(sessionMapper.selectActiveByUserId(1L)).thenReturn(List.of(new TerminalSessionEntity(), new TerminalSessionEntity()));

        assertError(ErrorCode.TERMINAL_SESSION_LIMIT_REACHED,
                () -> service.openSession(1L, 2L, "message-id"));
    }

    /** 空闲或超过最大时长的活动会话必须持久化为 timeout。 */
    @Test
    void closeExpiredSessionsShouldCloseIdleAndOverlongSessions() {
        TerminalSessionEntity idle = active("idle", LocalDateTime.of(2026, 7, 25, 23, 30),
                LocalDateTime.of(2026, 7, 25, 23, 30));
        TerminalSessionEntity overlong = active("overlong", LocalDateTime.of(2026, 7, 25, 15, 0), LocalDateTime.now(CLOCK));
        when(sessionMapper.selectAllActive()).thenReturn(List.of(idle, overlong));

        service.closeExpiredSessions();

        verify(sessionMapper).closeSession(eq("idle"), eq("timeout"), eq("idle_timeout"), any(LocalDateTime.class));
        verify(sessionMapper).closeSession(eq("overlong"), eq("timeout"), eq("max_session_duration"), any(LocalDateTime.class));
    }

    /** 构造最新审核状态用户。 */
    private UserEntity approvedUser() { UserEntity user = new UserEntity(); user.setId(1L); user.setReviewStatus("approved"); return user; }
    /** 为创建成功用例设置三个会话计数均未达到限制。 */
    private void stubNoActiveSessions() { when(sessionMapper.selectActiveByUserId(1L)).thenReturn(List.of()); when(sessionMapper.selectActiveByServerId(2L)).thenReturn(List.of()); when(sessionMapper.selectAllActive()).thenReturn(List.of()); }
    /** 构造在线 Agent 的服务器快照。 */
    private ServerEntity onlineServer() { ServerEntity server = new ServerEntity(); server.setId(2L); server.setAgentStatus("online"); return server; }
    /** 构造活动会话元数据。 */
    private TerminalSessionEntity active(String sessionId, LocalDateTime createdAt, LocalDateTime lastActivityAt) { TerminalSessionEntity session = new TerminalSessionEntity(); session.setSessionId(sessionId); session.setCreatedAt(createdAt); session.setLastActivityAt(lastActivityAt); return session; }
    /** 断言服务返回稳定业务错误码。 */
    private void assertError(ErrorCode errorCode, org.junit.jupiter.api.function.Executable executable) { BusinessException exception = assertThrows(BusinessException.class, executable); assertEquals(errorCode, exception.getErrorCode()); }
}
