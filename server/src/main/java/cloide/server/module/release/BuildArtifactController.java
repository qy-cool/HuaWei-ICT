package cloide.server.module.release;

import cloide.server.common.ApiResponse;
import cloide.server.websocket.AgentAuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/build-agent")
public class BuildArtifactController {
    private final AgentAuthenticationService authentication;
    private final ArtifactService artifacts;
    @PostMapping("/{jobId}/artifact")
    public ApiResponse<ArtifactService.Artifact> capture(@PathVariable UUID jobId,@RequestParam long serverId,
            @RequestParam long projectId,@RequestParam String commit,@RequestParam String kind,
            @RequestHeader("X-Agent-Token") String token,@RequestHeader("X-Content-SHA256") String sha,
            HttpServletRequest request) throws IOException {
        authentication.authenticate(serverId,token);
        return ApiResponse.success(artifacts.uploadFromNode(serverId,jobId.toString(), projectId,commit,kind,sha,request.getInputStream()));
    }
}
