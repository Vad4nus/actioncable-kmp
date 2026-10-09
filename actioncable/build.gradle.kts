plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.maven.publish)
}

val ideSync = providers.systemProperty("idea.sync.active").orNull == "true"
val stress = ideSync || project.hasProperty("stress")
val e2e = stress || project.hasProperty("e2e")

kotlin {
    explicitApi()

    compilerOptions {
        optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }

    androidLibrary {
        namespace = "io.github.vad4nus.actioncable"
        compileSdk = 36
        minSdk = 21
        withHostTest {}
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(libs.ktor.client.core)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        val androidHostTest by getting {
            dependencies {
                implementation(libs.ktor.client.okhttp)
            }
        }

        val iosTest by getting {
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }

        if (e2e) {
            val e2eTest by creating {
                dependsOn(commonTest.get())
            }
            val e2eAndroidHostTest by creating {
                dependsOn(e2eTest)
                dependencies {
                    implementation(libs.ktor.client.okhttp)
                }
            }
            val e2eIosTest by creating {
                dependsOn(e2eTest)
                dependencies {
                    implementation(libs.ktor.client.darwin)
                }
            }
            androidHostTest.dependsOn(e2eAndroidHostTest)
            iosTest.dependsOn(e2eIosTest)
        }

        if (stress) {
            val stressTest by creating {
                dependsOn(getByName("e2eTest"))
            }
            val stressAndroidHostTest by creating {
                dependsOn(stressTest)
                dependsOn(getByName("e2eAndroidHostTest"))
            }
            val stressIosTest by creating {
                dependsOn(stressTest)
                dependsOn(getByName("e2eIosTest"))
            }
            androidHostTest.dependsOn(stressAndroidHostTest)
            iosTest.dependsOn(stressIosTest)
        }
    }
}

providers.gradleProperty("iosSimulatorDevice").orNull?.let { name ->
    tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
        device.set(name)
    }
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    coordinates("io.github.vad4nus", "actioncable-kmp", "0.1.0")
    pom {
        name.set("actioncable-kmp")
        description.set("Kotlin Multiplatform Action Cable client for Android and iOS, built on Ktor")
        inceptionYear.set("2026")
        url.set("https://github.com/vad4nus/actioncable-kmp")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("vad4nus")
                name.set("Vadim Danilenko")
                url.set("https://github.com/vad4nus")
            }
        }
        scm {
            url.set("https://github.com/vad4nus/actioncable-kmp")
            connection.set("scm:git:git://github.com/vad4nus/actioncable-kmp.git")
            developerConnection.set("scm:git:ssh://git@github.com/vad4nus/actioncable-kmp.git")
        }
    }
}
