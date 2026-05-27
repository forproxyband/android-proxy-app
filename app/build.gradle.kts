plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
val appVersionName = if (buildNumber > 0) "1.0.$buildNumber" else "1.0.0-dev"
val appVersionCode = if (buildNumber > 0) buildNumber else 1

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
        minSdk = 21
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        // ABI filter for native libs. Matches the pre-built
        // libproxyagent.so under app/src/main/jniLibs/<abi>/ so both
        // our CMake-built libagentsplice.so and the Go binary cover
        // the same architectures. Expand if/when the Go binary is
        // rebuilt for armeabi-v7a / x86_64.
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += listOf("arm64-v8a", "x86")
        }

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
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time desugaring for kwik on minSdk 21..25.
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

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
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
    // desugaring is enabled below to support minSdk 21. When this dependency is
    // removed (e.g. third-party integrators using only the NativeProxyAgent.kt
    // drop-in), the NATIVE engine silently falls back to TCP-only.
    //
    // Kwik is being progressively replaced by the in-house QUIC implementation
    // in `nativeagent/quic/` — once `NativeQuicTransport` is field-validated
    // (see com.proxyagent.app.nativeagent.quic.DESIGN.md), this dep goes away.
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
}
