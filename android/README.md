# cloide-shell android

Kotlin 2.0.21 + Jetpack Compose 客户端：登录、服务器列表、实时监控、告警、终端与 AI
功能。原生实现（非 WebView）。

## 构建

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug        # 产物 app/build/outputs/apk/debug/app-debug.apk
```

- 需要 JDK 17+ 与 Android SDK 36；`local.properties` 仅本机使用，不入库。
- debug 默认连接 `http://10.0.2.2:18080`（模拟器回环），可用
  `-PBASE_URL=... -PWS_URL=...` 覆盖。
- release 必须显式注入 `-PBASE_URL` 与 `-PWS_URL`，否则 `assembleRelease` 失败；
  当前没有 release 签名配置，keystore 不入库。

## 目录

```text
app/src/main/java/cloide/app/
├── api/ data/ di/ service/ ui/{login,dashboard,servers,alerts,terminal,...}
├── util/Constants.kt        # 读 BuildConfig.BASE_URL / WS_URL
└── CloideApp.kt / MainActivity.kt
```

## 版本

Kotlin 2.0.21、AGP 8.13.0、Gradle 8.13、compileSdk 36、minSdk 26、targetSdk 36；
依赖版本见 `gradle/libs.versions.toml`。

## 文档

- 接口契约：`docs/openapi/`
- 部署与后端：`docs/ops-manual/cloide-shell-Server-部署安装手册.md`
