package cloide.server.module.workspace.controller;

import cloide.server.common.ApiResponse;
import cloide.server.module.workspace.service.WorkspaceService;
import cloide.server.module.workspace.service.WorkspaceService.Ticket;
import cloide.server.module.workspace.service.WorkspaceService.Workspace;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/workspaces")
public class WorkspaceController {
    public record Registration(@Positive long serverId, @Positive long ownerId,
                               @NotBlank @Size(max = 100) String name, @NotBlank String slug, boolean managed) {}

    private final WorkspaceService workspaces;

    public WorkspaceController(WorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    @GetMapping
    public ApiResponse<List<Workspace>> list(@PathVariable long projectId) {
        return ApiResponse.success(workspaces.list(projectId));
    }

    @PostMapping
    public ApiResponse<Workspace> register(@PathVariable long projectId, @Valid @RequestBody Registration registration) {
        return ApiResponse.success(workspaces.register(projectId, registration.serverId(), registration.ownerId(),
                registration.name(), registration.slug(), registration.managed()));
    }

    @PostMapping("/{id}/tickets")
    public ResponseEntity<ApiResponse<Ticket>> issue(@PathVariable long projectId, @PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(workspaces.issue(projectId, id)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> disable(@PathVariable long projectId, @PathVariable String id) {
        workspaces.disable(projectId, id);
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/sleep")
    public ApiResponse<Workspace> sleep(@PathVariable long projectId,@PathVariable String id) {
        return ApiResponse.success(workspaces.desired(projectId,id,"sleeping"));
    }

    @PostMapping("/{id}/wake")
    public ApiResponse<Workspace> wake(@PathVariable long projectId,@PathVariable String id) {
        return ApiResponse.success(workspaces.desired(projectId,id,"running"));
    }
}
