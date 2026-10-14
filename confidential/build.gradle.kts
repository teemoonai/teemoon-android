plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ai.teemoon.confidential"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testOptions {
        managedDevices {
            localDevices {
                create("emu35") {
                    device = "Pixel 6"
                    apiLevel = 35
                    systemImageSource = "aosp"
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // AGP 9 removed kotlinOptions; the bytecode target stays 17 (Android), the build JDK is whatever runs Gradle.
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    // AttestationSummary reads Provider (direct-host routing); the verdict flows the other way via :chat.
    implementation(project(":providers"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    // X25519 / Ed25519 / secp256k1 recovery / ChaCha20-Poly1305 — one deterministic implementation on both the JVM and Android.
    implementation(libs.bouncycastle)
    implementation(libs.dcap.qvl.android)
    constraints {
        // dcap-qvl-android 0.5.2 pulls JNA 5.14.0, whose libjnidispatch.so ends its RELRO segment on a 4 KB boundary
        // (0x36000): a 16 KB-page kernel cannot make the last page read-only, and Android 16 shows every debuggable
        // build "This app isn't 16 KB compatible. RELRO alignment check failed" over the app (Pixel 10 Pro,
        // 2026-10-05). 5.19.1's ends at 0x2c000, 16 KB-aligned. A floor, not a fork: dcap-qvl's own pin moves past it.
        implementation("net.java.dev.jna:jna:5.19.1") { because("16 KB-aligned RELRO in libjnidispatch.so") }
    }
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":conformance"))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

/**
 * The drift tripwires: tests that read the LIVE teemoon-ios checkout rather than the
 * sha ports.json pins. They are reports about another repo, so they must not sit in
 * `./gradlew test` — CONTRIBUTING.md names that as the loop and "green or it is not done"
 * as the standard, and a loop that goes red every time iOS ships cannot enforce either.
 * They are skipped there by an Assume and run on purpose here.
 *
 *     ./gradlew driftTripwire
 */
tasks.register<Test>("driftTripwire") {
    group = "verification"
    description = "Drift tripwires against the live teemoon-ios checkout (skipped in `test`)"
    testClassesDirs = files(tasks.named("testDebugUnitTest").map { (it as Test).testClassesDirs })
    classpath = files(tasks.named("testDebugUnitTest").map { (it as Test).classpath })
    systemProperty("teemoon.driftTripwire", "true")
    filter { includeTestsMatching("*SelfVerifyScriptTest*") }
    outputs.upToDateWhen { false }
}
