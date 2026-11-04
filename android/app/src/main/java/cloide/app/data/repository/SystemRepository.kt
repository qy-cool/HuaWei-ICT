package cloide.app.data.repository

import cloide.app.api.SystemApi
import cloide.app.data.ApiException
import cloide.app.data.model.ConsumeStats
import cloide.app.data.model.HealthStatus
import cloide.app.data.model.MonitorTicket
import cloide.app.data.model.QueueBacklog
import cloide.app.data.model.ReadyStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 系统仓库：健康检查 + 就绪检查 + Monitor Ticket 获取 + RabbitMQ 监控。
 */
@Singleton
class SystemRepository @Inject constructor(
    private val systemApi: SystemApi,
) {

    /** 后端存活状态。 */
    suspend fun health(): HealthStatus {
        val response = systemApi.health()
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }

    /** 后端 + MySQL + RabbitMQ 就绪状态。 */
    suspend fun ready(): ReadyStatus {
        val response = systemApi.ready()
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }

    /** 获取 30 秒一次性 Monitor Ticket（WS 连接握手用）。 */
    suspend fun monitorTicket(): MonitorTicket {
        val response = systemApi.monitorTicket()
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }

    /** RabbitMQ 队列积压深度（admin）。 */
    suspend fun rabbitmqQueues(): List<QueueBacklog> {
        val response = systemApi.rabbitmqQueues()
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }

    /** RabbitMQ 消费者吞吐统计（admin）。 */
    suspend fun rabbitmqConsumers(): List<ConsumeStats> {
        val response = systemApi.rabbitmqConsumers()
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }
}
