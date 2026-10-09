import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.actioncable)
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }

        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

android {
    namespace = "io.github.vad4nus.actioncable.check.legacy"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
    }

    flavorDimensions += listOf("brand", "env")
    productFlavors {
        create("core") { dimension = "brand" }
        create("partner") { dimension = "brand" }
        create("dev") { dimension = "env" }
        create("prod") { dimension = "env" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
