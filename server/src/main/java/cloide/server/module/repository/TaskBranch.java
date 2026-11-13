package cloide.server.module.repository;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import cloide.server.module.coding.CodingService;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 仅将已记录、可核验的文件变更写入新的任务引用。 */
@Component
@RequiredArgsConstructor
public class TaskBranch {
    private final GitHubApp app;
    private final CodingService coding;
    record Change(String before,String after,String contents) { }
    record Pull(long number,String sha,String state,String updated) { }

    Pull publish(long project, RepositoryService.Repository repo, UUID taskId) {
        var task=coding.get(project,taskId);
        if (!"ready_for_review".equals(task.status()) || task.plan() || !repo.repoName().equals(task.repository())) throw conflict();
        String token=app.token(Map.of("contents","write","pull_requests","write"),repo.remoteId(),null);
        String base="/repos/"+repo.ownerName()+"/"+repo.repoName();
        var info=app.call("GET",base,token,null,200);
        if (info.path("id").asLong()!=repo.remoteId()) throw conflict();
        String branch=info.path("default_branch").asText();
        String target="cloide/task-"+taskId;
        var changes=changes(project,taskId);
        if (changes.isEmpty()) throw conflict();
        var original=app.call("GET",base+"/git/commits/"+task.baseSha(),token,null,200);
        String baseTree=sha(original.path("tree"));
        Map<String,JsonNode> trees=new HashMap<>();
        var entries=new ArrayList<Map<String,String>>();
        for (var item:changes.entrySet()) {
            String path=item.getKey();
            var change=item.getValue();
            var entry=entry(base,token,baseTree,path,trees);
            String mode="100644";
            if (entry!=null) {
                mode=entry.path("mode").asText();
                if (!Set.of("100644","100755").contains(mode) || !"blob".equals(entry.path("type").asText())) throw conflict();
                var blob=app.call("GET",base+"/git/blobs/"+sha(entry),token,null,200);
                if (!"base64".equals(blob.path("encoding").asText()) || blob.path("size").asLong(-1)>65536) throw conflict();
                var bytes=Base64.getMimeDecoder().decode(blob.path("content").asText());
                if (!GitHubApp.sha(bytes).equals(change.before())) throw conflict();
            } else if (!"missing".equals(change.before())) throw conflict();
            entries.add(Map.of("path",path,"type","blob","mode",mode,"content",change.contents()));
        }
        var tree=app.call("POST",base+"/git/trees",token,Map.of("base_tree",baseTree,"tree",entries),201);
        String treeSha=sha(tree);
        if (treeSha.equals(baseTree)) throw conflict();
        var ref=app.call("GET",base+"/git/ref/heads/"+target,token,null,200,404);
        String head;
        if (ref.isNull()) {
            var latest=app.call("GET",base+"/git/ref/heads/"+branchPath(branch),token,null,200);
            if (!task.baseSha().equals(sha(latest.path("object")))) throw conflict();
            var commit=app.call("POST",base+"/git/commits",token,Map.of("tree",treeSha,"parents",List.of(task.baseSha()),
                    "message","Cloide task "+taskId),201);
            head=sha(commit);
            app.call("POST",base+"/git/refs",token,Map.of("ref","refs/heads/"+target,"sha",head),201);
        } else {
            head=sha(ref.path("object"));
            var old=app.call("GET",base+"/git/commits/"+head,token,null,200);
            if (!treeSha.equals(sha(old.path("tree"))) || old.path("parents").size()!=1
                    || !task.baseSha().equals(sha(old.path("parents").get(0)))) throw conflict();
        }
        var prior=app.call("GET",base+"/pulls?state=all&base="+enc(branch)+"&head="+enc(repo.ownerName()+":"+target),token,null,200);
        var pull=prior.isEmpty()?app.call("POST",base+"/pulls",token,Map.of("base",branch,"head",target,
                "title","工程任务 "+taskId,"body","Cloide 工程任务 "+taskId+"，基准提交 "+task.baseSha()),201):prior.get(0);
        if (!head.equals(sha(pull.path("head"))) || !branch.equals(pull.path("base").path("ref").asText())
                || pull.path("number").asLong()<=0) throw conflict();
        return new Pull(pull.path("number").asLong(),head,pull.path("merged_at").isTextual()?"merged":pull.path("state").asText(),
                pull.path("updated_at").asText());
    }

    private Map<String,Change> changes(long project,UUID id) {
        var files=new LinkedHashMap<String,Change>();
        int cursor=0;
        while (true) {
            var events=coding.events(project,id,cursor);
            for (var record:events) {
                cursor=record.seq();
                var event=record.event();
                if (!"change".equals(event.path("type").asText())) continue;
                String path=event.path("path").asText();
                if (path.length()>240 || !path.matches("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*")
                        || Arrays.stream(path.split("/")).anyMatch(part->Set.of(".","..",".git",".github").contains(part.toLowerCase(Locale.ROOT)))) throw conflict();
                String before=event.path("beforeHash").asText(), after=event.path("afterHash").asText();
                if (!event.path("content").isTextual()) throw conflict();
                String content=event.get("content").textValue();
                byte[] bytes=content.getBytes(StandardCharsets.UTF_8);
                if (bytes.length>65536 || !after.equals(GitHubApp.sha(bytes))) throw conflict();
                var old=files.get(path);
                if (old!=null && !before.equals(old.after())) throw conflict();
                files.put(path,new Change(old==null?before:old.before(),after,content));
                if (files.size()>32) throw conflict();
            }
            if (events.size()<64) return files;
        }
    }

    private JsonNode entry(String base,String token,String tree,String path,Map<String,JsonNode> cache) {
        String[] parts=path.split("/");
        for (int i=0;i<parts.length;i++) {
            var list=cache.computeIfAbsent(tree,key->app.call("GET",base+"/git/trees/"+key,token,null,200));
            if (list.path("truncated").asBoolean()) throw conflict();
            JsonNode found=null;
            for (var node:list.path("tree")) if (parts[i].equals(node.path("path").asText())) {found=node;break;}
            if (found==null || i==parts.length-1) return found;
            if (!"tree".equals(found.path("type").asText())) throw conflict();
            tree=sha(found);
        }
        throw conflict();
    }
    private static String sha(JsonNode node) {
        String result=node.path("sha").asText();
        if (!result.matches("[0-9a-f]{40}")) throw conflict();
        return result;
    }
    private static String enc(String value) {return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");}
    private static String branchPath(String value) {
        if (value.isBlank() || Arrays.stream(value.split("/",-1)).anyMatch(v -> Set.of(".","..","").contains(v))) throw conflict();
        return String.join("/",Arrays.stream(value.split("/")).map(TaskBranch::enc).toList());
    }
    static BusinessException conflict() {return new BusinessException(ErrorCode.RESOURCE_CONFLICT);}
}
