import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

group = "com.rmawatson.flutterisolate"
version = "1.0-SNAPSHOT"

repositories {
        google()
        mavenCentral()
    }

android {
    namespace = "com.rmawatson.flutterisolate"
    
    compileSdk = 37
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    defaultConfig {
        minSdk = 24
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }
}

kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }