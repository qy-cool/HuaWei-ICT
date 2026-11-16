package cloide.server.module.workspace.controller;

import cloide.server.common.ApiResponse;
import cloide.server.module.workspace.service.WorkspaceService;
import cloide.server.websocket.AgentAuthenticationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspace-agent")
public class WorkspaceNodeController {
    public record Observation(@Positive long generation,@NotNull @Pattern(regexp="running|sleeping|failed") String state) { }
    private final AgentAuthenticationService auth;
    private final WorkspaceService workspaces;

    @GetMapping("/{id}/runtime")
    public ResponseEntity<ApiResponse<WorkspaceService.Runtime>> desired(@RequestParam long serverId,@PathVariable UUID id,
            @RequestHeader(value="X-Agent-Token",required=false) String token) {
        auth.authenticate(serverId,token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(workspaces.runtime(serverId,id.toString())));
    }
    @PostMapping("/{id}/observations")
    public ApiResponse<Void> observe(@RequestParam long serverId,@PathVariable UUID id,
            @RequestHeader(value="X-Agent-Token",required=false) String token,@Valid @RequestBody Observation body) {
        auth.authenticate(serverId,token);
        workspaces.observe(serverId,id.toString(),body.generation,body.state);
        return ApiResponse.success(null);
    }
}
