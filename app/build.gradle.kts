import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

// defuddle browser bundle, injected into the web_fetch WebView via
// evaluateJavascript. The artifact must come from the npm registry release
// (never a local checkout). To update: download the new tarball from
// https://registry.npmjs.org/defuddle/-/defuddle-<version>.tgz, then bump
// defuddleVersion and defuddleSha256 together (sha256sum of the tarball).
val defuddleVersion = "0.19.3"
val defuddleSha256 =
    "5ee0e894b27f8342975f7acbbb96dd31b79baa0e2f1bba47d0d25f16cc49d153"

// Cached tarball under the build dir; downloaded only when missing, so
// up-to-date checks do not re-hit the network.
val defuddleTarball =
    layout.buildDirectory.file("defuddle/defuddle-$defuddleVersion.tgz")

// The runtime reads the bundle via context.assets.open("defuddle/index.js").
val defuddleAssetsDir = layout.buildDirectory.dir("generated/defuddleAssets")

val downloadDefuddle =
    tasks.register("downloadDefuddle") {
        description =
            "Downloads the pinned defuddle npm tarball and verifies its SHA-256."
        outputs.file(defuddleTarball)

        doLast {
            val tarball = defuddleTarball.get().asFile
            tarball.parentFile.mkdirs()
            if (!tarball.exists()) {
                val url =
                    URI(
                        "https://registry.npmjs.org/defuddle/-/defuddle-$defuddleVersion.tgz"
                    )
                        .toURL()
                url.openStream().use { input ->
                    Files.copy(
                        input,
                        tarball.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val actual =
                digest.digest(tarball.readBytes()).joinToString("") {
                    "%02x".format(it)
                }
            if (actual != defuddleSha256) {
                tarball.delete()
                throw GradleException(
                    "defuddle-$defuddleVersion.tgz SHA-256 mismatch: expected " +
                        "$defuddleSha256, got $actual. Delete the cached tarball and " +
                        "re-download it, then re-pin the hash."
                )
            }
        }
    }

// Extract only the UMD bundle from the tarball, re-rooted so the asset path
// is exactly defuddle/index.js. We ship dist/index.full.js, not the core
// dist/index.js: the core bundle has no markdown conversion, and only the
// full bundle runs turndown, so {markdown: true} in parse() requires it.
val extractDefuddle =
    tasks.register<Copy>("extractDefuddle") {
        description =
            "Extracts defuddle's dist/index.full.js into the generated assets directory."
        dependsOn(downloadDefuddle)
        from(tarTree(resources.gzip(defuddleTarball)))
        // Copy-spec include patterns are not part of Gradle's up-to-date
        // fingerprint; pin the entry so changing it re-extracts.
        inputs.property("entry", "package/dist/index.full.js")
        include("package/dist/index.full.js")
        into(defuddleAssetsDir)
        filesMatching("package/dist/index.full.js") { path = "defuddle/index.js" }
    }

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
        minSdk = 34
        targetSdk = 37
        versionCode = 3
        versionName = "0.3.0"
    }

    signingConfigs {
        create("release") {
            // Provided by the release workflow from repository secrets.
            storeFile = System.getenv("PATHFINDER_KEYSTORE")?.let(::file)
            storePassword = System.getenv("PATHFINDER_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("PATHFINDER_KEY_ALIAS")
            keyPassword = System.getenv("PATHFINDER_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets["main"].assets.srcDirs(defuddleAssetsDir.get())

    // android.util.Log calls at the ViewModel error boundary must not throw
    // in JVM unit tests.
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    // The generated defuddle asset must exist before AGP merges assets.
    tasks.named("preBuild") { dependsOn(extractDefuddle) }
    implementation(project(":packages:ai"))
    implementation(project(":packages:agent"))
    implementation(project(":packages:coding-agent"))
    implementation(project(":packages:telemetry"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel)

    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)

    // OAuth login URLs open in a Custom Tab.
    implementation(libs.androidx.browser)

    // The web_fetch tool renders pages in a hidden WebView with its own profile.
    implementation(libs.androidx.webkit)

    implementation(libs.androidx.datastore.preferences)

    // HTTP + JSON for the native provider layer.
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.kotlinx.serialization.json)

    // zstd request-body compression for the Codex SSE path. The AAR packages
    // Android natives; the plain jar carries desktop natives for unit tests.
    implementation(libs.zstd.jni) { artifact { type = "aar" } }
    testImplementation(libs.zstd.jni)

    // Markdown parsing for message rendering.
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.task.list.items)

    testImplementation(testFixtures(project(":packages:ai")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
