package cloide.server.module.metrics.entity;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 记录已接受的 Agent 指标消息，用于同一服务器内的至少一次投递幂等控制。
 */
@Data
public class MetricsIngestionEntity {

    private Long id;
    private Long serverId;
    private String messageId;
    private LocalDateTime collectedAt;
    private LocalDateTime createdAt;
}
