plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.msaidizi.voice"
    compileSdk = 35

    defaultConfig {
        minSdk = 26

        // NDK targets for llama.cpp and sherpa-onnx JNI
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // Native build — compiles sherpa_jni, llama_jni, vad_jni
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Point to the CMakeLists.txt that builds sherpa_jni, llama_jni, vad_jni
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Native libs packaging
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += listOf("**/*.so")
        }
    }

    // AAPT noCompress for model files
    @Suppress("DEPRECATION")
    aaptOptions {
        noCompress("gguf", "onnx", "bin", "tflite", "vocab")
    }
}

dependencies {
    implementation(project(":core"))

    // AndroidX Core
    implementation(libs.androidx.core.ktx)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // WorkManager
    implementation(libs.work.runtime)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Logging
    implementation(libs.timber)

    // Gson
    implementation(libs.gson)
}
