package cloide.server.module.repository;

import cloide.server.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/projects/{projectId}/repositories")
public class RepositoryController {
    public record Import(@NotBlank String name) {}
    public record Create(@NotBlank String name,@NotNull UUID requestId) {}
    public record Task(@NotNull UUID taskId) {}
    private final RepositoryService service;

    @GetMapping
    public ApiResponse<List<RepositoryService.Repository>> list(@PathVariable long projectId) {
        return ApiResponse.success(service.list(projectId));
    }
    @PostMapping("/import")
    public ApiResponse<RepositoryService.Repository> connect(@PathVariable long projectId,@Valid @RequestBody Import body) {
        return ApiResponse.success(service.connect(projectId,body.name(),null));
    }
    @PostMapping
    public ApiResponse<RepositoryService.Repository> create(@PathVariable long projectId,@Valid @RequestBody Create body) {
        return ApiResponse.success(service.connect(projectId,body.name(),body.requestId()));
    }
    @DeleteMapping("/{repositoryId}")
    public ApiResponse<Void> unlink(@PathVariable long projectId,@PathVariable long repositoryId) {
        service.unlink(projectId,repositoryId);
        return ApiResponse.success(null);
    }
    @GetMapping("/{repositoryId}/pulls")
    public ApiResponse<List<RepositoryService.Pull>> pulls(@PathVariable long projectId,@PathVariable long repositoryId) {
        return ApiResponse.success(service.pulls(projectId,repositoryId));
    }
    @PostMapping("/{repositoryId}/pulls")
    public ApiResponse<RepositoryService.Pull> publish(@PathVariable long projectId,@PathVariable long repositoryId,
            @Valid @RequestBody Task body) {
        return ApiResponse.success(service.publish(projectId,repositoryId,body.taskId()));
    }
}
