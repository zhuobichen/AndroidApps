plugins {
    id("com.android.application")
}

android {
    namespace = "com.zhaowen.videoquiz"
    compileSdk = 35
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.zhaowen.videoquiz"
        minSdk = 26
        targetSdk = 34
        versionCode = 16
        versionName = "1.6"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("D:/Android/MapMate/MapMate/keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("ru.noties:jlatexmath-android:0.2.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}