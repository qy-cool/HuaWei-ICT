import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "cloide.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "cloide.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // 本地联调默认指向模拟器回环；真机调试用 -PBASE_URL=http://<LAN_IP>:18080 覆盖。
            val debugBaseUrl = (project.findProperty("BASE_URL") as String?)?.takeIf { it.isNotBlank() }
                ?: "http://10.0.2.2:18080"
            val debugWsUrl = (project.findProperty("WS_URL") as String?)?.takeIf { it.isNotBlank() }
                ?: "ws://10.0.2.2:18080/ws/monitor"
            buildConfigField("String", "BASE_URL", "\"$debugBaseUrl\"")
            buildConfigField("String", "WS_URL", "\"$debugWsUrl\"")
        }
        release {
            // release 必须显式注入地址；占位值只用于编译，assembleRelease/bundleRelease 会拦截。
            val releaseBaseUrl = (project.findProperty("BASE_URL") as String?)?.takeIf { it.isNotBlank() }
                ?: "https://SERVER_IP_OR_DOMAIN"
            val releaseWsUrl = (project.findProperty("WS_URL") as String?)?.takeIf { it.isNotBlank() }
                ?: "wss://SERVER_IP_OR_DOMAIN/ws/monitor"
            buildConfigField("String", "BASE_URL", "\"$releaseBaseUrl\"")
            buildConfigField("String", "WS_URL", "\"$releaseWsUrl\"")

            // 开启 R8 压缩混淆 + 资源收缩：减小体积并提高逆向门槛（规则见 proguard-rules.pro）
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // JVM 单测不加载 Android 框架：Log 等调用返回默认值（0/null），避免 not mocked 异常
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// release 包禁止使用占位地址，避免把 SERVER_IP_OR_DOMAIN 打进正式包。
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    doFirst {
        if ((project.findProperty("BASE_URL") as String?)?.isBlank() != false ||
            (project.findProperty("WS_URL") as String?)?.isBlank() != false
        ) {
            throw GradleException("release 构建必须通过 -PBASE_URL 与 -PWS_URL 注入后端地址")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // AndroidX 基础
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)

    // 网络
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // 测试
    testImplementation(libs.junit)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    // Debug
    debugImplementation(libs.androidx.compose.ui.tooling)
}
