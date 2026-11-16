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
    private final ObjectMapper json=new ObjectMapper();
    private final ProjectAccess access=mock(ProjectAccess.class);
    private final AtomicReference<Throwable> failure=new AtomicReference<>();
    private JdbcTemplate jdbc;
    private HttpServer server;
    private GitHubApp app;
    private RepositoryService repositories;
    private SourceIdentityService service;
    /** 夹具控制的分支当前指向：默认 X，测试改为 Y 模拟前移。 */
    private volatile String mainSha=X;
    /** 夹具控制的树 SHA：与 mainSha 联动。 */
    private volatile String mainTree=TREE_X;

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
        repositories=new RepositoryService(jdbc,access,app,null,null,json);
        var tx=new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(db));
        service=new SourceIdentityService(jdbc,access,app,repositories,json,tx);
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
            if(p.equals("/repos/acme/orders/git/ref/heads/main")) {
                reply(e,200,Map.of("ref","refs/heads/main","object",Map.of("sha",mainSha))); return;
            }
            if(p.equals("/repos/acme/orders/git/commits/"+X)) { reply(e,200,Map.of("tree",Map.of("sha",TREE_X))); return; }
            if(p.equals("/repos/acme/orders/git/commits/"+Y)) { reply(e,200,Map.of("tree",Map.of("sha",TREE_Y))); return; }
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
        mainSha=Y;
        assertEquals(Y,service.refs(1,8,null,50).items().getFirst().commitSha());
    }

    @Test void snapshotRejectsRefOutsidePolicyAndFormats() {
        assertThrows(BusinessException.class,()->service.snapshot(1,8,UUID.randomUUID(),"refs/heads/develop",null));
        assertThrows(BusinessException.class,()->service.snapshot(1,8,UUID.randomUUID(),"main",null));
        assertThrows(BusinessException.class,()->service.snapshot(1,8,UUID.randomUUID(),"refs/heads/ma*",null));
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
}
