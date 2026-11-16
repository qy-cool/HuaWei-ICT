package cloide.server.module.repository;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import cloide.server.module.project.service.ProjectAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A1 仓库身份与提交选择：允许来源策略、ref 读取时观察与提交快照。
 *
 * 快照是不可变事实：创建时钉住 ref 当时的 40 位提交与树 SHA、绑定代次与策略版本；
 * 之后分支前移、改名或解绑都不改写历史行。幂等请求按
 * (项目,发起人,操作,requestId) 记账，响应丢失后的原样重发返回原结果。
 */
@Service
@RequiredArgsConstructor
public class SourceIdentityService {
    public record Policy(long remoteId,long policyVersion,List<String> allowedRefs) { }
    public record RefItem(String ref,String commitSha) { }
    public record RefPage(List<RefItem> items,String nextCursor) { }
    public record Snapshot(String snapshotId,long repositoryId,String ref,String commitSha,String treeSha,
            long bindingGeneration,long policyVersion,String resolvedAt,long createdBy,boolean bindingActive) { }
    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final GitHubApp app;
    private final RepositoryService repositories;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    private static final String REF_PATTERN="refs/heads/[A-Za-z0-9][A-Za-z0-9._/-]{0,127}";
    private static final int MAX_REFS=32;

    /** 校验 ref 为显式完整分支引用：禁通配符、空白、中文与非法 Git 字符序列。 */
    static String checkedRef(String ref) {
        if (ref==null || !ref.matches(REF_PATTERN) || ref.contains("..") || ref.contains("//")
                || ref.endsWith("/") || ref.endsWith(".") || ref.endsWith(".lock") || ref.contains("@{")) {
            throw new BusinessException(ErrorCode.SOURCE_REF_NOT_ALLOWED);
        }
        return ref;
    }

    /** 当前生效策略；无显式版本时按版本 0 仅允许登记时的默认分支。 */
    public Policy currentPolicy(long project,long remoteId) {
        var binding=repositories.activeBinding(project,remoteId);
        var rows=jdbc.query(
                "SELECT policy_version,allowed_refs FROM repository_source_policies WHERE remote_id=? ORDER BY policy_version DESC LIMIT 1",
                (rs,i)->new Policy(remoteId,rs.getLong("policy_version"),decode(rs.getString("allowed_refs"))),remoteId);
        if (rows.isEmpty()) return new Policy(remoteId,0,List.of("refs/heads/"+binding.defaultBranch()));
        return rows.getFirst();
    }

    /** 平台管理员更新允许清单：expectedPolicyVersion 必须等于当前生效版本（乐观锁），新版本行追加。 */
    @Transactional
    public Policy updatePolicy(long project,long remoteId,UUID requestId,long expectedVersion,List<String> refs) {
        access.requireAdmin();
        access.requireRead(project);
        repositories.activeBinding(project,remoteId);
        if (requestId==null || refs==null || refs.isEmpty() || refs.size()>MAX_REFS) throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        TreeSet<String> normalized=new TreeSet<>();
        for (String ref:refs) normalized.add(checkedRef(ref));
        String digest=digest(remoteId,expectedVersion,normalized);
        // 幂等检查先于版本比较：响应丢失后的原样重放返回原版本，即使当前版本已前移。
        String operation="source-policy";
        String existing=idempotentResult(project,operation,requestId,digest);
        if (existing!=null) return policyAt(remoteId,Long.parseLong(existing));
        Policy current=currentPolicy(project,remoteId);
        if (current.policyVersion()!=expectedVersion) throw new BusinessException(ErrorCode.SOURCE_REQUEST_CONFLICT);
        long next=expectedVersion+1;
        try {
            jdbc.update("INSERT INTO repository_source_policies(remote_id,policy_version,allowed_refs,updated_by) VALUES (?,?,?,?)",
                    remoteId,next,encode(normalized),access.principal().id());
        } catch (DuplicateKeyException e) {
            // 并发竞争：另一请求已抢先写入同版本，按幂等键冲突反馈而非内部错误。
            throw new BusinessException(ErrorCode.SOURCE_REQUEST_CONFLICT,e);
        }
        recordIdempotent(project,operation,requestId,digest,String.valueOf(next));
        return new Policy(remoteId,next,List.copyOf(normalized));
    }

    /** 读时观察：逐个解析当前允许分支；远端已删除的分支以 null SHA 返回，由调用方决定是否继续。 */
    public RefPage refs(long project,long remoteId,String cursor,int limit) {
        access.requireRead(project);
        var binding=repositories.activeBinding(project,remoteId);
        List<String> allowed=currentPolicy(project,remoteId).allowedRefs();
        int offset=cursor==null?0:decodeCursor(cursor);
        int size=Math.clamp(limit<=0?50:limit,1,100);
        if (offset>=allowed.size()) return new RefPage(List.of(),null);
        List<RefItem> page=new ArrayList<>();
        String token=app.token(Map.of("contents","read"),remoteId,null);
        for (int i=offset;i<Math.min(offset+size,allowed.size());i++) {
            String ref=allowed.get(i);
            String sha;
            try {
                sha=app.resolveRef(binding.ownerName(),binding.repoName(),ref,token);
            } catch (BusinessException e) {
                // 读时观察：分支可能在允许清单设置后被删除，列表不因此失败；其余错误照常上抛。
                if (e.getErrorCode()!=ErrorCode.SOURCE_REF_NOT_FOUND) throw e;
                sha=null;
            }
            page.add(new RefItem(ref,sha));
        }
        String next=offset+size<allowed.size()?Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.valueOf(offset+size).getBytes(StandardCharsets.UTF_8)):null;
        return new RefPage(page,next);
    }

    /** 创建提交快照：钉住 ref 当时的提交与树；期望 SHA 不符返回冲突并携带当前实际 SHA。 */
    public Snapshot snapshot(long project,long remoteId,UUID requestId,String ref,String expectedCommitSha) {
        access.requireDeveloper(project);
        var binding=repositories.activeBinding(project,remoteId);
        checkedRef(ref);
        if (requestId==null) throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        if (expectedCommitSha!=null && !expectedCommitSha.matches("[0-9a-f]{40}")) throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        Policy policy=currentPolicy(project,remoteId);
        if (!policy.allowedRefs().contains(ref)) throw new BusinessException(ErrorCode.SOURCE_REF_NOT_ALLOWED);
        String operation="snapshot";
        String digest=digest(remoteId,ref,expectedCommitSha);
        String existing=idempotentResult(project,operation,requestId,digest);
        if (existing!=null) return snapshotRow(project,existing);
        // 解析与树查询在事务外执行：远端网络等待期间不持有数据库行锁。
        String token=app.token(Map.of("contents","read"),remoteId,null);
        String commitSha=app.resolveRef(binding.ownerName(),binding.repoName(),ref,token);
        if (expectedCommitSha!=null && !expectedCommitSha.equals(commitSha)) {
            throw new BusinessException(ErrorCode.SOURCE_COMMIT_MOVED,
                    Map.of("ref",ref,"currentCommitSha",commitSha));
        }
        String treeSha=app.treeOf(binding.ownerName(),binding.repoName(),commitSha,token);
        String snapshotId=persist(project,remoteId,operation,requestId,digest,ref,commitSha,treeSha,
                binding.generation(),policy.policyVersion());
        return snapshotRow(project,snapshotId);
    }

    /** 落库前复核绑定代次仍然有效，防止解析期间被解绑的仓库写入新快照。 */
    private String persist(long project,long remoteId,String operation,UUID requestId,String digest,
            String ref,String commitSha,String treeSha,long generation,long policyVersion) {
        return tx.execute(status->{
            repositories.activeBinding(project,remoteId);
            String snapshotId=UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO source_snapshots(snapshot_id,project_id,remote_id,ref,commit_sha,tree_sha,binding_generation,policy_version,created_by)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """,snapshotId,project,remoteId,ref,commitSha,treeSha,generation,policyVersion,access.principal().id());
            try {
                jdbc.update("""
                        INSERT INTO repository_idempotent_requests(project_id,requester_id,operation,request_id,param_digest,result_id)
                        VALUES (?,?,?,?,?,?)
                        """,project,access.principal().id(),operation,requestId.toString(),digest,snapshotId);
            } catch (DuplicateKeyException e) {
                // 并发同键：另一方已持久化唯一结果，本方丢弃刚插入的快照行改用既有结果。
                jdbc.update("DELETE FROM source_snapshots WHERE snapshot_id=?",snapshotId);
                String existing=idempotentResult(project,operation,requestId,digest);
                if (existing!=null) return existing;
                throw new BusinessException(ErrorCode.SOURCE_REQUEST_CONFLICT,e);
            }
            return snapshotId;
        });
    }

    /** 读取快照；先校验项目读权限，跨项目请求按 404 处理，不暴露其他项目的仓库与 ref 信息。 */
    public Snapshot snapshotRow(long project,String snapshotId) {
        access.requireRead(project);
        if (snapshotId==null || !snapshotId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        var rows=jdbc.query("""
                SELECT s.snapshot_id,s.remote_id,s.ref,s.commit_sha,s.tree_sha,s.binding_generation,s.policy_version,
                       s.resolved_at,s.created_by,
                       CASE WHEN b.id IS NULL THEN 0 ELSE 1 END AS binding_active
                FROM source_snapshots s
                LEFT JOIN project_repository_bindings b ON b.remote_id=s.remote_id AND b.generation=s.binding_generation AND b.active=1
                WHERE s.snapshot_id=? AND s.project_id=?
                """,(rs,i)->new Object[]{
                        rs.getString("snapshot_id"),rs.getLong("remote_id"),rs.getString("ref"),
                        rs.getString("commit_sha"),rs.getString("tree_sha"),rs.getLong("binding_generation"),
                        rs.getLong("policy_version"),rs.getTimestamp("resolved_at"),rs.getLong("created_by"),
                        rs.getInt("binding_active")==1},snapshotId,project);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        Object[] row=rows.getFirst();
        return new Snapshot((String)row[0],(Long)row[1],(String)row[2],(String)row[3],(String)row[4],
                (Long)row[5],(Long)row[6],((java.sql.Timestamp)row[7]).toInstant().toString(),(Long)row[8],(Boolean)row[9]);
    }

    private Policy policyAt(long remoteId,long version) {
        var rows=jdbc.query(
                "SELECT allowed_refs FROM repository_source_policies WHERE remote_id=? AND policy_version=?",
                (rs,i)->decode(rs.getString("allowed_refs")),remoteId,version);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        return new Policy(remoteId,version,rows.getFirst());
    }

    /** 游标为本服务签发的不透明 base64url(offset)；非本服务格式一律按参数错误拒绝。 */
    private static int decodeCursor(String cursor) {
        try {
            return Math.toIntExact(Long.parseLong(new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
    }

    /** 幂等账本查询：同键同参返回原结果 ID；同键异参判定冲突。 */
    private String idempotentResult(long project,String operation,UUID requestId,String digest) {
        var rows=jdbc.queryForList("""
                SELECT param_digest,result_id FROM repository_idempotent_requests
                WHERE project_id=? AND requester_id=? AND operation=? AND request_id=?
                """,project,access.principal().id(),operation,requestId.toString());
        if (rows.isEmpty()) return null;
        if (!digest.equals(rows.getFirst().get("param_digest"))) throw new BusinessException(ErrorCode.SOURCE_REQUEST_CONFLICT);
        return String.valueOf(rows.getFirst().get("result_id"));
    }

    private void recordIdempotent(long project,String operation,UUID requestId,String digest,String resultId) {
        try {
            jdbc.update("""
                    INSERT INTO repository_idempotent_requests(project_id,requester_id,operation,request_id,param_digest,result_id)
                    VALUES (?,?,?,?,?,?)
                    """,project,access.principal().id(),operation,requestId.toString(),digest,resultId);
        } catch (DuplicateKeyException e) {
            // 同键重放且参数一致：idempotentResult 已核对摘要，此处沿用既有结果即可。
        }
    }

    private List<String> decode(String encoded) {
        try {
            List<String> refs=new ArrayList<>();
            for (JsonNode ref:json.readTree(encoded)) refs.add(ref.asText());
            return List.copyOf(refs);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR,e);
        }
    }

    private String encode(Collection<String> refs) {
        try {
            return json.writeValueAsString(refs);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR,e);
        }
    }

    /** 规范化业务字段的 SHA-256：字段按稳定顺序拼接，避免同参数不同序列化形式造成假冲突。 */
    static String digest(Object... fields) {
        StringBuilder canonical=new StringBuilder();
        for (Object field:fields) canonical.append(String.valueOf(field)).append('\n');
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

}
