package cloide.server.module.alert.service;

import cloide.server.module.alert.vo.AlertRecordVo;

/**
 * 告警触发事件，在告警评估事务提交后发布，供 AlertPushPublisher 推送。
 *
 * <p>使用 Spring ApplicationEvent 机制，配合
 * @TransactionalEventListener(AFTER_COMMIT) 确保只在告警记录
 * 和状态写入事务提交后才推送 WebSocket。</p>
 *
 * @param serverId 触发告警的服务器 ID
 * @param record   告警记录 VO
 */
public record AlertTriggeredEvent(Long serverId, AlertRecordVo record) {
}
