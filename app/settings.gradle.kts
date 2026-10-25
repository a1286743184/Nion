pluginManagement {
    repositories {
        // 阿里云镜像置前：dl.google.com 在本机网络环境直连被墙（TLS 握手被重置），
        // Gradle 按声明顺序解析，镜像必须排在 google()/mavenCentral() 之前才生效。
        // 镜像缺失的构件会自动回落到官方源。
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
    }
}

rootProject.name = "Nion"
include(":app")
