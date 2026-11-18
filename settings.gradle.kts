pluginManagement {
    repositories {
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
        google()
        mavenCentral()
        // jaudiotagger 的 Android 分支只在 JitPack 发布（去掉了 javax.imageio 依赖）
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "CyrilMusic"
include(":app")
