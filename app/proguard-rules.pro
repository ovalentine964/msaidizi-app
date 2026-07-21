# Msaidizi ProGuard Rules
# Keep model classes and serialization

# Keep Room entities
-keep class com.msaidizi.app.core.model.** { *; }
-keep class com.msaidizi.app.core.database.** { *; }

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.msaidizi.app.**$$serializer { *; }
-keepclassmembers class com.msaidizi.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.msaidizi.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Hilt generated code
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keep class **_HiltModules* { *; }
-keep class **_HiltModules$* { *; }
-keep class **_GeneratedInjector { *; }
-keep class **_MembersInjector { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.lifecycle.HiltViewModelFactory { *; }

# Keep Room generated code
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class **_Impl { *; }
-keepclassmembers class * { @androidx.room.* <methods>; }
-keepclassmembers class * { @androidx.room.* <fields>; }

# Sentry crash reporting — keep all classes (reflection, event processors, etc.)
-keep class io.sentry.** { *; }
-dontwarn io.sentry.**

# ONNX Runtime
-keep class ai.onnxruntime.** { *; }

# Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class io.ktor.client.engine.** { *; }
-keep class io.ktor.serialization.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepclassmembers class kotlinx.coroutines.flow.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# zstd
-keep class com.github.luben.zstd.** { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep new adaptive learning and language classes
-keep class com.msaidizi.app.core.language.** { *; }
-keep class com.msaidizi.app.core.dialect.** { *; }
-keep class com.msaidizi.app.agent.AdaptiveLearningEngine { *; }
-keep class com.msaidizi.app.agent.BusinessPatternTracker { *; }
-keep class com.msaidizi.app.voice.VoicePipeline { *; }
-keep class com.msaidizi.app.voice.WhisperTokenizer { *; }
-keep class com.msaidizi.app.voice.transfer.ModelTransfer { *; }
-keep class com.msaidizi.app.sync.** { *; }

# Sherpa-ONNX JNI bridge — CRITICAL: native methods + reflection
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# Agent loops, AGI, and Hermes session management
-keep class com.msaidizi.app.agent.loops.** { *; }
-keep class com.msaidizi.app.agent.agi.** { *; }
-keep class com.msaidizi.app.agent.hermes.** { *; }

# QuantumReadyLayer — inner classes with crypto providers
-keep class com.msaidizi.app.core.security.QuantumReadyLayer$* { *; }
-keep class com.msaidizi.app.core.security.QuantumReadyLayer { *; }

# Voice engines (SherpaVoiceEngine, DialectLearningEngine, ModelRegistry)
-keep class com.msaidizi.app.voice.SherpaVoiceEngine { *; }
-keep class com.msaidizi.app.voice.DialectLearningEngine$* { *; }
-keep class com.msaidizi.app.voice.DialectLearningEngine { *; }
-keep class com.msaidizi.app.voice.ModelRegistry$* { *; }
-keep class com.msaidizi.app.voice.dialect.** { *; }

# Keep SQLCipher
-keep class net.zetetic.** { *; }
-dontwarn net.zetetic.**

# Bouncy Castle — Post-Quantum Cryptography (ML-KEM, ML-DSA)
# Must keep all PQC algorithm classes for runtime reflection and key generation
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class com.msaidizi.app.security.crypto.pqc.** { *; }

# ══════════════════════════════════════════════════════════════
# SECURITY: Strip verbose logging in release builds
# Prevents PII, tokens, keys, and internal state from leaking via logs
#
# Strategy (updated 2026-07-21):
#   - Strip ONLY Timber.v and android.util.Log.v (verbose — never needed in prod)
#   - KEEP Timber.d, Timber.i, Timber.w, Timber.e for crash debugging
#   - Model loading diagnostics (Timber.i), native lib loading (Timber.d),
#     and database operations (Timber.i) are critical for production debugging
#   - Sentry breadcrumbs use io.sentry.Sentry.addBreadcrumb() directly,
#     NOT Timber — so they are unaffected by these rules
# ══════════════════════════════════════════════════════════════

# Strip ONLY verbose android.util.Log calls in release builds
# KEEP debug/info/warn/error/wtf — needed for crash diagnostics
-assumenosideeffects class android.util.Log {
    public static int v(...);
}

# Strip ONLY verbose Timber calls in release builds
# KEEP debug/info/warn/error — needed for model loading, native lib, and DB diagnostics
-assumenosideeffects class timber.log.Timber {
    public static *** v(...);
}

# Strip verbose on Timber.Tree subclasses
-assumenosideeffects class timber.log.Timber$Tree {
    protected *** v(...);
}

# Remove java.util.logging fine/finer/finest (low-value verbose)
# KEEP info/warning/severe — these are meaningful diagnostic levels
-assumenosideeffects class java.util.logging.Logger {
    public static *** fine(...);
    public static *** finer(...);
    public static *** finest(...);
}

# Remove SLF4J trace/debug calls (verbose)
# KEEP info/warn/error — meaningful diagnostic levels
-assumenosideeffects class org.slf4j.Logger {
    public static *** trace(...);
    public static *** debug(...);
}

# SECURITY: Obfuscate stack traces in release (prevents info leakage)
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# SECURITY: Strip debug metadata
# NOTE: Intrinsics.checkNotNull stripping was intentionally REMOVED.
# Stripping it disables Kotlin null-safety in release builds, causing
# NullPointerExceptions where the compiler guarantees non-null types.
# This was the likely cause of release-only crashes.

# Keep WorkManager
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class androidx.work.impl.** { *; }

# Keep Hilt Workers
-keep class * extends androidx.hilt.work.HiltWorkerFactory { *; }

# Keep AndroidX Startup
-keep class androidx.startup.** { *; }
-keep class * extends androidx.startup.Initializer { *; }
