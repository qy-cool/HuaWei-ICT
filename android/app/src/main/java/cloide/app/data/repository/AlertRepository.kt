package cloide.app.data.repository

import cloide.app.api.AlertApi
import cloide.app.data.ApiException
import cloide.app.data.model.AlertNotification
import cloide.app.data.model.AlertRecord
import cloide.app.data.model.AlertRecordQuery
import cloide.app.data.model.AlertRule
import cloide.app.data.model.CreateAlertRuleRequest
import cloide.app.data.model.PageResult
import cloide.app.data.model.UpdateAlertRuleRequest
import cloide.app.util.ErrorCodes
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 告警仓库：规则列表/CRUD + 记录分页/已读 + 通知投递记录。
 */
@Singleton
class AlertRepository @Inject constructor(
    private val alertApi: AlertApi,
) {

    /** 告警规则列表。 */
    suspend fun listRules(): List<AlertRule> {
        val response = alertApi.listRules()
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }

    /** 创建告警规则（admin）。 */
    suspend fun createRule(request: CreateAlertRuleRequest): AlertRule {
        val response = alertApi.createRule(request)
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }

    /** 更新告警规则（admin）。 */
    suspend fun updateRule(id: Long, request: UpdateAlertRuleRequest): AlertRule {
        val response = alertApi.updateRule(id, request)
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }

    /** 删除告警规则（admin，软删除）。 */
    suspend fun deleteRule(id: Long) {
        val response = alertApi.deleteRule(id)
        if (response.code != ErrorCodes.SUCCESS) {
            throw ApiException.Business(response.code, response.message)
        }
    }

    /** 告警记录分页。 */
    suspend fun listRecords(query: AlertRecordQuery): PageResult<AlertRecord> {
        val response = alertApi.listRecords(
            page = query.page,
            pageSize = query.pageSize,
            serverId = query.serverId,
            status = query.status,
        )
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }

    /** 标记记录已读。 */
    suspend fun markRead(id: Long) {
        val response = alertApi.markRead(id)
        if (response.code != ErrorCodes.SUCCESS) {
            throw ApiException.Business(response.code, response.message)
        }
    }

    /** 单条告警的外发通知投递记录（渠道/状态/重试）。 */
    suspend fun notifications(id: Long): List<AlertNotification> {
        val response = alertApi.notifications(id)
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }
}
