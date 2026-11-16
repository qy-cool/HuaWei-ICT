package cloide.server.module.job;

import cloide.server.common.ApiResponse;
import cloide.server.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/runs")
@Validated
@RequiredArgsConstructor
public class ProjectRunController {
    private final ProjectRunService service;

    @PostMapping
    public ApiResponse<ProjectRun> create(@PathVariable @Positive long projectId, @Valid @RequestBody RunRequest body,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.create(projectId, user.id(), body));
    }

    @GetMapping
    public ApiResponse<List<ProjectRun>> list(@PathVariable @Positive long projectId) {
        return ApiResponse.success(service.list(projectId));
    }

    @GetMapping("/{jobId}")
    public ApiResponse<ProjectRun> get(@PathVariable @Positive long projectId, @PathVariable UUID jobId) {
        return ApiResponse.success(service.read(projectId, jobId.toString()));
    }

    @GetMapping("/{jobId}/logs")
    public ApiResponse<List<ProjectRunStore.LogLine>> logs(@PathVariable @Positive long projectId,
            @PathVariable UUID jobId, @RequestParam(defaultValue = "0") @Min(0) int after) {
        return ApiResponse.success(service.logs(projectId, jobId.toString(), after));
    }

    @PostMapping("/{jobId}/cancel")
    public ApiResponse<ProjectRun> cancel(@PathVariable @Positive long projectId, @PathVariable UUID jobId) {
        return ApiResponse.success(service.cancel(projectId, jobId.toString()));
    }
}
