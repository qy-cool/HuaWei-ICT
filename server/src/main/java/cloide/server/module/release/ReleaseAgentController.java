package cloide.server.module.release;

import cloide.server.common.ApiResponse;
import cloide.server.module.release.ReleaseService.Receipt;
import cloide.server.websocket.AgentAuthenticationService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/release-agent")
public class ReleaseAgentController {
    @org.springframework.beans.factory.annotation.Value("${cloide.engineering.recovery:false}")
    private boolean recovering;
    private final AgentAuthenticationService authentication;
    private final ReleaseService releases;
    private final ArtifactService artifacts;

    @GetMapping("/next")
    public ResponseEntity<ApiResponse<ReleaseService.Release>> next(@RequestParam long serverId,
            @RequestHeader(value="X-Agent-Token", required=false) String token, @RequestParam(required=false) Long projectId,
            @RequestParam(required=false) String environment, @RequestParam(required=false) String service) {
        authentication.authenticate(serverId, token);
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(ApiResponse.success(recovering ? null : releases.claim(serverId, projectId, environment, service)));
    }

    @GetMapping("/{releaseId}/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable UUID releaseId, @RequestParam long serverId,
            @RequestHeader(value="X-Agent-Token", required=false) String token) throws IOException {
        authentication.authenticate(serverId, token);
        if (recovering) throw new cloide.server.common.BusinessException(cloide.server.common.ErrorCode.RESOURCE_CONFLICT);
        return artifacts.content(releases.downloadForNode(serverId, releaseId.toString()));
    }

    @PostMapping("/{releaseId}/receipt")
    public ApiResponse<Void> receipt(@PathVariable UUID releaseId, @RequestParam long serverId,
            @RequestHeader(value="X-Agent-Token", required=false) String token, @Valid @RequestBody Receipt receipt) {
        authentication.authenticate(serverId, token);
        releases.report(serverId, releaseId.toString(), receipt);
        return ApiResponse.success(null);
    }
}
