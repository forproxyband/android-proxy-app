# Compile-check harness for the Android app.
#
# The app cannot be built where it is edited (no JDK, no Android SDK, no
# NDK there), so the docker build IS the test.
#
# SCOPE NOTE, and it matters: the platform gives each repository its own
# build context, so the sibling proxy-agent-sdk-go checkout is NOT
# visible here. That means `sdkEngineAvailable` is false and this build
# exercises the src/noSdkEngine source set — i.e. it proves the app
# still compiles WITHOUT the SDK (the regression risk of making the SDK
# optional), not the SDK-enabled variant. The SDK-enabled path is
# compile-checked from the other side, by PublicApiSurfaceTest in the
# SDK repo, which pins every call SdkEngineProvider makes.

FROM eclipse-temurin:17-jdk-jammy AS build

ENV ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_COMPILE_SDK=35 \
    ANDROID_BUILD_TOOLS=35.0.0 \
    ANDROID_NDK=26.3.11579264 \
    ANDROID_CMAKE=3.22.1 \
    GRADLE_USER_HOME=/root/.gradle

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl unzip ca-certificates \
 && rm -rf /var/lib/apt/lists/*

RUN mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools" \
 && curl -fsSL -o /tmp/cmdline-tools.zip \
      https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
 && unzip -q /tmp/cmdline-tools.zip -d "$ANDROID_SDK_ROOT/cmdline-tools" \
 && mv "$ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest" \
 && rm /tmp/cmdline-tools.zip

ENV PATH="${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${PATH}"

RUN yes | sdkmanager --licenses > /dev/null \
 && sdkmanager --install \
      "platforms;android-${ANDROID_COMPILE_SDK}" \
      "build-tools;${ANDROID_BUILD_TOOLS}" \
      "ndk;${ANDROID_NDK}" \
      "cmake;${ANDROID_CMAKE}" > /dev/null

WORKDIR /src

# Wrapper + build scripts first so a source edit reuses the (slow)
# Gradle distribution and dependency downloads.
COPY gradlew gradlew.bat ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY app/build.gradle.kts ./app/
RUN chmod +x ./gradlew && ./gradlew --no-daemon --version > /dev/null

COPY keystore.jks ./
COPY app ./app

# Debug build: it compiles every source set the release build does, but
# skips signing and the OTA release coordinates, so it needs no secrets.
# assembleDebugUnitTest is added so app/src/test is type-checked too.
RUN ./gradlew --no-daemon --stacktrace \
      :app:assembleDebug \
      :app:assembleDebugUnitTest \
 && mkdir -p /out \
 && cp app/build/outputs/apk/debug/*.apk /out/

# Prove which engine source set was compiled. If this ever says the SDK
# engine is available, the harness has picked up a sibling checkout it
# was not supposed to see and the "builds standalone" claim is untested.
RUN cd /out \
 && SDK_FLAG="$(grep -rho 'SDK_ENGINE_AVAILABLE = [a-z]*' /src/app/build/generated/source/buildConfig 2>/dev/null | head -1)" \
 && echo "buildConfig: ${SDK_FLAG:-<not found>}" > engine-source-set.txt \
 && if [ -f /src/app/src/noSdkEngine/java/com/proxyagent/app/engine/SdkEngineProvider.kt ]; then \
      echo "stub source set present: src/noSdkEngine" >> engine-source-set.txt; \
    fi \
 && case "$SDK_FLAG" in \
      *false) echo "OK — app compiled WITHOUT the SDK (standalone path)" >> engine-source-set.txt ;; \
      *true)  echo "UNEXPECTED — SDK was on the classpath in this harness" >> engine-source-set.txt; exit 1 ;; \
      *)      echo "WARN — could not read the flag from BuildConfig" >> engine-source-set.txt ;; \
    esac \
 && cat engine-source-set.txt

RUN cd /out \
 && { echo "<!doctype html><meta charset=utf-8><title>android-proxy-app</title>"; \
      echo "<style>body{font:14px/1.5 system-ui;margin:2rem;max-width:60rem}pre{background:#f4f4f5;padding:1rem;overflow:auto}</style>"; \
      echo "<h1>android-proxy-app — build OK</h1>"; \
      echo "<p>Kotlin, resources, CMake shim and unit-test sources all compiled.</p>"; \
      echo "<h2>Artifact</h2><ul>"; \
      for f in *.apk; do echo "<li><a href=\"$f\">$f</a> — $(du -h "$f" | cut -f1)</li>"; done; \
      echo "</ul><h2>Engine source set</h2><pre>"; cat engine-source-set.txt; echo "</pre>"; \
    } > index.html

FROM nginx:alpine
COPY --from=build /out/ /usr/share/nginx/html/
