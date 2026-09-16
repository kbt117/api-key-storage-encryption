// -----------------------------------------------------------------------------
// Root build script.
//
// Plugins are declared with `apply false` here so the version catalog is the
// single source of truth for versions; :app applies them.
// -----------------------------------------------------------------------------

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.hilt) apply false
}
