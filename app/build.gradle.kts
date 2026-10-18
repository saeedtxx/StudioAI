plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ir.studioai.productphoto"
    compileSdk = 34

    defaultConfig {
        applicationId = "ir.studioai.productphoto"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            val keystoreFile = file("release-keystore.jks")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "StudioAI123456"
                keyAlias = System.getenv("KEY_ALIAS") ?: "studioai_key"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "StudioAI123456"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        kotlinCompilerExtensionVersion = "1.5.13"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.05.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")

    // بارگذاری تصاویر
    implementation("io.coil-kt:coil-compose:2.6.0")

    // هوش مصنوعی گوگل برای حذف پس‌زمینه + دانلودر خودکار ماژول
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    implementation("com.google.android.gms:play-services-base:18.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.0")

    // ارتباط با Cloudflare Worker
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // کتابخانه رسمی پرداخت درون‌برنامه‌ای کافه بازار (پولکی)
    implementation("com.github.cafebazaar.Poolakey:poolakey:2.2.0")
}