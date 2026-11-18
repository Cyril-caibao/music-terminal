plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("app.cash.paparazzi")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.multitrack.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.multitrack.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 64
        versionName = "3.51"

        ndk {
            // ONNX Runtime 的 .so 按 ABI 分；只留手机真正会用的两个，
            // 否则一个 AAR 能给 APK 塞进近百兆（x86/x86_64 只有模拟器用得到）
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // 签名信息从 signing.properties 读（不入库）：公开仓库不带密钥库与密码。
    // 没有这两个文件的机器照常构建 debug；release 构建会落到 unsigned。
    val signingProps = java.util.Properties().apply {
        val f = rootProject.file("signing.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val hasReleaseKey = rootProject.file("cyrilmusic-release.jks").exists() &&
        signingProps.getProperty("storePassword") != null

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file("cyrilmusic-release.jks")
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias") ?: "cyrilmusic"
                keyPassword = signingProps.getProperty("keyPassword")
                    ?: signingProps.getProperty("storePassword")
            }
        }
    }

    buildTypes {
        // debug 也用 release 签名：两种包可以互相覆盖安装，
        // 免得「装 debug 调试 → 换 release 提示应用未安装 / 反过来」这类问题把新版本挡在外面。
        debug {
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
            applicationIdSuffix = null
        }
        release {
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        // Compose Compiler 与 Kotlin 版本必须匹配：1.5.14 <-> Kotlin 1.9.24
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity:1.9.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.1")

    testImplementation("junit:junit:4.13.2")

    // ---- 播放器（P0）----
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    // 兜底媒体通知要 MediaStyle（Media3 自己也是靠它画通知的）
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.media3:media3-ui:1.4.1")

    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ---- 标签读写（Android 分支，无 AWT/ImageIO）----
    implementation("com.github.Adonai:jaudiotagger:2.3.13")

    // ---- AI 降噪：DeepFilterNet3 真权重（assets/dfn3/*.onnx）----
    // 手机上用 android 版（AAR 里是 arm 的 .so）；单元测试在 JVM 上跑，
    // 换桌面版（自带 windows/linux/macos 的原生库），下面是同一个 API 的两份实现，
    // 所以 testRuntimeClasspath 里必须把 android 版排掉，否则两边类名撞车、原生库加载错。
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.19.2")
}

configurations.matching { it.name.startsWith("test") && it.name.endsWith("RuntimeClasspath") }
    .configureEach {
        exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
    }
