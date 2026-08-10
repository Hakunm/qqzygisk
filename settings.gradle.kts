pluginManagement {
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
        maven { url =  uri("https://api.xposed.info/") }
    }
}

rootProject.name = "qqzygisk"
include(":app")
includeBuild("AndroidVMTools") {
    dependencySubstitution {
        // 将远程坐标替换为本地项目
        // 注意：这里的 GroupID 是 io.github.vova7878
        substitute(module("io.github.vova7878:AndroidVMTools")).using(project(":"))
    }
}