package cloide.server.module.project.service;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import cloide.server.security.AuthenticatedUser;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** API 和后台作业共享资源授权。派发时重新查询成员、审核状态和节点分配。 */
@Service
public class ProjectAccess {
    private final JdbcTemplate jdbc;

    public ProjectAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AuthenticatedUser principal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (!"approved".equals(user.reviewStatus())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return user;
    }

    public void requireRead(long projectId) {
        role(projectId);
    }

    public void requireOwner(long projectId) {
        String role = role(projectId);
        if (!"admin".equals(role) && !"owner".equals(role)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    public void requireDeveloper(long projectId) {
        if (!java.util.Set.of("admin", "owner", "developer").contains(role(projectId))) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    public void requireAdmin() {
        if (!"admin".equals(principal().role())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private String role(long projectId) {
        AuthenticatedUser user = principal();
        return jdbc.query("""
                SELECT CASE WHEN ? = 'admin' THEN 'admin' ELSE m.member_role END AS effective_role
                FROM projects p LEFT JOIN project_members m ON m.project_id = p.id AND m.user_id = ?
                WHERE p.id = ?
                """, (rs, row) -> rs.getString("effective_role"), user.role(), user.id(), projectId)
                .stream().filter(Objects::nonNull).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    public void requireRun(long projectId, long serverId) {
        if (!canRun(principal().id(), projectId, serverId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    public boolean canRun(long userId, long projectId, long serverId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(1)
                FROM users u
                JOIN project_nodes n ON n.server_id = ? AND n.project_id = ?
                JOIN servers s ON s.id = n.server_id AND s.deleted = 0
                LEFT JOIN project_members m ON m.project_id = n.project_id AND m.user_id = u.id
                WHERE u.id = ? AND u.review_status = 'approved' AND n.environment IN ('dev','test')
                  AND (u.role = 'admin' OR m.member_role IN ('owner','developer'))
                """, Integer.class, serverId, projectId, userId);
        return count != null && count > 0;
    }
}
