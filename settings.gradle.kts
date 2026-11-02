pluginManagement {
    repositories {
        maven { url = uri("D:/data/local-m2") } // 本地兜底仓库（网络异常构件手动放入）
        maven { url = uri("https://maven.aliyun.com/repository/central") } // mavenCentral 国内镜像
        maven { url = uri("https://repo.huaweicloud.com/repository/maven/") } // 备用镜像（阿里云个别文件异常时故障转移）
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") } // gradlePluginPortal 国内镜像
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("D:/data/local-m2") } // 本地兜底仓库
        maven { url = uri("https://maven.aliyun.com/repository/central") } // mavenCentral 国内镜像
        maven { url = uri("https://repo.huaweicloud.com/repository/maven/") } // 备用镜像
        google()
        mavenCentral()
    }
}
rootProject.name = "FFCompress"
include(":app")
