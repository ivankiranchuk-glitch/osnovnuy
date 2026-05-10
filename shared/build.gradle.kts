import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions { jvmTarget = "17" }
        }
    }

    jvm("desktop") {
        compilations.all {
            kotlinOptions { jvmTarget = "17" }
        }
    }

    sourceSets {
        val commonMain by getting {
            kotlin.srcDirs(emptyList<String>())
        }

        val jvmAndroidMain by creating {
            dependsOn(commonMain)
            kotlin.srcDir("src/commonMain/kotlin")
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(libs.coroutines.core)
                implementation(libs.serialization.json)
                implementation(libs.bouncycastle)
            }
        }

        val androidMain by getting {
            dependsOn(jvmAndroidMain)
            dependencies {
                implementation(libs.coroutines.android)
                implementation(libs.lifecycle.viewmodel)
                implementation(libs.lifecycle.runtime)
                implementation(libs.lifecycle.compose)
                implementation(libs.activity.compose)
                implementation(libs.core.ktx)
            }
        }

        val desktopMain by getting {
            dependsOn(jvmAndroidMain)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.coroutines.swing)
            }
        }

        val commonTest by getting {
            kotlin.srcDirs(emptyList<String>())
        }

        val jvmAndroidTest by creating {
            dependsOn(commonTest)
            kotlin.srcDir("src/commonTest/kotlin")
            dependencies {
                implementation(libs.junit5)
                implementation(libs.mockk)
                implementation(libs.coroutines.test)
            }
        }

        val desktopTest by getting {
            dependsOn(jvmAndroidTest)
        }
    }
}

android {
    namespace = "com.kirivsoft.directlink"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.register<Test>("relaySmokeTest") {
    group = "verification"
    description = "Runs the focused relay host/join, encrypted text, file, and bidirectional payload smoke tests."
    val desktopTest = tasks.named<Test>("desktopTest")
    dependsOn("desktopTestClasses")
    testClassesDirs = desktopTest.get().testClassesDirs
    classpath = desktopTest.get().classpath
    useJUnitPlatform()
    filter {
        includeTestsMatching("com.kirivsoft.directlink.RelayNetworkPeerTest")
    }
    shouldRunAfter(desktopTest)
}
