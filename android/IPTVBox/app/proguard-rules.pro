# Minification is disabled for this personal build; rules kept for a future shrink.
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keep,includedescriptorclasses class com.mypro.iptvbox.**$$serializer { *; }
-keepclassmembers class com.mypro.iptvbox.** { *** Companion; }
-keepclasseswithmembers class com.mypro.iptvbox.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn org.slf4j.**
