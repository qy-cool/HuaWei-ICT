package cloide.server.module.alert.consume;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 映射 V15 创建的 message_consume_records 表，一行表示一次已处理的事件消费。
 */
@Data
@TableName("message_consume_records")
public class ConsumeRecordEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String consumer;
    @TableField("event_id")
    private String eventId;
    private String status;
    private Integer attempts;
    @TableField("last_error")
    private String lastError;
    @TableField("consumed_at")
    private LocalDateTime consumedAt;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
