plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ai.teemoon.inference"
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
    // ResponseVerification (and, next, the E2EE codec) come from :confidential; the transport consumes them.
    api(project(":confidential"))
    implementation(project(":providers"))   // TeemoonHttp: every client carries the cleartext gate
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":conformance"))
}
