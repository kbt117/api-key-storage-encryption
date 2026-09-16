# =============================================================================
# ProGuard / R8 rules for the release build.
#
# The app has no native libraries of its own, so the 16 KB page-size alignment
# requirement introduced for Android 15/16 is satisfied trivially (Tink, OkHttp
# and Netty are all pure JVM bytecode on Android).
# =============================================================================

# --- Kotlin ------------------------------------------------------------------
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}

# --- Coroutines ---------------------------------------------------------------
-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# --- Ktor / Netty -------------------------------------------------------------
# Ktor discovers engines and plugins via ServiceLoader; R8 must not strip the
# registrations or the embedded server will fail to start at runtime.
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

-keep class io.netty.** { *; }
-dontwarn io.netty.**
# Netty probes for optional JDK/Android facilities (epoll, kqueue, Unsafe,
# JMX). Those probes legitimately fail on Android and fall back to NIO.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.aayushatharva.brotli4j.**
-dontwarn com.github.luben.zstd.**
-dontwarn net.jpountz.lz4.**
-dontwarn java.lang.management.**

# --- SLF4J --------------------------------------------------------------------
# No SLF4J binding is bundled; the no-op provider is used. Silence the lookup.
-dontwarn org.slf4j.**
-keep class org.slf4j.** { *; }

# --- Google Tink --------------------------------------------------------------
# Tink resolves key managers reflectively from the Registry.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# --- AndroidX Security Crypto (opt-in legacy backend) -------------------------
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# --- OkHttp -------------------------------------------------------------------
# OkHttp reflects over optional platform classes for TLS/ALPN support.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.codehaus.mojo.animal_sniffer.**

# --- Hilt / Dagger ------------------------------------------------------------
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keepclassmembers class * {
    @dagger.hilt.android.AndroidEntryPoint <fields>;
}
