package cloide.server.module.server.service;

import cloide.server.common.vo.PageResult;
import cloide.server.module.server.dto.CreateServerRequest;
import cloide.server.module.server.dto.ServerQueryRequest;
import cloide.server.module.server.dto.UpdateServerRequest;
import cloide.server.module.server.vo.ServerStatusVo;
import cloide.server.module.server.vo.ServerVo;

/**
 * 定义服务器资料与状态快照的业务契约，供接口层依赖。
 *
 * <p>servers 表数据所有权归 server 模块：metrics/terminal 等模块必须通过
 * 本接口访问服务器数据，不得直接注入 {@code ServerMapper}。</p>
 */
public interface ServerService {

    /** 创建服务器。 */
    ServerVo create(CreateServerRequest request);

    /** 分页查询服务器。 */
    PageResult<ServerVo> list(ServerQueryRequest request);

    /** 查询服务器详情。 */
    ServerVo get(Long serverId);

    /** 判断服务器是否有效存在。 */
    boolean existsActive(Long serverId);

    /** 判断服务器是否有效存在（事务内 FOR UPDATE 行锁语义，供指标接收等强一致校验使用）。 */
    boolean existsActiveForUpdate(Long serverId);

    /** 判断服务器是否存在且 Agent 在线（终端等能力校验用）。 */
    boolean isAgentOnline(Long serverId);

    /** 更新服务器。 */
    ServerVo update(Long serverId, UpdateServerRequest request);

    /** 软删除服务器。 */
    void delete(Long serverId);

    /** 查询服务器状态快照。 */
    ServerStatusVo status(Long serverId);
}
