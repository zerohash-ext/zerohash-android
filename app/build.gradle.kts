import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// TEST INFRA (see DO_NOT_COMMIT.md). Single source of truth for which Zerohash
// SDK build the demo app consumes.
//
// `useJitpack` defaults to FALSE — the demo builds against the local
// :zerohashsdk module so it works out of the box with no published artifact.
// Flip to true (or pass -PuseJitpack=true) once a JitPack artifact exists to
// test the published AAR instead. The hardcoded coordinates are placeholders.
// ---------------------------------------------------------------------------
val zerohashSdkGroup = "com.github.zerohash"
val zerohashSdkArtifact = "zerohash-android"
val zerohashSdkFallbackVersion = "0.0.1"

fun resolveLatestJitpackVersion(): String {
    (project.findProperty("zerohashSdkVersion") as String?)?.takeIf { it.isNotBlank() }
        ?.let { return it }
    return try {
        val api = uri("https://jitpack.io/api/builds/$zerohashSdkGroup/$zerohashSdkArtifact").toURL()
        val json = api.openConnection().apply {
            connectTimeout = 5000
            readTimeout = 5000
        }.getInputStream().bufferedReader().use { it.readText() }
        @Suppress("UNCHECKED_CAST")
        val builds = (JsonSlurper().parseText(json) as Map<String, Any?>)
            .let { it[zerohashSdkGroup] as? Map<String, Any?> }
            ?.let { it[zerohashSdkArtifact] as? Map<String, Any?> }
            ?: emptyMap()
        val latest = builds.filterValues { it == "ok" }.keys
            .maxWithOrNull(compareBy({ v ->
                v.split('.', '-').firstOrNull()?.toIntOrNull() ?: 0
            }, { v ->
                v.split('.', '-').getOrNull(1)?.toIntOrNull() ?: 0
            }, { v ->
                v.split('.', '-').getOrNull(2)?.toIntOrNull() ?: 0
            }))
        if (latest != null) {
            logger.lifecycle("ZerohashSDK: resolved latest JitPack version = $latest")
            latest
        } else {
            logger.warn("ZerohashSDK: no 'ok' builds on JitPack, falling back to $zerohashSdkFallbackVersion")
            zerohashSdkFallbackVersion
        }
    } catch (e: Exception) {
        logger.warn("ZerohashSDK: JitPack lookup failed (${e.message}); falling back to $zerohashSdkFallbackVersion")
        zerohashSdkFallbackVersion
    }
}

val useJitpack = (project.findProperty("useJitpack") as String?)?.toBoolean() ?: false
val zerohashSdkVersion = if (useJitpack) resolveLatestJitpackVersion() else ""
val zerohashSdkCoordinate = "$zerohashSdkGroup:$zerohashSdkArtifact:$zerohashSdkVersion"
val zerohashSdkSource =
    if (useJitpack) "JitPack: $zerohashSdkCoordinate"
    else "Local module: :zerohashsdk"
val zerohashSdkLabel =
    if (useJitpack) "Zerohash Fund Demo · $zerohashSdkArtifact $zerohashSdkVersion"
    else "Zerohash Fund Demo · local"

android {
    namespace = "com.zerohash.funddemo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zerohash.funddemo"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "ZEROHASH_SDK_SOURCE", "\"$zerohashSdkSource\"")
        // Title shown on the SDK's WebViewActivity bar (overridden in AndroidManifest).
        resValue("string", "zerohash_sdk_webview_label", zerohashSdkLabel)
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // Zerohash SDK — source controlled by `useJitpack` at the top of this file.
    if (useJitpack) {
        implementation(zerohashSdkCoordinate)
    } else {
        implementation(project(":zerohashsdk"))
    }

    // Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
