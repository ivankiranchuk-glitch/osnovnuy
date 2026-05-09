import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvm()
    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation(compose.desktop.currentOs)
                implementation(libs.coroutines.swing)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.kirivsoft.directlink.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "DirectLink"
            packageVersion = "1.0.0"
            description = "Encrypted P2P Direct Tunnel"
            vendor = "KirivSoft"
            licenseFile.set(rootProject.file("LICENSE"))

            windows {
                dirChooser = true
                perUserInstall = true
                menuGroup = "KirivSoft"
                upgradeUuid = "4B8C1D2E-3F4A-5B6C-7D8E-9F0A1B2C3D4E"
            }
            macOS {
                bundleID = "com.kirivsoft.directlink"
                packageVersion = "1.0.0"
                dmgPackageVersion = "1.0.0"
            }
            linux {
                debMaintainer = "kirivsoft@example.com"
                menuGroup = "Network"
            }
        }

        jvmArgs("-Xss4m", "-Xmx256m")

        buildTypes.release.proguard {
            configurationFiles.from(project.file("proguard-rules.pro"))
        }
    }
}

val jvmMainCompilation = kotlin.targets.getByName("jvm").compilations.getByName("main")

tasks.register<JavaExec>("runRelayServer") {
    group = "application"
    description = "Runs the DirectLink TCP relay server. Pass -PrelayPort=47777 to choose a port."
    dependsOn(jvmMainCompilation.compileTaskProvider)
    mainClass.set("com.kirivsoft.directlink.desktop.RelayServerMainKt")
    classpath = jvmMainCompilation.output.allOutputs + jvmMainCompilation.runtimeDependencyFiles
    args((findProperty("relayPort") as String?) ?: "47777")
}
