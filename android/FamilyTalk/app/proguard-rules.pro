# FamilyTalk release shrinking. Goal: smaller APK, not obfuscation.
# Everything that is reached through JNI or reflection is kept whole.
-dontobfuscate

# Our own code (kotlinx.serialization models, services, receivers).
-keep class com.mypro.familytalk.** { *; }

# Native media engines (called from C/C++ through JNI).
-keep class org.webrtc.** { *; }
-keep class io.getstream.webrtc.** { *; }
-keep class org.linphone.** { *; }

# Crypto (lightweight API is used directly; keep it whole to be safe).
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# kotlinx.serialization (the library ships its own rules; these are belt and braces).
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }

# Old MQTT client kept in core but unused; optional deps not on Android.
-dontwarn org.eclipse.paho.**
-dontwarn javax.**
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
