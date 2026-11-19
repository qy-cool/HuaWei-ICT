package cloide.server.module.repository;

import cloide.server.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * A2 源码同步端点。创建需项目开发权限并要求目标节点声明 source.sync.v1 能力；
 * 查询项目可读，跨项目资源一律 404 不暴露存在性。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/projects/{projectId}")
public class SourceSyncController {
    public record SyncCreate(@NotNull UUID requestId, @NotBlank String snapshotId, @NotNull @Min(1) Long serverId) { }
    private final SourceSyncService syncs;

    @PostMapping("/source-syncs")
    public ApiResponse<SourceSyncService.View> create(@PathVariable long projectId, @Valid @RequestBody SyncCreate body) {
        return ApiResponse.success(syncs.create(projectId, body.requestId(), body.snapshotId(), body.serverId()));
    }

    @GetMapping("/source-syncs/{syncId}")
    public ApiResponse<SourceSyncService.View> read(@PathVariable long projectId, @PathVariable String syncId) {
        return ApiResponse.success(syncs.read(projectId, syncId));
    }
}
