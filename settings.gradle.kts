pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Test-only: lets the demo app resolve a published JitPack artifact.
        // See app/build.gradle.kts (useJitpack). DO NOT COMMIT if you are
        // keeping the demo on the local :zerohashsdk module only.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "zerohash-android"
include(":zerohashsdk")
include(":app")
