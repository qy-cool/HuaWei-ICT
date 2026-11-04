package cloide.server.module.terminal.mapper;

import cloide.server.module.terminal.entity.TerminalSessionEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 访问终端会话元数据；该 Mapper 不提供任何终端输入或输出字段。
 */
@Mapper
public interface TerminalSessionMapper {

    /** 插入一条 opening 状态的终端会话元数据，并回写自增主键。 */
    int insertSession(@Param("session") TerminalSessionEntity session);

    /** 根据 Java 生成的会话 UUID 查询元数据。 */
    TerminalSessionEntity selectBySessionId(@Param("sessionId") String sessionId);

    /** 根据用户和 terminal.open 幂等键查询已创建会话。 */
    TerminalSessionEntity selectByUserIdAndOpenMessageId(@Param("userId") Long userId,
            @Param("openMessageId") String openMessageId);

    /** 查询指定服务器仍未关闭的会话，用于连接替换和服务器下线时收口。 */
    List<TerminalSessionEntity> selectActiveByServerId(@Param("serverId") Long serverId);

    /** 查询指定用户仍未关闭的会话，用于审核状态变化时收口。 */
    List<TerminalSessionEntity> selectActiveByUserId(@Param("userId") Long userId);

    /** 查询所有未关闭会话，用于单 JVM 全局限额和超时收口。 */
    List<TerminalSessionEntity> selectAllActive();

    /** 查询当前仍占用终端资源的不同用户数量。 */
    int countActiveUsers();

    /** Agent 确认 PTY 创建时将 opening 原子转换为 open。 */
    int markOpened(@Param("sessionId") String sessionId, @Param("shellIdentifier") String shellIdentifier,
            @Param("openedAt") LocalDateTime openedAt);

    /** 关闭会话；仅未关闭状态允许转换，避免重复 close 覆盖原始原因。 */
    int closeSession(@Param("sessionId") String sessionId, @Param("status") String status,
            @Param("closeReason") String closeReason, @Param("closedAt") LocalDateTime closedAt);

    /** 更新活动时间；会话关闭后不再刷新，防止超时任务与并发消息复活会话。 */
    int updateLastActivity(@Param("sessionId") String sessionId, @Param("lastActivityAt") LocalDateTime lastActivityAt);
}
