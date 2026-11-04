package cloide.server.module.metrics.outbox;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Outbox 数据访问，维护指标待发布事件的插入、轮询与状态回写。
 *
 * <p>轮询使用 {@code FOR UPDATE SKIP LOCKED} 防止多实例/重叠调度重复取行；
 * 状态回写带 {@code status='pending'} 条件，已发布行不会被重复处理。</p>
 */
@Mapper
public interface OutboxMapper {

    /** 插入待发布事件，回写主键。 */
    int insert(@Param("outbox") OutboxEntity outbox);

    /** 轮询待发布且已到退避时刻的事件行（行锁 + 跳过已锁行）。 */
    List<OutboxEntity> selectPendingForPublish(@Param("limit") int limit);

    /** 标记为已发布（仅 pending 行可转换）。 */
    int markPublished(@Param("id") Long id, @Param("publishedAt") LocalDateTime publishedAt);

    /** 回写退避重试状态（attempts+1、下次时刻、失败原因）。 */
    int markRetry(@Param("id") Long id, @Param("attempts") int attempts,
            @Param("nextAttemptAt") LocalDateTime nextAttemptAt, @Param("lastError") String lastError);
}
