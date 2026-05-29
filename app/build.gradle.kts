plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
val appVersionName = if (buildNumber > 0) "1.0.$buildNumber" else "1.0.0-dev"
val appVersionCode = if (buildNumber > 0) buildNumber else 1

// E2E build switch: `-Pe2e=true` adds x86_64 to abiFilters so the APK
// installs on the ubuntu-latest CI emulator (x86_64). Production builds
// stay arm64-only — libproxyagent.so (the legacy BINARY engine) is only
// available pre-built for arm64. The NATIVE engine that the e2e tests
// exercise has no such dep; libagentsplice.so is built from C source by
// CMake and compiles for any ABI in the filter list.
val isE2eBuild = (findProperty("e2e") as? String)?.equals("true", ignoreCase = true) == true

android {
    namespace = "com.proxyagent.app"
    compileSdk = 35

    // Pin NDK so CI (.github/workflows/build.yml installs this exact
    // package via sdkmanager) and local builds use the same toolchain.
    // r26d — current LTS line at time of writing; bumps should land
    // here AND in the workflow simultaneously.
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.proxyagent.app"
        // minSdk=23 (Android 6.0) — required for the load-bearing features:
        //   * ConnectivityManager.bindProcessToNetwork (API 23+) — the
        //     whole Wi-Fi return / split-routing story is built on it.
        //   * NetworkCapabilities.NET_CAPABILITY_VALIDATED (API 23+) —
        //     keeps captive-portal Wi-Fi from being treated as a real
        //     uplink path.
        //   * PowerManager.isIgnoringBatteryOptimizations + the
        //     REQUEST_IGNORE_BATTERY_OPTIMIZATIONS intent flow (API 23+) —
        //     without it long sessions get Doze-killed on every device.
        // Android 5.x devices are vanishingly rare on fleet hardware and
        // the agent would be missing its main features there anyway.
        minSdk = 23
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        // ABI filter for native libs. Matches the pre-built
        // libproxyagent.so under app/src/main/jniLibs/<abi>/ so our
        // CMake-built libagentsplice.so and the Go binary cover the
        // same architectures. Expand if/when the Go binary is rebuilt
        // for additional ABIs.
        //
        // e2e: we add x86_64 so the APK runs on the ubuntu-latest CI
        // emulator. libproxyagent.so is missing for that ABI (BINARY
        // engine becomes unavailable), but NATIVE engine — the path
        // the e2e tests cover — has no dep on it. libagentsplice.so is
        // built from source for both ABIs in this list.
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += if (isE2eBuild) listOf("arm64-v8a", "x86_64") else listOf("arm64-v8a")
        }

        // Instrumentation runner for `./gradlew connectedAndroidTest`.
        // Wired regardless of -Pe2e so IDE "Run androidTest" also works.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cFlags += listOf("-fvisibility=hidden")
            }
        }
    }

    // Native build: libagentsplice.so (kernel splice(2) shim, see
    // com.proxyagent.app.nativeagent.SpliceShim). Optional — when the
    // .so can't be loaded the NATIVE engine silently falls back to
    // its NIO + DirectByteBuffer bridge.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // Pin CMake to the same version installed by
            // .github/workflows/build.yml so CI and local builds use
            // identical toolchains.
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore.jks")
            storePassword = "proxyagent"
            keyAlias = "proxyagent"
            keyPassword = "proxyagent"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Skip native-debug-symbol extraction. This app isn't shipped
            // through Google Play, so the *.so debug symbols bundle that
            // Play Console uses to symbolicate native crashes goes
            // straight to /dev/null. Disabling drops the
            // extractReleaseNativeSymbolTables + mergeReleaseNativeDebugMetadata
            // tasks (and the stripReleaseDebugSymbols still runs as part
            // of the normal .so packaging — runtime crashes still produce
            // useful logcat traces via `addr2line` against the unstripped
            // build outputs in app/build/intermediates/).
            ndk {
                debugSymbolLevel = "none"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time desugaring for kwik on minSdk 23..25 (java.time was
        // added to Android in API 26 / Oreo). Still required even after
        // the minSdk=23 bump.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            // BouncyCastle ships the same OSGi metadata file in each
            // of bcprov / bcutil / bctls. AGP's default merger
            // refuses duplicates; these manifests are OSGi metadata
            // we don't run, so exclude them outright. The
            // META-INF/versions/9/* split is from BC's multi-
            // release jars — same content shipped at the JDK 9+
            // location and the legacy one.
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/versions/9/OSGI-INF/**",
                "META-INF/OSGI-INF/MANIFEST.MF",
                "META-INF/OSGI-INF/**",
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
            )
        }
    }

    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "proxy-agent-v$appVersionName-${buildType.name}.apk"
        }
    }
}

// Disable the native-debug-symbol pipeline. This app is sideloaded as
// a plain APK (via `adb install` or direct user install), never
// uploaded to Play, so the Play-Console symbol bundle is dead weight.
// `buildTypes.release.ndk.debugSymbolLevel = "none"` already keeps
// `extract*NativeSymbolTables` from registering, and `merge*NativeDebugMetadata`
// runs as SKIPPED — verified safe (`packageDebug`/`packageRelease` do
// not consume its output).
//
// IMPORTANT: do NOT try to also disable the AGP "Play Store metadata"
// pipeline (`extract*VersionControlInfo`, `collect*Dependencies`,
// `sdk*DependencyData`, `write*AppMetadata`). Even though their content
// is only read by Play Console, AGP 8.7.x wires their output files as
// *required inputs* to `package{Debug,Release}`. Disabling any of them
// makes packaging fail config-validation with:
//   - "appMetadata ... app-metadata.properties does not exist"
//   - "dependencyDataFile ... sdkDependencyData.pb does not exist"
// Both regressions verified in CI; the build-time saving is single-
// digit seconds anyway. Not worth the brittleness.
//
// What's NOT disabled and why:
//   - DEX tasks (dexBuilder*, merge*Dex*, mergeExtDex*, l8DexDesugarLib*)
//     are mandatory — DEX is the bytecode format Android Runtime
//     executes; an APK without DEX has no app code.
//   - stripReleaseDebugSymbols stays — it shrinks the .so files we
//     actually ship by removing unused debug info.
//   - Baseline profile tasks (*ArtProfile*) stay — they generate a
//     hot-path profile ART uses to JIT-compile on install, making
//     the user's first launch noticeably faster. Cheap to build,
//     valuable to keep.
//   - lintVital* stays — catches real bugs in release builds.
tasks.configureEach {
    if (name.startsWith("extract") && name.endsWith("NativeSymbolTables")) enabled = false
    if (name.startsWith("merge") && name.endsWith("NativeDebugMetadata")) enabled = false
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    // Google Code Scanner (ML Kit) — primary QR scanner. Works on stylized /
    // dense codes that ZXing's CameraX-less BarcodeView misses. No CAMERA
    // permission needed — Play Services owns the camera pipeline.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // ZXing kept as fallback: gallery image decoding + offline scanner on
    // devices without Play Services.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // Swipeable status panel: status / 24h-traffic / 24h-connections.
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    // QUIC client used by the NATIVE engine (com.proxyagent.app.nativeagent.*).
    // Pure-Java QUIC v1 implementation; uses java.time.Duration so core library
    // desugaring is enabled in compileOptions below to make it usable on
    // minSdk 23..25 (java.time was added to Android in API 26). When this
    // dependency is removed (e.g. third-party integrators using only the
    // NativeProxyAgent.kt drop-in), the NATIVE engine silently falls back
    // to TCP-only.
    //
    // Kwik is being progressively replaced by the in-house QUIC implementation
    // in `nativeagent/quic/` (NativeQuicTransport is the default since the
    // Settings picker was removed — see com.proxyagent.app.nativeagent.quic.DESIGN.md).
    // The dep stays compiled in as a safety net override; remove once the
    // in-house path has more field hours.
    implementation("tech.kwik:kwik:0.10.10")
    // DO NOT add BouncyCastle here. We tried (`bcprov`/`bctls` 1.79) to get
    // X25519 for the in-house QUIC client, and it broke kwik QUIC entirely:
    // Android bundles its own `org.bouncycastle.*` in the platform, and a
    // second full BC under the same package names corrupts the JCA provider
    // chain that kwik (and any other JCA crypto user) relies on. Confirmed
    // by isolation test — kwik recovered the instant BC left the classpath.
    // The in-house QUIC now uses the platform `XDH` KeyAgreement (Conscrypt)
    // for X25519 instead — see nativeagent/quic/tls/TlsCrypto.kt. If you ever
    // genuinely need BC, shade it into a private namespace first.
    // Desugaring for java.time.* on API < 26 (needed by kwik).
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    // ── Instrumentation tests (app/src/androidTest) ────────────────────
    // Only consumed by `connectedAndroidTest` / `connected<Variant>AndroidTest`.
    // Not in the shipping APK. AndroidX test infra at current LTS pins.
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
