import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// 签名信息放在仓库外的 key.properties（已被 .gitignore 排除）；
// 不存在时退回 debug 签名，保证任何人 clone 后 assembleRelease 也能直接出包
val keystorePropsFile = rootProject.file("key.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.ff.compress"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ff.compress"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.0"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    // 按 ABI 拆分。默认只出 arm64-v8a（真机包，约 43MB）；
    // 模拟器调试时加 -Pemu 出 x86_64 包：gradle :app:assembleDebug -Pemu
    splits {
        abi {
            isEnable = true
            reset()
            if (project.hasProperty("emu")) include("x86_64") else include("arm64-v8a")
            isUniversalApk = false
        }
    }

    lint {
        // 离线环境拉不到 lint-gradle；发布前人工验证，不在构建里跑 vital lint
        checkReleaseBuilds = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Media3 Transformer —— 硬件压缩引擎
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")

    // FFmpeg 软编引擎（社区维护 fork，含 libx264，GPL-3.0，自用可接受）
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.7")
    // fork 的 POM 未声明此传递依赖，需显式引入
    implementation("com.arthenica:smart-exception-java:0.2.0")

    // Room 任务持久化
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // 自定义输出文件夹（SAF）
    implementation("androidx.documentfile:documentfile:1.0.1")
}
