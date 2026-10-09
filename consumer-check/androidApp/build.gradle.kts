import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "io.github.vad4nus.actioncable.check.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.vad4nus.actioncable.check"
        minSdk = providers.gradleProperty("minSdk").getOrElse("26").toInt()
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "CABLE_URL", "\"${providers.gradleProperty("cableUrl").getOrElse("ws://10.0.2.2:3000/cable")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.ktor.client.okhttp)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.process)
}
