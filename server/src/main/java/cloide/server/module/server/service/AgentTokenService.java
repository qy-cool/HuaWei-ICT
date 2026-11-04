package cloide.server.module.server.service;

import cloide.server.module.server.vo.AgentTokenVo;

/**
 * 定义服务器 Agent Token 生命周期的业务契约，避免调用方依赖令牌实现细节。
 */
public interface AgentTokenService {

    /** 首次为服务器注册 Agent Token。 */
    AgentTokenVo register(Long serverId);

    /** 轮换服务器 Agent Token。 */
    AgentTokenVo rotate(Long serverId);

    /** 撤销服务器 Agent Token。 */
    void revoke(Long serverId);
}
