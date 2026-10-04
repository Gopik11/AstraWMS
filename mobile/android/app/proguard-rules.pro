# kotlinx.serialization: keep generated serializers of the API models (com.astrawms.mobile.core.api).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.astrawms.mobile.core.** {
    *** Companion;
}
-keepclasseswithmembers class com.astrawms.mobile.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# OkHttp / AppAuth ship their own consumer rules.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
