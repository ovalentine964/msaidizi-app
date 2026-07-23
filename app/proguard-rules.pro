# Msaidizi — ProGuard Rules
# Keep all classes needed for the super agent

# ── Keep Hilt injection points ──
-keepclasseswithmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel <init>(...);
}

# ── Keep Room entities ──
-keep class com.msaidizi.app.data.entity.** { *; }
-keep class com.msaidizi.app.data.dao.** { *; }

# ── Keep all tool classes (used by ToolRegistry) ──
-keep class com.msaidizi.app.agent.tools.** { *; }

# ── Keep SuperAgent and memory classes ──
-keep class com.msaidizi.app.agent.SuperAgent { *; }
-keep class com.msaidizi.app.agent.bootstrap.** { *; }
-keep class com.msaidizi.app.memory.** { *; }
-keep class com.msaidizi.app.security.** { *; }

# ── Keep voice classes (JNI) ──
-keep class com.msaidizi.app.voice.** { *; }

# ── Keep federated learning classes ──
-keep class com.msaidizi.app.core.federated.** { *; }
-keep class com.msaidizi.app.core.metrics.** { *; }

# ── Keep Compose ─-keep class androidx.compose.** { *; }

# ── Keep Kotlin coroutines ──
-keep class kotlinx.coroutines.** { *; }

# ── Keep Retrofit models ──
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# ── Keep Gson type adapters ──
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ── General rules ──
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
