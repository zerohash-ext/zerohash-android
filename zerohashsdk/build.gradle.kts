plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    namespace = "com.zerohash.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        targetSdk = 34

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        // Exposes the SDK version at runtime for the automation bridge's
        // core.ping reply (mirrors iOS `ConnectSDK.version`). Same single source
        // of truth as the publishing block: the git tag via -PSDK_VERSION.
        buildConfigField(
            "String",
            "SDK_VERSION",
            "\"${(findProperty("SDK_VERSION") as? String) ?: "0.0.0-SNAPSHOT"}\"",
        )
    }

    buildTypes {
        release {
            // Libraries ship un-minified bytecode + a consumer-rules.pro telling
            // consumers' R8 what to keep when minifying the final app. Minifying
            // the AAR itself would rename public types before consumers ever see
            // them.
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
        // Required for BuildConfig.DEBUG guards
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.browser:browser:1.7.0") // Chrome Custom Tabs for OAuth
    // WebViewCompat.addWebMessageListener with allowedOriginRules — provides
    // per-frame origin filtering on the JS↔Kotlin bridge.
    implementation("androidx.webkit:webkit:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // JSON parsing handled by org.json (bundled with Android).

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "com.zerohash"
            artifactId = "zerohash-sdk"

            // Single source of truth = git tag, forwarded by JitPack as
            // -PSDK_VERSION=$VERSION (see jitpack.yml); fall back to a SNAPSHOT
            // marker for local publishToMavenLocal runs.
            version = (findProperty("SDK_VERSION") as? String) ?: "0.0.0-SNAPSHOT"

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}
