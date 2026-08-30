plugins {
    alias(libs.plugins.android.application)
    // AGP 9 provides built-in Kotlin support; no kotlin-android plugin needed.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "works.resolve.pathfinder"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "works.resolve.pathfinder"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

}

dependencies {
    // AndroidX base
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel)

    // Navigation 3 (Compose-first navigation)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    // Compose (versions managed by the BOM)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Coroutines (used directly by production code; version shared with coroutines-test)
    implementation(libs.kotlinx.coroutines.android)

    // Settings persistence
    implementation(libs.androidx.datastore.preferences)

    // JSON for persistence codecs (Koog messages in session files)
    implementation(libs.kotlinx.serialization.json)

    // Koog runtime: provider LLM clients (Anthropic, OpenAI, OpenRouter,
    // Google, MistralAI, DeepSeek, DashScope), their shared contracts (prompt-llm,
    // prompt-model, prompt-executor-clients arrive transitively), and the HTTP
    // transport (Koog's Ktor client over an OkHttp engine shared with the
    // app's stack).
    implementation(libs.koog.prompt.executor.anthropic.client)
    implementation(libs.koog.prompt.executor.openai.client)
    implementation(libs.koog.prompt.executor.openrouter.client)
    implementation(libs.koog.prompt.executor.google.client)
    implementation(libs.koog.prompt.executor.mistralai.client)
    implementation(libs.koog.prompt.executor.deepseek.client)
    implementation(libs.koog.prompt.executor.dashscope.client)
    implementation(libs.koog.http.client.ktor)
    implementation(libs.ktor.client.okhttp)

    // Markdown parsing for message rendering (CommonMark + GFM extensions)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.task.list.items)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}
