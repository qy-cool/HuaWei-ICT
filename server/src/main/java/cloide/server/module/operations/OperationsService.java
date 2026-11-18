package cloide.server.module.operations;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import cloide.server.module.alert.service.AlertTriggeredEvent;
import cloide.server.module.coding.CodingController;
import cloide.server.module.coding.CodingService;
import cloide.server.module.project.service.ProjectAccess;
import cloide.server.module.release.ReleaseService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OperationsService {
    public record ServiceNode(String environment, long serverId, String nodeName, String agentStatus, String service,
            String desiredRelease, String desiredSha, String desiredStatus, String healthyRelease, String healthySha,
            LocalDateTime lastHealthyAt) {}
    public record Alert(long id, long serverId, String environment, String metric, String level, String status,
            BigDecimal currentValue, BigDecimal thresholdValue, LocalDateTime triggeredAt, LocalDateTime resolvedAt) {}
    public record Change(String id, String service, String status, String sha256, String repository, String commitSha,
            String previousHealthyId, LocalDateTime claimedAt, LocalDateTime reportedAt, LocalDateTime matchedAt) {}
    public record Action(String id, long alertId, long actorId, String action, String releaseId, Long buildNode,
            String rationale, String resultId, String resultStatus, LocalDateTime createdAt) {}
    public record Detail(Alert alert, List<Change> nearby, List<Change> healthyAtAlert, List<Action> actions) {}

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final CodingService coding;
    private final ReleaseService releases;
    private final Clock clock;

    private static final String ALERT = """
            SELECT s.alert_id AS id,s.server_id,s.environment,s.metric,s.level,COALESCE(a.status,'archived') AS status,
                   s.current_value,s.threshold_value,s.triggered_at,a.resolved_at
            FROM project_alert_scopes s LEFT JOIN alert_records a ON a.id=s.alert_id AND a.server_id=s.server_id
            WHERE s.project_id=?
            """;
    private static final String CHANGE = """
            SELECT r.id,r.service,r.status,a.sha256,a.repository,a.commit_sha,r.previous_healthy_id,r.claimed_at,r.reported_at,
                   CASE WHEN r.reported_at < ? THEN r.reported_at ELSE r.claimed_at END AS matched_at
            FROM project_releases r JOIN project_artifacts a ON a.id=r.artifact_id AND a.project_id=r.project_id
            WHERE r.project_id=? AND r.server_id=? AND r.environment=?
            """;
    private static final String ACTION = """
            SELECT h.id,h.alert_id,h.actor_id,h.action,h.release_id,h.build_node,h.rationale,h.result_id,h.created_at,
                   COALESCE(r.status,t.status) AS result_status
            FROM project_alert_actions h
            LEFT JOIN project_releases r ON h.action='rollback' AND r.id=h.result_id AND r.project_id=h.project_id
            LEFT JOIN project_coding_tasks t ON h.action='repair' AND t.task_id=h.result_id AND t.project_id=h.project_id
            WHERE h.project_id=?
            """;
    private static final String HEALTHY_AT_TIME = """
             AND r.status='healthy' AND r.reported_at < ? AND NOT EXISTS (
               SELECT 1 FROM project_releases later WHERE later.project_id=r.project_id AND later.environment=r.environment
               AND later.server_id=r.server_id AND later.service=r.service AND later.generation>r.generation
               AND later.status='healthy' AND later.reported_at < ?)
            """;

    // This synchronous event is published within alert evaluation's database transaction.
    // Old alerts without a captured owner stay in the admin-only global history after upgrade.
    @EventListener
    public void capture(AlertTriggeredEvent event) {
        jdbc.update("""
                INSERT INTO project_alert_scopes(alert_id,project_id,server_id,environment,metric,level,
                    current_value,threshold_value,triggered_at)
                SELECT a.id,n.project_id,n.server_id,n.environment,a.metric,a.level,a.current_value,a.threshold_value,a.triggered_at
                FROM project_nodes n JOIN servers s ON s.id=n.server_id AND s.deleted=0
                JOIN alert_records a ON a.server_id=n.server_id AND a.id=? WHERE n.server_id=?
                """,event.record().getId(),event.serverId());
    }

    @Scheduled(initialDelay=300000, fixedDelay=300000)
    public void pruneExpiredUnworkedAlerts() {
        jdbc.update("""
                DELETE FROM project_alert_scopes WHERE NOT EXISTS (
                    SELECT 1 FROM alert_records a WHERE a.id=project_alert_scopes.alert_id)
                AND NOT EXISTS (SELECT 1 FROM project_alert_actions h
                    WHERE h.project_id=project_alert_scopes.project_id AND h.alert_id=project_alert_scopes.alert_id)
                LIMIT 1000
                """);
    }

    public List<ServiceNode> topology(long project) {
        access.requireRead(project);
        return jdbc.query("""
                SELECT n.environment,n.server_id,s.name AS node_name,s.agent_status,d.service,d.id AS desired_release,
                       da.sha256 AS desired_sha,d.status AS desired_status,h.id AS healthy_release,
                       ha.sha256 AS healthy_sha,h.reported_at AS last_healthy_at
                FROM project_nodes n JOIN servers s ON s.id=n.server_id AND s.deleted=0
                LEFT JOIN project_releases d ON d.project_id=n.project_id AND d.server_id=n.server_id AND d.environment=n.environment
                  AND NOT EXISTS (SELECT 1 FROM project_releases later WHERE later.project_id=d.project_id
                    AND later.server_id=d.server_id AND later.environment=d.environment AND later.service=d.service
                    AND later.generation>d.generation)
                LEFT JOIN project_artifacts da ON da.id=d.artifact_id AND da.project_id=n.project_id
                LEFT JOIN project_releases h ON h.project_id=n.project_id AND h.server_id=n.server_id AND h.environment=n.environment
                  AND h.service=d.service AND h.status='healthy' AND NOT EXISTS (
                    SELECT 1 FROM project_releases later WHERE later.project_id=h.project_id AND later.server_id=h.server_id
                    AND later.environment=h.environment AND later.service=h.service AND later.status='healthy' AND later.generation>h.generation)
                LEFT JOIN project_artifacts ha ON ha.id=h.artifact_id AND ha.project_id=n.project_id
                WHERE n.project_id=? ORDER BY n.environment,n.server_id,d.service
                """,new DataClassRowMapper<>(ServiceNode.class),project);
    }

    public List<Alert> alerts(long project, long before, int limit) {
        access.requireRead(project);
        if (before < 0 || limit < 1 || limit > 100) throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        return jdbc.query(ALERT+" AND (?=0 OR s.alert_id<?) ORDER BY s.alert_id DESC LIMIT ?",
                new DataClassRowMapper<>(Alert.class),project,before,before,limit);
    }

    private Alert alert(long project, long id) {
        return jdbc.query(ALERT+" AND s.alert_id=?",new DataClassRowMapper<>(Alert.class),project,id)
                .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    public Detail detail(long project, long id) {
        access.requireRead(project);
        Alert a = alert(project,id);
        // Alert timestamps have second precision. Include events in that same recorded second.
        LocalDateTime end = a.triggeredAt.plusSeconds(1);
        var nearby = jdbc.query(CHANGE+"""
                 AND ((r.claimed_at>=? AND r.claimed_at<?) OR (r.reported_at>=? AND r.reported_at<?))
                 ORDER BY matched_at DESC,r.generation DESC LIMIT 50
                """,new DataClassRowMapper<>(Change.class),end,project,a.serverId,a.environment,
                a.triggeredAt.minusMinutes(30),end,a.triggeredAt.minusMinutes(30),end);
        var healthy = jdbc.query(CHANGE+HEALTHY_AT_TIME+" ORDER BY r.service",
                new DataClassRowMapper<>(Change.class),end,project,a.serverId,a.environment,end,end);
        var actions = jdbc.query(ACTION+" AND h.alert_id=? ORDER BY h.created_at DESC,h.id DESC",
                new DataClassRowMapper<>(Action.class),project,id);
        return new Detail(a,nearby,healthy,actions);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Action act(long project, long alertId, OperationsController.Request req) {
        access.requireDeveloper(project);
        if (!"repair".equals(req.action())) access.requireOwner(project);
        boolean repair = "repair".equals(req.action());
        if (repair != (req.buildNode()!=null)) throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        if (jdbc.queryForList("SELECT alert_id FROM project_alert_scopes WHERE project_id=? AND alert_id=? FOR UPDATE",
                Long.class,project,alertId).isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        long actor = access.principal().id();
        String request = req.requestId().toString();
        String source = req.releaseId().toString();
        String reason = req.rationale().strip();
        var previous = jdbc.query(ACTION+" AND h.actor_id=? AND h.request_id=?",new DataClassRowMapper<>(Action.class),
                project,actor,request);
        if (!previous.isEmpty()) {
            Action row = previous.getFirst();
            if (row.alertId != alertId || !row.action.equals(req.action()) || !row.releaseId.equals(source)
                    || !Objects.equals(row.buildNode,req.buildNode()) || !row.rationale.equals(reason)) {
                throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
            }
            return row;
        }
        Detail detail = detail(project,alertId);
        Change change = detail.healthyAtAlert.stream().filter(c -> c.id.equals(source)).findFirst().orElse(null);
        if (change == null && "confirm_cause".equals(req.action())) {
            change = detail.nearby.stream().filter(c -> c.id.equals(source)).findFirst().orElse(null);
        }
        if (change == null) throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
        // Separate our idempotency namespace from requests made directly to the coding/release API.
        UUID childRequest = UUID.nameUUIDFromBytes(("cloide-alert-action/" + project + "/" + actor + "/" + request)
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        String result = switch (req.action()) {
            case "repair" -> {
                String prompt = "调查告警 " + alertId + "（" + detail.alert.metric + "），核实与发布 " + source
                        + " 的关系，为确认的缺陷添加回归测试并提出修复。操作者说明：" + reason;
                yield coding.create(project,new CodingController.Create(childRequest,req.buildNode(),change.repository,
                        change.commitSha,prompt,"build",false)).taskId();
            }
            case "rollback" -> releases.rollback(project,detail.alert.environment,source,new ReleaseService.Retry(childRequest)).id();
            case "confirm_cause" -> null;
            default -> throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        };
        String actionId = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                INSERT INTO project_alert_actions(id,alert_id,project_id,actor_id,request_id,action,release_id,
                        build_node,rationale,result_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """,actionId,alertId,project,actor,request,req.action(),source,req.buildNode(),reason,result,
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        } catch (DuplicateKeyException clash) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
        }
        return jdbc.queryForObject(ACTION+" AND h.id=?",new DataClassRowMapper<>(Action.class),project,actionId);
    }
}
