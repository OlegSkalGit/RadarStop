import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val now = Date()
val formattedVersionName = SimpleDateFormat("yy.MM.dd_HHmm", Locale.US).format(now)
val formattedVersionCode = SimpleDateFormat("yyDDDHHmm", Locale.US).format(now).toInt()

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

    signingConfigs {
        getByName("debug") {
            storeFile = file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
    testImplementation("junit:junit:4.13.2")
}
