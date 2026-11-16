package cloide.server.module.project.controller;

import cloide.server.common.ApiResponse;
import cloide.server.module.project.service.ProjectService;
import cloide.server.module.project.service.ProjectService.Member;
import cloide.server.module.project.service.ProjectService.Node;
import cloide.server.module.project.service.ProjectService.Project;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {
    public record CreateProject(@NotBlank @Size(max = 100) String name) {}
    public record MemberGrant(@NotBlank String role) {}

    private final ProjectService projects;

    public ProjectController(ProjectService projects) {
        this.projects = projects;
    }

    @PostMapping
    public ApiResponse<Project> create(@Valid @RequestBody CreateProject request) {
        return ApiResponse.success(projects.create(request.name()));
    }

    @GetMapping
    public ApiResponse<List<Project>> list(@RequestParam(defaultValue = "0") long after,
                                           @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(projects.list(after, limit));
    }

    @GetMapping("/{projectId}/environments")
    public ApiResponse<List<String>> environments(@PathVariable long projectId) {
        return ApiResponse.success(projects.environments(projectId));
    }

    @GetMapping("/{projectId}/members")
    public ApiResponse<List<Member>> members(@PathVariable long projectId,
                                             @RequestParam(defaultValue = "0") long after,
                                             @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(projects.members(projectId, after, limit));
    }

    @PutMapping("/{projectId}/members/{userId}")
    public ApiResponse<Void> grant(@PathVariable long projectId, @PathVariable long userId,
                                   @Valid @RequestBody MemberGrant grant) {
        projects.setMember(projectId, userId, grant.role());
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{projectId}/members/{userId}")
    public ApiResponse<Void> remove(@PathVariable long projectId, @PathVariable long userId) {
        projects.removeMember(projectId, userId);
        return ApiResponse.success(null);
    }

    @GetMapping("/{projectId}/nodes")
    public ApiResponse<List<Node>> nodes(@PathVariable long projectId,
                                         @RequestParam(defaultValue = "0") long after,
                                         @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(projects.nodes(projectId, after, limit));
    }

    @PutMapping("/{projectId}/environments/{environment}/nodes/{serverId}")
    public ApiResponse<Void> assign(@PathVariable long projectId, @PathVariable String environment,
                                    @PathVariable long serverId) {
        projects.assign(projectId, environment, serverId);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{projectId}/environments/{environment}/nodes/{serverId}")
    public ApiResponse<Void> unassign(@PathVariable long projectId, @PathVariable String environment,
                                      @PathVariable long serverId) {
        projects.unassign(projectId, environment, serverId);
        return ApiResponse.success(null);
    }
}
