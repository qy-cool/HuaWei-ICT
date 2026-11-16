package cloide.server.module.job;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class ProjectRunStore {
    private final JdbcTemplate jdbc;
    private static final String COLUMNS = """
            SELECT job_id, project_id, server_id, created_by, repository, commit_sha,
                   status, exit_code, output, created_at FROM project_runs
            """;
    private static final RowMapper<ProjectRun> ROW = (rs, row) -> new ProjectRun(
            rs.getString("job_id"), rs.getLong("project_id"), rs.getLong("server_id"),
            rs.getLong("created_by"), rs.getString("repository"), rs.getString("commit_sha"),
            rs.getString("status"), rs.getObject("exit_code", Integer.class), rs.getString("output"),
            rs.getObject("created_at", LocalDateTime.class).atOffset(ZoneOffset.UTC));

    ProjectRun insertOrRead(String jobId, long projectId, long operatorId, RunRequest request, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO project_runs
                  (job_id, request_id, project_id, created_by, server_id, repository, commit_sha, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'queued', ?) ON DUPLICATE KEY UPDATE id = id
                """, jobId, request.requestId().toString(), projectId, operatorId, request.serverId(),
                request.repository(), request.commitSha(), now);
        return jdbc.queryForObject(COLUMNS + " WHERE project_id = ? AND created_by = ? AND request_id = ? FOR UPDATE",
                ROW, projectId, operatorId, request.requestId().toString());
    }

    List<ProjectRun> list(long projectId) {
        return jdbc.query(COLUMNS + " WHERE project_id = ? ORDER BY id DESC LIMIT 50", ROW, projectId);
    }

    ProjectRun read(long projectId, String jobId) {
        return required(jdbc.query(COLUMNS + " WHERE project_id = ? AND job_id = ?", ROW, projectId, jobId));
    }

    ProjectRun lockForProject(long projectId, String jobId) {
        return required(jdbc.query(COLUMNS + " WHERE project_id = ? AND job_id = ? FOR UPDATE", ROW, projectId, jobId));
    }

    ProjectRun lockForNode(long serverId, String jobId) {
        return required(jdbc.query(COLUMNS + " WHERE server_id = ? AND job_id = ? FOR UPDATE", ROW, serverId, jobId));
    }

    Optional<ProjectRun> lockNext(long serverId) {
        // 一个节点串行执行。尚未确认的旧作业先于更新的排队作业，重发沿用原 job_id。
        return jdbc.query(COLUMNS + """
                WHERE server_id = ? AND status IN ('queued','dispatched','running','cancelling')
                ORDER BY id LIMIT 1 FOR UPDATE
                """, ROW, serverId).stream().findFirst();
    }

    void status(String jobId, String status) {
        jdbc.update("UPDATE project_runs SET status = ? WHERE job_id = ?", status, jobId);
    }

    void complete(String jobId, String status, int exitCode, String output) {
        jdbc.update("UPDATE project_runs SET status = ?, exit_code = ?, output = ? WHERE job_id = ?",
                status, exitCode, output, jobId);
    }

    void expireStale(LocalDateTime before) {
        // 服务端超时兜底：宽限期后仍未收敛的活跃作业转 uncertain 终态，节点回归后仍可被真实回执覆盖核实。
        jdbc.update("UPDATE project_runs SET status = 'uncertain' WHERE status IN ('dispatched','running','cancelling') AND created_at < ?",
                before);
    }

    public record LogLine(int seq, String text) { }

    List<LogLine> logs(String jobId, int after) {
        return jdbc.query("""
                SELECT seq, log_text FROM project_run_logs WHERE job_id = ? AND seq > ? ORDER BY seq LIMIT 100
                """, (rs, n) -> new LogLine(rs.getInt("seq"), rs.getString("log_text")), jobId, after);
    }

    void append(String jobId, int seq, String text) {
        jdbc.update("INSERT INTO project_run_logs (job_id, seq, log_text) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE seq = seq",
                jobId, seq, text);
    }

    private static ProjectRun required(List<ProjectRun> rows) {
        return rows.stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }
}
