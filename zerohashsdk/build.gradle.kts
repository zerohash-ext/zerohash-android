plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    // Maven Central publishing; applied only under -PmavenCentralRelease (below).
    id("com.vanniktech.maven.publish") version "0.30.0" apply false
}

android {
    namespace = "com.zerohash.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        targetSdk = 34

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        // SDK version at runtime for the automation bridge's core.ping reply.
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

// Artifact version comes from the git tag via -PSDK_VERSION; SNAPSHOT locally.
val sdkVersion = (findProperty("SDK_VERSION") as? String) ?: "0.0.0-SNAPSHOT"

// -PmavenCentralRelease: signed Central Portal publish (workflow only).
// Default (no flag): unsigned maven-publish path below, used by JitPack.
if (project.hasProperty("mavenCentralRelease")) {
    apply(plugin = "com.vanniktech.maven.publish")

    configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
        // Staging deployment, released by hand in the Portal UI.
        publishToMavenCentral(
            com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL,
            automaticRelease = false,
        )
        signAllPublications()

        coordinates("com.zerohash", "zerohash-sdk", sdkVersion)
        configure(com.vanniktech.maven.publish.AndroidSingleVariantLibrary("release"))

        // url/scm point at the public mirror. All fields required by Central.
        pom {
            name.set("zerohash Android SDK")
            description.set("zerohash SDK for Android — drop-in native integration for the zerohash Fund flow.")
            url.set("https://github.com/zerohash-ext/zerohash-android")
            // Proprietary license; `name` matches the LICENSE file heading.
            licenses {
                license {
                    name.set("zerohash Android Wrapper License")
                    url.set("https://github.com/zerohash-ext/zerohash-android/blob/main/LICENSE")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("zerohash")
                    name.set("zerohash")
                    email.set("security@zerohash.com")
                }
            }
            scm {
                url.set("https://github.com/zerohash-ext/zerohash-android")
                connection.set("scm:git:https://github.com/zerohash-ext/zerohash-android.git")
                developerConnection.set("scm:git:ssh://git@github.com/zerohash-ext/zerohash-android.git")
            }
        }
    }
} else {
    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = "com.zerohash"
                artifactId = "zerohash-sdk"
                version = sdkVersion

                afterEvaluate {
                    from(components["release"])
                }
            }
        }
    }
}
