package cloide.server.websocket;

import cloide.server.module.auth.entity.UserEntity;
import cloide.server.module.auth.mapper.UserMapper;
import cloide.server.security.AuthenticatedUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/** Monitor ticket 保存签发时快照。项目隔离部署另查当前账号状态，兼容跨实例的历史票据。 */
@Component
@Slf4j
public class LegacyMonitorAccess {
    private final boolean projectIsolation;
    private final UserMapper users;

    public LegacyMonitorAccess(@Value("${cloide.security.project-isolation:false}") boolean projectIsolation,
                               UserMapper users) {
        this.projectIsolation = projectIsolation;
        this.users = users;
    }

    boolean enabled() {
        return projectIsolation;
    }

    boolean allowed(AuthenticatedUser snapshot) {
        if (!projectIsolation) {
            return true;
        }
        if (!"admin".equals(snapshot.role()) || !"approved".equals(snapshot.reviewStatus())) {
            return false;
        }
        try {
            UserEntity current = users.selectAuthenticationUserById(snapshot.id());
            return current != null && "admin".equals(current.getRole()) && "approved".equals(current.getReviewStatus());
        } catch (DataAccessException unavailable) {
            log.warn("Monitor 管理员权限复核失败，已拒绝当前连接", unavailable);
            return false;
        }
    }
}
