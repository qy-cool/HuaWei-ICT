package cloide.server.module.repository;

import cloide.server.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * A1 仓库身份与提交选择端点。允许来源策略由平台管理员维护；
 * refs 为读时观察；快照由项目开发者创建、项目成员可读。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/projects/{projectId}")
public class SourceController {
    public record PolicyUpdate(@NotNull UUID requestId,@NotNull @Min(0) Long expectedPolicyVersion,
            @NotEmpty @Size(max=32) List<String> allowedRefs) {}
    public record PolicyView(long repositoryId,long policyVersion,List<String> allowedRefs) {}
    public record SnapshotCreate(@NotNull UUID requestId,@NotBlank String ref,String expectedCommitSha) {}
    private final SourceIdentityService service;

    @PutMapping("/repositories/{repositoryId}/source-policy")
    public ApiResponse<PolicyView> updatePolicy(@PathVariable long projectId,@PathVariable long repositoryId,
            @Valid @RequestBody PolicyUpdate body) {
        var policy=service.updatePolicy(projectId,repositoryId,body.requestId(),body.expectedPolicyVersion(),body.allowedRefs());
        return ApiResponse.success(new PolicyView(repositoryId,policy.policyVersion(),policy.allowedRefs()));
    }
    @GetMapping("/repositories/{repositoryId}/refs")
    public ApiResponse<SourceIdentityService.RefPage> refs(@PathVariable long projectId,@PathVariable long repositoryId,
            @RequestParam(required=false) String cursor,@RequestParam(required=false,defaultValue="50") int limit) {
        return ApiResponse.success(service.refs(projectId,repositoryId,cursor,limit));
    }
    @PostMapping("/repositories/{repositoryId}/snapshots")
    public ApiResponse<SourceIdentityService.Snapshot> createSnapshot(@PathVariable long projectId,
            @PathVariable long repositoryId,@Valid @RequestBody SnapshotCreate body) {
        return ApiResponse.success(service.snapshot(projectId,repositoryId,body.requestId(),body.ref(),body.expectedCommitSha()));
    }
    @GetMapping("/snapshots/{snapshotId}")
    public ApiResponse<SourceIdentityService.Snapshot> snapshot(@PathVariable long projectId,@PathVariable String snapshotId) {
        return ApiResponse.success(service.snapshotRow(projectId,snapshotId));
    }
}
