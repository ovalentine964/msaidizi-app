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

# ONNX Runtime
-keep class ai.onnxruntime.** { *; }

# Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# zstd
-keep class com.github.luben.zstd.** { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Optimize
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
