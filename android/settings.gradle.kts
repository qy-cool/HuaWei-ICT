pluginManagement {
    repositories {
        // 本机优先阿里云：对 dl.google.com 的 JVM TLS 握手不稳定（Gradle 发行版同用腾讯镜像）。
        // CI 跳过这些镜像：阿里云对部分构件返回 502 时，Gradle 不会继续查后面的仓库。
        // 判断写在块内：pluginManagement 单独编译，看不到脚本顶层变量。
        if (System.getenv("CI") != "true" && System.getenv("GITHUB_ACTIONS") != "true") {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("CI") != "true" && System.getenv("GITHUB_ACTIONS") != "true") {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "cloide-shell"
include(":app")
