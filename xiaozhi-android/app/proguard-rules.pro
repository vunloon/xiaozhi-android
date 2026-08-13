# Add project specific ProGuard rules here.
-keep class com.xiaozhi.client.** { *; }
-keepclassmembers class com.xiaozhi.client.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.xiaozhi.client.**$$serializer { *; }
-keepclassmembers class com.xiaozhi.client.** {
    *** Companion;
}
-keepclasseswithmembers class com.xiaozhi.client.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# JNI
-keepclasseswithmembernames class * {
    native <methods>;
}
