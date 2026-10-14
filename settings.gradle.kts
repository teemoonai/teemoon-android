// Modules mirror the iOS layout so every Kotlin file has one Swift reference
// file (docs/ANDROID_PORT.md §3, §9.1). :conformance holds vendored fixtures
// and no production code.

pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
// Downloads the JDK the root build declares when the machine has none — what makes a clean-room rebuild one command.
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}

rootProject.name = "teemoon"

include(":app", ":chat", ":providers", ":inference", ":confidential", ":conformance", ":ondevice")
