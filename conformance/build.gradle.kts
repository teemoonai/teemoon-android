// Vendored from teemoon-ios. NOT editable here (§7.2): every fixture that
// crosses repos crosses through conformance/ and the sync script, which is
// what carries the version stamp and what runs the disclosure audit before
// anything recorded from a real endpoint enters an AGPL repo.
//
// A fixture file elsewhere in this repo with no conformance/ provenance is a
// review-blocking defect.

plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.junit)
}

sourceSets { named("main") { resources.srcDir("vendor") } }

// Bytecode stays Java 17 (what the Android modules target) without pinning a 17 toolchain:
// the build runs on whatever JDK runs Gradle (JDK 25 today), like every other module.
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
