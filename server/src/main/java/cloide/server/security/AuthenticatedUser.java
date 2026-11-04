package cloide.server.security;

import cloide.server.module.auth.vo.CurrentUserVo;
import java.time.OffsetDateTime;

/**
 * 保存已认证用户的安全快照，避免将数据库实体或敏感字段放入 SecurityContext。
 *
 * @param id 用户 ID
 * @param username 用户名
 * @param role 最新数据库角色
 * @param reviewStatus 最新审核状态
 * @param reviewedAt 审核时间
 * @param createdAt 创建时间
 */
public record AuthenticatedUser(
        Long id,
        String username,
        String role,
        String reviewStatus,
        OffsetDateTime reviewedAt,
        OffsetDateTime createdAt) {

    /**
     * 将认证快照转换为公开的当前用户响应对象。
     *
     * @return 当前用户响应
     */
    public CurrentUserVo toCurrentUserVo() {
        CurrentUserVo currentUserVo = new CurrentUserVo();
        currentUserVo.setId(id);
        currentUserVo.setUsername(username);
        currentUserVo.setRole(role);
        currentUserVo.setReviewStatus(reviewStatus);
        currentUserVo.setReviewedAt(reviewedAt);
        currentUserVo.setCreatedAt(createdAt);
        return currentUserVo;
    }
}
