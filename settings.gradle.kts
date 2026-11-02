// E-ink Reader — settings
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    // PREFER_PROJECT: 允许各模块声明自己的仓库。
    // 注：原 :desktop (Compose Desktop / Windows 移植) 模块已于 2026-10-28 取消（成果不成熟），
    // 源码归档于 E:/Android/_archived_desktop_2026-10-28/desktop，未纳入 git。
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "EinkReader"
include(":app")
