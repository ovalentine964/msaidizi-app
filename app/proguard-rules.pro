# Msaidizi ProGuard Rules
# Keep model classes and serialization

# ══════════════════════════════════════════════════════════════
# FIX 1: COMPREHENSIVE PROGUARD RULES — Prevent crash-on-launch
# ══════════════════════════════════════════════════════════════

# Keep ALL classes with @Inject constructors (Hilt DI)
# Without this, R8 strips constructor params → Dagger can't instantiate
-keepclasseswithmembers class * {
    @javax.inject.Inject <init>(...);
}
-keepclasseswithmembers class * {
    @javax.inject.Inject <fields>;
}

# Keep ALL classes with @Singleton scope
-keep @javax.inject.Singleton class * { *; }

# Keep ALL classes with native methods (JNI)
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep ALL JNI bridge classes — sherpa-onnx, llama.cpp, SQLCipher, BouncyCastle
# These are loaded via System.loadLibrary and called from native code
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**
-keep class com.msaidizi.app.voice.LlamaCppJNI { *; }
-keep class com.msaidizi.app.voice.llama_jni.** { *; }
-keep class net.zetetic.** { *; }
-dontwarn net.zetetic.**
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class com.msaidizi.app.security.crypto.pqc.** { *; }

# Keep ALL Room @Entity and @Dao classes
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.Database class * { *; }
-keep @androidx.room.TypeConverter class * { *; }
-keep @androidx.room.TypeDe class * { *; }

# Keep ALL classes referenced by reflection (Kotlin companion, object, etc.)
-keep class com.msaidizi.app.core.model.** { *; }
-keep class com.msaidizi.app.core.database.** { *; }
-keep class com.msaidizi.app.core.language.** { *; }
-keep class com.msaidizi.app.core.dialect.** { *; }
-keep class com.msaidizi.app.voice.** { *; }
-keep class com.msaidizi.app.sync.** { *; }
-keep class com.msaidizi.app.agent.** { *; }
-keep class com.msaidizi.app.core.security.** { *; }

# Keep classes with @Provides and @Module (Hilt modules)
-keep @dagger.Module class * { *; }
-keep @dagger.Provides class * { *; }
-keep @dagger.Binds class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }

# Keep all Hilt entry points
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }

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

# Keep native methods (redundant with Fix 1 block above, but kept for safety)
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
# (kept in Fix 1 block above as well for redundancy)

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
# (kept in Fix 1 block above as well for redundancy)

# Bouncy Castle — Post-Quantum Cryptography (ML-KEM, ML-DSA)
# (kept in Fix 1 block above as well for redundancy)

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

# ══════════════════════════════════════════════════════════════
# CRASH FIX: Keep ALL Hilt-injected classes and their constructors
# R8 strips classes it thinks are unused, but Hilt uses reflection
# to construct them at runtime. Without these rules, the app crashes
# on launch in release builds with ClassNotFoundException.
# ══════════════════════════════════════════════════════════════

# Keep ALL classes with @Inject constructors (Hilt needs them)
-keepclasseswithmembers class * {
    @javax.inject.Inject <init>(...);
}

# Keep ALL classes with @Inject fields (Hilt field injection)
-keepclasseswithmembers class * {
    @javax.inject.Inject <fields>;
}

# Keep ALL Hilt @Module classes
-keep @dagger.Module class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }

# Keep ALL @Provides and @Binds methods
-keepclassmembers class * {
    @dagger.Provides <methods>;
    @dagger.Binds <methods>;
}

# Keep ALL @Singleton classes
-keep @javax.inject.Singleton class * { *; }

# Keep ALL Hilt component references
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Keep ALL classes in the app's DI modules
-keep class com.msaidizi.app.core.di.** { *; }
-keep class com.msaidizi.app.security.di.** { *; }
-keep class com.msaidizi.app.briefing.** { *; }

# ══════════════════════════════════════════════════════════════
# CRASH FIX: Keep ALL JNI bridge classes
# Native libraries call into these Java/Kotlin classes via JNI.
# If R8 strips them, the native lib throws UnsatisfiedLinkError.
# ══════════════════════════════════════════════════════════════

# llama.cpp JNI bridge
-keep class com.msaidizi.app.voice.llama_jni.** { *; }
-keep class com.msaidizi.app.voice.LlamaCppJNI { *; }
-keep class com.msaidizi.app.voice.LlamaCppEngine { *; }

# Sherpa-ONNX JNI (already covered above but reinforcing)
-keep class com.k2fsa.sherpa.onnx.** { *; }

# ONNX Runtime JNI
-keep class ai.onnxruntime.** { *; }

# SQLCipher JNI
-keep class net.sqlcipher.** { *; }
-keep class net.zetetic.** { *; }

# BouncyCastle JNI and PQC
-keep class org.bouncycastle.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.pqc.** { *; }

# ══════════════════════════════════════════════════════════════
# CRASH FIX: Keep ALL agent and voice classes
# These are constructed via Hilt and used at runtime.
# ══════════════════════════════════════════════════════════════

# All agent classes
-keep class com.msaidizi.app.agent.** { *; }

# All voice classes
-keep class com.msaidizi.app.voice.** { *; }

# All core classes
-keep class com.msaidizi.app.core.** { *; }

# All finance classes
-keep class com.msaidizi.app.finance.** { *; }

# All gamification classes
-keep class com.msaidizi.app.gamification.** { *; }

# All mindset classes
-keep class com.msaidizi.app.mindset.** { *; }

# All CFO classes
-keep class com.msaidizi.app.cfo.** { *; }

# All loop classes
-keep class com.msaidizi.app.loops.** { *; }

# All evolution classes
-keep class com.msaidizi.app.evolution.** { *; }

# All social classes
-keep class com.msaidizi.app.social.** { *; }

# All security classes
-keep class com.msaidizi.app.security.** { *; }

# All onboarding classes
-keep class com.msaidizi.app.onboarding.** { *; }

# All data classes
-keep class com.msaidizi.app.data.** { *; }

# All UI classes
-keep class com.msaidizi.app.ui.** { *; }

# All update classes
-keep class com.msaidizi.app.update.** { *; }

# ══════════════════════════════════════════════════════════════
# CRASH FIX: Keep DatabaseKeyManager and crypto classes
# These use EncryptedSharedPreferences which uses reflection.
# ══════════════════════════════════════════════════════════════

-keep class com.msaidizi.app.security.crypto.DatabaseKeyManager { *; }
-keep class com.msaidizi.app.security.crypto.** { *; }
-keep class androidx.security.crypto.** { *; }

# Keep EncryptedSharedPreferences reflection targets
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# ══════════════════════════════════════════════════════════════
# CRASH FIX: Keep Retrofit and Gson (used by MsaidiziApi)
# ══════════════════════════════════════════════════════════════

-keep class retrofit2.** { *; }
-dontwarn retrofit2.**
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Keep API response classes
-keep class com.msaidizi.app.data.api.** { *; }

# ══════════════════════════════════════════════════════════════
# CRASH FIX: Keep Gradle BuildConfig
# R8 can strip BuildConfig fields used at runtime.
# ══════════════════════════════════════════════════════════════

-keep class com.msaidizi.app.BuildConfig { *; }

# ══════════════════════════════════════════════════════════════
# CRASH FIX: Keep R class references
# Resource shrinking can strip R fields used in code.
# ══════════════════════════════════════════════════════════════

-keepclassmembers class **.R$* {
    public static <fields>;
}

# Keep R class itself
-keep class **.R { *; }
-keep class **.R$* { *; }
