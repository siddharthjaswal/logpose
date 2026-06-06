import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library") version "8.7.3"
    kotlin("android") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    `maven-publish`
}

android {
    namespace = "io.github.siddharthjaswal.logpose"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // OkHttp is provided by the host app — keep it as compileOnly so LogPose
    // doesn't pin a version on consumers.
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

// Version comes from -Pversion (JitPack passes the git tag via $VERSION), defaulting
// to a dev version for local publishToMavenLocal.
version = (findProperty("version") as String?)?.takeIf { it != "unspecified" } ?: "0.1.0"

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "io.github.siddharthjaswal"
                artifactId = "logpose-android"
                version = project.version.toString()
            }
        }
    }
}
