@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.*

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    kotlin("plugin.serialization") version "2.3.20"
}

fun hasAndroidSdkConfigured(): Boolean {
    val localPropertiesFile = rootProject.file("local.properties")

    if (localPropertiesFile.exists()) {
        val properties = Properties()
        localPropertiesFile.inputStream().use(properties::load)
        if (!properties.getProperty("sdk.dir").isNullOrBlank()) {
            return true
        }
    }

    return !System.getenv("ANDROID_SDK_ROOT").isNullOrBlank() || !System.getenv("ANDROID_HOME").isNullOrBlank()
}

val enableAndroid = providers.gradleProperty("enableAndroid")
    .map(String::toBoolean)
    .orElse(hasAndroidSdkConfigured())
    .get()

if (enableAndroid) {
    apply(plugin = "com.android.application")
}

kotlin {
    if (enableAndroid) {
        androidTarget {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_11)
            }
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    jvm()

    sourceSets.all {
        languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            // The runtime API surfaces StateFlows; the GUI collects them with its own copy.
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
        if (enableAndroid) {
            androidMain.dependencies {
                implementation(libs.compose.uiToolingPreview)
                implementation(libs.androidx.activity.compose)
            }
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
        }
    }
}

if (enableAndroid) {
    extensions.configure<com.android.build.api.dsl.ApplicationExtension>("android") {
        namespace = "org.yapyap"
        compileSdk = libs.versions.android.compileSdk.get().toInt()

        // IntelliJ's Android plugin treats a file at src/androidMain/AndroidManifest.xml as proof
        // that the project is Android and then refuses to sync without an SDK. Keeping the manifest
        // in a non-conventional folder lets the project sync SDK-free when Android is disabled.
        sourceSets.getByName("main").manifest.srcFile("src/androidMain/manifest/AndroidManifest.xml")

        defaultConfig {
            applicationId = "org.yapyap"
            minSdk = libs.versions.android.minSdk.get().toInt()
            targetSdk = libs.versions.android.targetSdk.get().toInt()
            versionCode = 1
            versionName = "0.0.1"
        }
        packaging {
            resources {
                excludes += "/META-INF/{AL2.0,LGPL2.1}"
            }
        }
        buildTypes {
            getByName("release") {
                isMinifyEnabled = false
            }
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
    }

    dependencies {
        add("debugImplementation", libs.compose.uiTooling)
    }
}

compose.desktop {
    application {
        mainClass = "org.yapyap.MainKt"

        nativeDistributions {
            targetFormats(
                TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage,
                TargetFormat.Exe
            )
            packageName = "YapYap"
            packageVersion = "0.0.1"
        }
    }
}
