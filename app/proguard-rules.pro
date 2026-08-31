# Referenced by app/build.gradle.kts release buildType.
# isMinifyEnabled is currently false, so these rules are not exercised yet.
# Written up front so shrinking can be turned on later without guessing
# which reflection-based library needs a keep rule first.

# Gson parses Xtream/API responses via reflection; keep field names intact.
-keepattributes Signature
-keepattributes *Annotation*
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.network24.player.**.models.** { *; }
-keep class com.network24.player.features.**.model.** { *; }
-keep class com.network24.player.features.updater.models.** { *; }

# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes Exceptions

# Room entities/DAOs are annotation-processed at compile time; nothing extra needed,
# but keep generated schema classes from being stripped just in case.
-keep class com.network24.player.core.database.entity.** { *; }

# Firebase and Media3 already ship consumer ProGuard rules with their AARs.
