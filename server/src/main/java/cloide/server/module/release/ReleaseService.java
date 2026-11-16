package cloide.server.module.release;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import cloide.server.module.project.service.ProjectAccess;
import jakarta.validation.constraints.*;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReleaseService {
    public record Release(String id, long projectId, String environment, String service, long serverId, long generation,
            String action, String artifactId, String sha256, String kind, String previousHealthyId, String rollbackOf,
            String status, String evidence, long requestedBy, LocalDateTime createdAt, LocalDateTime claimedAt,
            LocalDateTime reportedAt) { }
    public record Deploy(@NotNull UUID requestId, @NotNull UUID artifactId, @Positive long serverId,
            @NotNull @Pattern(regexp="[a-z][a-z0-9-]{0,62}") String service) { }
    public record Retry(@NotNull UUID requestId) { }
    public record Receipt(@NotNull @Pattern(regexp="[0-9a-f]{64}") String sha256,
            @NotNull @Pattern(regexp="healthy|failed|uncertain") String status,
            @NotBlank @Size(max=2048) String evidence) { }
    public record Event(long seq, String status, String actorType, Long actorId, String detail, LocalDateTime createdAt) { }

    private static final String SELECT = """
            SELECT r.*, a.sha256, a.kind FROM project_releases r JOIN project_artifacts a
            ON r.artifact_id=a.id AND r.project_id=a.project_id
            """;
    private static final DataClassRowMapper<Release> ROW = new DataClassRowMapper<>(Release.class);
    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final ArtifactService artifacts;
    private final Clock clock;

    public List<Release> list(long project, String env) {
        access.requireRead(project);
        return jdbc.query(SELECT + " WHERE r.project_id=? AND r.environment=? ORDER BY r.created_at DESC,r.id LIMIT 100",
                ROW, project, env);
    }

    public List<Event> events(long project, String env, String id) {
        access.requireRead(project);
        scoped(project, env, id);
        return jdbc.query("SELECT * FROM release_events WHERE release_id=? ORDER BY seq",
                new DataClassRowMapper<>(Event.class), id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Release deploy(long project, String env, Deploy request) {
        requireRelease(project, env);
        artifacts.find(project, request.artifactId().toString());
        return create(project, env, request.service(), request.serverId(), request.artifactId().toString(),
                request.requestId().toString(), null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Release rollback(long project, String env, String id, Retry request) {
        requireRelease(project, env);
        var source = scoped(project, env, id);
        if (!"healthy".equals(source.status()) || source.previousHealthyId() == null) throw conflict();
        var previous = scoped(project, env, source.previousHealthyId());
        return create(project, env, source.service(), source.serverId(), previous.artifactId(),
                request.requestId().toString(), id);
    }

    private Release create(long project, String env, String service, long server, String artifactId, String requestId,
            String rollbackOf) {
        long user = access.principal().id();
        jdbc.update("""
                INSERT INTO release_targets(project_id,environment,service) VALUES (?,?,?)
                ON DUPLICATE KEY UPDATE generation=generation
                """, project, env, service);
        Long generation = jdbc.queryForObject("""
                SELECT generation FROM release_targets WHERE project_id=? AND environment=? AND service=? FOR UPDATE
                """, Long.class, project, env, service);
        var existing = jdbc.query(SELECT + " WHERE r.project_id=? AND r.requested_by=? AND r.request_id=?",
                ROW, project, user, requestId);
        if (!existing.isEmpty()) {
            var release = existing.getFirst();
            if (release.serverId() != server || !env.equals(release.environment()) || !service.equals(release.service())
                    || !artifactId.equals(release.artifactId()) || !Objects.equals(rollbackOf, release.rollbackOf())) throw conflict();
            return release;
        }
        if (!assigned(project, env, server)) throw new BusinessException(ErrorCode.FORBIDDEN);
        if (count("""
                SELECT COUNT(*) FROM project_releases WHERE project_id=? AND environment=? AND service=?
                AND status IN ('queued','applying','uncertain')
                """, project, env, service) > 0) throw conflict();
        var healthy = jdbc.query(SELECT + """
                 WHERE r.project_id=? AND r.environment=? AND r.service=? AND r.server_id=? AND r.status='healthy'
                 ORDER BY r.generation DESC LIMIT 1
                """, ROW, project, env, service, server);
        String previousId = healthy.isEmpty() ? null : healthy.getFirst().id();
        if (rollbackOf != null && !rollbackOf.equals(previousId)) throw conflict();
        if (rollbackOf == null && "prod".equals(env) && count("""
                SELECT COUNT(*) FROM project_releases r JOIN project_artifacts a ON a.id=r.artifact_id AND a.project_id=r.project_id
                WHERE r.project_id=? AND r.environment='test' AND r.service=?
                AND r.artifact_id=? AND r.status='healthy' AND a.source_node_id IS NOT NULL
                """, project, service, artifactId) == 0) throw conflict();
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                UPDATE release_targets SET generation=generation+1 WHERE project_id=? AND environment=? AND service=?
                """, project, env, service);
        jdbc.update("""
                INSERT INTO project_releases(id,request_id,project_id,environment,service,server_id,generation,action,
                artifact_id,previous_healthy_id,rollback_of,status,requested_by,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,'queued',?,?)
                """, id, requestId, project, env, service, server, generation + 1, rollbackOf == null ? "deploy" : "rollback",
                artifactId, previousId, rollbackOf, user, now());
        event(id, "queued", "user", user, null);
        return scoped(project, env, id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Release claim(long server, Long project, String env, String service) {
        List<Release> rows;
        String filter = " WHERE r.server_id=? AND r.status IN ('applying','queued')";
        String order = " ORDER BY CASE WHEN r.status='applying' THEN 0 ELSE 1 END,r.created_at,r.id LIMIT 1 FOR UPDATE";
        if (project == null && env == null && service == null) {
            rows = jdbc.query(SELECT + filter + order, ROW, server);
        } else if (project != null && env != null && service != null) {
            rows = jdbc.query(SELECT + filter + " AND r.project_id=? AND r.environment=? AND r.service=?" + order,
                    ROW, server, project, env, service);
        } else {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        if (rows.isEmpty()) return null;
        var release = rows.getFirst();
        if (!assigned(release.projectId(), release.environment(), server) || !authorized(release)) {
            boolean sent = "applying".equals(release.status());
            terminal(release, sent ? "uncertain" : "cancelled", "system", null, "发布授权或目标节点分配已变更");
            return null;
        }
        if ("queued".equals(release.status())) {
            jdbc.update("UPDATE project_releases SET status='applying',claimed_at=? WHERE id=?", now(), release.id());
            event(release.id(), "applying", "node", server, null);
        }
        return forNode(server, release.id(), false);
    }

    public ArtifactService.Artifact downloadForNode(long server, String id) {
        var release = forNode(server, id, false);
        if (!"applying".equals(release.status()) || !assigned(release.projectId(), release.environment(), server)
                || !authorized(release)) throw new BusinessException(ErrorCode.FORBIDDEN);
        return artifacts.find(release.projectId(), release.artifactId());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void report(long server, String id, Receipt receipt) {
        var release = forNode(server, id, true);
        if (!release.sha256().equals(receipt.sha256()) || !Set.of("healthy","failed","uncertain").contains(receipt.status())) {
            throw conflict();
        }
        if (release.status().equals(receipt.status()) && Objects.equals(release.evidence(), receipt.evidence())) return;
        if (!"applying".equals(release.status()) && !("uncertain".equals(release.status())
                && !"uncertain".equals(receipt.status()))) throw conflict();
        terminal(release, receipt.status(), "node", server, receipt.evidence());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Release cancel(long project, String env, String id) {
        requireRelease(project, env);
        var release = jdbc.query(SELECT + " WHERE r.project_id=? AND r.environment=? AND r.id=? FOR UPDATE", ROW,
                project, env, id).stream().findFirst().orElseThrow(ReleaseService::missing);
        if ("cancelled".equals(release.status())) return release;
        if (!"queued".equals(release.status())) throw conflict();
        terminal(release, "cancelled", "user", access.principal().id(), "用户取消尚未认领的发布");
        return scoped(project, env, id);
    }

    private void terminal(Release release, String status, String actor, Long actorId, String detail) {
        jdbc.update("UPDATE project_releases SET status=?,evidence=?,reported_at=? WHERE id=?", status, detail, now(), release.id());
        event(release.id(), status, actor, actorId, detail);
    }

    private void event(String id, String status, String actor, Long actorId, String detail) {
        jdbc.update("INSERT INTO release_events(release_id,status,actor_type,actor_id,detail,created_at) VALUES (?,?,?,?,?,?)",
                id, status, actor, actorId, detail, now());
    }

    private boolean assigned(long project, String env, long server) {
        return count("""
                SELECT COUNT(*) FROM project_nodes n JOIN servers s ON s.id=n.server_id AND s.deleted=0
                WHERE n.project_id=? AND n.environment=? AND n.server_id=?
                """, project, env, server) > 0;
    }

    private boolean authorized(Release r) {
        return count("""
                SELECT COUNT(*) FROM users u LEFT JOIN project_members m ON m.project_id=? AND m.user_id=u.id
                WHERE u.id=? AND u.review_status='approved' AND (u.role='admin' OR (? <> 'prod' AND m.member_role='owner'))
                """, r.projectId(), r.requestedBy(), r.environment()) > 0;
    }

    private void requireRelease(long project, String env) {
        if (!Set.of("dev", "test", "prod").contains(env)) throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        access.requireOwner(project);
        if ("prod".equals(env)) access.requireAdmin();
    }

    private int count(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Integer.class, args));
    }

    private Release scoped(long project, String env, String id) {
        return jdbc.query(SELECT + " WHERE r.project_id=? AND r.environment=? AND r.id=?", ROW, project, env, id)
                .stream().findFirst().orElseThrow(ReleaseService::missing);
    }

    private Release forNode(long server, String id, boolean lock) {
        return jdbc.query(SELECT + " WHERE r.server_id=? AND r.id=?" + (lock ? " FOR UPDATE" : ""), ROW, server, id)
                .stream().findFirst().orElseThrow(ReleaseService::missing);
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private static BusinessException conflict() { return new BusinessException(ErrorCode.RESOURCE_CONFLICT); }
    private static BusinessException missing() { return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND); }
}
