package cloide.app.di

import cloide.app.api.AdminApi
import cloide.app.api.AiApi
import cloide.app.api.AlertApi
import cloide.app.api.AuthApi
import cloide.app.api.CommandApi
import cloide.app.api.MetricsApi
import cloide.app.api.ServerApi
import cloide.app.api.SystemApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * Retrofit 接口实例绑定。
 */
@Module
@InstallIn(SingletonComponent::class)
object ApiModule {

    @Provides
    @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)

    @Provides
    @Singleton
    fun provideServerApi(retrofit: Retrofit): ServerApi = retrofit.create(ServerApi::class.java)

    @Provides
    @Singleton
    fun provideMetricsApi(retrofit: Retrofit): MetricsApi = retrofit.create(MetricsApi::class.java)

    @Provides
    @Singleton
    fun provideAlertApi(retrofit: Retrofit): AlertApi = retrofit.create(AlertApi::class.java)

    @Provides
    @Singleton
    fun provideSystemApi(retrofit: Retrofit): SystemApi = retrofit.create(SystemApi::class.java)

    @Provides
    @Singleton
    fun provideAdminApi(retrofit: Retrofit): AdminApi = retrofit.create(AdminApi::class.java)

    @Provides
    @Singleton
    fun provideAiApi(retrofit: Retrofit): AiApi = retrofit.create(AiApi::class.java)

    @Provides
    @Singleton
    fun provideCommandApi(retrofit: Retrofit): CommandApi = retrofit.create(CommandApi::class.java)
}
