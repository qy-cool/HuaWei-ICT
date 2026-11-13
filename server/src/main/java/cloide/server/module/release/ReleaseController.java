package cloide.server.module.release;

import cloide.server.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/projects/{projectId}/environments/{environment}/releases")
public class ReleaseController {
    private final ReleaseService releases;

    @GetMapping
    public ApiResponse<List<ReleaseService.Release>> list(@PathVariable long projectId, @PathVariable String environment) {
        return ApiResponse.success(releases.list(projectId, environment));
    }

    @PostMapping
    public ApiResponse<ReleaseService.Release> deploy(@PathVariable long projectId, @PathVariable String environment,
            @Valid @RequestBody ReleaseService.Deploy body) {
        return ApiResponse.success(releases.deploy(projectId, environment, body));
    }

    @PostMapping("/{releaseId}/rollback")
    public ApiResponse<ReleaseService.Release> rollback(@PathVariable long projectId, @PathVariable String environment,
            @PathVariable UUID releaseId, @Valid @RequestBody ReleaseService.Retry body) {
        return ApiResponse.success(releases.rollback(projectId, environment, releaseId.toString(), body));
    }

    @PostMapping("/{releaseId}/cancel")
    public ApiResponse<ReleaseService.Release> cancel(@PathVariable long projectId, @PathVariable String environment,
            @PathVariable UUID releaseId) {
        return ApiResponse.success(releases.cancel(projectId, environment, releaseId.toString()));
    }

    @GetMapping("/{releaseId}/events")
    public ApiResponse<List<ReleaseService.Event>> events(@PathVariable long projectId, @PathVariable String environment,
            @PathVariable UUID releaseId) {
        return ApiResponse.success(releases.events(projectId, environment, releaseId.toString()));
    }
}
