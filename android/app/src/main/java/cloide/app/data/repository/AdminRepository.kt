package cloide.app.data.repository

import cloide.app.api.AdminApi
import cloide.app.data.ApiException
import cloide.app.data.model.AdminUserQuery
import cloide.app.data.model.AdminUserVo
import cloide.app.data.model.BatchReviewRequest
import cloide.app.data.model.BatchReviewResult
import cloide.app.data.model.PageResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 管理员用户审核仓库：列表/搜索 + 单条/批量通过拒绝。
 */
@Singleton
class AdminRepository @Inject constructor(
    private val adminApi: AdminApi,
) {

    /** 分页查询用户列表。 */
    suspend fun listUsers(query: AdminUserQuery): PageResult<AdminUserVo> {
        val response = adminApi.listUsers(
            page = query.page,
            pageSize = query.pageSize,
            status = query.status,
            keyword = query.keyword,
        )
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }

    /** 批量通过待审核用户。 */
    suspend fun batchApprove(userIds: List<Long>): BatchReviewResult {
        val response = adminApi.batchApprove(BatchReviewRequest(userIds))
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }

    /** 批量拒绝待审核用户。 */
    suspend fun batchReject(userIds: List<Long>): BatchReviewResult {
        val response = adminApi.batchReject(BatchReviewRequest(userIds))
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }

    /** 通过指定用户。 */
    suspend fun approveUser(id: Long) {
        val response = adminApi.approveUser(id)
        if (response.code != cloide.app.util.ErrorCodes.SUCCESS) {
            throw ApiException.Business(response.code, response.message)
        }
    }

    /** 拒绝指定用户。 */
    suspend fun rejectUser(id: Long) {
        val response = adminApi.rejectUser(id)
        if (response.code != cloide.app.util.ErrorCodes.SUCCESS) {
            throw ApiException.Business(response.code, response.message)
        }
    }
}
