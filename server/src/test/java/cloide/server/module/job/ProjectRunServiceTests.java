package cloide.server.module.job;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import cloide.server.module.project.service.ProjectAccess;
import cloide.server.websocket.AgentConnectionRegistry;
import cloide.server.websocket.AgentMessage;
import cloide.server.websocket.AgentWebSocketSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.WebSocketSession;

class ProjectRunServiceTests {
    private static final long PROJECT = 2L, USER = 3L, NODE = 4L;
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC);
    private final RunRequest request = new RunRequest(NODE, "orders", "0".repeat(40), UUID.randomUUID());
    private final ProjectAccess access = mock(ProjectAccess.class);
    private JdbcTemplate jdbc;
    private ProjectRunService service;
    private TransactionTemplate tx;

    @BeforeEach
    void open() {
        JdbcDataSource db = new JdbcDataSource();
        db.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V39__create_project_runs.sql"),
                new ClassPathResource("db/migration/V52__project_runs_sweep_index.sql")).execute(db);
        jdbc = new JdbcTemplate(db);
        tx = new TransactionTemplate(new DataSourceTransactionManager(db));
        service = new ProjectRunService(new ProjectRunStore(jdbc), access, clock,
                new DefaultListableBeanFactory().getBeanProvider(RunWakeupEvents.class));
        when(access.canRun(USER, PROJECT, NODE)).thenReturn(true);
    }

    @org.junit.jupiter.api.AfterEach
    void close() {
        if (jdbc != null) jdbc.execute("SHUTDOWN");
    }

    private <T> T committed(Supplier<T> action) {
        return tx.execute(ignored -> action.get());
    }

    private String enqueue() {
        return committed(() -> service.create(PROJECT, USER, request)).jobId();
    }

    @Test
    void duplicateRequestsAndLostDispatchUseTheSameImmutableJob() {
        String id = enqueue();
        assertEquals(id, enqueue());
        assertEquals(1, service.list(PROJECT).size());
        RunRequest different = new RunRequest(NODE, "other", request.commitSha(), request.requestId());
        assertEquals(ErrorCode.RESOURCE_CONFLICT, assertThrows(BusinessException.class,
                () -> committed(() -> service.create(PROJECT, USER, different))).getErrorCode());
        var first = committed(() -> service.next(NODE)).orElseThrow();
        assertEquals(id, first.payload().get("job_id"));
        assertEquals(first, committed(() -> service.next(NODE)).orElseThrow());
        assertEquals("dispatched", service.read(PROJECT, id).status());
    }

    @Test
    void duplicateResultAfterLostAckOnlyReconfirmsTheCommittedReport() {
        String id = enqueue();
        committed(() -> service.next(NODE));
        service.accepted(NODE, id);
        service.log(NODE, id, 1, "test started");
        service.log(NODE, id, 1, "test started");
        assertEquals(List.of(new ProjectRunStore.LogLine(1, "test started")), service.logs(PROJECT, id, 0));
        assertTrue(service.logs(PROJECT, id, 1).isEmpty());
        tx.executeWithoutResult(ignored -> service.finish(NODE, id, "succeeded", 0, "1 passed"));
        tx.executeWithoutResult(ignored -> service.finish(NODE, id, "succeeded", 0, "1 passed"));
        assertTrue(service.next(NODE).isEmpty());
        assertEquals("1 passed", service.read(PROJECT, id).output());
        assertEquals(ErrorCode.RESOURCE_CONFLICT, assertThrows(BusinessException.class,
                () -> service.finish(NODE, id, "failed", 1, "different")).getErrorCode());
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.finish(100L, id, "succeeded", 0, "1 passed")).getErrorCode());
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.read(200L, id)).getErrorCode());
    }

    @Test
    void revocationCancelsUnsentWorkAndAsksTheNodeAboutWorkAlreadySent() {
        String id = enqueue();
        when(access.canRun(USER, PROJECT, NODE)).thenReturn(false);
        assertTrue(committed(() -> service.next(NODE)).isEmpty());
        assertEquals("cancelled", service.read(PROJECT, id).status());
        var second = new RunRequest(NODE, "orders", request.commitSha(), UUID.randomUUID());
        var dispatched = committed(() -> service.create(PROJECT, USER, second));
        when(access.canRun(USER, PROJECT, NODE)).thenReturn(true);
        assertEquals("job.execute", committed(() -> service.next(NODE)).orElseThrow().type());
        when(access.canRun(USER, PROJECT, NODE)).thenReturn(false);
        assertEquals("job.cancel", committed(() -> service.next(NODE)).orElseThrow().type());
        assertEquals("cancelling", service.read(PROJECT, dispatched.jobId()).status());
        service.finish(NODE, dispatched.jobId(), "succeeded", 0, "completed before cancellation");
        assertEquals("succeeded", service.read(PROJECT, dispatched.jobId()).status());
    }

    @Test
    void cancellationBeforeDispatchAndExpiredQueueNeverStartAProcess() {
        String id = enqueue();
        assertEquals("cancelled", committed(() -> service.cancel(PROJECT, id)).status());
        assertTrue(committed(() -> service.next(NODE)).isEmpty());
        var next = new RunRequest(NODE, "orders", request.commitSha(), UUID.randomUUID());
        String expired = committed(() -> service.create(PROJECT, USER, next)).jobId();
        jdbc.update("UPDATE project_runs SET created_at = ? WHERE job_id = ?", LocalDateTime.now(clock).minusMinutes(31), expired);
        assertTrue(committed(() -> service.next(NODE)).isEmpty());
        assertEquals("cancelled", service.read(PROJECT, expired).status());
    }

    @Test
    void staleActiveRunsFallBackToUncertainAndNodeReceiptReconciles() {
        String id = enqueue();
        committed(() -> service.next(NODE));
        service.accepted(NODE, id);
        // 创建时间回拨越过 35 分钟宽限（30 分钟 deadline + 5 分钟）：活跃作业落 uncertain 终态，
        // 节点永久离线时这是唯一收敛路径（next() 依赖活会话）。
        jdbc.update("UPDATE project_runs SET created_at = ? WHERE job_id = ?", LocalDateTime.now(clock).minusMinutes(36), id);
        service.expireStaleRuns();
        assertEquals("uncertain", service.read(PROJECT, id).status());
        // uncertain 是终态：不再阻塞节点的串行队列。
        assertTrue(committed(() -> service.next(NODE)).isEmpty());
        // 节点回归后的真实回执覆盖核实。
        service.finish(NODE, id, "succeeded", 0, "late but true");
        assertEquals("succeeded", service.read(PROJECT, id).status());
        assertEquals("late but true", service.read(PROJECT, id).output());
    }

    @Test
    void freshActiveRunsAreNotSwept() {
        String id = enqueue();
        committed(() -> service.next(NODE));
        service.expireStaleRuns();
        assertEquals("dispatched", service.read(PROJECT, id).status());
    }

    @Test
    void lostAckCanReplayThroughANewAuthenticatedConnection() throws IOException {
        String id = enqueue();
        var json = new ObjectMapper();
        var connections = new AgentConnectionRegistry();
        var channel = new AgentJobChannel(service, connections, json, clock);
        var socket = mock(WebSocketSession.class);
        when(socket.isOpen()).thenReturn(true);
        when(socket.getId()).thenReturn("first");
        var first = new AgentWebSocketSession(socket, clock);
        first.authenticate(NODE, LocalDateTime.now(clock));
        connections.replace(first);
        var ready = new AgentMessage("job.ready", null, null, json.createObjectNode().put("protocol_version", 1));
        channel.handle(first, ready);
        var result = new AgentMessage("job.result", null, null, json.createObjectNode().put("job_id", id)
                .put("status", "succeeded").put("exit_code", 0).put("output", "done"));
        doThrow(new IOException("closed after commit")).when(socket).sendMessage(any());
        assertThrows(IOException.class, () -> channel.handle(first, result));
        var nextSocket = mock(WebSocketSession.class);
        when(nextSocket.isOpen()).thenReturn(true);
        when(nextSocket.getId()).thenReturn("next");
        var reconnected = new AgentWebSocketSession(nextSocket, clock);
        reconnected.authenticate(NODE, LocalDateTime.now(clock));
        connections.replace(reconnected);
        assertThrows(BusinessException.class, () -> channel.handle(first, result));
        channel.handle(reconnected, ready);
        channel.handle(reconnected, result);
        verify(nextSocket).sendMessage(argThat(frame -> frame.getPayload().toString().contains("job.result.ack")));
        assertEquals("done", service.read(PROJECT, id).output());
    }
}
