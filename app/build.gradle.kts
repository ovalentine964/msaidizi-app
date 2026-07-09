// ============================================================
// OPTION B: KSP Migration — app/build.gradle.ksp
// ============================================================
// To use: rename app/build.gradle.kts → app/build.gradle.kts.kapt-backup
//         rename this file → app/build.gradle.kts
//
// Changes from original:
//   - Replaced `id("org.jetbrains.kotlin.kapt")` with `id("com.google.devtools.ksp")`
//   - All `kapt(...)` → `ksp(...)`
//   - Room schema export uses KSP arguments (not javaCompileOptions)
//   - Removed `kapt { correctErrorTypes = true }` (KSP doesn't need it)
//   - Removed kapt-specific gradle.properties settings
// ============================================================

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")  // replaces: id("org.jetbrains.kotlin.kapt")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.gitlab.arturbosch.detekt")
}

detekt {
    config.setFrom("$rootDir/config/detekt/detekt.yml")
    buildUponDefaultConfig = true
    allRules = false
    disableDefaultRuleSets = false
    ignoredBuildTypes = listOf("debug")

    reports {
        html.required.set(true)
        xml.required.set(true)
        sarif.required.set(false)
        txt.required.set(false)
    }
}

android {
    namespace = "com.msaidizi.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.msaidizi.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Build config fields for model paths
        buildConfigField("String", "MODEL_DIR", "\"models\"")
        buildConfigField("String", "WHISPER_MODEL", "\"whisper-tiny-int4.onnx\"")
        buildConfigField("String", "LLM_MODEL", "\"qwen-0.5b-q4_k_m.gguf\"")
        buildConfigField("String", "TTS_MODEL", "\"piper-swahili.onnx\"")
        buildConfigField("String", "VAD_MODEL", "\"silero_vad.onnx\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            jniLibs.useLegacyPackaging = true
        }
        // Don't compress large model files — they need to be memory-mapped or streamed
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // AAPT options for large APK with model assets
    aaptOptions {
        // Don't compress model files (they're accessed via AssetManager or mmap)
        noCompress += listOf("gguf", "onnx", "txt")
        // Increase cruncher timeout for large assets
        cruncherProcesses = 4
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // Lifecycle (ViewModel, LiveData)
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")

    // Room Database — KSP replaces kapt for annotation processing
    // Updated 2.7.1: KMP support, improved paging, better KSP performance
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    implementation("androidx.room:room-paging:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")  // was: kapt(...)

    // SQLCipher for Room database encryption
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    implementation("androidx.sqlite:sqlite:2.4.0")

    // Hilt DI — KSP replaces kapt
    implementation("com.google.dagger:hilt-android:2.53.1")
    ksp("com.google.dagger:hilt-android-compiler:2.53.1")  // was: kapt(...)

    // Kotlin reflect — MUST match Kotlin compiler 1.9.24
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.9.24")

    // Coroutines — 1.9.0: improved structured concurrency, better debugging
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Kotlin Serialization (for JSON)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Retrofit + Gson
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // Network (Ktor client for sync)
    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-okhttp:3.0.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
    implementation("io.ktor:ktor-client-logging:3.0.3")

    // DataStore (preferences)
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // EncryptedSharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // WorkManager — 2.10.0: improved battery-aware scheduling
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")  // was: kapt(...)

    // zstd compression for sync
    implementation("com.github.luben:zstd-jni:1.5.5-11@aar")

    // ONNX Runtime — 1.20.0: ARM inference optimization, NNAPI improvements
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // Lottie for animations
    implementation("com.airbnb.android:lottie:6.3.0")

    // Timber logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // SwipeRefreshLayout
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // MPAndroidChart for dashboard
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // Testing — Unit (JUnit 5 + MockK + Turbine)
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.1")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.1")
    testImplementation("io.mockk:mockk:1.13.9")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("app.cash.turbine:turbine:1.0.0")
    // Keep JUnit 4 + vintage engine for backward compat
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.1")

    // Testing — Android Integration
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.room:room-testing:2.7.1")
    androidTestImplementation("androidx.test:runner:1.5.2")

    // Detekt linting
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.7")

    // Bouncy Castle — Post-Quantum Cryptography (ML-KEM, ML-DSA)
    // bcprov-jdk18on 1.79+ includes production FIPS 203 (ML-KEM) and FIPS 204 (ML-DSA)
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.79")
    implementation("org.bouncycastle:bctls-jdk18on:1.79")
}

// KSP configuration — Room schema export
// (replaces javaCompileOptions.annotationProcessorOptions for kapt)
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "false")
    arg("room.generateKotlin", "true")
    arg("room.verbose", "true")
}

// Force ALL Kotlin deps to match Kotlin 1.9.24 (compiler version)
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-reflect:1.9.24")
        force("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.24")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.24")
    }
}

// JUnit 5 platform for all test tasks
tasks.withType<Test> {
    useJUnitPlatform()
}

// JaCoCo code coverage reporting
apply(plugin = "jacoco")
tasks.withType<JacocoReport> {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
