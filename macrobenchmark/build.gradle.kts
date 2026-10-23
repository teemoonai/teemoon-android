// Startup and scroll macrobenchmarks of :app (docs/PERFORMANCE_1.0.6.md). Android-only: no Swift counterpart.
//
//   ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest      (an API 29+ device or the teemoon AVD; never the owner's phone)
//   ./gradlew :app:generateBaselineProfile                       (API 33+; BaselineProfileGenerator)
//
// The measured app is :app's `benchmark` build type (release, profileable, debug-signed, ai.teemoon.benchmark). This module
// is self-instrumenting, so the two APKs also run as an ordinary instrumentation pair on Firebase Test Lab:
//   app:  app/build/outputs/apk/benchmark/app-benchmark.apk
//   test: macrobenchmark/build/outputs/apk/benchmark/macrobenchmark-benchmark.apk
plugins {
    alias(libs.plugins.android.test)
    // Produces the Baseline Profile (BaselineProfileGenerator) for :app.
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "ai.teemoon.macrobenchmark"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // An emulator is fine for a smoke run (the numbers are not); without this the library refuses to run on one.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }
    buildTypes {
        // Matches :app's `benchmark`. Debuggable is the TEST apk only; the app it measures is not.
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}

// No `debug` variant: it would measure :app's debuggable build, which measures nothing. `benchmark` runs the benchmarks;
// the plugin's nonMinifiedRelease runs the generator (and benchmarkRelease mirrors `benchmark`).
androidComponents {
    beforeVariants(selector().all()) { it.enable = it.buildType != "debug" }
}

baselineProfile {
    useConnectedDevices = true   // the teemoon AVD or a lab device; ANDROID_SERIAL picks one — never the owner's phone
}
