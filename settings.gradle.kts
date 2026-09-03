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
    }
}

rootProject.name = "android-proxy-app"
include(":app")

// ── SDK engine (optional) ─────────────────────────────────────────────
// When the proxy-agent SDK repo is checked out next to this one, wire it
// in as a composite build so the app can run the agent through the
// published Kotlin SDK (Engine = SDK) instead of its own in-tree copy.
// Gradle substitutes com.proxyagent:proxy-agent-android for the included
// project automatically — the SDK module declares matching group/name.
//
// Deliberately CONDITIONAL: a clone of this repo on its own must still
// build. When the directory is absent the SDK engine is compiled out
// (see app/build.gradle.kts) and the option disappears from Settings
// rather than failing at runtime.
val sdkBuild = file("../proxy-agent-sdk-go/android")
if (sdkBuild.isDirectory) {
    includeBuild(sdkBuild)
    logger.lifecycle("SDK engine: including ${sdkBuild.canonicalPath}")
} else {
    logger.lifecycle("SDK engine: ${sdkBuild.path} not found — building without it")
}
