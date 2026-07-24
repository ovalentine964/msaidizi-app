import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.msaidizi.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.msaidizi.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-alpha1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // NDK targets for llama.cpp and sherpa-onnx JNI
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // Room schema export
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    // ── Signing ──────────────────────────────────────────────
    // Release signing reads from env vars (CI) or a local
    // keystore.properties file (developer workstation).
    signingConfigs {
        create("release") {
            val keystoreFile = System.getenv("APK_SIGNING_KEYSTORE_FILE")
                ?: rootProject.file("keystore.properties").let { f ->
                    if (f.exists()) {
                        Properties().apply { load(FileInputStream(f)) }
                            .getProperty("store.file")
                    } else null
                }

            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("APK_KEYSTORE_PASSWORD")
                    ?: loadProperty("store.password")
                keyAlias = System.getenv("APK_KEY_ALIAS")
                    ?: loadProperty("key.alias")
                keyPassword = System.getenv("APK_KEY_PASSWORD")
                    ?: loadProperty("key.password")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only apply signing config if keystore is available
            val ks = signingConfigs.getByName("release")
            if (ks.storeFile != null && ks.storeFile!!.exists()) {
                signingConfig = ks
            }
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // ── Native libs & model assets ───────────────────────────
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += listOf(
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
            )
        }
    }

    // AAPT noCompress — prevents compression of model files in assets/
    // so they can be memory-mapped directly from the APK at runtime.
    // llama.cpp and sherpa-onnx both expect uncompressed, seekable files.
    @Suppress("DEPRECATION")
    aaptOptions {
        noCompress("gguf", "onnx", "bin", "tflite", "vocab")
    }
}

// ── Helper: load from keystore.properties ────────────────────
fun loadProperty(key: String): String {
    val propsFile = rootProject.file("keystore.properties")
    if (!propsFile.exists()) return ""
    return Properties().apply { load(FileInputStream(propsFile)) }
        .getProperty(key, "")
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.activity)
    implementation(libs.compose.navigation)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Lifecycle
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room + SQLCipher
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)
    implementation(libs.sqlcipher)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Serialization
    implementation(libs.serialization.json)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // DataStore
    implementation(libs.datastore.preferences)

    // Security
    implementation(libs.security.crypto)

    // Logging
    implementation(libs.timber)

    // Gson (JNI interop)
    implementation(libs.gson)

    // WorkManager (model download on first launch)
    implementation(libs.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
}
