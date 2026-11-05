package cloide.server.websocket;

import cloide.server.module.server.entity.ServerEntity;

/**
 * 定义 Agent 首帧认证契约，供 WebSocket Handler 依赖。
 */
public interface AgentAuthenticationService {

    /** 校验 Agent Token 并返回有效服务器快照。 */
    ServerEntity authenticate(Long serverId, String token);
}
