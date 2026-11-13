package cloide.server.module.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cloide.server.common.GlobalExceptionHandler;
import cloide.server.module.project.controller.ProjectController;
import cloide.server.module.project.service.ProjectAccess;
import cloide.server.module.project.service.ProjectService;
import cloide.server.security.AuthenticatedUser;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

@SpringJUnitConfig(ProjectFlowTests.Config.class)
@Transactional
class ProjectFlowTests {
    @Configuration
    @EnableTransactionManagement
    @Import({ProjectAccess.class, ProjectService.class, ProjectController.class})
    static class Config {
        @Bean(destroyMethod = "shutdown")
        EmbeddedDatabase dataSource() {
            EmbeddedDatabase db = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                    .generateUniqueName(true).build();
            JdbcTemplate jdbc = new JdbcTemplate(db);
            jdbc.execute("SET MODE MySQL");
            jdbc.execute("CREATE TABLE users(id BIGINT PRIMARY KEY, username VARCHAR(50), role VARCHAR(20), review_status VARCHAR(20))");
            jdbc.execute("CREATE TABLE servers(id BIGINT PRIMARY KEY, name VARCHAR(100), deleted INT, agent_status VARCHAR(20))");
            jdbc.update("INSERT INTO servers VALUES (9,'old-node',0,'offline'),(12,'removed',1,'offline')");
            new ResourceDatabasePopulator(new ClassPathResource(
                    "db/migration/V38__project_members_environments_and_nodes.sql")).execute(db);
            return db;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource db) {
            return new JdbcTemplate(db);
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource db) {
            return new DataSourceTransactionManager(db);
        }
    }

    @Autowired private ProjectController controller;
    @Autowired private ProjectAccess access;
    @Autowired private JdbcTemplate jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(60L)).build();
        jdbc.update("""
                INSERT INTO users VALUES (1,'admin','admin','approved'),(2,'owner','user','approved'),
                    (3,'member','user','approved'),(4,'other','user','approved')
                """);
        jdbc.update("INSERT INTO servers VALUES (10,'builder',0,'online'),(11,'production',0,'offline')");
    }

    @AfterEach
    void clearPrincipal() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void checkedOutJobsLoseAuthorizationWhenMemberIsRevokedOrNodeUnassigned() throws Exception {
        long project = createProject();
        String path = "/api/projects/" + project;
        mvc.perform(put(path + "/members/3").contentType(APPLICATION_JSON).content("{\"role\":\"developer\"}"))
                .andExpect(status().isOk());
        mvc.perform(put(path + "/environments/dev/nodes/10")).andExpect(status().isForbidden());
        login(1);
        mvc.perform(put(path + "/environments/dev/nodes/10")).andExpect(status().isOk());
        mvc.perform(put(path + "/environments/dev/nodes/10")).andExpect(status().isOk());
        mvc.perform(put(path + "/environments/prod/nodes/10")).andExpect(status().isConflict());
        mvc.perform(put(path + "/environments/prod/nodes/11")).andExpect(status().isOk());
        login(3);
        mvc.perform(get(path + "/nodes")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].serverId").value(10))
                .andExpect(jsonPath("$.data[0].agentStatus").value("online"))
                .andExpect(jsonPath("$.data[0].environment").value("dev"));
        access.requireRun(project, 10);
        assertThat(access.canRun(3, project, 11)).isFalse();
        assertThat(access.canRun(4, project, 10)).isFalse();
        login(2);
        mvc.perform(delete(path + "/members/3")).andExpect(status().isOk());
        assertThat(access.canRun(3, project, 10)).isFalse();
        login(3);
        mvc.perform(get(path + "/nodes")).andExpect(status().isNotFound());
        login(1);
        mvc.perform(delete(path + "/environments/dev/nodes/10")).andExpect(status().isOk());
        assertThat(access.canRun(1, project, 10)).isFalse();
    }

    @Test
    void migrationAndPaginationDoNotExposeLegacyOrOtherTeamsNodes() throws Exception {
        long project = createProject();
        String path = "/api/projects/" + project;
        mvc.perform(get("/api/projects").param("limit", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].id").value(project));
        mvc.perform(get("/api/projects").param("after", Long.toString(project))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(delete(path + "/members/2")).andExpect(status().isConflict());
        login(4);
        mvc.perform(get(path + "/members")).andExpect(status().isNotFound());
        mvc.perform(put(path + "/members/4").contentType(APPLICATION_JSON).content("{\"role\":\"developer\"}"))
                .andExpect(status().isNotFound());
        login(1);
        long legacy = jdbc.queryForObject("SELECT id FROM projects WHERE name='历史节点'", Long.class);
        mvc.perform(get("/api/projects/" + legacy + "/nodes")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].environment").value("prod"));
        mvc.perform(put(path + "/environments/dev/nodes/9")).andExpect(status().isConflict());
        assertThat(access.canRun(1, legacy, 9)).isFalse();
    }

    private long createProject() throws Exception {
        login(2);
        mvc.perform(post("/api/projects").contentType(APPLICATION_JSON).content("{\"name\":\"订单系统\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("owner"));
        long project = jdbc.queryForObject("SELECT id FROM projects WHERE name = '订单系统'", Long.class);
        mvc.perform(get("/api/projects/" + project + "/environments")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));
        return project;
    }

    private void login(long id) {
        var principal = new AuthenticatedUser(id, "user-" + id, id == 1 ? "admin" : "user", "approved", null, null);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    }
}
