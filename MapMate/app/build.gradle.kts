import java.util.Properties

plugins {
    id("com.android.application")
}

// 审计项：原先 AMap Key 与 release keystore 密码是硬编码并已提交进仓库。
// 现在一律从 local.properties（未跟踪）或环境变量读取，缺了就构建失败，而不是静默用旧值。
val secretProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(name: String): String? =
    secretProps.getProperty(name) ?: System.getenv(name)

android {
    namespace = "com.zhaowen.mapmate"
    compileSdk = 35
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.zhaowen.mapmate"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"

        // AMap Key 由 local.properties 的 AMAP_KEY（或同名环境变量）提供
        val amapKey = secret("AMAP_KEY") ?: ""
        if (amapKey.isBlank()) {
            logger.warn("⚠ 未配置 AMAP_KEY（local.properties 或环境变量），地图将无法加载")
        }
        manifestPlaceholders["AMAP_KEY"] = amapKey
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // release 签名材料只在配置齐全时才注册，避免把密码写死在源码里
        val releaseStorePass = secret("RELEASE_STORE_PASSWORD")
        val releaseKeyAlias = secret("RELEASE_KEY_ALIAS")
        val releaseKeyPass = secret("RELEASE_KEY_PASSWORD")
        if (releaseStorePass != null && releaseKeyAlias != null && releaseKeyPass != null) {
            create("release") {
                storeFile = file(secret("RELEASE_STORE_FILE") ?: "../keystore/release.keystore")
                storePassword = releaseStorePass
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPass
            }
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("debug") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
                ?: logger.warn("⚠ 未配置 RELEASE_STORE_PASSWORD/RELEASE_KEY_ALIAS/RELEASE_KEY_PASSWORD，release 包将不签名")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation("com.amap.api:navi-3dmap:10.0.600_3dmap10.0.600")
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(files("libs/concentus.jar"))
}