import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
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
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }
    
    jvm()
    
    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
        }
        val ktor_version: String by project
        val vKmpTorResource: String by project
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.coroutinesCore)
            implementation("io.matthewnelson.kmp-tor:runtime:2.6.0")
            implementation("io.matthewnelson.kmp-tor:resource-exec-tor:${vKmpTorResource}")
            implementation("io.matthewnelson.kmp-tor:resource-noexec-tor:${vKmpTorResource}")
            implementation("io.ktor:ktor-network:${ktor_version}")
            implementation("io.ktor:ktor-io:${ktor_version}")
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation("dev.onvoid.webrtc:webrtc-java:0.14.0")
            if (webrtcNativeClassifier != null) {
                runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.14.0:$webrtcNativeClassifier")
            }
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}

android {
    namespace = "org.yapyap"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.yapyap"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
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
    debugImplementation(libs.compose.uiTooling)
}

compose.desktop {
    application {
        mainClass = "org.yapyap.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "org.yapyap"
            packageVersion = "1.0.0"
        }
    }
}
