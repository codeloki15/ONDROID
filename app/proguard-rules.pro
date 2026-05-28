# LocalLink Pro ProGuard Rules

# Keep Gson models
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.locallink.pro.data.model.** { *; }
-keep class com.locallink.pro.domain.model.** { *; }

# Keep Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# SSHJ
-dontwarn com.hierynomus.**
-keep class com.hierynomus.** { *; }
