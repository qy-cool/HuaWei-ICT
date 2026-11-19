package cloide.server.module.project.service;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class NodeCapabilities {
    public record Snapshot(String os,String arch,int cpus,Long diskFree,Long memoryAvailable,List<String> toolchains,
                           List<String> repositories,LocalDateTime reportedAt) {}
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final ProjectAccess access;

    public NodeCapabilities(JdbcTemplate db, ObjectMapper json, ProjectAccess access) {
        this.db=db; this.json=json; this.access=access;
    }

    public void record(long server, JsonNode data) {
        var labels = data.path("toolchains");
        var repos = data.path("repositories");
        var actions = data.path("actions");
        if (!validLabel(data.path("os")) || !validLabel(data.path("arch")) || !data.path("cpus").isInt()
                || data.path("cpus").intValue() < 1 || !labels.isArray() || labels.size()>32
                || !repos.isArray() || repos.size()>128) throw invalid();
        for (var label : labels) if (!validLabel(label)) throw invalid();
        // actions 为可选能力数组（如 source.sync.v1）；出现时每项按同一标签规则校验。
        if (!actions.isMissingNode() && !actions.isNull()) {
            if (!actions.isArray() || actions.size()>16) throw invalid();
            for (var action : actions) if (!validLabel(action)) throw invalid();
        }
        for (var repo : repos) {
            var id = repo.path("project_id");
            if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue()<1 || !validLabel(repo.path("name"))) throw invalid();
        }
        size(data.get("disk_free"));
        size(data.get("memory_available"));
        String encoded = data.toString();
        db.update("""
                INSERT INTO node_job_capabilities(server_id,details_json) VALUES (?,?)
                ON DUPLICATE KEY UPDATE details_json=?, reported_at=CURRENT_TIMESTAMP(6)
                """,server,encoded,encoded);
    }

    /** 目标节点是否声明了指定动作能力（如 source.sync.v1）；未上报能力的节点按不支持处理。 */
    public boolean supportsAction(long project, long server, String action) {
        access.requireRead(project);
        String encoded = db.query("""
                SELECT c.details_json FROM project_nodes n JOIN servers s ON s.id=n.server_id AND s.deleted=0
                LEFT JOIN node_job_capabilities c ON c.server_id=n.server_id
                WHERE n.project_id=? AND n.server_id=?
                """,(rs,i)->rs.getString(1),project,server).stream().findFirst()
                .orElseThrow(()->new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (encoded==null) return false;
        try {
            for (var item : json.readTree(encoded).path("actions")) {
                if (action.equals(item.textValue())) return true;
            }
            return false;
        } catch(JsonProcessingException e) { throw new IllegalStateException("无效的节点能力记录",e); }
    }
    public Snapshot get(long project,long server) {
        access.requireRead(project);
        return db.query("""
                SELECT c.details_json,c.reported_at FROM project_nodes n JOIN servers s ON s.id=n.server_id AND s.deleted=0
                LEFT JOIN node_job_capabilities c ON c.server_id=n.server_id WHERE n.project_id=? AND n.server_id=?
                """,(rs,i)-> {
                    String encoded = rs.getString(1);
                    if (encoded==null) return new Snapshot(null,null,0,null,null,List.of(),List.of(),null);
                    try {
                        var data=json.readTree(encoded);
                        List<String> labels=new ArrayList<>(), repositories=new ArrayList<>();
                        for(var label:data.path("toolchains")) labels.add(label.textValue());
                        for(var repo:data.path("repositories")) {
                            if(repo.path("project_id").longValue()==project) repositories.add(repo.path("name").textValue());
                        }
                        return new Snapshot(data.path("os").textValue(),data.path("arch").textValue(),data.path("cpus").intValue(),
                                size(data.get("disk_free")),size(data.get("memory_available")),labels,repositories,rs.getTimestamp(2).toLocalDateTime());
                    } catch(JsonProcessingException e) { throw new IllegalStateException("无效的节点能力记录",e); }
                },project,server).stream().findFirst().orElseThrow(()->new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }
    private static Long size(JsonNode node) {
        if (node==null || node.isNull()) return null;
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue()<0) throw invalid();
        return node.longValue();
    }
    private static boolean validLabel(JsonNode node) {
        return node.isTextual() && node.textValue().matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}");
    }
    private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER); }
}
