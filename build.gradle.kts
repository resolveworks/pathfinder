// AGP 9 ships built-in Kotlin support, so there is no
// org.jetbrains.kotlin.android plugin. The compose and serialization
// plugins are Kotlin compiler subplugins: their version must match the
// Kotlin version built into AGP.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
