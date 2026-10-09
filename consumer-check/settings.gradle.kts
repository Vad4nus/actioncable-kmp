pluginManagement {
    val latest = providers.gradleProperty("matrix").orNull == "latest"
    val kotlinVersion = providers.gradleProperty("kotlin").orNull ?: if (latest) "2.4.10" else "2.1.21"
    val agpVersion = if (latest) "8.13.2" else "8.11.2"
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        kotlin("multiplatform") version kotlinVersion
        kotlin("android") version kotlinVersion
        id("com.android.kotlin.multiplatform.library") version agpVersion
        id("com.android.library") version agpVersion
        id("com.android.application") version agpVersion
    }
}

val latest = providers.gradleProperty("matrix").orNull == "latest"
val useCentral = providers.gradleProperty("useCentral").isPresent

dependencyResolutionManagement {
    repositories {
        exclusiveContent {
            forRepository { if (useCentral) mavenCentral() else mavenLocal() }
            filter { includeGroup("io.github.vad4nus") }
        }
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            val ktor = providers.gradleProperty("ktor").orNull ?: if (latest) "3.5.2" else "3.0.3"
            val coroutines = if (latest) "1.11.0" else "1.9.0"
            val serialization = if (latest) "1.11.0" else "1.7.3"
            library("actioncable", "io.github.vad4nus:actioncable-kmp:0.1.0")
            library("ktor-client-okhttp", "io.ktor:ktor-client-okhttp:$ktor")
            library("ktor-client-darwin", "io.ktor:ktor-client-darwin:$ktor")
            library("kotlinx-coroutines-core", "org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutines")
            library("kotlinx-coroutines-test", "org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutines")
            library("kotlinx-serialization-json", "org.jetbrains.kotlinx:kotlinx-serialization-json:$serialization")
            library("androidx-activity", "androidx.activity:activity-ktx:1.9.3")
            library("androidx-lifecycle-process", "androidx.lifecycle:lifecycle-process:2.8.5")
        }
    }
}

rootProject.name = "consumer-check"
include(":shared", ":androidApp", ":legacy")
