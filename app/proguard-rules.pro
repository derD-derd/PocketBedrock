# PocketBedrock release rules.

-keepattributes *Annotation*, InnerClasses, Signature, Exceptions

# kotlinx.serialization
-keep,includedescriptorclasses class com.pocketbedrock.app.**$$serializer { *; }
-keepclassmembers class com.pocketbedrock.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.pocketbedrock.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Conscrypt optional TLS providers
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**

# commons-compress reflection-based factories
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**

# zstd-jni native bridge
-keep class com.github.luben.zstd.** { *; }
-dontwarn com.github.luben.zstd.**

# Room entities are accessed via generated code; keep field annotations
-keep class com.pocketbedrock.app.data.db.** { *; }
