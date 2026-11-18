package cloide.server.module.project;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cloide.server.module.auth.entity.UserEntity;
import cloide.server.module.auth.mapper.UserMapper;
import cloide.server.module.metrics.controller.MonitorTicketController;
import cloide.server.module.project.controller.ProjectController;
import cloide.server.module.project.service.ProjectService;
import cloide.server.security.JwtTokenService;
import cloide.server.security.SecurityConfig;
import cloide.server.security.SecurityErrorHandler;
import cloide.server.websocket.MonitorTicketService;
import cloide.server.websocket.MonitorTicketServiceImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(properties = "cloide.security.project-isolation=true")
@ContextConfiguration(classes = ProjectIsolationHttpTests.Config.class)
class ProjectIsolationHttpTests {
    @Configuration
    @Import({SecurityConfig.class, SecurityErrorHandler.class, ProjectController.class, MonitorTicketController.class})
    static class Config {
        @Bean
        MonitorTicketService monitorTicketService() {
            return new MonitorTicketServiceImpl(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        }
    }

    @Autowired private MockMvc mvc;
    @MockitoBean private ProjectService projects;
    @MockitoBean private UserMapper users;
    @MockitoBean private JwtTokenService jwt;

    @Test
    void projectMemberCanReadProjectRoutesButCannotIssueGlobalTicketOrReadOldAggregates() throws Exception {
        authenticate("user");
        for (String path : List.of("/api/servers", "/api/servers/9", "/api/servers/9/status",
                "/api/servers/9/metrics/latest", "/api/servers/9/resources/latest", "/api/servers/9/processes/latest",
                "/api/alerts/records", "/api/alerts/rules", "/api/alerts/records/1/notifications",
                "/api/system/rabbitmq/consumers", "/api/ai/commands/runs")) {
            mvc.perform(get(path).header("Authorization", "Bearer test-token"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(40300));
        }
        mvc.perform(put("/api/alerts/records/1/read").header("Authorization", "Bearer test-token"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/ws/monitor-ticket").header("Authorization", "Bearer test-token"))
                .andExpect(status().isForbidden());
        when(projects.nodes(3, 0, 100)).thenReturn(List.of(new ProjectService.Node(9, "builder", "test", "online")));
        mvc.perform(get("/api/projects/3/nodes").header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].agentStatus").value("online"));
    }

    @Test
    void adminCanIssueTicketAndAnonymousCannot() throws Exception {
        mvc.perform(post("/api/ws/monitor-ticket")).andExpect(status().isUnauthorized());
        authenticate("admin");
        mvc.perform(post("/api/ws/monitor-ticket").header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.ticket").isNotEmpty());
    }

    private void authenticate(String role) {
        UserEntity user = new UserEntity();
        user.setId(7L);
        user.setUsername("member");
        user.setRole(role);
        user.setReviewStatus("approved");
        when(users.selectAuthenticationUserById(7L)).thenReturn(user);
        when(jwt.parseToken("test-token")).thenReturn(new JwtTokenService.ParsedToken(7L, "member", "t1", Instant.MAX));
    }
}
