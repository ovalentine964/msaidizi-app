// ============================================================
// OPTION B: KSP Migration — Root build.gradle.ksp
// ============================================================
// To use: rename build.gradle.kts → build.gradle.kts.kapt-backup
//         rename this file → build.gradle.kts
//
// Changes from original:
//   - Replaced `org.jetbrains.kotlin.kapt` with `com.google.devtools.ksp`
//   - Hilt plugin unchanged (supports KSP since 2.48)
//   - Kotlin 1.9.24 retained (KSP 1.9.24-1.0.20 is stable)
// ============================================================

plugins {
    id("com.android.application") version "8.2.2" apply false
    id("com.android.library") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("com.google.dagger.hilt.android") version "2.50" apply false
    // KSP replaces kapt — eliminates "Could not load module <Error module>"
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.4" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
