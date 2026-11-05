package cloide.app.data.repository

import cloide.app.data.model.ApiResponse
import cloide.app.data.model.LoginVo
import cloide.app.data.model.CurrentUser
import cloide.app.api.AuthApi
import cloide.app.data.SessionStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AuthRepository 单元测试：mock AuthApi 与 SessionStore。
 */
class AuthRepositoryTest {

    private val authApi = mockk<AuthApi>()
    private val sessionStore = mockk<SessionStore>()
    private val repository = AuthRepository(authApi, sessionStore)

    @Test
    fun `login saves session and returns user`() = runTest {
        val user = CurrentUser(
            id = 1L,
            username = "admin",
            role = "admin",
            reviewStatus = "approved",
            createdAt = "2026-07-20T00:00:00Z",
        )
        val loginVo = LoginVo(
            token = "jwt-token",
            tokenType = "Bearer",
            expiresIn = 259200L,
            user = user,
        )
        coEvery { authApi.login(any()) } returns ApiResponse(0, "success", loginVo)
        coEvery { sessionStore.saveSession(any(), any()) } returns Unit

        val result = repository.login("admin", "secret123")

        assertEquals("admin", result.username)
        coVerify(exactly = 1) { sessionStore.saveSession("jwt-token", user) }
    }

    @Test
    fun `logout clears session even when api fails`() = runTest {
        coEvery { authApi.logout() } throws RuntimeException("network down")
        coEvery { sessionStore.clear() } returns Unit

        repository.logout()

        coVerify(exactly = 1) { sessionStore.clear() }
    }
}
