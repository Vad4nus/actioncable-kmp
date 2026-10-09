plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

val stress = providers.gradleProperty("stress").isPresent
val e2e = stress || providers.gradleProperty("e2e").isPresent

kotlin {
    androidLibrary {
        namespace = "io.github.vad4nus.actioncable.check"
        compileSdk = 36
        minSdk = providers.gradleProperty("minSdk").getOrElse("26").toInt()
        withHostTest {}
    }

    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework { baseName = "Shared" }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.actioncable)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }

        val androidHostTest by getting {
            dependencies {
                implementation(libs.ktor.client.okhttp)
            }
        }

        val iosTest by getting

        if (e2e) {
            commonTest.get().kotlin.srcDir("../../actioncable/src/e2eTest/kotlin")
            androidHostTest.kotlin.srcDir("../../actioncable/src/e2eAndroidHostTest/kotlin")
            iosTest.kotlin.srcDir("../../actioncable/src/e2eIosTest/kotlin")
        }

        if (stress) {
            commonTest.get().kotlin.srcDir("../../actioncable/src/stressTest/kotlin")
            androidHostTest.kotlin.srcDir("../../actioncable/src/stressAndroidHostTest/kotlin")
            iosTest.kotlin.srcDir("../../actioncable/src/stressIosTest/kotlin")
        }
    }
}

providers.gradleProperty("iosSimulatorDevice").orNull?.let { name ->
    tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
        device.set(name)
    }
}
