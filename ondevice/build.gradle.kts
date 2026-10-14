plugins { alias(libs.plugins.android.library) }

android {
    namespace = "ai.teemoon.ondevice"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
    testImplementation(libs.junit)
    api(project(":inference"))
    // The on-device runtime (CONTRIBUTING.md allowlist: litertlm-android). arm64-v8a + x86_64 native libs; minSdk 24 upstream.
    implementation(libs.litertlm.android)
    implementation(libs.kotlinx.coroutines.core)
}
