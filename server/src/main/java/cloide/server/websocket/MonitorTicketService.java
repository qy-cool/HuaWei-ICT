package cloide.server.websocket;

import cloide.server.security.AuthenticatedUser;

/**
 * 定义 Monitor WebSocket 一次性 ticket 的签发、消费和清理契约。
 */
public interface MonitorTicketService {

    /** 为已认证用户签发一次性 ticket。 */
    MonitorTicketVo issue(AuthenticatedUser user);

    /** 原子消费一次性 ticket。 */
    AuthenticatedUser consume(String ticket);

    /** 清理未消费的过期 ticket。 */
    void purgeExpiredTickets();
}
