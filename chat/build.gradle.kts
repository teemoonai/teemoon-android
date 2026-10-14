plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "ai.teemoon.chat"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // AGP 9 removed kotlinOptions; the bytecode target stays 17 (Android), the build JDK is whatever runs Gradle.
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    // The send gate consumes TrustSendPolicy; the verdict that produces it lives in :confidential.
    implementation(project(":confidential"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.kotlinx.coroutines.core)
    // The chat-search sidecar runs on the bundled SQLite: FTS5 on every device, not whichever the OEM shipped.
    implementation(libs.androidx.sqlite.bundled)
    ksp(libs.room.compiler)
    testImplementation(libs.room.testing)
    // JVM stand-in for the platform SQLite so ChatSearchIndex runs its real FTS5 SQL under test.
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":conformance"))
}

// Room's exported schemas are the migration paper trail; they belong in git.
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
