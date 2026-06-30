@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary) apply false
    id("app.cash.sqldelight") version "2.3.2"
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
    apply(plugin = "com.android.library")
}

val webrtcNativeClassifier: String? = run {
    val osName = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    when {
        osName.contains("win") -> if (arch.contains("64")) "windows-x86_64" else null
        osName.contains("mac") || osName.contains("darwin") ->
            if (arch.contains("aarch64") || arch.contains("arm64")) "macos-aarch64" else "macos-x86_64"
        osName.contains("linux") ->
            if (arch.contains("aarch64") || arch.contains("arm64")) "linux-aarch64" else "linux-x86_64"
        else -> null
    }
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
        iosTarget.binaries.all {
            linkerOpts("-lsqlite3")
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    sourceSets {
        val ktor_version: String by project
        val vKmpTorResource: String by project

        commonMain.dependencies {
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.runtime)
            implementation("io.matthewnelson.kmp-tor:resource-exec-tor:$vKmpTorResource")
            implementation("io.matthewnelson.kmp-tor:resource-noexec-tor:$vKmpTorResource")
            implementation("io.ktor:ktor-network:$ktor_version")
            implementation("io.ktor:ktor-io:$ktor_version")
            implementation(libs.coroutines.extensions)
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.provider.optimal)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
        if (enableAndroid) {
            androidMain.dependencies {
                implementation(libs.android.driver)
                implementation(libs.cryptography.provider.jdk.bc)
            }
        }
        iosMain.dependencies {
            implementation(libs.native.driver)
        }
        jvmMain.dependencies {
            implementation(libs.cryptography.provider.jdk.bc)
            implementation(libs.webrtc.java)
            if (webrtcNativeClassifier != null) {
                runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.14.0:$webrtcNativeClassifier")
            }
            implementation(libs.sqlite.driver)
            implementation(libs.sqlite.jdbc)
            implementation(libs.java.keyring)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}

if (enableAndroid) {
    extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
        namespace = "org.yapyap.core"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        defaultConfig {
            minSdk = libs.versions.android.minSdk.get().toInt()
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
    }
}

sqldelight {
    databases {
        create("YapYapDatabase") {
            packageName.set("org.yapyap.persistence")
        }
    }
}

/**
 * Opt-in slow / environment-sensitive JVM integration tests (real Tor, real WebRTC stack), e.g.:
 * `./gradlew :core:jvmTest -PintegrationTests=true --rerun-tasks`
 */
val integrationTestsEnabled =
    (findProperty("integrationTests") as? String)?.equals("true", ignoreCase = true) == true

tasks.named<Test>("jvmTest") {
    filter {
        if (!integrationTestsEnabled) {
            excludeTestsMatching("*TorRealBackendTransportIntegrationTest")
            excludeTestsMatching("*WebRtcInMemorySignalingIntegrationTest")
            excludeTestsMatching("*DefaultRouterLiveIntegrationTest")
            excludeTestsMatching("*DefaultKeyStoreJavaKeyringIntegrationTest")
            excludeTestsMatching("*JvmEncryptedDriverFactoryIntegrationTest")
        }
    }
}
