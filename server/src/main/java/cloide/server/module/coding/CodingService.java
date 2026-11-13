package cloide.server.module.coding;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import cloide.server.module.project.service.ProjectAccess;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CodingService {
    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final ObjectMapper json;
    private final Clock clock;
    private static final String SELECT_TASK = """
            SELECT task_id,project_id,server_id,created_by,repository,base_sha,prompt,permission_mode,
                   plan_only,status,last_seq,approval_id,approval_decision,worker_id FROM project_coding_tasks WHERE
            """;
    private static final Set<String> ACTIVE = Set.of("running","awaiting_approval","cancelling");
    private static final Set<String> OUTCOMES = Set.of("ready_for_review","cancelled","failed","uncertain");

    public record Task(String taskId, long projectId, long serverId, long createdBy, String repository, String baseSha,
            String prompt, String mode, boolean plan, String status, int lastSeq, String approvalId, Boolean decision) {}
    public record Event(int seq, JsonNode event) {}
    private record Row(Task task,String worker) {}
    private static Row row(ResultSet rs, int ignored) throws SQLException {
        boolean allow = rs.getBoolean("approval_decision");
        Boolean decision = rs.wasNull() ? null : allow;
        return new Row(new Task(rs.getString("task_id"), rs.getLong("project_id"), rs.getLong("server_id"),
                rs.getLong("created_by"), rs.getString("repository"), rs.getString("base_sha"), rs.getString("prompt"),
                rs.getString("permission_mode"), rs.getBoolean("plan_only"), rs.getString("status"), rs.getInt("last_seq"),
                rs.getString("approval_id"), decision),rs.getString("worker_id"));
    }
    private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER); }
    private static BusinessException conflict() { return new BusinessException(ErrorCode.RESOURCE_CONFLICT); }
    private static BusinessException missing() { return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND); }
    private List<Row> rows(String where,Object... params) {
        return jdbc.query(SELECT_TASK + where,CodingService::row,params);
    }
    private Row lookup(long projectId, UUID id, boolean lock) {
        return rows("project_id=? AND task_id=?" + (lock ? " FOR UPDATE" : ""),projectId,id.toString())
                .stream().findFirst().orElseThrow(CodingService::missing);
    }
    private Row nodeRow(long node,UUID id) {
        return rows("server_id=? AND task_id=? FOR UPDATE",node,id.toString()).stream().findFirst()
                .orElseThrow(CodingService::missing);
    }

    @Transactional
    public Task create(long projectId,CodingController.Create body) {
        access.requireRun(projectId,body.serverId());
        long user = access.principal().id();
        String mode = body.mode() == null ? "build" : body.mode();
        jdbc.update("""
            INSERT INTO project_coding_tasks
            (task_id,request_id,project_id,server_id,created_by,repository,base_sha,prompt,permission_mode,plan_only,status)
            VALUES (?,?,?,?,?,?,?,?,?,?,'queued') ON DUPLICATE KEY UPDATE request_id=request_id
            """,UUID.randomUUID().toString(),body.requestId().toString(),projectId,body.serverId(),user,body.repository(),
                body.baseSha(),body.prompt(),mode,body.plan());
        Task saved = rows("project_id=? AND created_by=? AND request_id=?",projectId,user,body.requestId().toString()).get(0).task();
        if (saved.serverId()!=body.serverId() || !saved.repository().equals(body.repository()) ||
                !saved.baseSha().equals(body.baseSha()) || !saved.prompt().equals(body.prompt()) ||
                !saved.mode().equals(mode) || saved.plan()!=body.plan()) throw conflict();
        return saved;
    }
    public List<Task> list(long projectId) {
        access.requireRead(projectId);
        return rows("project_id=? ORDER BY created_at DESC,task_id DESC LIMIT 50",projectId).stream().map(Row::task).toList();
    }
    public Task get(long projectId,UUID id) {
        access.requireRead(projectId);
        return lookup(projectId,id,false).task();
    }
    public List<Event> events(long projectId,UUID id,int after) {
        get(projectId,id);
        return jdbc.query("SELECT seq,event_json FROM project_coding_events WHERE task_id=? AND seq>? ORDER BY seq LIMIT 64",
                (rs,i)->new Event(rs.getInt(1),parse(rs.getString(2))),id.toString(),after);
    }
    private JsonNode parse(String source) {
        try { return json.readTree(source); } catch (JsonProcessingException invalid) { throw new IllegalStateException(invalid); }
    }
    private Task manageable(long projectId,UUID id) {
        Task task = lookup(projectId,id,true).task();
        access.requireRun(projectId,task.serverId());
        if (task.createdBy()!=access.principal().id()) access.requireOwner(projectId);
        return task;
    }
    @Transactional
    public Task decide(long projectId,UUID id,CodingController.Decision decision) {
        Task t = manageable(projectId,id);
        if (!"awaiting_approval".equals(t.status()) || !decision.approvalId().toString().equals(t.approvalId())) throw conflict();
        if (t.decision()!=null && !decision.allow().equals(t.decision())) throw conflict();
        if (t.decision()==null) {
            jdbc.update("UPDATE project_coding_tasks SET approval_decision=?,decided_by=? WHERE task_id=?",
                    decision.allow(),access.principal().id(),t.taskId());
            jdbc.update("INSERT INTO project_coding_decisions(task_id,approval_id,actor_id,allowed) VALUES(?,?,?,?)",
                    t.taskId(),t.approvalId(),access.principal().id(),decision.allow());
        }
        return lookup(projectId,id,false).task();
    }
    @Transactional
    public Task cancel(long projectId,UUID id) {
        Task t=manageable(projectId,id);
        if ("queued".equals(t.status()) || ACTIVE.contains(t.status())) {
            jdbc.update("UPDATE project_coding_tasks SET status=? WHERE task_id=?",
                    "queued".equals(t.status()) ? "cancelled" : "cancelling",t.taskId());
        }
        return lookup(projectId,id,false).task();
    }

    @Scheduled(fixedDelay=30000)
    public void expireLeases() {
        // 缺少节点收尾证据时保留待核实状态，防止把有改动的任务再执行一遍。
        Instant now=clock.instant();
        jdbc.update("""
            UPDATE project_coding_tasks SET status='uncertain'
            WHERE status IN ('running','awaiting_approval','cancelling') AND last_seen < ?
            """,Timestamp.from(now.minusSeconds(90)));
        jdbc.update("UPDATE project_coding_tasks SET status='cancelled' WHERE status='queued' AND created_at<?",
                Timestamp.from(now.minus(Duration.ofMinutes(30))));
    }
    @Transactional
    public Map<String,Object> poll(long node,CodingNodeController.Poll p) {
        String worker=p.workerId().toString();
        if (p.taskId()!=null) {
            Row row=nodeRow(node,p.taskId());
            Task t=row.task();
            if (!worker.equals(row.worker()) || t.projectId()!=p.projectId() || !t.repository().equals(p.repository())) throw conflict();
            if (!ACTIVE.contains(t.status())) return Map.of("action","stop");
            boolean authorised=access.canRun(t.createdBy(),t.projectId(),node);
            jdbc.update("UPDATE project_coding_tasks SET last_seen=? WHERE task_id=?",Timestamp.from(clock.instant()),t.taskId());
            if (!authorised || "cancelling".equals(t.status())) {
                jdbc.update("UPDATE project_coding_tasks SET status='cancelling' WHERE task_id=?",t.taskId());
                return Map.of("action","cancel","revoked",!authorised,"lastSeq",t.lastSeq());
            }
            if (t.decision()!=null) return Map.of("action","decision","approvalId",t.approvalId(),"allow",t.decision());
            return Map.of("action","continue");
        }
        // 先恢复同进程已认领但响应丢失的派发。
        var active=rows("server_id=? AND project_id=? AND repository=? AND worker_id=? AND status='running' AND last_seq=0 LIMIT 1 FOR UPDATE",
                node,p.projectId(),p.repository(),worker);
        var next=active.isEmpty() ? rows("server_id=? AND project_id=? AND repository=? AND status='queued' ORDER BY created_at,task_id LIMIT 1 FOR UPDATE",
                node,p.projectId(),p.repository()) : active;
        if (next.isEmpty()) return Map.of("action","idle");
        Task task=next.get(0).task();
        if (!access.canRun(task.createdBy(),task.projectId(),node)) {
            jdbc.update("UPDATE project_coding_tasks SET status='cancelled' WHERE task_id=?",task.taskId());
            return Map.of("action","idle");
        }
        jdbc.update("UPDATE project_coding_tasks SET status='running',worker_id=?,last_seen=? WHERE task_id=?",
                worker,Timestamp.from(clock.instant()),task.taskId());
        return Map.of("action","start","taskId",task.taskId(),"baseSha",task.baseSha(),"prompt",task.prompt(),
                "mode",task.mode(),"plan",task.plan());
    }

    @Transactional
    public int record(long node,CodingNodeController.Receipt body) {
        Row row=nodeRow(node,body.taskId());
        Task t=row.task();
        if (!Objects.equals(row.worker(),body.workerId().toString())) throw conflict();
        JsonNode event=body.event();
        if (!event.isObject()) throw invalid();
        if (body.seq()<=t.lastSeq()) {
            var previous=jdbc.queryForObject("SELECT event_json FROM project_coding_events WHERE task_id=? AND seq=?",
                    String.class,t.taskId(),body.seq());
            if (!parse(previous).equals(event)) throw conflict();
            return body.seq();
        }
        if (!ACTIVE.contains(t.status()) || t.lastSeq()+1!=body.seq()) throw conflict();
        String type=event.path("type").asText();
        String state=t.status();
        String approval=t.approvalId();
        Boolean decision=t.decision();
        if (!access.canRun(t.createdBy(),t.projectId(),node)) {
            if (!"finished".equals(type) || !"cancelled".equals(event.path("status").asText())) throw conflict();
        }
        switch (type) {
            case "started" -> { if (body.seq()!=1) throw conflict(); }
            case "text" -> requiredText(event,"text",16000);
            case "change" -> {
                requiredText(event,"path",240);
                if (!requiredText(event,"beforeHash",64).matches("missing|[a-f0-9]{64}") ||
                        !digest(requiredText(event,"content",65536)).equals(requiredText(event,"afterHash",64))) throw invalid();
                if (t.plan()) throw conflict();
                if ("build".equals(t.mode()) && !Boolean.TRUE.equals(decision)) throw conflict();
                if (approval!=null) {
                    String saved=jdbc.queryForObject("""
                        SELECT e.event_json FROM project_coding_events e JOIN project_coding_tasks t
                        ON t.task_id=e.task_id AND t.approval_seq=e.seq WHERE t.task_id=?
                        """,String.class,t.taskId());
                    JsonNode original=parse(saved);
                    if (!Boolean.TRUE.equals(decision) || !List.of("path","beforeHash","afterHash").stream()
                            .allMatch(field->original.path(field).equals(event.path(field)))) throw conflict();
                }
                if (!"cancelling".equals(state)) state="running";
                approval=null; decision=null;
            }
            case "declined" -> {
                if (!Boolean.FALSE.equals(decision) || !Objects.equals(approval,event.path("approvalId").asText())) throw conflict();
                if (!"cancelling".equals(state)) state="running";
                approval=null; decision=null;
            }
            case "approval" -> {
                if (t.plan() || "cancelling".equals(state) || (approval!=null && decision==null)) throw conflict();
                approval=requiredText(event,"approvalId",36);
                try { if (!UUID.fromString(approval).toString().equals(approval)) throw invalid(); }
                catch (IllegalArgumentException bad) { throw invalid(); }
                requiredText(event,"path",240);
                String content=requiredText(event,"content",65536);
                if (!requiredText(event,"beforeHash",64).matches("missing|[a-f0-9]{64}") ||
                        !digest(content).equals(requiredText(event,"afterHash",64))) throw invalid();
                jdbc.update("UPDATE project_coding_tasks SET approval_seq=? WHERE task_id=?",body.seq(),t.taskId());
                state="awaiting_approval"; decision=null;
            }
            case "patch" -> requiredText(event,"text",5*1024*1024);
            case "finished" -> {
                state=requiredText(event,"status",24);
                if (!OUTCOMES.contains(state)) throw invalid();
                approval=null; decision=null;
            }
            default -> throw invalid();
        }
        String encoded=event.toString();
        if (encoded.length()> ("patch".equals(type) ? 6*1024*1024 : 400000)) throw invalid();
        jdbc.update("INSERT INTO project_coding_events(task_id,seq,event_json) VALUES(?,?,?)",t.taskId(),body.seq(),encoded);
        jdbc.update("UPDATE project_coding_tasks SET status=?,last_seq=?,approval_id=?,approval_decision=? WHERE task_id=?",
                state,body.seq(),approval,decision,t.taskId());
        return body.seq();
    }
    private String requiredText(JsonNode event,String field,int limit) {
        JsonNode value=event.path(field);
        if (!value.isTextual() || value.textValue().length()>limit) throw invalid();
        return value.textValue();
    }
    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException missingAlgorithm) { throw new IllegalStateException(missingAlgorithm); }
    }
}
