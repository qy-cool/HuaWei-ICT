package cloide.server.module.release;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import cloide.server.module.job.ProjectRunService;
import cloide.server.module.project.service.ProjectAccess;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ArtifactService {
    public record Artifact(String id, long projectId, String runId, String repository, String commitSha,
            String kind, String sha256, long byteCount, long uploadedBy, LocalDateTime createdAt, Long sourceNodeId) { }
    private static final DataClassRowMapper<Artifact> ROW = new DataClassRowMapper<>(Artifact.class);
    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final ProjectRunService runs;
    private final ArtifactFiles files;
    private final Clock clock;

    public Artifact upload(long project, String runId, String kind, String sha, InputStream contents) throws IOException {
        access.requireDeveloper(project);
        if (!Set.of("java-jar", "static-tar").contains(kind)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        var run = runs.read(project, runId);
        if (!"succeeded".equals(run.status())) throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
        long bytes = files.put(sha, contents);
        access.requireDeveloper(project);
        jdbc.update("""
                INSERT INTO project_artifacts
                (id,project_id,run_id,repository,commit_sha,kind,sha256,byte_count,uploaded_by,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id
                """, UUID.randomUUID().toString(), project, runId, run.repository(), run.commitSha(), kind, sha,
                bytes, access.principal().id(), LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        return jdbc.queryForObject("""
                SELECT * FROM project_artifacts WHERE project_id=? AND run_id=? AND kind=? AND sha256=?
                """, ROW, project, runId, kind, sha);
    }

    /** 节点在可信宿主从本次隔离构建收集输出后登记。上传时再次核对执行人权限和实际提交。 */
    public Artifact uploadFromNode(long node, String runId, long project, String commit, String kind, String sha,
                                   InputStream contents) throws IOException {
        if (!Set.of("java-jar","static-tar").contains(kind)) throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        var permitted = jdbc.queryForList("""
                SELECT created_by FROM project_runs WHERE job_id=? AND server_id=? AND project_id=? AND commit_sha=? AND status='succeeded'
                """,Long.class,runId,node,project,commit);
        if (permitted.isEmpty() || !access.canRun(permitted.getFirst(),project,node)) throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
        long bytes=files.put(sha,contents);
        jdbc.update("""
                INSERT INTO project_artifacts(id,project_id,run_id,repository,commit_sha,kind,sha256,byte_count,uploaded_by,created_at,source_node_id)
                SELECT ?,r.project_id,r.job_id,r.repository,r.commit_sha,?,?,?,r.created_by,?,r.server_id
                FROM project_runs r JOIN users u ON u.id=r.created_by AND u.review_status='approved'
                JOIN servers s ON s.id=r.server_id AND s.deleted=0 AND s.agent_token_revoked_at IS NULL
                JOIN project_nodes n ON n.server_id=r.server_id AND n.project_id=r.project_id AND n.environment IN ('dev','test')
                LEFT JOIN project_members m ON m.user_id=u.id AND m.project_id=r.project_id
                WHERE r.job_id=? AND r.project_id=? AND r.server_id=? AND r.commit_sha=? AND r.status='succeeded'
                    AND (u.role='admin' OR m.member_role IN ('owner','developer'))
                ON DUPLICATE KEY UPDATE source_node_id=?
                """,UUID.randomUUID().toString(),kind,sha,bytes,LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC),
                runId,project,node,commit,node);
        return jdbc.query("""
                SELECT * FROM project_artifacts WHERE project_id=? AND run_id=? AND kind=? AND sha256=? AND source_node_id=?
                """,ROW,project,runId,kind,sha,node).stream().findFirst().orElseThrow(()->new BusinessException(ErrorCode.FORBIDDEN));
    }

    public List<Artifact> list(long project) {
        access.requireRead(project);
        return jdbc.query("SELECT * FROM project_artifacts WHERE project_id=? ORDER BY created_at DESC,id LIMIT 100",
                ROW, project);
    }

    Artifact find(long project, String id) {
        return jdbc.query("SELECT * FROM project_artifacts WHERE project_id=? AND id=?", ROW, project, id).stream()
                .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    public ResponseEntity<InputStreamResource> download(long project, String id) throws IOException {
        access.requireRead(project);
        return content(find(project, id));
    }

    ResponseEntity<InputStreamResource> content(Artifact artifact) throws IOException {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Cache-Control", "no-store").eTag('"' + artifact.sha256() + '"')
                .contentLength(artifact.byteCount()).body(files.open(artifact.sha256(), artifact.byteCount()));
    }
}
