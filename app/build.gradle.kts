import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun getBuildTimestamp(): Date = Date()
fun getFormattedVersionName(): String = SimpleDateFormat("yy.MM.dd_HHmm", Locale.US).format(getBuildTimestamp())
fun getFormattedVersionCode(): Int = SimpleDateFormat("yyDDDHHmm", Locale.US).format(getBuildTimestamp()).toInt()

android {
    namespace = "com.example.radardetector"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.radardetector"
        minSdk = 26
        targetSdk = 34
        versionCode = getFormattedVersionCode()
        versionName = getFormattedVersionName()
        resourceConfigurations += setOf("en")
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
            isCrunchPngs = true
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
