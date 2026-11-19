package cloide.server.module.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import cloide.server.module.project.service.ProjectAccess;
import cloide.server.security.AuthenticatedUser;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** A1 仓库身份与提交选择：回环 GitHub 夹具 + H2，覆盖分支前移、跨项目、解绑重绑与幂等。 */
class SourceIdentityTests {
    @TempDir Path temp;
    private static final String X="a".repeat(40), Y="b".repeat(40), TREE_X="c".repeat(40), TREE_Y="d".repeat(40);
    private static final String R="e".repeat(40), TREE_R="f".repeat(40);
    private final ObjectMapper json=new ObjectMapper();
    private final ProjectAccess access=mock(ProjectAccess.class);
    private final AtomicReference<Throwable> failure=new AtomicReference<>();
    private final AtomicReference<Runnable> beforeTreeResponse=new AtomicReference<>();
    private JdbcTemplate jdbc;
    private HttpServer server;
    private GitHubApp app;
    private RepositoryService repositories;
    private SourceIdentityService service;
    /** 夹具控制的分支当前指向：默认 X，测试改为 Y 模拟前移。 */
    private volatile String mainSha=X;
    /** 夹具控制的树 SHA：与 mainSha 联动。 */
    private volatile String mainTree=TREE_X;
    /** 夹具开关：置 true 后单 ref 端点对 main 返回 404，模拟远端分支已删除。 */
    private volatile boolean mainMissing;
    /** 批量 heads 端点命中计数：断言 refs 列表单次调用只发一次请求。 */
    private int batchRefCalls;

    @BeforeEach void start() throws Exception {
        var db=new JdbcDataSource();
        db.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(db);
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V44__project_github_repositories.sql"),
                new ClassPathResource("db/migration/V51__repository_source_identity.sql")).execute(db);
        var gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);var keys=gen.generateKeyPair();
        var pem=temp.resolve("app.pem");
        Files.writeString(pem,"-----BEGIN PRIVATE KEY-----\n"+Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",this::serve);
        server.start();
        app=new GitHubApp(json,Clock.systemUTC(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()),
                "91",pem.toString(),17,"acme","acme/template","fixture-secret");
        var tx=new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(db));
        repositories=new RepositoryService(jdbc,access,app,null,null,json,tx);
        service=new SourceIdentityService(jdbc,access,app,repositories,json,tx,Clock.systemUTC());
        // 默认按平台管理员（id=5）+ 项目 1 成员放行；个别用例里改紧。
        when(access.principal()).thenReturn(new AuthenticatedUser(5L,"operator","admin","approved",null,null));
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
        // 与生产 connect() 一致：代次取 MAX+1，重绑生成新一代次。
        jdbc.update("""
                INSERT INTO project_repository_bindings(project_id,remote_id,owner_name,repo_name,default_branch,installation_id,generation,active,linked_by)
                SELECT ?,?,?,?,?,?,COALESCE(MAX(generation),0)+1,1,5 FROM project_repository_bindings WHERE remote_id=?
                """,project,remoteId,owner,repo,defaultBranch,17,remoteId);
    }

    private void reply(HttpExchange e,int code,Object data) throws IOException {
        byte[] bytes=data instanceof byte[] b?b:json.writeValueAsBytes(data);
        e.sendResponseHeaders(code,bytes.length);
        e.getResponseBody().write(bytes);
    }
    private void serve(HttpExchange e) {
        try {
            String p=e.getRequestURI().getPath();
            if(p.equals("/app/installations/17/access_tokens")) { reply(e,201,Map.of("token","short-lived")); return; }
            if(p.equals("/repos/acme/orders")) {
                reply(e,200,Map.of("id",8,"name","orders","owner",Map.of("login","acme"),
                        "private",true,"default_branch","main","description","")); return;
            }
            if(p.equals("/repos/acme/orders/git/matching-refs/heads/")) {
                // 批量端点：返回夹具当前的全部 heads（跟随 mainSha，模拟分支前移）。
                batchRefCalls++;
                reply(e,200,List.of(Map.of("ref","refs/heads/main","object",Map.of("sha",mainSha)))); return;
            }
            if(p.equals("/repos/acme/orders/git/matching-refs/heads/release/1.2")) {
                // 含斜杠分支：matching-refs 按前缀返回多条，掺入干扰项验证完整 ref 精确匹配。
                reply(e,200,List.of(
                        Map.of("ref","refs/heads/release/1.2","object",Map.of("sha",R)),
                        Map.of("ref","refs/heads/release/1.2.3","object",Map.of("sha","9".repeat(40))))); return;
            }
            if(p.equals("/repos/acme/orders/git/ref/heads/main")) {
                if (mainMissing) { reply(e,404,Map.of("message","Not Found")); return; }
                reply(e,200,Map.of("ref","refs/heads/main","object",Map.of("sha",mainSha))); return;
            }
            if(p.equals("/repos/acme/orders/git/commits/"+X)) {
                Runnable callback=beforeTreeResponse.getAndSet(null);
                if(callback!=null) callback.run();
                reply(e,200,Map.of("tree",Map.of("sha",TREE_X))); return;
            }
            if(p.equals("/repos/acme/orders/git/commits/"+Y)) { reply(e,200,Map.of("tree",Map.of("sha",TREE_Y))); return; }
            if(p.equals("/repos/acme/orders/git/commits/"+R)) { reply(e,200,Map.of("tree",Map.of("sha",TREE_R))); return; }
            fail("unexpected route "+p);
        } catch(Throwable err) {
            failure.set(err); try {reply(e,500,Map.of());} catch(Exception ignored) {}
        } finally {e.close();}
    }

    @Test void branchMovedBeforeSnapshotYieldsConflictAndOldSnapshotStaysPinned() {
        // A1-01：读 ref 得 X，创建快照前分支移到 Y，带 expectedCommitSha=X 请求 → 冲突且携带当前实际 SHA。
        UUID requestId=UUID.randomUUID();
        mainSha=Y; mainTree=TREE_Y;
        var error=assertThrows(BusinessException.class,
                ()->service.snapshot(1,8,requestId,"refs/heads/main",X));
        assertEquals(ErrorCode.SOURCE_COMMIT_MOVED,error.getErrorCode());
        assertEquals(Y,((Map<?,?>)error.getData()).get("currentCommitSha"));

        // 接受新提交后创建成功；随后分支再前移，原样重试同 requestId 仍返回首次钉住的快照。
        var snapshot=service.snapshot(1,8,requestId,"refs/heads/main",Y);
        assertEquals(Y,snapshot.commitSha());
        assertEquals(TREE_Y,snapshot.treeSha());
        assertEquals(1,snapshot.bindingGeneration());
        mainSha=X; mainTree=TREE_X;
        var replay=service.snapshot(1,8,requestId,"refs/heads/main",Y);
        assertEquals(snapshot.snapshotId(),replay.snapshotId());
        assertEquals(Y,replay.commitSha());
        // 同 requestId 但换期望 SHA：同键异参 → 冲突。
        assertThrows(BusinessException.class,()->service.snapshot(1,8,requestId,"refs/heads/main",X));
    }

    @Test void refsListingIsPointInTimeObservation() {
        var page=service.refs(1,8,null,50);
        assertEquals(1,page.items().size());
        assertEquals("refs/heads/main",page.items().getFirst().ref());
        assertEquals(X,page.items().getFirst().commitSha());
        assertNull(page.nextCursor());
        // 每次列表调用仅发一次批量 heads 请求（此前为逐分支串行）。
        assertEquals(1,batchRefCalls);
        mainSha=Y;
        assertEquals(Y,service.refs(1,8,null,50).items().getFirst().commitSha());
        assertEquals(2,batchRefCalls);
    }

    @Test void snapshotRejectsRefOutsidePolicyAndFormats() {
        assertThrows(BusinessException.class,()->service.snapshot(1,8,UUID.randomUUID(),"refs/heads/develop",null));
        assertThrows(BusinessException.class,()->service.snapshot(1,8,UUID.randomUUID(),"main",null));
        assertThrows(BusinessException.class,()->service.snapshot(1,8,UUID.randomUUID(),"refs/heads/ma*",null));
    }

    @Test void snapshotOnDeletedBranchMapsToSourceRefNotFound() {
        // 契约 40405：允许清单内的分支在远端已被删除（单 ref 端点 404）→ 404 语义，
        // 而非落入 SHA 格式校验的 409 RESOURCE_CONFLICT。
        mainMissing=true;
        var error=assertThrows(BusinessException.class,
                ()->service.snapshot(1,8,UUID.randomUUID(),"refs/heads/main",null));
        assertEquals(ErrorCode.SOURCE_REF_NOT_FOUND,error.getErrorCode());
    }

    @Test void slashBranchSnapshotResolvesViaMatchingRefs() {
        // 含斜杠分支走 matching-refs 集合端点并按完整 ref 精确匹配，干扰项（release/1.2.3）不命中。
        service.updatePolicy(1,8,UUID.randomUUID(),0,List.of("refs/heads/release/1.2"));
        var snapshot=service.snapshot(1,8,UUID.randomUUID(),"refs/heads/release/1.2",null);
        assertEquals(R,snapshot.commitSha());
        assertEquals(TREE_R,snapshot.treeSha());
    }

    @Test void refsReportNullShaForDeletedRemoteBranch() {
        // 允许清单内的分支在远端已删除：refs 列表以 null SHA 表达，列表不因此失败。
        service.updatePolicy(1,8,UUID.randomUUID(),0,List.of("refs/heads/main","refs/heads/gone"));
        var page=service.refs(1,8,null,50);
        assertEquals(2,page.items().size());
        assertNull(page.items().stream().filter(i->i.ref().equals("refs/heads/gone")).findFirst().orElseThrow().commitSha());
    }

    @Test void refsPaginationRoundTripsCursor() {
        // 分页往返：nextCursor 定位下一页并最终终止（null）。
        service.updatePolicy(1,8,UUID.randomUUID(),0,List.of("refs/heads/main","refs/heads/release/1.2"));
        var first=service.refs(1,8,null,1);
        assertEquals("refs/heads/main",first.items().getFirst().ref());
        assertNotNull(first.nextCursor());
        var second=service.refs(1,8,first.nextCursor(),1);
        assertEquals("refs/heads/release/1.2",second.items().getFirst().ref());
        assertNull(second.nextCursor());
    }

    @Test void policyUpdatesAreOptimisticAndVersioned() {
        UUID requestId=UUID.randomUUID();
        var v1=service.updatePolicy(1,8,requestId,0,List.of("refs/heads/main","refs/heads/release/1.2"));
        assertEquals(1,v1.policyVersion());
        assertEquals(List.of("refs/heads/main","refs/heads/release/1.2"),v1.allowedRefs());
        // 版本冲突：用过期版本更新被拒绝。
        assertThrows(BusinessException.class,
                ()->service.updatePolicy(1,8,UUID.randomUUID(),0,List.of("refs/heads/main")));
        // 原样重放同 requestId：返回原版本，不产生新行（v0 是隐式默认，不落库）。
        var replay=service.updatePolicy(1,8,requestId,0,List.of("refs/heads/main","refs/heads/release/1.2"));
        assertEquals(1,replay.policyVersion());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM repository_source_policies",Integer.class));
        // 同 requestId 异参：冲突。
        assertThrows(BusinessException.class,
                ()->service.updatePolicy(1,8,requestId,0,List.of("refs/heads/main")));
    }

    @Test void crossProjectAccessHidesExistence() {
        var snapshot=service.snapshot(1,8,UUID.randomUUID(),"refs/heads/main",null);
        // A1-02：项目 2 请求项目 1 的快照 → 404，不暴露仓库或 ref。
        var error=assertThrows(BusinessException.class,()->service.snapshotRow(2,snapshot.snapshotId()));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND,error.getErrorCode());
        // 项目 2 对仓库 8 的 refs/快照操作同样 404。
        assertThrows(BusinessException.class,()->service.refs(2,8,null,50));
        assertThrows(BusinessException.class,()->service.snapshot(2,8,UUID.randomUUID(),"refs/heads/main",null));
    }

    @Test void unlinkInvalidatesOldGenerationAndRebindCreatesNewOne() {
        var pinned=service.snapshot(1,8,UUID.randomUUID(),"refs/heads/main",null);
        repositories.unlink(1,8);
        // 解绑后：旧快照可读但标记绑定失效；新快照创建被拒。
        var audited=service.snapshotRow(1,pinned.snapshotId());
        assertFalse(audited.bindingActive());
        assertThrows(BusinessException.class,()->service.snapshot(1,8,UUID.randomUUID(),"refs/heads/main",null));
        // 重绑产生第 2 代次；旧代次不能创建新快照，历史快照不串联到新代次。
        link(1,8,"acme","orders","main");
        var fresh=service.snapshot(1,8,UUID.randomUUID(),"refs/heads/main",null);
        assertEquals(2,fresh.bindingGeneration());
        assertTrue(fresh.bindingActive());
        assertEquals(1,audited.bindingGeneration());
        // 旧快照仍指向第 1 代次且不因重绑复活。
        var stillOld=service.snapshotRow(1,pinned.snapshotId());
        assertEquals(1,stillOld.bindingGeneration());
        assertFalse(stillOld.bindingActive());
    }

    @Test void rebindWhileResolvingTreeDiscardsSnapshotAndAllowsRetry() {
        UUID requestId=UUID.randomUUID();
        // 分支已解析，在提交树返回前由另一个连接撤销并重绑。
        beforeTreeResponse.set(()->{
            repositories.unlink(1,8);
            link(1,8,"acme","orders","main");
        });

        var error=assertThrows(BusinessException.class,
                ()->service.snapshot(1,8,requestId,"refs/heads/main",X));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND,error.getErrorCode());
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM source_snapshots",Long.class));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM repository_idempotent_requests",Long.class));

        var retried=service.snapshot(1,8,requestId,"refs/heads/main",X);
        assertEquals(2,retried.bindingGeneration());
        assertTrue(retried.bindingActive());
        assertEquals(retried,service.snapshot(1,8,requestId,"refs/heads/main",X));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM source_snapshots",Long.class));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM repository_idempotent_requests",Long.class));
    }

    @Test void sameRemoteIdCannotServeTwoProjects() {
        // V44 约束保留：remote_id 全局唯一绑定一个项目；项目 2 视角下仓库 8 不存在。
        assertThrows(BusinessException.class,()->repositories.activeBinding(2,8));
        assertEquals(1,(long)jdbc.queryForObject("SELECT COUNT(*) FROM project_repository_bindings WHERE remote_id=8",Long.class));
    }

    @Test void snapshotReadRequiresProjectReadPermission() {
        var snapshot=service.snapshot(1,8,UUID.randomUUID(),"refs/heads/main",null);
        // snapshot() 创建路径与显式读取都经过 requireRead；非成员在该步即被拒。
        verify(access,atLeastOnce()).requireRead(1);
        doThrow(new BusinessException(ErrorCode.FORBIDDEN)).when(access).requireRead(1);
        var error=assertThrows(BusinessException.class,()->service.snapshotRow(1,snapshot.snapshotId()));
        assertEquals(ErrorCode.FORBIDDEN,error.getErrorCode());
    }

    @Test void policyVersionRaceMapsDuplicateInsertToConflict() {
        // 确定性复现并发竞争：另一请求已抢先写入 v1，本请求再写同版本撞主键 → 40908 而非 500。
        jdbc.update("INSERT INTO repository_source_policies(remote_id,policy_version,allowed_refs,updated_by) VALUES (8,1,'[\"refs/heads/main\"]',5)");
        var error=assertThrows(BusinessException.class,
                ()->service.updatePolicy(1,8,UUID.randomUUID(),0,List.of("refs/heads/main")));
        assertEquals(ErrorCode.SOURCE_REQUEST_CONFLICT,error.getErrorCode());
    }

    @Test void idempotentForUpdateResolvesCommittedLedgerRow() {
        // DuplicateKeyException 收敛路径的锁定读（FOR UPDATE 当前读）：直测命中、异参与缺失三种形态；
        // REPEATABLE READ 读视图导致的陈旧读仅真实 MySQL 可复现，H2 下验证 SQL 形态与 digest 语义。
        String digestA="a".repeat(64), digestB="b".repeat(64);
        UUID requestId=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO repository_idempotent_requests(project_id,requester_id,operation,request_id,param_digest,result_id)
                VALUES (1,5,'snapshot',?,?,'11111111-1111-1111-1111-111111111111')
                """,requestId.toString(),digestA);
        assertEquals("11111111-1111-1111-1111-111111111111",
                service.idempotentResultForUpdate(1,"snapshot",requestId,digestA));
        var conflict=assertThrows(BusinessException.class,
                ()->service.idempotentResultForUpdate(1,"snapshot",requestId,digestB));
        assertEquals(ErrorCode.SOURCE_REQUEST_CONFLICT,conflict.getErrorCode());
        assertNull(service.idempotentResultForUpdate(1,"snapshot",UUID.randomUUID(),digestA));
    }

    @Test void refsRejectsMalformedCursor() {
        var error=assertThrows(BusinessException.class,()->service.refs(1,8,"!!!",50));
        assertEquals(ErrorCode.INVALID_REQUEST_PARAMETER,error.getErrorCode());
        // 解码为超 int 的 offset：同样按 400 拒绝。
        String overflow=Base64.getUrlEncoder().withoutPadding()
                .encodeToString("99999999999999999999".getBytes(StandardCharsets.UTF_8));
        var error2=assertThrows(BusinessException.class,()->service.refs(1,8,overflow,50));
        assertEquals(ErrorCode.INVALID_REQUEST_PARAMETER,error2.getErrorCode());
        // 解码为负数 offset：同样按 400 拒绝，不落到 IndexOutOfBoundsException 的 500。
        String negative=Base64.getUrlEncoder().withoutPadding()
                .encodeToString("-1".getBytes(StandardCharsets.UTF_8));
        var error3=assertThrows(BusinessException.class,()->service.refs(1,8,negative,50));
        assertEquals(ErrorCode.INVALID_REQUEST_PARAMETER,error3.getErrorCode());
    }

    @Test void connectOnExistingActiveBindingIsIdempotent() {
        // 并发/重复导入同一仓库：V44 活动行已存在时不产生新代次，返回原绑定。
        var linked=repositories.connect(1,"orders",null);
        assertEquals(8,linked.remoteId());
        assertEquals(1,(long)jdbc.queryForObject(
                "SELECT COUNT(*) FROM project_repository_bindings WHERE remote_id=8 AND active=1",Long.class));
    }
}
