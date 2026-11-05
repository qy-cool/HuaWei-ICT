package cloide.server.module.terminal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 映射 V12 创建的终端会话元数据表。
 *
 * <p>实体刻意不包含 PTY 输入、输出、命令、环境或凭据，避免 Java 后端持久化敏感终端内容。</p>
 */
@Data
@TableName("terminal_sessions")
public class TerminalSessionEntity {

    /** 数据库自增主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** Java 生成并跨 WebSocket 路由的会话 UUID。 */
    @TableField("session_id")
    private String sessionId;

    /** 浏览器 terminal.open 的 UUID 幂等键。 */
    @TableField("open_message_id")
    private String openMessageId;

    /** 目标服务器主键。 */
    @TableField("server_id")
    private Long serverId;

    /** 创建终端的已审核用户主键。 */
    @TableField("user_id")
    private Long userId;

    /** 会话状态 opening/open/closed/timeout/error。 */
    private String status;

    /** Agent 返回的受保护固定 Shell 标识，不是 Shell 命令。 */
    @TableField("shell_identifier")
    private String shellIdentifier;

    /** 会话关闭原因，不包含终端数据。 */
    @TableField("close_reason")
    private String closeReason;

    /** Agent 确认 PTY 创建完成的时间。 */
    @TableField("opened_at")
    private LocalDateTime openedAt;

    /** 会话关闭时间。 */
    @TableField("closed_at")
    private LocalDateTime closedAt;

    /** 最近一次已接受控制消息的时间，用于后续超时清理。 */
    @TableField("last_activity_at")
    private LocalDateTime lastActivityAt;

    /** 创建时间。 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 更新时间。 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
