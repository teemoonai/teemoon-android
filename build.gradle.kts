plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// The Kotlin compiler contract, once, for every module and every source set.
// Warnings are errors so the tree never accumulates them: a deprecation or an
// unnecessary `!!` fails the build the day it appears, not after an audit.
// The coroutines-test opt-in is what every `runTest` suite needs.
subprojects {
    // One JDK for every build of this tree, declared rather than documented: a third party on another JDK gets
    // a clear toolchain error (or, with the foojay resolver in settings.gradle.kts, the right JDK downloaded)
    // instead of a bundle that differs from the released one. Bytecode still targets 17 (below).
    pluginManager.withPlugin("org.jetbrains.kotlin.android") { extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension> { jvmToolchain(25) } }
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") { extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> { jvmToolchain(25) } }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            allWarningsAsErrors.set(true)
            // :conformance is fixtures only and has no coroutines on its classpath; an unresolved marker is itself a warning.
            if (project.name != "conformance") freeCompilerArgs.add("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
        }
    }
}
