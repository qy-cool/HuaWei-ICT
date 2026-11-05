package cloide.server.module.metrics.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 映射 V3 创建的 Metrics 固定宽表，一行表示一个服务器采样时刻的完整指标快照。
 */
@Data
@TableName("metrics")
public class MetricsEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField("server_id")
    private Long serverId;
    @TableField("cpu_percent")
    private BigDecimal cpuPercent;
    @TableField("memory_percent")
    private BigDecimal memoryPercent;
    @TableField("memory_used")
    private Long memoryUsed;
    @TableField("memory_total")
    private Long memoryTotal;
    @TableField("disk_percent")
    private BigDecimal diskPercent;
    @TableField("disk_used")
    private Long diskUsed;
    @TableField("disk_total")
    private Long diskTotal;
    @TableField("net_rx")
    private Long netRx;
    @TableField("net_tx")
    private Long netTx;
    private BigDecimal temperature;
    @TableField("load_avg")
    private BigDecimal loadAvg;
    @TableField("collected_at")
    private LocalDateTime collectedAt;
    @TableField("created_at")
    private LocalDateTime createdAt;
}
