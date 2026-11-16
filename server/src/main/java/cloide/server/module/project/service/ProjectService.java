package cloide.server.module.project.service;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import cloide.server.security.AuthenticatedUser;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {
    public record Project(long id, String name, String role) {}
    public record Member(long userId, String username, String role) {}
    public record Node(long serverId, String name, String environment, String agentStatus) {}
    public record MemberAccessRevoked(long projectId, long userId) {}
    public record NodeUnassigned(long projectId, long serverId) {}

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final ApplicationEventPublisher events;

    public ProjectService(JdbcTemplate jdbc, ProjectAccess access, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.access = access;
        this.events = events;
    }

    @Transactional
    public Project create(String name) {
        AuthenticatedUser user = access.principal();
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        String displayName = name.strip();
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement insert = connection.prepareStatement("INSERT INTO projects(name) VALUES (?)",
                    new String[]{"id"});
            insert.setString(1, displayName);
            return insert;
        }, key);
        Number id = key.getKey();
        if (id == null) {
            throw new BusinessException(ErrorCode.DATABASE_ERROR);
        }
        long projectId = id.longValue();
        jdbc.update("INSERT INTO project_members(project_id,user_id,member_role) VALUES (?,?,'owner')",
                projectId, user.id());
        jdbc.update("""
                INSERT INTO project_environments(project_id,environment) VALUES (?,'dev'),(?,'test'),(?,'prod')
                """, projectId, projectId, projectId);
        return new Project(projectId, displayName, "admin".equals(user.role()) ? "admin" : "owner");
    }

    public List<Project> list(long after, int limit) {
        page(after, limit);
        AuthenticatedUser user = access.principal();
        return jdbc.query("""
                SELECT p.id, p.name, CASE WHEN ? = 'admin' THEN 'admin' ELSE m.member_role END AS effective_role
                FROM projects p LEFT JOIN project_members m ON m.project_id = p.id AND m.user_id = ?
                WHERE p.id > ? AND (? = 'admin' OR m.user_id IS NOT NULL)
                ORDER BY p.id LIMIT ?
                """, (rs, row) -> new Project(rs.getLong("id"), rs.getString("name"), rs.getString("effective_role")),
                user.role(), user.id(), after, user.role(), limit);
    }

    public List<String> environments(long projectId) {
        access.requireRead(projectId);
        return jdbc.queryForList("""
                SELECT environment FROM project_environments WHERE project_id = ? ORDER BY environment
                """, String.class, projectId);
    }

    public List<Member> members(long projectId, long after, int limit) {
        page(after, limit);
        access.requireRead(projectId);
        return jdbc.query("""
                SELECT u.id, u.username, m.member_role FROM project_members m
                JOIN users u ON u.id = m.user_id WHERE m.project_id = ? AND u.id > ? ORDER BY u.id LIMIT ?
                """, (rs, row) -> new Member(rs.getLong("id"), rs.getString("username"), rs.getString("member_role")),
                projectId, after, limit);
    }

    @Transactional
    public void setMember(long projectId, long userId, String role) {
        access.requireOwner(projectId);
        if (role == null || !Set.of("viewer", "developer").contains(role)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        boolean approved = !jdbc.queryForList("""
                SELECT id FROM users WHERE id = ? AND review_status = 'approved' FOR UPDATE
                """, Long.class, userId).isEmpty();
        if (!approved) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        List<String> previous = memberRoleForUpdate(projectId, userId);
        if (previous.isEmpty()) {
            jdbc.update("INSERT INTO project_members(project_id,user_id,member_role) VALUES (?,?,?)",
                    projectId, userId, role);
        } else {
            jdbc.update("UPDATE project_members SET member_role = ? WHERE project_id = ? AND user_id = ?",
                    role, projectId, userId);
            if ("viewer".equals(role) && previous.contains("developer")) {
                events.publishEvent(new MemberAccessRevoked(projectId, userId));
            }
        }
    }

    @Transactional
    public void removeMember(long projectId, long userId) {
        access.requireOwner(projectId);
        memberRoleForUpdate(projectId, userId);
        if (jdbc.update("DELETE FROM project_members WHERE project_id = ? AND user_id = ?", projectId, userId) > 0) {
            events.publishEvent(new MemberAccessRevoked(projectId, userId));
        }
    }

    private List<String> memberRoleForUpdate(long projectId, long userId) {
        List<String> roles = jdbc.queryForList("""
                SELECT member_role FROM project_members WHERE project_id = ? AND user_id = ? FOR UPDATE
                """, String.class, projectId, userId);
        if (roles.contains("owner")) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
        }
        return roles;
    }

    public List<Node> nodes(long projectId, long after, int limit) {
        page(after, limit);
        access.requireRead(projectId);
        return jdbc.query("""
                SELECT s.id, s.name, n.environment, s.agent_status FROM project_nodes n
                JOIN servers s ON s.id = n.server_id AND s.deleted = 0
                WHERE n.project_id = ? AND s.id > ? ORDER BY s.id LIMIT ?
                """, (rs, row) -> new Node(rs.getLong("id"), rs.getString("name"), rs.getString("environment"),
                rs.getString("agent_status")),
                projectId, after, limit);
    }

    @Transactional
    public void assign(long projectId, String environment, long serverId) {
        access.requireAdmin();
        requireEnvironment(projectId, environment);
        if (jdbc.queryForList("SELECT id FROM servers WHERE id = ? AND deleted = 0 FOR UPDATE",
                Long.class, serverId).isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        // 锁定已有分配。重复 PUT 返回成功；跨环境移动必须先明确解除旧分配。
        List<Boolean> matching = jdbc.query("""
                SELECT project_id, environment FROM project_nodes WHERE server_id = ? FOR UPDATE
                """, (rs, row) -> rs.getLong("project_id") == projectId
                && environment.equals(rs.getString("environment")), serverId);
        if (matching.isEmpty()) {
            jdbc.update("INSERT INTO project_nodes(server_id,project_id,environment) VALUES (?,?,?)",
                    serverId, projectId, environment);
        } else if (!matching.getFirst()) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
        }
    }

    @Transactional
    public void unassign(long projectId, String environment, long serverId) {
        access.requireAdmin();
        requireEnvironment(projectId, environment);
        if (jdbc.update("DELETE FROM project_nodes WHERE project_id = ? AND environment = ? AND server_id = ?",
                projectId, environment, serverId) > 0) {
            events.publishEvent(new NodeUnassigned(projectId, serverId));
        }
    }

    private void requireEnvironment(long projectId, String environment) {
        if (!environments(projectId).contains(environment)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    private void page(long after, int limit) {
        if (after < 0 || limit < 1 || limit > 100) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
    }
}
