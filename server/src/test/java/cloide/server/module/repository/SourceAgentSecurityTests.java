package cloide.server.module.repository;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import cloide.server.common.GlobalExceptionHandler;
import cloide.server.module.auth.mapper.UserMapper;
import cloide.server.module.server.entity.ServerEntity;
import cloide.server.security.JwtTokenService;
import cloide.server.security.SecurityConfig;
import cloide.server.security.SecurityErrorHandler;
import cloide.server.websocket.AgentAuthenticationService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 经完整 SecurityFilterChain 的 A2 节点下载端点回归：路径必须匹配
 * /api/source-agent/syncs/{syncId}/grant 与 content；正确 X-Agent-Token 可匿名到达
 * 控制器，错误令牌或越权节点被拒绝，且不依赖浏览器 JWT。
 */
@WebMvcTest
@ContextConfiguration(classes = SourceAgentSecurityTests.Config.class)
class SourceAgentSecurityTests {
    @Configuration
    @Import({SecurityConfig.class, SecurityErrorHandler.class, GlobalExceptionHandler.class, SourceAgentController.class})
    static class Config { }

    private static final String SYNC_ID = "11111111-1111-1111-1111-111111111111";
    private static final String GRANT = SYNC_ID + "/grant";
    private static final String CONTENT = SYNC_ID + "/content";
    private static final String AGENT = "/api/source-agent/syncs/";

    @Autowired private MockMvc mvc;
    @MockitoBean private AgentAuthenticationService authentication;
    @MockitoBean private SourceSyncService syncs;
    @MockitoBean private JwtTokenService jwt;
    @MockitoBean private UserMapper users;
    @TempDir Path temp;

    @BeforeEach
    void stubValidNode() {
        when(authentication.authenticate(eq(2L), eq("good-token"))).thenReturn(new ServerEntity());
        when(authentication.authenticate(eq(2L), eq("bad-token")))
                .thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));
        when(authentication.authenticate(eq(9L), eq("good-token"))).thenReturn(new ServerEntity());
    }

    @Test
    void validAgentTokenCanClaimGrantWithoutJwt() throws Exception {
        when(syncs.grant(2L, SYNC_ID)).thenReturn(new SourceSyncService.GrantView(
                "22222222-2222-2222-2222-222222222222", "secret-value",
                "2099-01-01T00:00:00Z", "2099-01-01T02:00:00Z",
                1L, 8L, SYNC_ID, 1L, "c".repeat(40), "t".repeat(40), "b".repeat(64), 12L));
        mvc.perform(post(AGENT + GRANT).queryParam("serverId", "2")
                        .header("X-Agent-Token", "good-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.grantId").value("22222222-2222-2222-2222-222222222222"))
                .andExpect(jsonPath("$.data.secret").value("secret-value"));
    }

    @Test
    void badAgentTokenIsUnauthorizedOnGrantAndContent() throws Exception {
        mvc.perform(post(AGENT + GRANT).queryParam("serverId", "2")
                        .header("X-Agent-Token", "bad-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
        mvc.perform(get(AGENT + CONTENT).queryParam("serverId", "2")
                        .header("X-Agent-Token", "bad-token")
                        .header("X-Sync-Grant", "g.s"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void foreignNodeCannotClaimGrant() throws Exception {
        when(syncs.grant(9L, SYNC_ID)).thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        mvc.perform(post(AGENT + GRANT).queryParam("serverId", "9")
                        .header("X-Agent-Token", "good-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void validAgentTokenCanDownloadContentAndRejectsBadGrant() throws Exception {
        Path bundle = temp.resolve("bundle");
        Files.writeString(bundle, "bundle-bytes");
        when(syncs.content(2L, SYNC_ID, "grant-id", "secret")).thenReturn(bundle);
        when(syncs.content(eq(2L), eq(SYNC_ID), eq("grant-id"), eq("wrong")))
                .thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));
        mvc.perform(get(AGENT + CONTENT).queryParam("serverId", "2")
                        .header("X-Agent-Token", "good-token")
                        .header("X-Sync-Grant", "grant-id.secret"))
                .andExpect(status().isOk())
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(content().string("bundle-bytes"));
        mvc.perform(get(AGENT + CONTENT).queryParam("serverId", "2")
                        .header("X-Agent-Token", "good-token")
                        .header("X-Sync-Grant", "grant-id.wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void missingGrantHeaderIsUnauthorizedAndSecurityStillPermitsTheRoute() throws Exception {
        mvc.perform(get(AGENT + CONTENT).queryParam("serverId", "2")
                        .header("X-Agent-Token", "good-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void shortSourceAgentGrantPathIsNotAnonymous() throws Exception {
        // 旧错误通配 /api/source-agent/*/grant 会误放行该路径并落到 404；
        // 修正后该路径仍需认证，匿名请求由过滤器返回 401。
        mvc.perform(post("/api/source-agent/" + SYNC_ID + "/grant").queryParam("serverId", "2")
                        .header("X-Agent-Token", "good-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }
}
