import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // JVM snapshots of the screens with layout risk (test-only; allowlisted in CONTRIBUTING.md). Needs JDK 21+.
    alias(libs.plugins.paparazzi)
}

android {
    // LiteRT-LM finds a vendor NPU dispatch library by LISTING the directory it is handed
    // (litert_dispatch.cc: "No dispatch library found in <nativeLibraryDir>"), not by dlopen-by-name.
    // Modern packaging leaves .so files inside the APK and that directory EMPTY, so the Tensor
    // dispatcher was present in the APK and never found (Pixel 10 Pro, 2026-09-16). Legacy packaging
    // extracts them at install, which is the only way that directory is populated on API 29+. (Loading
    // from the app's own data directory does work — measured on API 35 — but would mean copying them
    // there on every update; NpuDispatch links to this directory instead.)
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Never strip the prebuilt native libraries. AGP strips them with whatever NDK the build machine happens to
            // have — and skips the step, silently, on a machine without one — so two clean builds of the same tag
            // packed different bytes (release-verify of 1.0.4 (3909): five .so files differed, 4.8 MB of dcap-qvl
            // debug symbols among them). Keeping the symbols costs a few MB and makes the APK a function of the source
            // alone, which is what docs/REPRODUCIBLE.md promises. The upstream releases are already stripped where it
            // matters; the 8-byte deltas on the LiteRT libraries were llvm-strip rewriting section headers.
            keepDebugSymbols += "**/*.so"
        }
    }
    namespace = "ai.teemoon.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "ai.teemoon"
        manifestPlaceholders["appLabel"] = "teemoon"
        minSdk = 29
        targetSdk = 37
        // Versions follow iOS: versionName is the iOS release this build is ported
        // from — "-beta.N" until every port has caught up with that release's tag (the 1.0.4 tree is
        // teemoon-ios a411f18; drift 0 since 2026-10-02, kit 0.5.0) — and versionCode is that release's iOS build
        // number × 100 + the beta number, 99 for the release itself. 1.0.4 is iOS build 38: 1.0.4-beta.1 → 3801, beta.2 → 3802,
        // 1.0.4 → 3899, and iOS's next build starts above both, so Play's codes only ever rise.
        versionCode = 3916   // 1.0.5: saved Fireworks setups on kimi-k2p6 move to kimi-k3 with a notice (3916, iOS 5d2fb66); the developer card shows decode and prefill for on-device turns, no drafter asked of the 8 Elite NPU (3914); 1.0.4 on LiteRT-LM 0.18.0 / LiteRT 2.3.0 (3913); 1.0.4 with the 2026-10-13/14 audit fixes: key hygiene, notification channels, clipboard expiry, seeding gate (3912); 3911 1.0.4 pinned dark and running LiteRT as iOS does (thinking off, 4800 cap), search payload sized to the bundle's window (3911); 3910 the first verified rebuild; 3908 the Play review build; 3899 the first 1.0.4
        versionName = "1.0.5"
        testInstrumentationRunner = "ai.teemoon.app.TeemoonTestRunner"   // arms UiTestSeeding (UiTestSeeding.armed)
    }
    // The one emulator job (CONTRIBUTING.md): `./gradlew pixelDebugAndroidTest` runs the device suites on a
    // managed AOSP ATD image — no device on the desk, no laptop-side script. (SmokeTest, its first test, was removed
    // 2026-10-14: every label it tapped was gone, and its search assertion is journey F3.)
    testOptions {
        // Steady frames for the screen crawl and the capture suites: a screenshot taken mid-transition is a
        // screenshot of nothing in particular, and a golden recorded from one is worse.
        animationsDisabled = true
        managedDevices {
            localDevices {
                create("pixel") {
                    device = "Pixel 6"
                    apiLevel = 34
                    systemImageSource = "aosp-atd"
                }
            }
        }
    }
    // Release signing (the developer key is ENROLLED in Play App Signing, so Play and the
    // GitHub APK carry one signature). The keys and their passwords live OUTSIDE the repo, in
    // ~/.teemoon-release/keystore.properties (or $TEEMOON_RELEASE_PROPS); without that file a release build is simply
    // unsigned, so every other machine and CI still builds. Play bundles are signed with the UPLOAD key; the GitHub APK
    // with the app-signing key itself: `-Pteemoon.signWith=app`.
    val releaseProps = Properties().apply {
        val f = file(System.getenv("TEEMOON_RELEASE_PROPS") ?: "${System.getProperty("user.home")}/.teemoon-release/keystore.properties")
        if (f.isFile) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (releaseProps.getProperty("uploadStoreFile") != null) create("upload") {
            storeFile = file(releaseProps.getProperty("uploadStoreFile")); storePassword = releaseProps.getProperty("uploadPassword")
            keyAlias = releaseProps.getProperty("uploadKeyAlias"); keyPassword = releaseProps.getProperty("uploadPassword")
        }
        if (releaseProps.getProperty("appSigningStoreFile") != null) create("appSigning") {
            storeFile = file(releaseProps.getProperty("appSigningStoreFile")); storePassword = releaseProps.getProperty("appSigningPassword")
            keyAlias = releaseProps.getProperty("appSigningKeyAlias"); keyPassword = releaseProps.getProperty("appSigningPassword")
        }
    }
    buildTypes {
        // `-Pteemoon.devId=true`: the build installs as ai.teemoon.dev, BESIDE the store build. The dev phone runs
        // the Play build with its owner's data; installing a build over it means an uninstall, which wipes it
        // (2026-09-17). With its own id the test app has its own data and the owner's app is never touched. Off by
        // default, so the AVD's ai.teemoon (and the weights pushed into it) stay where every script expects them.
        // Both build types take it: a DEBUG dev build misses a frame in four on the Pixel (99th percentile 101 ms vs
        // 15 ms for the release build, 2026-10-12), so motion and feel are judged on a RELEASE dev build —
        // `assembleRelease -Pteemoon.devId=true -Pteemoon.signWith=app -Pteemoon.abi=arm64`.
        val devId = project.findProperty("teemoon.devId") == "true"
        // `-Pteemoon.e2eId=true`: a THIRD app, ai.teemoon.e2e, for device suites on the dev phone. The release dev build
        // there carries the owner's keys and chats and is signed with the app key, so a debug build cannot replace it
        // without an uninstall, which wipes it — and the suites' seeding would replace its places anyway (2026-10-13).
        // The e2e app has its own data and is uninstalled after the run. iOS does the same (ai.teemoon.app.e2e).
        val e2eId = project.findProperty("teemoon.e2eId") == "true"
        getByName("debug") {
            if (devId) { applicationIdSuffix = ".dev"; manifestPlaceholders["appLabel"] = "teemoon dev" }
            if (e2eId) { applicationIdSuffix = ".e2e"; manifestPlaceholders["appLabel"] = "teemoon e2e" }
        }
        getByName("release") {
            if (devId) { applicationIdSuffix = ".dev"; manifestPlaceholders["appLabel"] = "teemoon dev" }
            val which = if (project.findProperty("teemoon.signWith") == "app") "appSigning" else "upload"
            signingConfigs.findByName(which)?.let { signingConfig = it }
            // ABIs. LiteRT-LM ships arm64-v8a and x86_64 only; the 32-bit armeabi-v7a and x86 libraries in the
            // dependency set (dcap-qvl, bundled SQLite, JNA) would let a 32-bit-only phone install an app whose
            // on-device model then fails to load. Dropping them makes Play refuse those devices instead. x86_64 stays
            // for the Play bundle (Play serves one ABI per device, so phones never download it; Intel Chromebooks need
            // it). `-Pteemoon.abi=arm64` is the GitHub door: a universal APK has no per-device split, and x86_64 is
            // 33 MB nobody with a phone can use.
            ndk { abiFilters += if (project.findProperty("teemoon.abi") == "arm64") setOf("arm64-v8a") else setOf("arm64-v8a", "x86_64") }
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // AGP 9 removed kotlinOptions; the bytecode target stays 17 (Android), the build JDK is whatever runs Gradle.
}

dependencies {
    implementation(project(":chat"))
    implementation(project(":providers"))
    implementation(project(":inference"))
    implementation(project(":confidential"))
    implementation(project(":ondevice"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.markdown.renderer.m3)   // the markdown renderer (CONTRIBUTING.md allowlist): lists, tables, code, quotes — Textual's role on iOS   // list.bullet / gear / plus / xmark / lock / search / check

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    // The kit's quotes/collateral classes, proved against the real dcap-qvl engine on the device.
    androidTestImplementation(project(":conformance"))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(platform(libs.compose.bom))
    // Diagnostic only (OnDeviceDecodeBenchTest): :ondevice keeps the runtime `implementation`, so the flags it exposes
    // are not on the app's compile path. A test that measures the runtime has to be able to talk to it.
    androidTestImplementation(libs.litertlm.android)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.espresso.core)   // pins 3.7.0 over the 3.5.0 ui-test drags in; see the catalog note
    debugImplementation(libs.compose.ui.test.manifest)
}

/**
 * THE WIRING GATE. The JVM suites prove the policy functions; this proves the app
 * actually calls them. Every defect of the 2026-09-20 audit was a call site — a
 * retry that skipped the send gate, a gate argument hardcoded `false`, a computed
 * verdict with no `when` arm, and a duplicate SendPrep the app called instead of
 * the mapped one. All four passed a green suite. See tools/behavior_ledger.py.
 *
 * It hangs off `test` as well as `check` on purpose: CONTRIBUTING.md names `./gradlew test`
 * as the loop, and `test` does not run `check`. A gate that only fires in a command
 * nobody types is the failure mode this whole exercise was about — the repo's only
 * CI file still opens "# NEVER RUN". Pure Python, no device, ~100 ms.
 */
val behaviorLedger = tasks.register<Exec>("behaviorLedger") {
    group = "verification"
    description = "Wiring parity: policy call sites in app/ (tools/behavior_ledger.py)"
    workingDir = rootDir
    commandLine("python3", "tools/behavior_ledger.py", "--gate")
    // ports.json carries the rules; the Kotlin sources are what they are asserted against.
    inputs.file(rootProject.file("ports.json"))
    inputs.file(rootProject.file("tools/behavior_ledger.py"))
    inputs.files(rootProject.fileTree(".") { include("*/src/main/kotlin/**/*.kt") })
    outputs.upToDateWhen { false }
}

tasks.named("check") { dependsOn(behaviorLedger) }
// AGP registers the unit-test tasks lazily, so `tasks.named("test")` does not resolve at
// configuration time. withType catches testDebugUnitTest / testReleaseUnitTest — the tasks
// `./gradlew test` actually runs.
tasks.withType<Test>().configureEach { dependsOn(behaviorLedger) }

/*
 * THE SNAPSHOT GATE, on `check` — for the reason above, found the hard way on 2026-09-21.
 *
 * `verifyPaparazziDebug` was green in nobody's build because nothing depended on it, and the
 * where sheet's four goldens had been stale since the OpenRouter and NVIDIA presets landed
 * (f198e3e): two new "browse …" rows that no one had looked at or re-recorded. CONTRIBUTING.md says
 * to record new baselines and commit them with the change, and there was no way to notice the
 * rule had been broken. Five RenderRecordTest cases were failing it too — they drive
 * composition through `paparazzi.snapshot` to collect rows, which under verify means a golden
 * they had never been given.
 *
 * On `check` rather than `test` on purpose. Verify is `testDebugUnitTest` again with a flag
 * set, so this costs a second run of the unit suite (~20 s here) — too much for the inner loop
 * CONTRIBUTING.md names, right for the command you type before committing a screen.
 */
tasks.named("check") { dependsOn("verifyPaparazziDebug") }
