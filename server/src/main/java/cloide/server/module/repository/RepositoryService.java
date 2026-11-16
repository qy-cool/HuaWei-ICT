package cloide.server.module.repository;

import cloide.server.common.*;
import cloide.server.module.coding.CodingService;
import cloide.server.module.project.service.ProjectAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RepositoryService {
    public record Repository(long projectId,long remoteId,String ownerName,String repoName,String defaultBranch,
            long installationId,long linkedBy) { }
    public record Pull(long remoteId,String taskId,long prNumber,String headSha,String baseSha,String prState,long requestedBy) { }
    /** 当前活动绑定：来源策略与快照服务据此校验项目归属与代次。 */
    public record ActiveBinding(long projectId,long remoteId,String ownerName,String repoName,String defaultBranch,
            long installationId,long generation) { }
    private static final DataClassRowMapper<Repository> REPO=new DataClassRowMapper<>(Repository.class);
    private static final DataClassRowMapper<Pull> PR=new DataClassRowMapper<>(Pull.class);
    private static final DataClassRowMapper<ActiveBinding> BINDING=new DataClassRowMapper<>(ActiveBinding.class);
    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final GitHubApp app;
    private final TaskBranch branches;
    private final CodingService coding;
    private final ObjectMapper json;

    public List<Repository> list(long project) {
        access.requireRead(project);
        return jdbc.query("SELECT * FROM project_github_repositories WHERE project_id=? ORDER BY repo_name",REPO,project);
    }
    @Transactional
    public Repository connect(long project,String name, UUID newRequest) {
        access.requireAdmin();
        access.requireRead(project);
        String owner=app.owner();
        GitHubApp.checkedName(name);
        String path="/repos/"+owner+"/"+name;
        boolean create=newRequest!=null;
        String token=app.token(create?Map.of("administration","write","contents","read"):Map.of("metadata","read"),
                null,create?null:name);
        JsonNode info=app.call("GET",path,token,null,200,404);
        String marker="Cloide project "+project+" creation "+newRequest;
        if (create && info.isNull()) {
            info=app.call("POST","/repos/"+app.template()+"/generate",token, Map.of("name",name,"owner",owner,
                    "private",true,"description",marker,"include_all_branches",false),201);
        }
        if (info.isNull()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        if ((create && (!marker.equals(info.path("description").asText()) || !info.path("private").asBoolean()))
                || !owner.equalsIgnoreCase(info.path("owner").path("login").asText()) || !name.equalsIgnoreCase(info.path("name").asText())
                || info.path("id").asLong()<=0 || info.path("default_branch").asText().isBlank()) throw TaskBranch.conflict();
        long id=info.get("id").asLong();
        String ownerName=info.get("owner").get("login").asText(), repoName=info.get("name").asText(),
                defaultBranch=info.get("default_branch").asText();
        var existing=jdbc.query("SELECT * FROM project_github_repositories WHERE remote_id=?",REPO,id);
        if (existing.isEmpty()) {
            // 首次绑定或解绑后重绑：V44 活动行 + 新的绑定代次（generation 单调递增）。
            jdbc.update("""
                    INSERT INTO project_github_repositories(project_id,remote_id,owner_name,repo_name,default_branch,installation_id,linked_by)
                    VALUES (?,?,?,?,?,?,?)
                    """,project,id,ownerName,repoName,defaultBranch,app.installation(),access.principal().id());
            jdbc.update("""
                    INSERT INTO project_repository_bindings(project_id,remote_id,owner_name,repo_name,default_branch,installation_id,generation,active,linked_by)
                    SELECT ?,?,?,?,?,?,COALESCE(MAX(generation),0)+1,1,? FROM project_repository_bindings WHERE remote_id=?
                    """,project,id,ownerName,repoName,defaultBranch,app.installation(),access.principal().id(),id);
        } else {
            Repository saved=existing.getFirst();
            if (saved.projectId()!=project) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            if (!saved.ownerName().equals(ownerName) || !saved.repoName().equals(repoName)) {
                // 仓库改名：按稳定 remote_id 核对后同步活动行与当前代次的名称。
                jdbc.update("UPDATE project_github_repositories SET owner_name=?,repo_name=? WHERE remote_id=?",ownerName,repoName,id);
                jdbc.update("UPDATE project_repository_bindings SET owner_name=?,repo_name=? WHERE remote_id=? AND active=1",ownerName,repoName,id);
            }
        }
        return find(project,id,false);
    }
    @Transactional
    public void unlink(long project,long id) {
        access.requireAdmin(); access.requireRead(project);
        find(project,id,true);
        // 撤销：V44 活动行删除（旧客户端语义不变），绑定历史置 NULL 并记录撤销人，快照保留审计。
        jdbc.update("""
                UPDATE project_repository_bindings SET active=NULL,unlinked_at=CURRENT_TIMESTAMP(6),unlinked_by=?
                WHERE remote_id=? AND active=1
                """,access.principal().id(),id);
        jdbc.update("DELETE FROM project_github_repositories WHERE project_id=? AND remote_id=?",project,id);
    }
    /** 查询当前活动绑定；仓库未绑定或属于其他项目时抛 404，不区分两种情况以免泄露其他项目存在性。 */
    public ActiveBinding activeBinding(long project,long remoteId) {
        return jdbc.query("""
                SELECT project_id,remote_id,owner_name,repo_name,default_branch,installation_id,generation
                FROM project_repository_bindings WHERE remote_id=? AND active=1 AND project_id=?
                """,BINDING,remoteId,project).stream().findFirst()
                .orElseThrow(()->new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }
    private Repository find(long project,long id,boolean lock) {
        return jdbc.query("SELECT * FROM project_github_repositories WHERE project_id=? AND remote_id=?"+(lock?" FOR UPDATE":""),
                REPO,project,id).stream().findFirst().orElseThrow(()->new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }
    public List<Pull> pulls(long project,long id) {
        access.requireRead(project);
        find(project,id,false);
        return jdbc.query("""
                SELECT * FROM project_github_pulls WHERE project_id=? AND remote_id=? ORDER BY pr_number DESC LIMIT 100
                """,PR,project,id);
    }
    @Transactional(timeout=120)
    public Pull publish(long project,long id,UUID taskId) {
        access.requireDeveloper(project);
        var repo=find(project,id,true);
        if (repo.installationId()!=app.installation()) throw TaskBranch.conflict();
        var task=coding.get(project,taskId);
        if (task.createdBy()!=access.principal().id()) access.requireOwner(project);
        var saved=jdbc.query("SELECT * FROM project_github_pulls WHERE project_id=? AND remote_id=? AND task_id=?",
                PR,project,id,taskId.toString());
        if (!saved.isEmpty()) return saved.getFirst();
        var result=branches.publish(project,repo,taskId);
        jdbc.update("""
                INSERT INTO project_github_pulls(project_id,remote_id,task_id,pr_number,head_sha,base_sha,pr_state,remote_updated,requested_by)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,project,id,taskId.toString(),result.number(),result.sha(),task.baseSha(),result.state(),result.updated(),access.principal().id());
        return new Pull(id,taskId.toString(),result.number(),result.sha(),task.baseSha(),result.state(),access.principal().id());
    }

    @Transactional
    public void webhook(UUID delivery,String type,String signature,byte[] raw) throws IOException {
        app.verify(raw,signature);
        if (type==null || !type.matches("[a-z_]{1,40}")) throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        JsonNode data=json.readTree(raw);
        if (data==null || !data.isObject()) throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        String digest=GitHubApp.sha(raw), nonce=UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO github_deliveries(delivery_id,event_type,body_sha,receipt_nonce) VALUES (?,?,?,?)
                ON DUPLICATE KEY UPDATE delivery_id=delivery_id
                """,delivery.toString(),type,digest,nonce);
        var row=jdbc.queryForMap("SELECT event_type,body_sha,receipt_nonce FROM github_deliveries WHERE delivery_id=? FOR UPDATE",delivery.toString());
        if (!digest.equals(row.get("body_sha")) || !type.equals(row.get("event_type"))) throw TaskBranch.conflict();
        if (!nonce.equals(row.get("receipt_nonce")) || app.installation()<=0 || data.path("installation").path("id").asLong()!=app.installation()) return;
        long id=data.path("repository").path("id").asLong();
        String action=data.path("action").asText();
        if ("pull_request".equals(type) && id>0) {
            var p=data.path("pull_request");
            String state=p.path("merged").asBoolean()?"merged":p.path("state").asText();
            String updated=p.path("updated_at").asText();
            try {java.time.Instant.parse(updated);} catch(java.time.DateTimeException bad) {throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);}
            if (!Set.of("open","closed","merged").contains(state)) throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
            jdbc.update("""
                    UPDATE project_github_pulls SET pr_state=?,remote_updated=? WHERE remote_id=? AND pr_number=?
                    AND remote_updated<=? AND pr_state<>'merged'
                    """,state,updated,id,p.path("number").asLong(),updated);
        }
        if ("installation_repositories".equals(type) && "removed".equals(action)) {
            for (var removed:data.path("repositories_removed")) revoke(removed.path("id").asLong());
        }
        if ("installation".equals(type) && Set.of("deleted","suspend").contains(action)) {
            for (var bound : jdbc.queryForList("SELECT remote_id FROM project_github_repositories WHERE installation_id=?",app.installation()))
                revoke((Long)bound.get("remote_id"));
        }
        if ("repository".equals(type) && "deleted".equals(action)) {
            revoke(id);
        }
    }
    /** GitHub 侧撤销：软删绑定历史（保留快照审计），并移除 V44 活动行；unlinked_by 记 0 表示系统撤销。 */
    private void revoke(long remoteId) {
        jdbc.update("""
                UPDATE project_repository_bindings SET active=NULL,unlinked_at=CURRENT_TIMESTAMP(6),unlinked_by=0
                WHERE remote_id=? AND active=1
                """,remoteId);
        jdbc.update("DELETE FROM project_github_repositories WHERE remote_id=? AND installation_id=?",remoteId,app.installation());
    }
}
