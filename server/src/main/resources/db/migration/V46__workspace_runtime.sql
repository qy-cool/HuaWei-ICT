ALTER TABLE workspaces
    ADD COLUMN managed BOOLEAN NOT NULL DEFAULT FALSE COMMENT '由固定节点管理器实际运行';
ALTER TABLE workspaces
    ADD COLUMN desired_state VARCHAR(12) NOT NULL DEFAULT 'sleeping' COMMENT '期望运行态';
ALTER TABLE workspaces
    ADD COLUMN runtime_generation BIGINT NOT NULL DEFAULT 1 COMMENT '期望状态代次';
ALTER TABLE workspaces
    ADD COLUMN observed_state VARCHAR(12) NOT NULL DEFAULT 'unknown' COMMENT '节点最近观测';
ALTER TABLE workspaces
    ADD COLUMN observed_generation BIGINT NOT NULL DEFAULT 0 COMMENT '节点已核实的代次';
ALTER TABLE workspaces
    ADD COLUMN observed_at BIGINT NOT NULL DEFAULT 0 COMMENT '最近有效回执 UTC 秒';
