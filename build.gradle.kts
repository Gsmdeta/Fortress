// Root build script — Fortress (defensive root security scanner).
//
// Why AGP 8.7 + Kotlin 2.0: AGP 8.7 supports compileSdk 35 and the
// FOREGROUND_SERVICE_SPECIAL_USE runtime attribute (API 34+), while Kotlin 2.0
// gives us stable coroutines/Flow ergonomics used across the scanner pipeline.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.0 ships the Compose compiler as a compiler plugin — no
    // composeOptions.kotlinCompilerExtensionVersion is needed anywhere.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
