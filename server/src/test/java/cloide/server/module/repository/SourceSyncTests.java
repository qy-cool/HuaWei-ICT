package cloide.server.module.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import cloide.server.module.project.service.NodeCapabilities;
import cloide.server.module.project.service.ProjectAccess;
import cloide.server.security.AuthenticatedUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A2 节点源码同步：H2 + 回环 GitHub 夹具 + 真实本地 Git 对象库，覆盖创建幂等与
 * 能力校验、受管 bundle 生成与摘要可核验、派发信封、终态裁决、短期授权、
 * source_unavailable 与超时兜底。
 */
class SourceSyncTests {
    @TempDir Path temp;
    private final ObjectMapper json=new ObjectMapper();
    private final ProjectAccess access=mock(ProjectAccess.class);
    private final NodeCapabilities capabilities=mock(NodeCapabilities.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<SourceSyncChannel> channel=mock(ObjectProvider.class);
    private final AtomicReference<Throwable> failure=new AtomicReference<>();
    private JdbcTemplate jdbc;
    private HttpServer server;
    private SourceIdentityService identity;
    private SourceBundleStore bundleStore;
    private SourceBundleService bundles;
    private SourceSyncService service;
    private RepositoryService repositories;
    private String commitSha, treeSha;

    @BeforeEach void start() throws Exception {
        var db=new JdbcDataSource();
        db.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(db);
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V44__project_github_repositories.sql"),
                new ClassPathResource("db/migration/V51__repository_source_identity.sql"),
                new ClassPathResource("db/migration/V53__source_syncs.sql")).execute(db);
        // 受信 Git 出口：本地对象库充当企业 GitHub（同一 JGit fetch/bundle 代码路径）。
        // 初始分支与 GitHub 夹具一致为 main，快照 pin 的即 refs/heads/main。
        Path work=temp.resolve("source/acme/orders.git");
        try (Git git=Git.init().setDirectory(work.toFile()).setInitialBranch("main").call()) {
            Files.writeString(work.resolve("README.md"),"orders baseline");
            git.add().addFilepattern("README.md").call();
            var commit=git.commit().setMessage("baseline")
                    .setAuthor(new PersonIdent("t","t@example.com"))
                    .setCommitter(new PersonIdent("t","t@example.com")).call();
            commitSha=commit.getName();
            treeSha=commit.getTree().getName();
        }
        var gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);var keys=gen.generateKeyPair();
        var pem=temp.resolve("app.pem");
        Files.writeString(pem,"-----BEGIN PRIVATE KEY-----\n"+Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",this::serve);
        server.start();
        var app=new GitHubApp(json,Clock.systemUTC(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()),
                "91",pem.toString(),17,"acme","acme/template","fixture-secret");
        var tx=new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(db));
        repositories=new RepositoryService(jdbc,access,app,null,null,json,tx);
        identity=new SourceIdentityService(jdbc,access,app,repositories,json,tx,Clock.systemUTC());
        bundleStore=new SourceBundleStore(temp.resolve("bundles").toString(),128*1024*1024);
        bundles=new SourceBundleService(app,bundleStore,temp.resolve("source").toUri());
        service=new SourceSyncService(new SourceSyncStore(jdbc),identity,repositories,bundles,bundleStore,
                capabilities,access,tx,Clock.systemUTC(),channel);
        when(access.principal()).thenReturn(new AuthenticatedUser(5L,"operator","admin","approved",null,null));
        when(capabilities.supportsAction(1,2,"source.sync.v1")).thenReturn(true);
        link(1,8,"acme","orders","main");
    }
    @AfterEach void stop() {
        if(server!=null) server.stop(0);
        if(jdbc!=null) jdbc.execute("SHUTDOWN");
        if(failure.get()!=null) throw new AssertionError(failure.get());
    }

    private void link(long project,long remoteId,String owner,String repo,String defaultBranch) {
        jdbc.update("""
                INSERT INTO project_github_repositories(project_id,remote_id,owner_name,repo_name,default_branch,installation_id,linked_by)
                VALUES (?,?,?,?,?,17,5)
                """,project,remoteId,owner,repo,defaultBranch);
        jdbc.update("""
                INSERT INTO project_repository_bindings(project_id,remote_id,owner_name,repo_name,default_branch,installation_id,generation,active,linked_by)
                SELECT ?,?,?,?,?,?,COALESCE(MAX(generation),0)+1,1,5 FROM project_repository_bindings WHERE remote_id=?
                """,project,remoteId,owner,repo,defaultBranch,17,remoteId);
    }

    private void reply(com.sun.net.httpserver.HttpExchange e,int code,Object data) throws Exception {
        byte[] bytes=json.writeValueAsBytes(data);
        e.sendResponseHeaders(code,bytes.length);
        try (OutputStream out=e.getResponseBody()) { out.write(bytes); }
    }
    private void serve(com.sun.net.httpserver.HttpExchange e) {
        try {
            String p=e.getRequestURI().getPath();
            if(p.equals("/app/installations/17/access_tokens")) { reply(e,201,Map.of("token","short-lived")); return; }
            if(p.equals("/repos/acme/orders/git/ref/heads/main")) {
                reply(e,200,Map.of("ref","refs/heads/main","object",Map.of("sha",commitSha))); return;
            }
            if(p.equals("/repos/acme/orders/git/commits/"+commitSha)) {
                reply(e,200,Map.of("tree",Map.of("sha",treeSha))); return;
            }
            fail("unexpected route "+p);
        } catch(Throwable err) {
            failure.set(err); try {reply(e,500,Map.of());} catch(Exception ignored) {}
        } finally {e.close();}
    }

    private SourceSyncService.View createSync() {
        var snapshot=identity.snapshot(1,8,UUID.randomUUID(),"refs/heads/main",null);
        return service.create(1,UUID.randomUUID(),snapshot.snapshotId(),2);
    }

    @Test void createRequiresCapabilityAndIsIdempotent() {
        var view=createSync();
        assertEquals("queued",view.state());
        assertEquals(commitSha,view.commitSha());
        assertEquals(2,view.serverId());
        // 同 requestId 原样重放返回原同步；同键异参（换节点）按 40908 拒绝。
        var snapshot=identity.snapshotRow(1,view.snapshotId());
        UUID requestId=UUID.randomUUID();
        var first=service.create(1,requestId,snapshot.snapshotId(),2);
        var replay=service.create(1,requestId,snapshot.snapshotId(),2);
        assertEquals(first.syncId(),replay.syncId());
        assertThrows(BusinessException.class,()->service.create(1,requestId,snapshot.snapshotId(),3));
        // 跨项目读取与创建均不暴露存在性。
        assertThrows(BusinessException.class,()->service.read(2,first.syncId()));
        // 未声明 source.sync.v1 的节点：40909，换节点或升级 Agent 后重试。
        when(capabilities.supportsAction(1,3,"source.sync.v1")).thenReturn(false);
        var error=assertThrows(BusinessException.class,()->service.create(1,UUID.randomUUID(),snapshot.snapshotId(),3));
        assertEquals(ErrorCode.SOURCE_SYNC_CAPABILITY_MISSING,error.getErrorCode());
    }

    @Test void prepareProducesVerifiableBundleAndDispatchEnvelope() throws Exception {
        var view=createSync();
        service.prepareNext();
        var prepared=service.read(1,view.syncId());
        assertEquals("transferring",prepared.state(),"lastError="+prepared.lastError()+" attempts="+prepared.attempts());
        assertEquals(64,prepared.bundleSha256().length());
        assertTrue(prepared.byteCount()>0);
        // bundle 可独立导入：私有 ref、commit 与 tree 与快照事实一致（无令牌核验路径）。
        Path verify=temp.resolve("verify");
        try (Git git=Git.init().setBare(true).setDirectory(verify.toFile()).call()) {
            git.fetch().setRemote(bundleStore.open(prepared.bundleSha256()).toString())
                    .setRefSpecs(new RefSpec("refs/cloide/snapshots/*:refs/cloide/snapshots/*")).call();
            Ref pinned=git.getRepository().exactRef("refs/cloide/snapshots/"+view.snapshotId());
            assertNotNull(pinned);
            assertEquals(commitSha,pinned.getObjectId().getName());
            try (RevWalk walk=new RevWalk(git.getRepository())) {
                assertEquals(treeSha,walk.parseCommit(ObjectId.fromString(commitSha)).getTree().getName());
            }
        }
        // 节点轮询取到完整派发信封；ready 轮询可触发重发且保持原请求（断线重连补发）。
        var dispatch=service.next(2).orElseThrow();
        assertEquals("source.sync",dispatch.type());
        assertEquals(view.syncId(),dispatch.payload().get("sync_id"));
        assertEquals(commitSha,dispatch.payload().get("commit_sha"));
        assertEquals(prepared.bundleSha256(),dispatch.payload().get("bundle_sha256"));
        assertEquals(prepared.byteCount(),((Number)dispatch.payload().get("byte_count")).longValue());
        var resend=service.next(2).orElseThrow();
        assertEquals("source.sync",resend.type());
        assertEquals(dispatch.payload(),resend.payload());
    }

    @Test void finishReadyIsUniqueAndReplayIsIdempotent() {
        var view=createSync();
        service.prepareNext();
        service.next(2).orElseThrow();
        service.accepted(2,view.syncId());
        service.progress(2,view.syncId(),"verifying");
        assertEquals("verifying",service.read(1,view.syncId()).state());
        service.finish(2,view.syncId(),"ready",commitSha,treeSha,
                service.read(1,view.syncId()).bundleSha256(),"installed");
        assertEquals("ready",service.read(1,view.syncId()).state());
        // 同结果重放：不产生新状态、不冲突；摘要与派发不符则拒绝。
        service.finish(2,view.syncId(),"ready",commitSha,treeSha,
                service.read(1,view.syncId()).bundleSha256(),"installed");
        assertThrows(BusinessException.class,
                ()->service.finish(2,view.syncId(),"ready","0".repeat(40),treeSha,null,"installed"));
        assertThrows(BusinessException.class,
                ()->service.finish(2,view.syncId(),"failed",null,null,null,"late divergence"));
    }

    @Test void wrongNodeCannotClaimGrantOrFinish() {
        var view=createSync();
        service.prepareNext();
        // 错误节点提交第一节点的回执：按不存在拒绝，不产生任何状态变化。
        assertThrows(BusinessException.class,()->service.accepted(9,view.syncId()));
        assertThrows(BusinessException.class,()->service.grant(9,view.syncId()));
        assertThrows(BusinessException.class,
                ()->service.finish(9,view.syncId(),"ready",commitSha,treeSha,"x".repeat(64),"installed"));
        assertEquals("transferring",service.read(1,view.syncId()).state());
    }

    @Test void grantContentRoundTripRejectsBadSecretAndTerminalState() {
        var view=createSync();
        service.prepareNext();
        var grant=service.grant(2,view.syncId());
        assertEquals(43,grant.secret().length());
        assertEquals(commitSha,grant.commitSha());
        Path bundle=service.content(2,view.syncId(),grant.grantId(),grant.secret());
        assertTrue(Files.isRegularFile(bundle));
        // 错误密钥：立即拒绝；授权不属于该节点同样拒绝。
        assertThrows(BusinessException.class,()->service.content(2,view.syncId(),grant.grantId(),"w".repeat(43)));
        assertThrows(BusinessException.class,()->service.content(9,view.syncId(),grant.grantId(),grant.secret()));
        // 授权过期：即使密钥正确也不可再取新分段（逐请求重查期限）。
        jdbc.update("UPDATE source_sync_grants SET expires_at='2000-01-01 00:00:00' WHERE grant_id=?",grant.grantId());
        assertThrows(BusinessException.class,()->service.content(2,view.syncId(),grant.grantId(),grant.secret()));
        // 终态后凭旧授权取得新分段被立即阻止。
        service.next(2);
        service.finish(2,view.syncId(),"ready",commitSha,treeSha,grant.bundleSha256(),"installed");
        assertThrows(BusinessException.class,()->service.content(2,view.syncId(),grant.grantId(),grant.secret()));
        assertThrows(BusinessException.class,()->service.grant(2,view.syncId()));
    }

    @Test void stalePreparingConvergesAfterControlPlaneRestart() {
        // 中控在 bundle 生成期间崩溃：preparing 滞留超过时限后按截止时间收敛——
        // 未过期回 queued 供重试，已过期落 uncertain；均在超时兜底扫描中完成。
        var view=createSync();
        jdbc.update("UPDATE source_syncs SET status='preparing',updated_at='2000-01-01 00:00:00' WHERE sync_id=?",view.syncId());
        service.expireStale();
        assertEquals("queued",service.read(1,view.syncId()).state());
        // 重新入队后仍可正常生成 bundle 并派发。
        service.prepareNext();
        assertEquals("transferring",service.read(1,view.syncId()).state());
        // 截止时间已过的滞留 preparing：落 uncertain，节点回归后真实回执可覆盖。
        var other=createSync();
        jdbc.update("UPDATE source_syncs SET status='preparing',updated_at='2000-01-01 00:00:00',deadline='2000-01-01 00:00:00' WHERE sync_id=?",other.syncId());
        service.expireStale();
        assertEquals("uncertain",service.read(1,other.syncId()).state());
        // 从未生成 bundle 的同步收到 ready 回执：无派发事实可核验，按冲突拒绝而非 NPE。
        assertThrows(BusinessException.class,
                ()->service.finish(2,other.syncId(),"ready",commitSha,treeSha,null,"installed late"));
    }

    @Test void unavailableCommitFailsAsSourceUnavailable() {
        // 快照存在但远端对象库无法证明该提交（强推/清理后）：source_unavailable 终态，原快照保留审计。
        String snapshotId=UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO source_snapshots(snapshot_id,project_id,remote_id,ref,commit_sha,tree_sha,binding_generation,policy_version,resolved_at,created_by)
                VALUES (?,1,8,'refs/heads/main',?,? ,1,0,CURRENT_TIMESTAMP,5)
                """,snapshotId,"f".repeat(40),"e".repeat(40));
        var view=service.create(1,UUID.randomUUID(),snapshotId,2);
        service.prepareNext();
        var failed=service.read(1,view.syncId());
        // 远端不可证明固定提交来源：落 failed 且错误分类明确。
        assertEquals("failed",failed.state());
        assertNotNull(failed.lastError());
        // 失败终态不阻断后续请求：新的 requestId 对同一快照可再建同步。
        var replay=service.create(1,UUID.randomUUID(),snapshotId,2);
        assertNotEquals(view.syncId(),replay.syncId());
    }

    @Test void staleSyncFallsToUncertainAndRealReceiptOverwrites() {
        var view=createSync();
        service.prepareNext();
        // 截止时间已过：节点轮询改发取消；宽限期后兜底 uncertain。
        jdbc.update("UPDATE source_syncs SET deadline='2000-01-01 00:00:00' WHERE sync_id=?",view.syncId());
        var dispatch=service.next(2).orElseThrow();
        assertEquals("source.sync.cancel",dispatch.type());
        jdbc.update("UPDATE source_syncs SET deadline='2000-01-01 00:00:00' WHERE sync_id=?",view.syncId());
        service.expireStale();
        assertEquals("uncertain",service.read(1,view.syncId()).state());
        // 节点回归后的真实回执覆盖核实。
        service.finish(2,view.syncId(),"ready",commitSha,treeSha,
                service.read(1,view.syncId()).bundleSha256(),"installed late");
        assertEquals("ready",service.read(1,view.syncId()).state());
    }

    @Test void progressRejectsUnknownStageAndForeignSync() {
        var view=createSync();
        service.prepareNext();
        assertThrows(BusinessException.class,()->service.progress(2,view.syncId(),"downloading"));
        assertThrows(BusinessException.class,()->service.progress(9,view.syncId(),"verifying"));
    }
}
