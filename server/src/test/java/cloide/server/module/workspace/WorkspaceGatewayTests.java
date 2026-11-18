package cloide.server.module.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cloide.server.common.GlobalExceptionHandler;
import cloide.server.module.project.service.ProjectAccess;
import cloide.server.module.project.service.ProjectService;
import cloide.server.module.workspace.controller.WorkspaceController;
import cloide.server.module.workspace.controller.WorkspaceGatewayController;
import cloide.server.module.workspace.service.WorkspaceService;
import cloide.server.security.AuthenticatedUser;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.*;

@SpringJUnitConfig(WorkspaceGatewayTests.Config.class)
@TestPropertySource(properties={"WORKSPACE_DOMAIN=ide.example.test","WORKSPACE_CONSOLE_ORIGIN=https://console.example.test",
        "WORKSPACE_GATEWAY_KEY=01234567890123456789012345678901"})
@Transactional
class WorkspaceGatewayTests {
    private static final String KEY = "01234567890123456789012345678901";
    private static final String HOST = "orders.ide.example.test";
    private static final String CONSOLE = "https://console.example.test";
    private static final String WORKSPACES = "/api/projects/50/workspaces";

    @Configuration
    @EnableTransactionManagement
    @Import({ProjectAccess.class, ProjectService.class, WorkspaceService.class, WorkspaceController.class, WorkspaceGatewayController.class})
    static class Config {
        @Bean(destroyMethod = "shutdown")
        EmbeddedDatabase dataSource() {
            EmbeddedDatabase db = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build();
            var jdbc = new JdbcTemplate(db);
            jdbc.execute("SET MODE MySQL");
            jdbc.execute("CREATE TABLE users(id BIGINT PRIMARY KEY, role VARCHAR(20), review_status VARCHAR(20))");
            jdbc.execute("CREATE TABLE servers(id BIGINT PRIMARY KEY, deleted INT)");
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V38__project_members_environments_and_nodes.sql"),
                    new ClassPathResource("db/migration/V41__browser_workspaces.sql"),
                    new ClassPathResource("db/migration/V46__workspace_runtime.sql")).execute(db);
            return db;
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource db) { return new JdbcTemplate(db); }
        @Bean DataSourceTransactionManager transactionManager(DataSource db) { return new DataSourceTransactionManager(db); }
        @Bean Clock clock() { return Clock.systemUTC(); }
    }

    @Autowired WorkspaceController workspaces;
    @Autowired WorkspaceService lifecycle;
    @Autowired ProjectAccess access;
    @Autowired WorkspaceGatewayController gateway;
    @Autowired ProjectService projects;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;
    String id;

    @BeforeEach void setup() throws Exception {
        mvc = MockMvcBuilders.standaloneSetup(workspaces, gateway).setControllerAdvice(new GlobalExceptionHandler(60L)).build();
        jdbc.update("INSERT INTO users VALUES (1,'admin','approved'),(2,'user','approved'),(3,'user','approved')");
        jdbc.update("INSERT INTO servers VALUES (13,0)");
        jdbc.update("INSERT INTO projects(id,name) VALUES (50,'orders')");
        jdbc.update("INSERT INTO project_members VALUES (50,2,'developer'),(50,3,'owner')");
        jdbc.update("INSERT INTO project_nodes VALUES (13,50,'dev')");
        login(1);
        id = JsonPath.read(mvc.perform(post(WORKSPACES).contentType("application/json")
                .content("{\"serverId\":13,\"ownerId\":2,\"name\":\"Orders\",\"slug\":\"orders\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),"$.data.id");
        login(2);
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void oneUseTicketIsBoundToOriginHostAndOwnerAndCreatesOnlyAHostCookie() throws Exception {
        String ticket = issue();
        redeem(ticket, "other.ide.example.test", CONSOLE, KEY).andExpect(status().isUnauthorized());
        redeem(ticket, HOST, "https://other.example.test", KEY).andExpect(status().isForbidden());
        redeem(ticket, HOST, CONSOLE, "wrong").andExpect(status().isUnauthorized());
        var response = redeem(ticket, HOST, CONSOLE, KEY).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getHeader("Set-Cookie")).startsWith("__Host-cloide-workspace=")
                .contains("Path=/","Secure","HttpOnly","SameSite=Strict","Max-Age=1800").doesNotContain("Domain");
        redeem(ticket, HOST, CONSOLE, KEY).andExpect(status().isUnauthorized());
        Cookie cookie = cookie(response);
        authorize(HOST, cookie, KEY).andExpect(status().isNoContent());
        authorize("other.ide.example.test", cookie, KEY).andExpect(status().isUnauthorized());
        authorize(HOST, cookie, "bad").andExpect(status().isUnauthorized());
        login(3);
        mvc.perform(get(WORKSPACES)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(post(WORKSPACES+"/"+id+"/tickets")).andExpect(status().isNotFound());
        mvc.perform(delete(WORKSPACES+"/"+id)).andExpect(status().isForbidden());
        projects.removeMember(50, 2);
        projects.setMember(50, 2, "developer");
        authorize(HOST, cookie, KEY).andExpect(status().isUnauthorized());
    }

    @Test void expirationAndPermanentDisableApplyToNewAndExistingSessions() throws Exception {
        String old = issue();
        jdbc.update("UPDATE workspace_credentials SET expires_at=0");
        redeem(old, HOST, CONSOLE, KEY).andExpect(status().isUnauthorized());
        Cookie session = cookie(redeem(issue(), HOST, CONSOLE, KEY).andExpect(status().isOk()).andReturn().getResponse());
        jdbc.update("UPDATE workspace_credentials SET expires_at=0");
        authorize(HOST, session, KEY).andExpect(status().isUnauthorized());
        String pending = issue();
        login(1);
        mvc.perform(delete(WORKSPACES+"/"+id)).andExpect(status().isOk());
        redeem(pending, HOST, CONSOLE, KEY).andExpect(status().isUnauthorized());
        login(2);
        mvc.perform(post(WORKSPACES+"/"+id+"/tickets")).andExpect(status().isNotFound());
    }

    @Test void registryRejectsProductionAndOriginInjection() throws Exception {
        login(1);
        mvc.perform(post(WORKSPACES).contentType("application/json")
                .content("{\"serverId\":13,\"ownerId\":2,\"name\":\"X\",\"slug\":\"outside.example.com/path\"}"))
                .andExpect(status().isBadRequest());
        jdbc.update("UPDATE project_nodes SET environment='prod'");
        login(2);
        mvc.perform(post(WORKSPACES+"/"+id+"/tickets")).andExpect(status().isForbidden());
    }

    @Test void managedWorkspaceWaitsForTheRightNodeAndFencesStaleResumes() throws Exception {
        jdbc.update("UPDATE workspaces SET managed=TRUE,desired_state='running' WHERE id=?",id);
        mvc.perform(post(WORKSPACES+"/"+id+"/tickets")).andExpect(status().isConflict());
        long node=jdbc.queryForObject("SELECT server_id FROM workspaces WHERE id=?",Long.class,id);
        lifecycle.observe(node,id,1,"running");
        Cookie session=cookie(redeem(issue(),HOST,CONSOLE,KEY).andReturn().getResponse());
        mvc.perform(post(WORKSPACES+"/"+id+"/sleep")).andExpect(status().isOk()).andExpect(jsonPath("$.data.generation").value(2));
        authorize(HOST,session,KEY).andExpect(status().isUnauthorized());
        org.junit.jupiter.api.Assertions.assertThrows(cloide.server.common.BusinessException.class,()->lifecycle.observe(node,id,1,"running"));
        lifecycle.observe(node,id,2,"sleeping");
        mvc.perform(post(WORKSPACES+"/"+id+"/wake")).andExpect(status().isOk()).andExpect(jsonPath("$.data.generation").value(3));
        lifecycle.observe(node,id,3,"running");
        issue();
        jdbc.update("UPDATE workspaces SET observed_at=0 WHERE id=?",id);
        mvc.perform(post(WORKSPACES+"/"+id+"/tickets")).andExpect(status().isConflict());
    }

    @Test void restoredControllerOnlyObservesAnOldRunningRequest() {
        jdbc.update("UPDATE workspaces SET managed=TRUE,desired_state='running' WHERE id=?",id);
        long node=jdbc.queryForObject("SELECT server_id FROM workspaces WHERE id=?",Long.class,id);
        var restored=new WorkspaceService(jdbc,access,Clock.systemUTC(),"ide.example.test",CONSOLE,KEY,true);
        assertThat(restored.runtime(node,id).desired()).isEqualTo("observe");
        restored.observe(node,id,1,"sleeping");
        org.junit.jupiter.api.Assertions.assertThrows(cloide.server.common.BusinessException.class,
                ()->restored.desired(50,id,"running"));
        assertThat(restored.list(50).getFirst().observed()).isEqualTo("sleeping");
    }

    private String issue() throws Exception {
        return JsonPath.read(mvc.perform(post(WORKSPACES+"/"+id+"/tickets")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString(),"$.data.ticket");
    }
    private ResultActions redeem(String ticket, String host, String origin, String key) throws Exception {
        return mvc.perform(post("/api/workspace-gateway/session").header("X-Cloide-Workspace-Host",host)
                .header("X-Cloide-Browser-Origin",origin).header("X-Cloide-Gateway-Key",key)
                .contentType("text/plain").content("ticket="+ticket));
    }
    private ResultActions authorize(String host, Cookie cookie, String key) throws Exception {
        return mvc.perform(get("/api/workspace-gateway/authorize").header("X-Cloide-Workspace-Host",host)
                .header("X-Cloide-Gateway-Key",key).cookie(cookie));
    }
    private Cookie cookie(MockHttpServletResponse response) {
        String pair = response.getHeader("Set-Cookie").split(";")[0];
        return new Cookie(WorkspaceGatewayController.COOKIE, pair.substring(pair.indexOf('=') + 1));
    }
    private void login(long id) {
        var principal = new AuthenticatedUser(id, "user-" + id, id == 1 ? "admin" : "user", "approved", null, null);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of()));
    }
}
