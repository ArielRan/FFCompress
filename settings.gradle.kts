pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/central") } // mavenCentral 国内镜像
        google()
        mavenCentral()
    }
}
rootProject.name = "FFCompress"
include(":app")
