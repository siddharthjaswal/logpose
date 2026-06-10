import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // No version — the Kotlin Gradle plugin is already on the build classpath (applied by
    // the root android library module).
    kotlin("jvm")
    `maven-publish`
}

// The no-op is a tiny pure-JVM library: it implements only okhttp3.Interceptor (no Android
// APIs), so it needs no Android SDK and ships as a plain jar that any Android app can consume.

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

dependencies {
    // Provided by the host app — compileOnly so the no-op pins no version and adds nothing
    // transitive to release builds.
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
}

// Version comes from -Pversion (JitPack passes the git tag via $VERSION), defaulting to a
// dev version for local publishToMavenLocal.
version = (findProperty("version") as String?)?.takeIf { it != "unspecified" } ?: "0.1.0"

publishing {
    publications {
        create<MavenPublication>("release") {
            from(components["java"])
            groupId = "io.github.siddharthjaswal"
            artifactId = "logpose-no-op"
            version = project.version.toString()
        }
    }
}
