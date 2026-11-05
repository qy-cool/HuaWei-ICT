package cloide.app.util

import cloide.app.BuildConfig

/**
 * 后端连接配置。地址由 BuildConfig 注入，可在构建时通过
 * build.gradle.kts 的 buildConfigField 覆盖（如本地/私有部署）。
 */
object Constants {
    /** REST API 基础地址（HTTPS）。 */
    const val BASE_URL: String = BuildConfig.BASE_URL

    /** Monitor WebSocket 通道地址（WSS，经 nginx TLS 反代）。 */
    const val WS_URL: String = BuildConfig.WS_URL

    /** 每页默认条数。 */
    const val DEFAULT_PAGE_SIZE = 20
}
