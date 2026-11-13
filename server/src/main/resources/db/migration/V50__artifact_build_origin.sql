ALTER TABLE project_artifacts ADD COLUMN source_node_id BIGINT UNSIGNED NULL COMMENT '已认证的实际构建节点，人工上传为空';
