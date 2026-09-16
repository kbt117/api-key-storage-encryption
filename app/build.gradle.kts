// -----------------------------------------------------------------------------
// app/build.gradle.kts
//
// Single-module Android application. Everything a CLI build needs is here;
// Android Studio simply imports it.
//
//   ./gradlew :app:assembleDebug      -> app/build/outputs/apk/debug/app-debug.apk
//   ./gradlew :app:testDebugUnitTest  -> JVM unit tests (no device needed)
// -----------------------------------------------------------------------------

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    // kapt (not KSP) is deliberate: see README "Why kapt, not KSP?".
    alias(libs.plugins.kotlin.kapt)
}

// ---------------------------------------------------------------------------
// Optional, git-ignored signing configuration.
//
// Create `keystore.properties` next to this file to produce a signed release
// APK; without it the release build falls back to the debug signing config so
// `./gradlew assembleRelease` still succeeds on a fresh clone / CI runner.
// ---------------------------------------------------------------------------
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseKeystore: Boolean = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "dev.kbt117.keyproxy"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.kbt117.keyproxy"

        // 26 (Android 8.0) is the floor required by the brief. It also happens
        // to be the first release with java.time and with a usable Keystore
        // AES-GCM implementation, so no desugaring flags are needed.
        minSdk = 26
        // 36 == Android 16. Required for Play submissions after 2026-08-31 and
        // needed to exercise the Android 16 behaviour changes we handle
        // (edge-to-edge, predictive back, specialUse FGS quotas).
        targetSdk = 36

        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Selects the at-rest encryption backend at build time. Flip to "true"
        // to use the (deprecated) androidx EncryptedSharedPreferences path.
        // Changing it after keys are stored requires clearing app data.
        //
        // Positional arguments: buildConfigField is a Java-declared method, so
        // Kotlin named arguments are not available for it.
        buildConfigField(
            "boolean",
            "USE_LEGACY_ENCRYPTED_PREFS",
            (findProperty("useLegacyEncryptedPrefs") as? String ?: "false"),
        )
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Falls back to the debug keystore when keystore.properties is
            // absent, so a bare `assembleRelease` still produces an installable
            // APK (self-signed).
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // AGP 8 turns BuildConfig off by default; we need it for the backend flag.
        buildConfig = true
    }

    packaging {
        resources {
            // Ktor, Netty, OkHttp and Tink all ship META-INF metadata that
            // collides when merged into a single APK.
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module",
                "META-INF/versions/**",
                "META-INF/io.netty.versions.properties",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        warningsAsErrors = false
    }
}

// `kotlinOptions { jvmTarget = ... }` is deprecated under Kotlin 2.3, so the
// compiler target is set through the modern `compilerOptions` DSL instead.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

kapt {
    // Hilt generates a lot of code; correctErrorTypes avoids stub mismatches
    // when a referenced type is only visible to KSP/kapt output.
    correctErrorTypes = true
}

dependencies {
    // --- AndroidX core / Compose -----------------------------------------
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // --- Dependency injection --------------------------------------------
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    kapt(libs.hilt.compiler)

    // --- Local HTTP server (loopback proxy) -------------------------------
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)

    // --- Outbound client ---------------------------------------------------
    implementation(libs.okhttp)

    // --- Secure storage ----------------------------------------------------
    implementation(libs.tink.android)
    // Deprecated upstream; retained for the opt-in legacy backend.
    implementation(libs.androidx.security.crypto)

    // --- Tests --------------------------------------------------------------
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
