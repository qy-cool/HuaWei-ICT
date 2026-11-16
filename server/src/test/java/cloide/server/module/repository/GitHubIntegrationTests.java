package cloide.server.module.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cloide.server.common.BusinessException;
import cloide.server.module.coding.CodingService;
import cloide.server.module.project.service.ProjectAccess;
import cloide.server.security.AuthenticatedUser;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import io.jsonwebtoken.Jwts;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** 用真实 HTTP 验证签名和 GitHub REST 交互；只绑定回环。 */
class GitHubIntegrationTests {
    @TempDir Path temp;
    private static final String BASE="1".repeat(40), TREE="2".repeat(40), NEW_TREE="3".repeat(40), HEAD="4".repeat(40);
    private final ObjectMapper json=new ObjectMapper();
    private final UUID taskId=UUID.randomUUID();
    private final CodingService coding=mock(CodingService.class);
    private final ProjectAccess access=mock(ProjectAccess.class);
    private final AtomicReference<Throwable> failure=new AtomicReference<>();
    private JdbcTemplate jdbc;
    private HttpServer server;
    private RepositoryService service;
    private KeyPair keys;
    private volatile boolean branch,pull;
    private String created;
    private int createdBranches,createdPulls;

    @BeforeEach void start() throws Exception {
        var db=new JdbcDataSource();
        db.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(db);
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V44__project_github_repositories.sql"),
                new ClassPathResource("db/migration/V51__repository_source_identity.sql")).execute(db);
        var gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);keys=gen.generateKeyPair();
        var pem=temp.resolve("app.pem");
        Files.writeString(pem,"-----BEGIN PRIVATE KEY-----\n"+Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",this::serve);
        server.start();
        var app=new GitHubApp(json,Clock.systemUTC(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()),
                "91",pem.toString(),17,"acme","acme/template","fixture-secret");
        var task=new CodingService.Task(taskId.toString(),1,11,5,"orders",BASE,"change","build",false,"ready_for_review",2,null,null);
        when(coding.get(1,taskId)).thenReturn(task);
        when(coding.events(1,taskId,0)).thenReturn(List.of(new CodingService.Event(1,json.valueToTree(Map.of(
                "type","change","path","hello.txt","beforeHash","missing","afterHash",GitHubApp.sha("done".getBytes()),"content","done")))));
        when(access.principal()).thenReturn(new AuthenticatedUser(5L,"operator","admin","approved",null,null));
        service=new RepositoryService(jdbc,access,app,new TaskBranch(app,coding),coding,json);
    }
    @AfterEach void stop() {
        if(server!=null) server.stop(0);
        if(jdbc!=null) jdbc.execute("SHUTDOWN");
        if(failure.get()!=null) throw new AssertionError(failure.get());
    }
    private void reply(HttpExchange e,int code,Object data) throws Exception {
        byte[] bytes=json.writeValueAsBytes(data); e.sendResponseHeaders(code,bytes.length);e.getResponseBody().write(bytes);
    }
    private Map<String,Object> repo(long id,String name) {
        return Map.of("id",id,"name",name,"owner",Map.of("login","acme"),"private",true,"default_branch","main",
                "description",created==null?"":created);
    }
    private Map<String,Object> pr() {
        return Map.of("number",9,"state","open","updated_at","2026-11-12T01:02:00Z","head",Map.of("sha",HEAD),"base",Map.of("ref","main"));
    }
    private void serve(HttpExchange e) {
        try {
            String p=e.getRequestURI().getPath(),method=e.getRequestMethod();
            JsonNode body=json.readTree(e.getRequestBody().readAllBytes());
            String auth=e.getRequestHeaders().getFirst("Authorization");
            if(p.equals("/app/installations/17/access_tokens")) {
                var claims=Jwts.parser().verifyWith(keys.getPublic()).build().parseSignedClaims(auth.substring(7)).getPayload();
                assertEquals("91",claims.getIssuer());assertTrue(claims.getExpiration().toInstant().isAfter(Instant.now()));
                if(body.path("permissions").has("pull_requests")) assertEquals(8,body.path("repository_ids").get(0).asInt());
                reply(e,201,Map.of("token","short-lived")); return;
            }
            assertEquals("Bearer short-lived",auth);
            if(p.equals("/repos/acme/orders")) reply(e,200,repo(8,"orders"));
            else if(p.equals("/repos/acme/new")) reply(e,created==null?404:200,repo(9,"new"));
            else if(p.equals("/repos/acme/template/generate")) {
                assertTrue(body.path("private").asBoolean());created=body.get("description").asText();reply(e,201,repo(9,"new"));
            } else if(p.endsWith("/git/commits/"+BASE)) reply(e,200,Map.of("tree",Map.of("sha",TREE)));
            else if(p.endsWith("/git/trees/"+TREE)) reply(e,200,Map.of("tree",List.of(),"truncated",false));
            else if(p.endsWith("/git/trees")) {
                assertEquals(TREE,body.path("base_tree").asText());assertEquals("done",body.path("tree").get(0).path("content").asText());
                reply(e,201,Map.of("sha",NEW_TREE));
            } else if(p.endsWith("/git/ref/heads/cloide/task-"+taskId)) reply(e,branch?200:404,Map.of("object",Map.of("sha",HEAD)));
            else if(p.endsWith("/git/ref/heads/main")) reply(e,200,Map.of("object",Map.of("sha",BASE)));
            else if(p.endsWith("/git/commits")) {assertEquals(BASE,body.path("parents").get(0).asText());reply(e,201,Map.of("sha",HEAD));}
            else if(p.endsWith("/git/refs")) {
                assertEquals("refs/heads/cloide/task-"+taskId,body.get("ref").asText());branch=true;createdBranches++;reply(e,201,Map.of("ref","ok"));
            } else if(p.endsWith("/git/commits/"+HEAD)) reply(e,200,Map.of("tree",Map.of("sha",NEW_TREE),"parents",List.of(Map.of("sha",BASE))));
            else if(p.endsWith("/pulls") && method.equals("GET")) reply(e,200,pull?List.of(pr()):List.of());
            else if(p.endsWith("/pulls") && method.equals("POST")) {
                assertEquals("main",body.path("base").asText());pull=true;createdPulls++;reply(e,201,pr());
            } else fail("unexpected route "+method+" "+p);
        } catch(Throwable err) {
            failure.set(err); try {reply(e,500,Map.of());} catch(Exception ignored) {}
        } finally {e.close();}
    }
    @Test void installsImportsAndCreatesPrivateTemplateWithoutDuplicateLinks() {
        assertEquals(8,service.connect(1,"orders",null).remoteId());
        UUID key=UUID.randomUUID();assertEquals(service.connect(1,"new",key),service.connect(1,"new",key));
        assertThrows(BusinessException.class,()->service.connect(1,"new",UUID.randomUUID()));
        assertEquals(2,service.list(1).size());
        assertThrows(BusinessException.class,()->service.connect(2,"orders",null));
        service.unlink(1,9);assertEquals(1,service.list(1).size());
    }
    @Test void recoversRemoteSideEffectsAndWebhookUsesRawSignedBytes() throws Exception {
        service.connect(1,"orders",null);
        service.publish(1,8,taskId);
        jdbc.update("DELETE FROM project_github_pulls");
        assertEquals(9,service.publish(1,8,taskId).prNumber());
        assertEquals(1,createdBranches);assertEquals(1,createdPulls);
        var msg=Map.of("installation",Map.of("id",17),"repository",Map.of("id",8),"action","closed",
                "pull_request",Map.of("number",9,"merged",true,"state","closed","updated_at","2026-11-12T03:04:00Z"));
        byte[] bytes=json.writeValueAsBytes(msg);
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec("fixture-secret".getBytes(),"HmacSHA256"));
        String sig="sha256="+HexFormat.of().formatHex(mac.doFinal(bytes));UUID delivery=UUID.randomUUID();
        assertThrows(BusinessException.class,()->service.webhook(delivery,"pull_request",sig,new byte[]{1}));
        service.webhook(delivery,"pull_request",sig,bytes);
        service.webhook(delivery,"pull_request",sig,bytes);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM github_deliveries",Integer.class));
        assertEquals("merged",service.pulls(1,8).getFirst().prState());
    }
}
