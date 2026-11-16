package cloide.server.module.coding;

import cloide.server.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/projects/{projectId}/coding-tasks")
public class CodingController {
    private final CodingService service;

    public record Create(@NotNull UUID requestId, @Positive long serverId,
            @NotNull @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._-]{0,63}") String repository,
            @NotNull @Pattern(regexp="[0-9a-f]{40}") String baseSha,
            @NotBlank @Size(max=8000) String prompt,
            @Pattern(regexp="build|guarded|yolo") String mode, boolean plan) {}
    public record Decision(@NotNull UUID approvalId, @NotNull Boolean allow) {}

    @PostMapping
    public ApiResponse<CodingService.Task> create(@PathVariable long projectId, @Valid @RequestBody Create body) {
        return ApiResponse.success(service.create(projectId, body));
    }

    @GetMapping
    public ApiResponse<List<CodingService.Task>> list(@PathVariable long projectId) {
        return ApiResponse.success(service.list(projectId));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<CodingService.Task> get(@PathVariable long projectId, @PathVariable UUID taskId) {
        return ApiResponse.success(service.get(projectId, taskId));
    }

    @GetMapping("/{taskId}/events")
    public ApiResponse<List<CodingService.Event>> events(@PathVariable long projectId, @PathVariable UUID taskId,
            @RequestParam(defaultValue="0") @Min(0) int after) {
        return ApiResponse.success(service.events(projectId, taskId, after));
    }

    @PostMapping("/{taskId}/decision")
    public ApiResponse<CodingService.Task> decide(@PathVariable long projectId, @PathVariable UUID taskId,
            @Valid @RequestBody Decision body) {
        return ApiResponse.success(service.decide(projectId, taskId, body));
    }

    @PostMapping("/{taskId}/cancel")
    public ApiResponse<CodingService.Task> cancel(@PathVariable long projectId, @PathVariable UUID taskId) {
        return ApiResponse.success(service.cancel(projectId, taskId));
    }
}
