// ============================================================
// Msaidizi — Root build.gradle.kts
// ============================================================
// Uses KSP for annotation processing (Room, Hilt, WorkManager).
// Kotlin 2.0.21 + KSP 2.0.21-1.0.28
// ============================================================

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("com.google.dagger.hilt.android") version "2.52" apply false
    // KSP replaces kapt — eliminates "Could not load module <Error module>"
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
