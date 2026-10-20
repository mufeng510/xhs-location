# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   https://developer.android.com/studio/build/shrink-code.html

# Hilt
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.HiltAndroidApp
-keep class * extends dagger.hilt.HiltActivity
-keep class * extends dagger.hilt.HiltService
-keep class * extends dagger.hilt.HiltBroadcastReceiver
-keep class * extends dagger.hilt.HiltFragment
-keep class * extends dagger.hilt.HiltViewModel
-keep @interface dagger.hilt.* { *; }
-keep class * @dagger.hilt.* { *; }

# Retrofit / OkHttp
-keepclassmembers class com.mufeng.xhs.location.map.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class sun.misc.Unsafe { *; }
-keep class com.google.gson.stream.** { *; }

# MapLibre
-keep class com.maplibre.** { *; }
-keep interface com.maplibre.** { *; }
