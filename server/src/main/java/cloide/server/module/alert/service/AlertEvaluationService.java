package cloide.server.module.alert.service;

import cloide.server.module.metrics.vo.MetricsLatestVo;

/**
 * 告警评估契约：对一次指标快照执行该服务器全部匹配规则的状态迁移与记录生成。
 *
 * <p>事务边界由调用方管理：消息消费者（MVP-11）在消费事务内调用，
 * 保证评估结果与消费幂等记录同事务提交。</p>
 */
public interface AlertEvaluationService {

    /** 对指标快照执行全部匹配规则的告警评估。 */
    void evaluate(MetricsLatestVo metrics);
}
