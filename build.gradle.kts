// Top-level build file. Plugin versions come from gradle/libs.versions.toml.
//
// Note on Kotlin: with AGP 9 the app module does NOT apply
// org.jetbrains.kotlin.android — AGP ships built-in Kotlin support
// (see developer.android.com/build/migrate-to-built-in-kotlin).
// The Kotlin plugins declared here are the Compose compiler and
// serialization subplugins, versioned with the Kotlin release they ship with.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
