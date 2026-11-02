# Minification is off for this personal build; rules kept for a future shrink.
-keepclassmembers class com.mypro.minutes.** { *** Companion; }
-keepclasseswithmembers class com.mypro.minutes.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.mypro.minutes.**$$serializer { *; }
