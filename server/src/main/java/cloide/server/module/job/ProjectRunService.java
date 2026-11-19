package cloide.server.module.job;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import cloide.server.module.project.service.ProjectAccess;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProjectRunService {
    private final ProjectRunStore store;
    private final ProjectAccess access;
    private final Clock clock;
    private final ObjectProvider<RunWakeupEvents> wakeups;

    @Transactional
    public ProjectRun create(long projectId, long userId, RunRequest request) {
        access.requireRun(projectId, request.serverId());
        String newId = UUID.randomUUID().toString();
        ProjectRun run = store.insertOrRead(newId, projectId, userId, request,
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        if (run.serverId() != request.serverId() || !run.repository().equals(request.repository())
                || !run.commitSha().equals(request.commitSha())) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
        }
        if (newId.equals(run.jobId())) {
            wakeups.ifAvailable(events -> events.enqueue(run));
        }
        return run;
    }

    public List<ProjectRun> list(long projectId) {
        access.requireRead(projectId);
        return store.list(projectId);
    }

    public ProjectRun read(long projectId, String jobId) {
        access.requireRead(projectId);
        return store.read(projectId, jobId);
    }

    public List<ProjectRunStore.LogLine> logs(long projectId, String jobId, int after) {
        read(projectId, jobId);
        return store.logs(jobId, after);
    }

    @Transactional
    public ProjectRun cancel(long projectId, String jobId) {
        access.requireRead(projectId);
        ProjectRun run = store.lockForProject(projectId, jobId);
        access.requireRun(projectId, run.serverId());
        if (Set.of("queued", "dispatched", "running").contains(run.status())) {
            store.status(jobId, run.status().equals("queued") ? "cancelled" : "cancelling");
            wakeups.ifAvailable(events -> events.enqueue(run));
        }
        return store.read(projectId, jobId);
    }

    public record Dispatch(String type, Map<String, Object> payload) { }

    @Transactional
    public Optional<Dispatch> next(long serverId) {
        Optional<ProjectRun> next = store.lockNext(serverId);
        if (next.isEmpty()) {
            return Optional.empty();
        }
        ProjectRun run = next.get();
        if (run.status().equals("cancelling")) {
            return Optional.of(new Dispatch("job.cancel", Map.of("job_id", run.jobId())));
        }
        var deadline = run.createdAt().plusMinutes(30).toInstant();
        if (!deadline.isAfter(clock.instant()) || !access.canRun(run.createdBy(), run.projectId(), serverId)) {
            // 从未派发的作业可立即取消；曾派发的只能请求节点核实最终效果。
            if (run.status().equals("queued")) {
                store.status(run.jobId(), "cancelled");
                return Optional.empty();
            }
            store.status(run.jobId(), "cancelling");
            return Optional.of(new Dispatch("job.cancel", Map.of("job_id", run.jobId())));
        }
        if (run.status().equals("queued")) {
            store.status(run.jobId(), "dispatched");
        }
        return Optional.of(new Dispatch("job.execute", Map.of(
                "job_id", run.jobId(), "project_id", run.projectId(),
                "repository", run.repository(), "commit_sha", run.commitSha(), "deadline", deadline.toString())));
    }

    @Transactional
    public void accepted(long serverId, String jobId) {
        ProjectRun run = store.lockForNode(serverId, jobId);
        if (run.status().equals("dispatched")) {
            store.status(jobId, "running");
        } else if (run.status().equals("queued")) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
        }
    }

    @Transactional
    public void log(long serverId, String jobId, int seq, String text) {
        if (seq < 1 || seq > 256 || text == null || text.length() > 4096) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        ProjectRun run = store.lockForNode(serverId, jobId);
        if (Set.of("dispatched", "running", "cancelling").contains(run.status())) {
            store.append(jobId, seq, text);
        }
    }

    /**
     * 超时终态兜底：deadline（创建后 30 分钟）加 5 分钟宽限后仍未收到节点回执的作业落
     * uncertain。节点永久离线时这是 dispatched/running/cancelling 唯一的收敛路径
     * （next() 依赖目标节点的活会话）；节点回归后 finish() 仍可按真实回执覆盖核实。
     */
    @Scheduled(fixedDelay=30000)
    public void expireStaleRuns() {
        store.expireStale(LocalDateTime.ofInstant(clock.instant().minus(Duration.ofMinutes(35)), ZoneOffset.UTC));
    }

    @Transactional
    public void finish(long serverId, String jobId, String status, int exitCode, String output) {
        if (status == null || !ProjectRun.TERMINAL.contains(status) || output == null || output.length() > 8192
                || ("succeeded".equals(status) && exitCode != 0) || ("failed".equals(status) && exitCode == 0)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        ProjectRun run = store.lockForNode(serverId, jobId);
        if (run.status().equals("uncertain")) {
            // 服务端超时兜底标记的 uncertain 允许被节点真实回执覆盖核实（含 uncertain 回执补全明细）。
            store.complete(jobId, status, exitCode, output);
            return;
        }
        if (ProjectRun.TERMINAL.contains(run.status())) {
            if (!run.status().equals(status) || !Objects.equals(run.exitCode(), exitCode)
                    || !Objects.equals(run.output(), output)) {
                throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
            }
            return;
        }
        if (run.status().equals("queued")) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
        }
        store.complete(jobId, status, exitCode, output);
    }
}
