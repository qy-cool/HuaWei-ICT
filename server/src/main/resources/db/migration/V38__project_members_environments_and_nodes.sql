CREATE TABLE projects (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '项目 ID',
    name VARCHAR(100) NOT NULL COMMENT '项目名称',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工程项目';

CREATE TABLE project_members (
    project_id BIGINT UNSIGNED NOT NULL COMMENT '所属项目',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '成员用户',
    member_role VARCHAR(20) NOT NULL COMMENT 'owner、developer 或 viewer',
    PRIMARY KEY (project_id, user_id),
    KEY idx_project_members_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目授权成员';

CREATE TABLE project_environments (
    project_id BIGINT UNSIGNED NOT NULL COMMENT '所属项目',
    environment VARCHAR(20) NOT NULL COMMENT 'dev、test 或 prod',
    PRIMARY KEY (project_id, environment)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目环境';

CREATE TABLE project_nodes (
    server_id BIGINT UNSIGNED NOT NULL COMMENT '独占分配的服务器',
    project_id BIGINT UNSIGNED NOT NULL COMMENT '所属项目',
    environment VARCHAR(20) NOT NULL COMMENT '项目环境',
    PRIMARY KEY (server_id),
    KEY idx_project_nodes_project_environment (project_id, environment)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='节点与项目环境的分配关系';

-- 升级时把历史节点归入仅平台管理员可管理的项目，待人工确认用途再分配。
INSERT INTO projects (name)
SELECT '历史节点' WHERE EXISTS (SELECT 1 FROM servers WHERE deleted = 0);

INSERT INTO project_environments (project_id, environment)
SELECT id, 'dev' FROM projects UNION ALL
SELECT id, 'test' FROM projects UNION ALL
SELECT id, 'prod' FROM projects;

INSERT INTO project_nodes (server_id, project_id, environment)
SELECT s.id, p.id, 'prod' FROM servers s CROSS JOIN projects p WHERE s.deleted = 0;
