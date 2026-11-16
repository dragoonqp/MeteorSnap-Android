import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.meteorsnap"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.meteorsnap"
        minSdk = 23
        targetSdk = 36
        versionCode = 4
        versionName = "1.2.1"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = false
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // 维护中的 saki4510t UVCCamera 分支，API 与旧版教程并不兼容。
    implementation("com.herohan:UVCAndroid:1.0.13")
    // OpenCV 官方 Maven Central AAR（4.9.0 起由 OpenCV 团队维护）。
    implementation("org.opencv:opencv:4.13.0")

    testImplementation("junit:junit:4.13.2")
}
