import java.io.File
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // JVM snapshots of the screens with layout risk (test-only; allowlisted in CONTRIBUTING.md). Needs JDK 21+.
    alias(libs.plugins.paparazzi)
    // Consumes the Baseline Profile :macrobenchmark generates (androidx.benchmark; AndroidX, allowlisted).
    alias(libs.plugins.androidx.baselineprofile)
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
        versionCode = 3919   // 1.1.1: every enclave near.ai lists is checked (C3a) and the trust screens say who holds the key (key-custody copy v2), a model key with no quote blocks (iOS 1.1.1 quick patch) (3919); 1.1.0: import chats from near.ai private chat (IMPORT_NEAR.md draft 8), a chat named once by its first question, the sources chip under a reply that thinks (iOS 2fd9f7e) (3918); 1.0.6 (internal track, then production): the near.ai signature verifier (ed25519 against the quote-bound key, gateway at most GatewayTrustOnly, NRAS JWT checked, TCB allow-list) and the 1.0.6 performance work — kept on-device conversation, shared connection pool, late reply-signature check, day-resolved {{datetime}} with the minute on the turn, Fireworks session affinity, streamed markdown recomposing only the typed block, Think Harder on the newest reply (high effort, 8192 out), low effort by default, per-turn "[Sent at …]" notes and {{date}}, a 600 s header wait (iOS 1.0.6 build 45) (3917); 1.0.5: saved Fireworks setups on kimi-k2p6 move to kimi-k3 with a notice (3916, iOS 5d2fb66); the developer card shows decode and prefill for on-device turns, no drafter asked of the 8 Elite NPU (3914); 1.0.4 on LiteRT-LM 0.18.0 / LiteRT 2.3.0 (3913); 1.0.4 with the 2026-10-13/14 audit fixes: key hygiene, notification channels, clipboard expiry, seeding gate (3912); 3911 1.0.4 pinned dark and running LiteRT as iOS does (thinking off, 4800 cap), search payload sized to the bundle's window (3911); 3910 the first verified rebuild; 3908 the Play review build; 3899 the first 1.0.4
        versionName = "1.1.1"
        testInstrumentationRunner = "ai.teemoon.app.TeemoonTestRunner"   // arms UiTestSeeding (UiTestSeeding.armed)
    }
    // The one emulator job (CONTRIBUTING.md): `./gradlew pixelDebugAndroidTest` runs the device suites on a
    // managed AOSP ATD image — no device on the desk, no laptop-side script. (SmokeTest, its first test, was removed
    // 2026-10-14: every label it tapped was gone, and its search assertion is journey F3.)
    testOptions {
        // Steady frames for the screen crawl and the capture suites: a screenshot taken mid-transition is a
        // screenshot of nothing in particular, and a golden recorded from one is worse.
        animationsDisabled = true
        // Robolectric reads the merged manifest (the compose test rule's ComponentActivity, debug only) and resources
        unitTests.isIncludeAndroidResources = true
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
        // `benchmark`: what :macrobenchmark measures. A release build (a debuggable one measures the interpreter, not the
        // app), profileable so the shell can trace it, signed with the debug key so any machine can build and install it,
        // and installed as ai.teemoon.benchmark — beside the AVD's ai.teemoon and its pushed weights, never over a store
        // build. Its seeding arm is the build type itself (BENCHMARK_SEEDING below, UiTestSeeding.consumeArm). Never shipped.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            isDebuggable = false
            isProfileable = true
            applicationIdSuffix = ".benchmark"
        }
    }
    // buildConfig: BuildConfig.DEBUG gates DebugDiagnostics (StrictMode, JankStats) to debug builds.
    buildFeatures { compose = true; buildConfig = true; aidl = true }
    // The isolated image decoder's Wuffs (IMAGES.md §2, the maintainer 2026-10-25). Both versions pinned: the reproducible build
    // depends on the same NDK and CMake on every machine that builds a release.
    ndkVersion = "27.2.12479018"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // AGP 9 removed kotlinOptions; the bytecode target stays 17 (Android), the build JDK is whatever runs Gradle.
}

// BuildConfig.BENCHMARK_SEEDING: true only for the measured build types, where seeding cannot be armed the debuggable
// way (UiTestSeeding.consumeArm). Set per variant rather than per build type so the Baseline Profile plugin's own
// build types, which it creates from `release`, get it too. Every shipped variant reads false.
val benchmarkBuildTypes = setOf("benchmark", "nonMinifiedRelease", "benchmarkRelease")
androidComponents {
    // The Baseline Profile plugin's own build types (copies of `release`, which the plugin creates in ITS finalizeDsl, so a
    // buildTypes block never sees them): the same id and key as `benchmark`. Without this, generating a profile on the
    // AVD tried to install a release-keyed ai.teemoon over its debug-keyed one — refused, here; an uninstall would wipe it.
    finalizeDsl { ext ->
        ext.buildTypes.matching { it.name == "nonMinifiedRelease" || it.name == "benchmarkRelease" }.configureEach {
            signingConfig = ext.signingConfigs.getByName("debug")
            applicationIdSuffix = ".benchmark"
        }
    }
    onVariants { variant ->
        val on = variant.buildType in benchmarkBuildTypes
        variant.buildConfigFields?.put("BENCHMARK_SEEDING", com.android.build.api.variant.BuildConfigField("boolean", on.toString(), "measured build: the build type arms seeding"))
    }
}

// The generated profile lives in src/main (merged, committed), so every variant — release and `benchmark` — ships it.
baselineProfile {
    mergeIntoMain = true
    saveInSrc = true
    automaticGenerationDuringBuild = false   // a release build never needs a device
}
// The plugin wires that directory into `release` only; `benchmark` must carry the same profile or StartupBenchmark's
// Partial run measures a build users never get. (nonMinifiedRelease stays without it: generation starts from nothing.)
// MigrationTestHelper reads the exported Room schemas as assets (ChatMigrationTest).
android.sourceSets.getByName("androidTest").assets.directories.add("$rootDir/chat/schemas")
android.sourceSets.getByName("benchmark").baselineProfiles.directories.add("src/main/generated/baselineProfiles")
// The store this build is for (the maintainer 2026-11-01: the rating prompt in 1.1, the APK free of Google Play libraries). `-Pteemoon.store=play`
// is the Play bundle, with Play In-App Review (src/store-play + src/play-review). Anything else, the default, is the
// GitHub APK and every local build: src/store-github's no-op and no Google Play library on the classpath at all. A
// property, not product flavours, so every task name, output path and REPRODUCIBLE.md's recipe stay as they were.
val playStore = project.findProperty("teemoon.store") == "play"
android.sourceSets.getByName("main").kotlin.directories.addAll(
    if (playStore) listOf("src/store-play/kotlin", "src/play-review/kotlin") else listOf("src/store-github/kotlin"))
// the Play half is tested on every run (JVM: the rule and a refused request; device: FakeReviewManager), whichever
// store the build is for
android.sourceSets.getByName("test").kotlin.directories.add("src/play-review/kotlin")
if (!playStore) android.sourceSets.getByName("androidTest").kotlin.directories.add("src/play-review/kotlin")

// Robolectric's Android runtime jar, resolved here and handed to the tests offline (ROBOLECTRIC, below)
val robolectricRuntime: Configuration = configurations.create("robolectricRuntime") { isCanBeConsumed = false; isTransitive = false }

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
    implementation(libs.androidx.profileinstaller)   // installs the Baseline Profile where Play does not
    "baselineProfile"(project(":macrobenchmark"))
    implementation(libs.androidx.metrics.performance)   // JankStats; debug builds only at runtime (DebugDiagnostics)
    implementation(libs.markdown.renderer.m3)   // the markdown renderer (CONTRIBUTING.md allowlist): lists, tables, code, quotes — Textual's role on iOS   // list.bullet / gear / plus / xmark / lock / search / check

    if (playStore) implementation(libs.play.review)
    testImplementation(libs.play.review)
    androidTestImplementation(libs.play.review)
    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":confidential")))
    testImplementation(libs.kotlinx.coroutines.test)
    // The motion check (MotionCheck*): Compose UI tests on the JVM, stepped a frame at a time. Test scope only; the
    // release runtime classpath is unchanged (proved by a dependency report diff in the commit that added it).
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    "robolectricRuntime"(libs.robolectric.android.all)
    androidTestImplementation(libs.androidx.test.runner)
    // The kit's quotes/collateral classes, proved against the real dcap-qvl engine on the device.
    testImplementation(project(":conformance"))   // the import corpus (ChatImportRunnerTest)
    androidTestImplementation(project(":conformance"))
    androidTestImplementation(libs.room.testing)   // ChatMigrationTest: MigrationTestHelper over chat/schemas
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

/*
 * ROBOLECTRIC, offline and pinned (approved via pm, 2026-11-14; pins are option B until full dependency
 * verification lands after 1.1.0). Robolectric fetches its Android runtime jar from Maven at test time unless told
 * otherwise: Gradle resolves it here instead and the tests run with robolectric.offline, so `./gradlew test` makes no
 * network call. verifyRobolectricPins fails the unit run when any artifact Robolectric brings (its whole subgraph on the
 * unit-test classpath, plus the runtime jar) is missing from gradle/robolectric-pins.txt or has another sha256.
 * Re-pin after a deliberate bump: ./gradlew --no-daemon :app:verifyRobolectricPins -Pteemoon.repinRobolectric=true
 */
val robolectricJars = tasks.register<Sync>("robolectricJars") {
    from(robolectricRuntime)
    into(layout.buildDirectory.dir("robolectric-jars"))
}
val verifyRobolectricPins = tasks.register("verifyRobolectricPins") {
    group = "verification"
    description = "Robolectric's artifacts against gradle/robolectric-pins.txt (sha256)"
    val cfg = configurations.named("debugUnitTestRuntimeClasspath")
    val rootComponent = cfg.flatMap { it.incoming.resolutionResult.rootComponent }
    // external modules only: a project dependency (:chat, :inference …) is this repo's own code, and selecting its artifact
    // with no artifact type is ambiguous
    val artifacts = cfg.flatMap { c -> c.incoming.artifactView { componentFilter { it is org.gradle.api.artifacts.component.ModuleComponentIdentifier } }.artifacts.resolvedArtifacts }
    val runtimeJars = robolectricRuntime.incoming.artifacts.resolvedArtifacts
    val pins = rootProject.file("gradle/robolectric-pins.txt")
    val repin = providers.gradleProperty("teemoon.repinRobolectric").map { it == "true" }.orElse(false)
    outputs.upToDateWhen { false }
    doLast {
        val reach = mutableSetOf<org.gradle.api.artifacts.component.ComponentIdentifier>()
        fun walk(c: org.gradle.api.artifacts.result.ResolvedComponentResult) {
            if (reach.add(c.id)) c.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>().forEach { walk(it.selected) }
        }
        rootComponent.get().dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>().map { it.selected }
            .filter { (it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.let { m -> m.group == "org.robolectric" && m.module == "robolectric" } == true }
            .forEach(::walk)
        check(reach.isNotEmpty()) { "org.robolectric:robolectric is not on the unit-test classpath" }
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { b -> "%02x".format(b) }
        val now = (artifacts.get().filter { it.id.componentIdentifier in reach } + runtimeJars.get())
            .map { "${it.id.componentIdentifier.displayName} ${it.file.name} ${sha(it.file)}" }.toSortedSet()
        if (repin.get()) { pins.writeText("# Robolectric's artifacts and their sha256 (app/build.gradle.kts: verifyRobolectricPins)\n" + now.joinToString("\n") + "\n"); return@doLast }
        val pinned = if (pins.exists()) pins.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.toSortedSet() else sortedSetOf()
        val changed = (now - pinned).map { "not pinned or changed: $it" } + (pinned - now).map { "pinned, not resolved: $it" }
        check(changed.isEmpty()) { "Robolectric pins differ (gradle/robolectric-pins.txt):\n" + changed.joinToString("\n") }
    }
}
tasks.withType<Test>().configureEach {
    dependsOn(verifyRobolectricPins, robolectricJars)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-jars").get().asFile.absolutePath)
}
// AGP registers the unit-test tasks lazily, so `tasks.named("test")` does not resolve at
// configuration time. withType catches testDebugUnitTest / testReleaseUnitTest — the tasks
// `./gradlew test` actually runs.
tasks.withType<Test>().configureEach { dependsOn(behaviorLedger) }

/*
 * THE MOTION CHECK RUNS IN ITS OWN JVM. Robolectric and Paparazzi each swap in their own Android, and once a Robolectric
 * test had run in a test JVM every Paparazzi test after it failed ("Failed to init Bridge"): the unit gate stayed green
 * only by the order the classes happened to run in. The unit tasks leave ai.teemoon.app.motion out; this task runs it,
 * from the debug unit test's classes, and `test` (what merge_on_green and CONTRIBUTING.md run) depends on it.
 */
val motionCheck = tasks.register<Test>("testDebugMotionCheck") {
    group = "verification"
    description = "The motion check (Robolectric), apart from Paparazzi's JVM"
    val unit = tasks.named<Test>("testDebugUnitTest")
    testClassesDirs = files(unit.map { it.testClassesDirs })
    classpath = files(unit.map { it.classpath })
    include("ai/teemoon/app/motion/**")
    dependsOn(unit.map { it.dependsOn })
    useJUnit()
}
tasks.withType<Test>().matching { it.name.endsWith("UnitTest") }.configureEach { exclude("ai/teemoon/app/motion/**") }
tasks.matching { it.name == "test" }.configureEach { dependsOn(motionCheck) }

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
