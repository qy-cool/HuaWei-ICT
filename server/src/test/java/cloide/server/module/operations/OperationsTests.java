package cloide.server.module.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cloide.server.common.GlobalExceptionHandler;
import cloide.server.module.alert.service.AlertTriggeredEvent;
import cloide.server.module.alert.vo.AlertRecordVo;
import cloide.server.module.coding.CodingService;
import cloide.server.module.project.service.ProjectAccess;
import cloide.server.module.release.ArtifactService;
import cloide.server.module.release.ReleaseService;
import cloide.server.security.AuthenticatedUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.*;

@SpringJUnitConfig(OperationsTests.Config.class)
@Transactional
class OperationsTests {
    @Configuration
    @EnableTransactionManagement
    @Import({OperationsService.class,OperationsController.class,ReleaseService.class,CodingService.class,ProjectAccess.class})
    static class Config {
        @Bean(destroyMethod="shutdown") EmbeddedDatabase source() {
            var db = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build();
            var j = new JdbcTemplate(db);
            j.execute("SET MODE MySQL");
            j.execute("CREATE TABLE servers(id BIGINT PRIMARY KEY,name VARCHAR,deleted INT,agent_status VARCHAR)");
            j.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,role VARCHAR,review_status VARCHAR)");
            var migrations = new ResourceDatabasePopulator();
            for (String file : List.of("V38__project_members_environments_and_nodes.sql","V42__project_artifacts_and_releases.sql",
                    "V43__project_coding_tasks.sql","V49__project_alert_maintenance.sql")) {
                migrations.addScript(new ClassPathResource("db/migration/"+file));
            }
            migrations.execute(db);
            j.execute("""
                    CREATE TABLE alert_records(id BIGINT PRIMARY KEY,server_id BIGINT,metric VARCHAR,level VARCHAR,
                    status VARCHAR,current_value DECIMAL,threshold_value DECIMAL,triggered_at DATETIME,resolved_at DATETIME)
                    """);
            return db;
        }
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean JdbcTemplate jdbc(DataSource db) { return new JdbcTemplate(db); }
        @Bean DataSourceTransactionManager tx(DataSource db) { return new DataSourceTransactionManager(db); }
        @Bean ObjectMapper json() { return new ObjectMapper(); }
        @Bean ArtifactService artifacts() { return org.mockito.Mockito.mock(ArtifactService.class); }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired OperationsController controller;
    @Autowired OperationsService operations;
    @Autowired ApplicationEventPublisher publisher;
    MockMvc mvc;
    String current, old, later;
    static final String OPS="/api/projects/10/operations";

    @BeforeEach void setup() {
        mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler(60L)).build();
        jdbc.update("INSERT INTO projects(id,name) VALUES(10,'A'),(20,'B')");
        jdbc.update("INSERT INTO users VALUES(1,'admin','approved'),(2,'user','approved'),(3,'user','approved'),(4,'user','approved')");
        jdbc.update("INSERT INTO project_members VALUES(10,2,'owner'),(10,3,'developer'),(20,4,'owner')");
        jdbc.update("INSERT INTO servers VALUES(11,'app',0,'online'),(12,'build',0,'online'),(30,'other',0,'offline')");
        jdbc.update("INSERT INTO project_nodes VALUES(11,10,'test'),(12,10,'dev'),(30,20,'dev')");
        jdbc.update("INSERT INTO release_targets VALUES(10,'test','api',3)");
        old=release(1,"healthy","2026-11-12 04:00:00", "2026-11-12 04:01:00",null);
        current=release(2,"healthy","2026-11-12 09:48:00","2026-11-12 09:49:00",old);
        later=release(3,"failed","2026-11-12 09:58:00","2026-11-12 10:06:00",current);
        jdbc.update("INSERT INTO alert_records VALUES(51,11,'cpu','warning','unread',95,90,'2026-11-12 10:00:00',NULL),(52,11,'cpu','warning','unread',95,90,'2026-11-12 10:00:00',NULL)");
        var captured = new AlertRecordVo();
        captured.setId(51L);
        publisher.publishEvent(new AlertTriggeredEvent(11L,captured));
        login(2);
    }

    @AfterEach void logout() { SecurityContextHolder.clearContext(); }

    private String release(int generation,String status,String claimed,String reported,String previous) {
        String id=UUID.randomUUID().toString(), artifact=UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO project_artifacts(id,project_id,run_id,repository,commit_sha,kind,sha256,byte_count,uploaded_by,created_at)
                VALUES(?,10,?,'orders',?,'java_jar',?,200,1,CURRENT_TIMESTAMP)
                """,
                artifact,UUID.randomUUID().toString(),Integer.toString(generation).repeat(40),Integer.toString(generation).repeat(64));
        jdbc.update("""
                INSERT INTO project_releases(id,request_id,project_id,environment,service,server_id,generation,action,
                artifact_id,previous_healthy_id,status,requested_by,created_at,claimed_at,reported_at)
                VALUES(?,?,10,'test','api',11,?,'deploy',?,?,?,2,?,?,?)
                """,id,UUID.randomUUID().toString(),generation,artifact,previous,status,claimed,claimed,reported);
        return id;
    }
    private void login(long id) {
        var principal=new AuthenticatedUser(id,"u"+id,id==1?"admin":"user","approved",null,null);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of()));
    }

    @Test void frozenAttributionAndEvidenceDistinguishCurrentHealthyFromDesiredAndTimeCorrelation() throws Exception {
        mvc.perform(get(OPS+"/topology")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[1].healthyRelease").value(current))
                .andExpect(jsonPath("$.data[1].desiredRelease").value(later));
        mvc.perform(get(OPS+"/alerts/51")).andExpect(status().isOk()).andExpect(jsonPath("$.data.nearby.length()").value(2))
                .andExpect(jsonPath("$.data.nearby[0].id").value(later))
                .andExpect(jsonPath("$.data.healthyAtAlert[0].id").value(current))
                .andExpect(jsonPath("$.data.actions.length()").value(0));
        mvc.perform(get(OPS+"/alerts/52")).andExpect(status().isNotFound());
        jdbc.update("UPDATE project_nodes SET project_id=20 WHERE server_id=11");
        login(4);
        mvc.perform(get("/api/projects/20/operations/alerts/51")).andExpect(status().isNotFound());
        mvc.perform(get("/api/projects/20/operations/alerts")).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(OPS+"/alerts")).andExpect(status().isNotFound());
    }

    @Test void idempotentRepairUsesVerifiedReleaseCommitAndRequiresDevelopmentNode() throws Exception {
        login(3);
        String request=UUID.randomUUID().toString();
        action(request,"repair",later,12L).andExpect(status().isConflict());
        action(request,"repair",current,30L).andExpect(status().isForbidden());
        action(request,"repair",current,12L).andExpect(status().isOk()).andExpect(jsonPath("$.data.resultStatus").value("queued"));
        action(request,"repair",current,12L).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM project_alert_actions",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT base_sha FROM project_coding_tasks",String.class)).isEqualTo("2".repeat(40));
        assertThat(jdbc.queryForObject("SELECT permission_mode FROM project_coding_tasks",String.class)).isEqualTo("build");
        action(UUID.randomUUID().toString(),"confirm_cause",later,null).andExpect(status().isForbidden());
    }

    @Test void humanEvidenceAndActualRollbackResultRemainAuditable() throws Exception {
        action(UUID.randomUUID().toString(),"confirm_cause",later,null).andExpect(status().isOk());
        String request=UUID.randomUUID().toString();
        action(request,"rollback",current,null).andExpect(status().isOk()).andExpect(jsonPath("$.data.resultStatus").value("queued"));
        action(request,"rollback",current,null).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM project_releases WHERE action='rollback' AND rollback_of=?",
                Integer.class,current)).isOne();
        mvc.perform(get(OPS+"/alerts/51")).andExpect(jsonPath("$.data.actions.length()").value(2));
        jdbc.update("DELETE FROM alert_records WHERE id=51");
        operations.pruneExpiredUnworkedAlerts();
        mvc.perform(get(OPS+"/alerts/51")).andExpect(jsonPath("$.data.alert.status").value("archived"))
                .andExpect(jsonPath("$.data.actions.length()").value(2));
    }

    private ResultActions action(String request,String action,String release,Long node) throws Exception {
        return mvc.perform(post(OPS+"/alerts/51/actions").contentType("application/json").content("""
                {"requestId":"%s","action":"%s","releaseId":"%s","buildNode":%s,"rationale":"核对交易探针与节点日志"}
                """.formatted(request,action,release,node)));
    }
}
