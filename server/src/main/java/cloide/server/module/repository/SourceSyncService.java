package cloide.server.module.repository;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import cloide.server.module.project.service.NodeCapabilities;
import cloide.server.module.project.service.ProjectAccess;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A2 节点源码同步状态机：queued → preparing（中控生成 bundle）→ transferring（派发与
 * 下载）→ verifying（节点核验）→ ready；取消走 cancelling → cancelled，超时兜底 uncertain，
 * 节点回归后的真实回执可覆盖核实。创建幂等沿用 V51 仓库域请求账本
 * ((项目,发起人,操作,requestId) 唯一 + 参数摘要)。
 */
@Service
@RequiredArgsConstructor
public class SourceSyncService {
    public record View(String syncId,long projectId,long repositoryId,String snapshotId,long serverId,long generation,
            String commitSha,String treeSha,String state,String bundleSha256,Long byteCount,int attempts,
            String lastError,String deadline,String createdAt,String updatedAt) { }
    public record GrantView(String grantId,String secret,String expiresAt,String deadline,long projectId,
            long repositoryId,String snapshotId,long generation,String commitSha,String treeSha,
            String bundleSha256,long byteCount) { }
    public record Dispatch(String type, Map<String, Object> payload) { }

    private static final String OPERATION = "source-sync";
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration DEADLINE = Duration.ofHours(2);
    private static final Duration GRANT_TTL = Duration.ofMinutes(30);
    private final SourceSyncStore store;
    private final SourceIdentityService identity;
    private final RepositoryService repositories;
    private final SourceBundleService bundles;
    private final SourceBundleStore bundleStore;
    private final NodeCapabilities capabilities;
    private final ProjectAccess access;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ObjectProvider<SourceSyncChannel> channel;
    private final SecureRandom random = new SecureRandom();

    /** 项目开发者把已钉住的快照登记为目标节点的同步任务；同键原样重放返回原同步。 */
    public View create(long projectId, UUID requestId, String snapshotId, long serverId) {
        access.requireDeveloper(projectId);
        if (requestId == null || snapshotId == null
                || !snapshotId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        var snapshot = identity.snapshotRow(projectId, snapshotId);
        if (!snapshot.bindingActive()) throw new BusinessException(ErrorCode.SOURCE_REQUEST_CONFLICT);
        repositories.activeBinding(projectId, snapshot.repositoryId());
        // 节点须已分配给本项目且可运行工程任务，并声明 source.sync.v1 能力；旧 Agent 明确拒绝。
        access.requireRun(projectId, serverId);
        if (!capabilities.supportsAction(projectId, serverId, "source.sync.v1")) {
            throw new BusinessException(ErrorCode.SOURCE_SYNC_CAPABILITY_MISSING);
        }
        String digest = SourceIdentityService.digest(snapshot.snapshotId(), serverId);
        String existing = identity.idempotentResult(projectId, OPERATION, requestId, digest);
        if (existing != null) return view(store.read(projectId, existing));
        return view(tx.execute(status -> persist(projectId, requestId, digest, snapshot, serverId)));
    }

    /** 事务内先插同步行再记账本；同键并发赢家已落账本时丢弃本行并按锁定读复核收敛。 */
    private SourceSync persist(long projectId, UUID requestId, String digest,
            SourceIdentityService.Snapshot snapshot, long serverId) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String syncId = UUID.randomUUID().toString();
        store.insert(new SourceSync(syncId, projectId, snapshot.repositoryId(), snapshot.snapshotId(), serverId,
                access.principal().id(), snapshot.bindingGeneration(), snapshot.commitSha(), snapshot.treeSha(),
                "queued", null, null, 0, null, now.plus(DEADLINE), now, now));
        try {
            identity.recordIdempotent(projectId, OPERATION, requestId, digest, syncId);
        } catch (DuplicateKeyException e) {
            store.delete(syncId);
            // 复核必须用锁定读：普通 SELECT 沿用本事务先前的 REPEATABLE READ 读视图，
            // 看不到赢家刚提交的账本行，同参重放会被误判为 40908。
            String winner = identity.idempotentResultForUpdate(projectId, OPERATION, requestId, digest);
            if (winner != null) return store.read(projectId, winner);
            throw new BusinessException(ErrorCode.SOURCE_REQUEST_CONFLICT, e);
        }
        return store.read(projectId, syncId);
    }

    public View read(long projectId, String syncId) {
        access.requireRead(projectId);
        return view(store.read(projectId, checkedSyncId(syncId)));
    }

    /**
     * 中控准备循环：把最旧的 queued 同步置 preparing（短事务释放行锁）后，在事务外生成
     * bundle，成功落 transferring 并唤醒目标节点。远端无法证明固定提交来源落
     * source_unavailable 终态；暂时不可用回 queued 等下一轮，超过尝试上限判失败。
     */
    @Scheduled(fixedDelay = 10000)
    public void prepareNext() {
        SourceSync sync = tx.execute(status -> {
            var next = store.lockNextQueued();
            if (next.isPresent() && next.get().attempts() >= MAX_ATTEMPTS) {
                store.complete(next.get().syncId(), "failed", "too_many_attempts",
                        LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
                return null;
            }
            if (next.isPresent()) {
                var current = next.get();
                store.complete(current.syncId(), "preparing", null,
                        LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
                return current;
            }
            return null;
        });
        if (sync == null) return;
        try {
            var snapshot = identity.snapshotRow(sync.projectId(), sync.snapshotId());
            var bundle = bundles.produce(snapshot, repositories.activeBinding(sync.projectId(), sync.repositoryId()));
            tx.executeWithoutResult(status -> store.bundled(sync.syncId(), bundle.sha256(), bundle.byteCount(),
                    LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)));
            wake(sync.serverId());
        } catch (BusinessException e) {
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            if (e.getErrorCode() == ErrorCode.GITHUB_API_UNAVAILABLE && sync.attempts() + 1 < MAX_ATTEMPTS) {
                tx.executeWithoutResult(status -> {
                    store.dispatched(sync.syncId(), now);
                    store.complete(sync.syncId(), "queued", null, now);
                });
                return;
            }
            String reason = e.getErrorCode() == ErrorCode.SOURCE_REF_NOT_FOUND ? "source_unavailable"
                    : e.getErrorCode() == ErrorCode.INVALID_REQUEST_PARAMETER ? "capacity"
                    : e.getErrorCode() == ErrorCode.SOURCE_REQUEST_CONFLICT ? "digest_mismatch"
                    : e.getErrorCode() == ErrorCode.RESOURCE_NOT_FOUND ? "binding_revoked" : "internal";
            tx.executeWithoutResult(status -> store.complete(sync.syncId(), "failed", reason, now));
        }
    }

    /**
     * 超时兜底：preparing 滞留（中控崩溃）超过时限的行按截止时间回 queued 或落
     * uncertain；transferring/verifying/cancelling 在 deadline 加 5 分钟宽限后仍未
     * 收敛的转 uncertain；同时清理已过期的短期授权行。
     */
    @Scheduled(fixedDelay = 30000)
    public void expireStale() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        store.recoverStalePreparing(now.minus(Duration.ofMinutes(10)), now);
        store.expireStale(now.minus(Duration.ofMinutes(5)));
        store.purgeExpiredGrants(now);
    }

    /** WSS 派发：节点 ready 轮询时取一条 bundle 就绪的同步；超期或取消中的同步改发取消。 */
    public Optional<Dispatch> next(long serverId) {
        return tx.execute(status -> {
            var next = store.lockNextForNode(serverId);
            if (next.isEmpty()) return Optional.<Dispatch>empty();
            SourceSync sync = next.get();
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            if (!bindingAllows(sync)) {
                invalidateOpen(sync, "binding_revoked", now);
                return Optional.of(new Dispatch("source.sync.cancel", Map.of("sync_id", sync.syncId())));
            }
            if (sync.status().equals("cancelling") || sync.deadline().isBefore(now)) {
                store.status(sync.syncId(), "cancelling", now);
                return Optional.of(new Dispatch("source.sync.cancel", Map.of("sync_id", sync.syncId())));
            }
            store.dispatched(sync.syncId(), now);
            return Optional.of(new Dispatch("source.sync", Map.of(
                    "sync_id", sync.syncId(), "project_id", sync.projectId(),
                    "repository_id", sync.repositoryId(), "snapshot_id", sync.snapshotId(),
                    "generation", sync.generation(), "commit_sha", sync.commitSha(),
                    "tree_sha", sync.treeSha(), "bundle_sha256", sync.bundleSha256(),
                    "byte_count", sync.byteCount(), "deadline", sync.deadline().toInstant(ZoneOffset.UTC).toString())));
        });
    }

    /** 节点接单确认：只校验归属；下载进度由 progress 上报。 */
    public void accepted(long serverId, String syncId) {
        store.lockForNode(serverId, checkedSyncId(syncId));
    }

    public void progress(long serverId, String syncId, String stage) {
        if (!"transferring".equals(stage) && !"verifying".equals(stage)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        store.lockForNode(serverId, checkedSyncId(syncId));
        store.progress(syncId, stage, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    /** 节点终态回执：ready 必须携带与派发一致的三个摘要；uncertain 可被真实回执覆盖。 */
    public void finish(long serverId, String syncId, String status, String commitSha, String treeSha,
            String bundleSha256, String output) {
        if (status == null || !SourceSync.TERMINAL.contains(status) || output == null || output.length() > 512) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        SourceSync sync = store.lockForNode(serverId, checkedSyncId(syncId));
        if ("ready".equals(status)) {
            // 从未生成 bundle 的同步无派发事实可核验（节点不可能对其报 ready），按冲突拒绝。
            if (sync.bundleSha256() == null || !sync.commitSha().equals(commitSha)
                    || !sync.treeSha().equals(treeSha) || !sync.bundleSha256().equals(bundleSha256)) {
                throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
            }
        }
        if (sync.status().equals("uncertain")) {
            // 服务端超时兜底标记的 uncertain 允许被节点真实回执覆盖核实。
            store.complete(sync.syncId(), status,
                    output != null && output.length() > 64 ? output.substring(0, 64) : output,
                    LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            return;
        }
        if (sync.terminal()) {
            // 解绑后已 cancelled 的同步允许节点再报 cancelled，避免并发回执被误判冲突。
            if ("cancelled".equals(sync.status()) && "cancelled".equals(status)) return;
            boolean same = sync.status().equals(status)
                    && Objects.equals(sync.commitSha(), commitSha) && Objects.equals(sync.treeSha(), treeSha)
                    && Objects.equals(sync.bundleSha256(), bundleSha256);
            if (!same) throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
            return;
        }
        // failed/cancelled：保留节点给出的有界说明作为最后错误。
        store.complete(sync.syncId(), status, output.length() > 64 ? output.substring(0, 64) : output,
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    /**
     * 解绑或 GitHub 撤权后调用：该仓库所有未终态同步立即 cancelled，并清除短期授权。
     * 可重入、可在绑定行已软删之后调用。
     */
    public void revokeOpenByRepository(long remoteId) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        tx.executeWithoutResult(status -> {
            store.cancelOpenByRepository(remoteId, now, "binding_revoked");
            store.deleteGrantsByRepository(remoteId);
        });
    }

    /** 已认证节点领取短期下载授权：绑定同步、节点与有效期；密钥只此一次返回。 */
    public GrantView grant(long serverId, String syncId) {
        // 撤权取消必须先提交再抛冲突，避免 TransactionTemplate 回滚刚写入的 cancelled。
        var outcome = new Object() { GrantView view; ErrorCode error; };
        tx.executeWithoutResult(status -> {
            String id = checkedSyncId(syncId);
            SourceSync hinted = store.readForNode(serverId, id);
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            if (!bindingAllows(hinted)) {
                invalidateOpen(store.lockForNode(serverId, id), "binding_revoked", now);
                outcome.error = ErrorCode.RESOURCE_CONFLICT;
                return;
            }
            SourceSync sync = store.lockForNode(serverId, id);
            if (!SourceSync.GRANTABLE.contains(sync.status()) || sync.bundleSha256() == null) {
                outcome.error = ErrorCode.RESOURCE_CONFLICT;
                return;
            }
            String grantId = UUID.randomUUID().toString();
            byte[] secretBytes = new byte[32];
            random.nextBytes(secretBytes);
            String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
            store.insertGrant(grantId, sync.syncId(), serverId, SourceIdentityService.digest(secret),
                    now.plus(GRANT_TTL), now);
            outcome.view = new GrantView(grantId, secret, now.plus(GRANT_TTL).toInstant(ZoneOffset.UTC).toString(),
                    sync.deadline().toInstant(ZoneOffset.UTC).toString(), sync.projectId(), sync.repositoryId(),
                    sync.snapshotId(), sync.generation(), sync.commitSha(), sync.treeSha(),
                    sync.bundleSha256(), sync.byteCount());
        });
        if (outcome.error != null) throw new BusinessException(outcome.error);
        return outcome.view;
    }

    /**
     * 内容下载校验：每次请求在同一事务内先锁定绑定行再锁定同步行，重查节点凭据
     * （Controller 已认证）、绑定是否仍活动且代次一致、短期授权归属与期限、以及
     * 同步状态是否仍允许下载；任一不满足即拒绝。过期授权即使密钥正确也不可用。
     */
    public Path content(long serverId, String syncId, String grantId, String secret) {
        if (grantId == null || secret == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        // 与 grant 相同：先提交取消再抛错，避免冲突回滚刚写入的 cancelled。
        var outcome = new Object() { Path path; ErrorCode error; };
        tx.executeWithoutResult(status -> {
            String id = checkedSyncId(syncId);
            SourceSync hinted = store.readForNode(serverId, id);
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            if (!bindingAllows(hinted)) {
                invalidateOpen(store.lockForNode(serverId, id), "binding_revoked", now);
                outcome.error = ErrorCode.RESOURCE_CONFLICT;
                return;
            }
            var grant = store.lockGrantRow(grantId, serverId, id);
            if (grant.isEmpty()
                    || !constantTimeEquals(SourceIdentityService.digest(secret), grant.get().secretHash())
                    || !grant.get().expiresAt().isAfter(now)) {
                outcome.error = ErrorCode.UNAUTHORIZED;
                return;
            }
            SourceSync sync = store.lockForNode(serverId, id);
            if (!SourceSync.GRANTABLE.contains(sync.status()) || sync.bundleSha256() == null) {
                // 撤销或终态后凭旧授权取得新分段被立即阻止。
                outcome.error = ErrorCode.RESOURCE_CONFLICT;
                return;
            }
            outcome.path = bundleStore.open(sync.bundleSha256());
        });
        if (outcome.error != null) throw new BusinessException(outcome.error);
        return outcome.path;
    }

    private boolean bindingAllows(SourceSync sync) {
        try {
            repositories.lockActiveGeneration(sync.projectId(), sync.repositoryId(), sync.generation());
            return true;
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.RESOURCE_NOT_FOUND) return false;
            throw e;
        }
    }

    private void invalidateOpen(SourceSync sync, String reason, LocalDateTime now) {
        if (sync.terminal()) return;
        store.deleteGrantsForSync(sync.syncId());
        store.complete(sync.syncId(), "cancelled", reason, now);
    }

    /** bundle 就绪后唤醒目标节点；发送失败不影响状态机，节点 ready 轮询会重取。 */
    void wake(long serverId) {
        try {
            channel.ifAvailable(ch -> {
                try {
                    ch.wake(serverId);
                } catch (java.io.IOException deferred) {
                    // 节点暂不可达：同步保持 transferring，由下一次轮询或重连派发。
                }
            });
        } catch (RuntimeException deferred) {
            // 同上：唤醒只是加速派发的旁路，不作为状态转移的依据。
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(), b.getBytes());
    }

    private static String checkedSyncId(String syncId) {
        if (syncId == null || !syncId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        return syncId;
    }

    private View view(SourceSync sync) {
        return new View(sync.syncId(), sync.projectId(), sync.repositoryId(), sync.snapshotId(), sync.serverId(),
                sync.generation(), sync.commitSha(), sync.treeSha(), sync.status(), sync.bundleSha256(),
                sync.byteCount(), sync.attempts(), sync.lastError(),
                sync.deadline().toInstant(ZoneOffset.UTC).toString(),
                sync.createdAt().toInstant(ZoneOffset.UTC).toString(),
                sync.updatedAt().toInstant(ZoneOffset.UTC).toString());
    }
}
