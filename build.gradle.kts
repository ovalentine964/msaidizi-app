// ============================================================
// Msaidizi — Root build.gradle.kts
// ============================================================
// Uses KSP for annotation processing (Room, Hilt, WorkManager).
// Kotlin 1.9.24 + KSP 1.9.24-1.0.20
// ============================================================

plugins {
    id("com.android.application") version "8.2.2" apply false
    id("com.android.library") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("com.google.dagger.hilt.android") version "2.51.1" apply false
    // KSP replaces kapt — eliminates "Could not load module <Error module>"
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
