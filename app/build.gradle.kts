plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.screentranslator"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.screentranslator"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
            // ไฟล์ license ซ้ำกันจากไลบรารีของ Anthropic SDK (Jackson, OkHttp)
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/versions/**",
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // OCR (โมเดลฝังในแอป ใช้ออฟไลน์ได้ทันที)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")

    // ตัวแปลฟรี ออฟไลน์ (ดาวน์โหลดโมเดลครั้งแรก ~30 MB ต่อภาษา) และใช้เป็นตัวสำรองของ Claude
    implementation("com.google.mlkit:translate:17.0.3")

    // ตัวแปล Claude
    implementation("com.anthropic:anthropic-java:2.34.0")
}
