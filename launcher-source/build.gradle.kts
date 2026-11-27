import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application") version "8.10.1"
    id("org.jetbrains.kotlin.android") version "2.4.0"
}

android {
    namespace = "net.hotiana.launcher.source"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.hotiana.launcher.source"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"
    }

    sourceSets["main"].java.srcDir("../launcher-core/src/main/java")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}
