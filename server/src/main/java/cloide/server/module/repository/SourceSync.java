package cloide.server.module.repository;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * A2 节点源码同步的持久状态。bundle_sha256 在中控生成 bundle 后填充；
 * attempts 计入准备与派发的总尝试次数，作为无进展重试的上限。
 */
public record SourceSync(String syncId,long projectId,long repositoryId,String snapshotId,long serverId,long createdBy,
        long generation,String commitSha,String treeSha,String status,String bundleSha256,Long byteCount,
        int attempts,String lastError,LocalDateTime deadline,LocalDateTime createdAt,LocalDateTime updatedAt) {
    static final Set<String> TERMINAL = Set.of("ready","cancelled","failed","uncertain");
    static final Set<String> GRANTABLE = Set.of("transferring","verifying");
    boolean terminal() { return TERMINAL.contains(status); }
}
