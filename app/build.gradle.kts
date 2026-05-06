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

    defaultConfig {
        applicationId = "com.proxyagent.app"
        minSdk = 21
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
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
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
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
}
