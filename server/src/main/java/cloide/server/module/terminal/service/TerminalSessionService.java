package cloide.server.module.terminal.service;

import cloide.server.module.terminal.entity.TerminalSessionEntity;

/** 管理终端会话元数据、授权与单 JVM 生命周期边界。 */
public interface TerminalSessionService {

    /** 创建或按同一用户 open 消息幂等返回 opening 会话。 */
    TerminalSessionEntity openSession(Long userId, Long serverId, String openMessageId);

    /** 标记 Agent 已创建固定 Shell 的 PTY。 */
    void markOpened(String sessionId, String shellIdentifier);

    /** 记录已接受的控制活动，不接受终端内容。 */
    void touchSession(String sessionId);

    /** 回查最新审核状态并验证用户拥有仍可操作的会话。 */
    TerminalSessionEntity requireActiveSession(Long userId, String sessionId);

    /** 关闭会话，重复关闭保持幂等。 */
    void closeSession(String sessionId, String status, String reason);

    /** 关闭空闲或超过最大生命周期的会话。 */
    void closeExpiredSessions();
}
