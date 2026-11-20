-- 服务端超时兜底扫描的支撑索引：按状态过滤仍在执行的作业并按创建时间判定超期。
CREATE INDEX idx_project_runs_sweep ON project_runs (status, created_at);
