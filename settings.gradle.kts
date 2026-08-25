pluginManagement {
    includeBuild("ZygoteLoader")
    repositories {
        mavenLocal()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven("https://central.sonatype.com/repository/maven-snapshots")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        maven("https://central.sonatype.com/repository/maven-snapshots")
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "qqhook"
include(":app")
includeBuild("AndroidVMTools") {
    dependencySubstitution {
        // 将远程坐标替换为本地项目
        // 注意：这里的 GroupID 是 io.github.vova7878
        substitute(module("io.github.vova7878:AndroidVMTools")).using(project(":"))
    }
}
includeBuild("ZygoteLoader") {
    dependencySubstitution {
        substitute(module("io.github.nightstars1.ZygoteLoader:runtime")).using(project(":runtime"))
        substitute(module("io.github.vova7878.ZygoteLoader:runtime")).using(project(":runtime"))
    }
}
