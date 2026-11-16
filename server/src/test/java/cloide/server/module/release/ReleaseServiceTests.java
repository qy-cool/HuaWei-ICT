package cloide.server.module.release;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import cloide.server.module.job.ProjectRun;
import cloide.server.module.job.ProjectRunService;
import cloide.server.module.project.service.ProjectAccess;
import cloide.server.security.AuthenticatedUser;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class ReleaseServiceTests {
    private final ProjectAccess access = mock(ProjectAccess.class);
    private final ProjectRunService runs = mock(ProjectRunService.class);
    private final String runId = UUID.randomUUID().toString();
    private ArtifactService artifacts;
    private ReleaseService releases;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    @TempDir Path storage;

    @BeforeEach void start() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V42__project_artifacts_and_releases.sql"),
                new ClassPathResource("db/migration/V50__artifact_build_origin.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE servers(id BIGINT,deleted INTEGER)");
        jdbc.execute("CREATE TABLE project_nodes(project_id BIGINT,server_id BIGINT, environment VARCHAR)");
        jdbc.execute("CREATE TABLE users(id BIGINT,role VARCHAR,review_status VARCHAR)");
        jdbc.execute("CREATE TABLE project_members(user_id BIGINT,project_id BIGINT,member_role VARCHAR)");
        jdbc.update("INSERT INTO servers VALUES(11,0),(12,0)");
        jdbc.update("INSERT INTO project_nodes VALUES(1,11,'test'),(1,12,'prod')");
        jdbc.update("INSERT INTO users VALUES(5,'admin','approved')");
        when(access.principal()).thenReturn(new AuthenticatedUser(5L,"operator","admin","approved",null,null));
        successfulRun("succeeded");
        var clock = Clock.fixed(Instant.parse("2026-11-12T11:12:13Z"), ZoneOffset.UTC);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        artifacts = new ArtifactService(jdbc, access, runs, new ArtifactFiles(storage.toString(), 100), clock);
        releases = new ReleaseService(jdbc, access, artifacts, clock);
    }

    private void successfulRun(String status) {
        when(runs.read(1,runId)).thenReturn(new ProjectRun(runId,1,11,5,"orders","1".repeat(40),status,
                status.equals("succeeded") ? 0 : 1, "", null));
    }

    @AfterEach void stop() { if (jdbc != null) jdbc.execute("SHUTDOWN"); }
    private <T> T commit(Supplier<T> work) { return tx.execute(ignored -> work.get()); }
    private ArtifactService.Artifact upload(String text) throws Exception {
        byte[] data = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        return artifacts.upload(1, runId, "static-tar", digest, new ByteArrayInputStream(data));
    }
    private ReleaseService.Deploy request(ArtifactService.Artifact a, long server) {
        return new ReleaseService.Deploy(UUID.randomUUID(), UUID.fromString(a.id()), server, "orders");
    }
    private void report(long node, ReleaseService.Release r, String status) {
        tx.executeWithoutResult(ignored -> releases.report(node, r.id(), new ReleaseService.Receipt(r.sha256(), status, "probe " + status)));
    }

    @Test void identicalUploadsPreserveMetadataAndCorruptedObjectsFailClosed() throws Exception {
        var artifact = upload("static");
        assertEquals(artifact, upload("static"));
        assertEquals(1, artifacts.list(1).size());
        try (var read = artifacts.download(1, artifact.id()).getBody().getInputStream()) {
            assertArrayEquals("static".getBytes(), read.readAllBytes());
        }
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, assertThrows(BusinessException.class,
                () -> artifacts.download(2, artifact.id())).getErrorCode());
        assertThrows(BusinessException.class, () -> artifacts.upload(1, runId, "java-jar", artifact.sha256(),
                new ByteArrayInputStream(new byte[101])));
        successfulRun("failed");
        assertThrows(BusinessException.class, () -> upload("second"));
        successfulRun("succeeded");
        Files.writeString(storage.resolve(artifact.sha256().substring(0,2)).resolve(artifact.sha256()).resolve("blob"), "broken");
        assertThrows(BusinessException.class, () -> artifacts.download(1, artifact.id()));
        assertThrows(BusinessException.class, () -> upload("static"));
    }

    @Test void receiptAndPromotionKeepSameObjectAndUndoCreatesNewGeneration() throws Exception {
        var first = upload("v1");
        var second = upload("v2");
        var production = request(first, 12);
        assertThrows(BusinessException.class, () -> commit(() -> releases.deploy(1,"prod", production)));
        var test = request(first, 11);
        var candidate = commit(() -> releases.deploy(1, "test", test));
        assertEquals(candidate.id(), commit(() -> releases.deploy(1, "test", test)).id());
        assertThrows(BusinessException.class, () -> commit(() -> releases.deploy(1, "test", request(second,11))));
        assertEquals("applying", commit(() -> releases.claim(11, 1L, "test", "orders")).status());
        assertEquals(candidate.id(), commit(() -> releases.claim(11, null, null, null)).id());
        assertThrows(BusinessException.class, () -> releases.downloadForNode(12,candidate.id()));
        assertEquals(first, releases.downloadForNode(11,candidate.id()));
        report(11, candidate, "healthy");
        report(11, candidate, "healthy");
        assertThrows(BusinessException.class, () -> report(11, candidate,"failed"));
        assertEquals(List.of("queued","applying","healthy"), releases.events(1,"test",candidate.id()).stream().map(ReleaseService.Event::status).toList());
        assertThrows(BusinessException.class, () -> commit(() -> releases.deploy(1,"prod",production)),
                "人工关联成功测试的文件不能独立证明是本次运行的构建产物");
        jdbc.update("UPDATE project_artifacts SET source_node_id=11 WHERE id=?",first.id());
        var prod = commit(() -> releases.deploy(1,"prod",production));
        assertEquals(first.sha256(), prod.sha256());
        verify(access, times(3)).requireAdmin();
        var upgrade = commit(() -> releases.deploy(1,"test",request(second,11)));
        commit(() -> releases.claim(11,null,null,null));
        report(11, upgrade, "uncertain");
        assertThrows(BusinessException.class, () -> commit(() -> releases.deploy(1, "test", request(first,11))));
        report(11, upgrade, "healthy");
        var undo = new ReleaseService.Retry(UUID.randomUUID());
        var rollback = commit(() -> releases.rollback(1,"test",upgrade.id(),undo));
        assertEquals(3,rollback.generation());
        assertEquals(first.sha256(),rollback.sha256());
        commit(() -> releases.claim(11,null,null,null));
        report(11,rollback,"healthy");
        assertEquals(rollback.id(),commit(() -> releases.rollback(1,"test",upgrade.id(),undo)).id());
        assertThrows(BusinessException.class, () -> commit(() -> releases.rollback(1,"test",upgrade.id(),
                new ReleaseService.Retry(UUID.randomUUID()))));
    }

    @Test void revocationAndAssignmentChangesFenceDispatchAndKeepUnknownState() throws Exception {
        var a = upload("a");
        var request = request(a,11);
        var first = commit(() -> releases.deploy(1,"test",request));
        jdbc.update("UPDATE users SET review_status='pending'");
        assertNull(commit(() -> releases.claim(11,null,null,null)));
        assertEquals("cancelled",releases.list(1,"test").getFirst().status());
        assertThrows(BusinessException.class, () -> report(11,first,"healthy"));
        jdbc.update("UPDATE users SET review_status='approved'");
        var second = commit(() -> releases.deploy(1,"test",request(a,11)));
        commit(() -> releases.claim(11,null,null,null));
        assertThrows(BusinessException.class, () -> commit(() -> releases.cancel(1,"test",second.id())));
        jdbc.update("DELETE FROM project_nodes WHERE server_id=11");
        assertThrows(BusinessException.class, () -> releases.downloadForNode(11,second.id()));
        assertNull(commit(() -> releases.claim(11,null,null,null)));
        assertEquals("uncertain",releases.events(1,"test",second.id()).getLast().status());
        assertThrows(BusinessException.class, () -> commit(() -> releases.deploy(1, "Prod", request(a,12))));
    }
}
