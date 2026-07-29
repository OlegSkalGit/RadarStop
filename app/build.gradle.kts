import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val formattedVersionName = SimpleDateFormat("yy.MM.dd").format(Date())
val formattedVersionCode = SimpleDateFormat("HHmm").format(Date()).toInt()

android {
    namespace = "com.example.radardetector"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.radardetector"
        minSdk = 26
        targetSdk = 34
        versionCode = formattedVersionCode
        versionName = formattedVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
}
