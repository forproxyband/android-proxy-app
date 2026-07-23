# Proxy Agent (Android)

[![Build APK](https://github.com/forproxyband/android-proxy-app/actions/workflows/build.yml/badge.svg)](https://github.com/forproxyband/android-proxy-app/actions/workflows/build.yml)

Turns an Android phone into a **mobile/residential proxy node**. The
phone dials home to a registrator pool, and whoever holds the agent
key can route proxy traffic out through this phone's cellular IP.

## What it actually does

- Keeps a persistent uplink to a registrator. Transport is
  **TCP** by default with **QUIC** as a fallback, auto-negotiated
  per session and sticky-cached on disk so reconnects skip the
  losing probe.
- Forwards incoming proxy requests through the phone's cellular
  network — so external clients see the phone's mobile exit IP.
- **Rotates the cellular IP** on demand or on server-side trigger:
  airplane-mode toggle → RAT switch (LTE↔GSM) → optional APN swap
  / IMEI rotation. Root makes this much more reliable. A **rotation
  guard** (on by default) ignores overlapping rotation requests while
  one is running and for a configurable cooldown (default 10s) after —
  toggle + cooldown live in Settings. See [ADMIN_GUIDE.md §4.4](ADMIN_GUIDE.md).
- **Wi-Fi return** (optional): the agent↔registrator control link
  can ride Wi-Fi while target dials still go through cellular —
  saves mobile data without leaking the Wi-Fi IP to targets.
- **Network optimization preset** (`100 / 500 / 1000 Mbps`):
  one dropdown that scales TCP socket / bridge buffers and QUIC
  Brutal CC target / UDP buffer / flow-control refresh together
  to the link's expected ceiling — avoids the bufferbloat that
  comes from fat buffers + slow pacer at lower link rates.
  NATIVE engine only.
- Auto-stops on low battery / no-internet, and **auto-reconnects
  after an app update or a device reboot** if the session was live
  (gated on a `was_running` flag, so a deliberate STOP stays stopped).
  Non-root: in-app deep-links to the battery-whitelist and OEM
  autostart screens. Rooted fleet: one-tap `INSTALL ROOT AUTOSTART`
  drops a Magisk/KernelSU boot script that bypasses OEM autostart
  blocking and Doze — the guaranteed path. A screen lock defers the
  restart until first unlock (detected + warned in-app).

## Who needs this

Operators running fleets of Android phones with SIM cards to sell
mobile-IP proxy access. Not a VPN, not a Tor client, not a Magisk
module — just a foreground service that maintains an outbound
tunnel.

## Quick start

1. Install the APK on the phone (CI builds it on every push to
   `main` — grab artifact from the GitHub Actions run).
2. Open the app → `⚙` → `SCAN QR` → point at the QR provided by
   the operator → `Save` → `START`.
3. Wait for the green `CONNECTED` badge. Done — the phone is now
   in the proxy pool.

For everything else (settings, IP rotation tuning, OEM autostart
fixes, troubleshooting) → **[ADMIN_GUIDE.md](ADMIN_GUIDE.md)**.

## How it works inside

Two processes (`:main` for UI, `:proxy` for the foreground
service), two swappable agent engines (NATIVE Kotlin port —
default; BINARY subprocess — legacy, kept for comparison),
file-based IPC through `filesDir`, no Binder.

Full write-up — **[ARCHITECTURE.md](ARCHITECTURE.md)**:
- Process model + IPC files
- Agent engines (NATIVE / BINARY trade-offs; AAR was removed)
- In-house QUIC client (default) with kwik adapter kept as a
  manual override
- TCP fast path (kernel `splice(2)` shim, NIO fallback)
- IP rotation algorithm + interrupted-cycle recovery
- Wi-Fi return relay (split-routing, self-test, OEM caveats)
- Auto-stop watchdog
- Surviving an app update or reboot (heartbeat-staleness,
  auto-restart, Direct Boot / lock-screen, root autostart)

Wire protocol + SDK runtime surface of the bundled BINARY —
**[BINARIES.md](BINARIES.md)**.

## Build

Standard Android Gradle project. Requires JDK 17, Android SDK,
NDK r26d (pinned in `app/build.gradle.kts`).

```bash
./gradlew assembleRelease
```

CI builds on every push — see `.github/workflows/build.yml`.

## Requirements

| | Required | Optional |
| --- | --- | --- |
| Android | **6.0+ (API 23)** — `minSdk=23`, `targetSdk=35`. Android 11+ (API 30) additionally unlocks the kernel `splice(2)` zero-copy TCP fast path. | OEM autostart whitelist on Xiaomi / Huawei / Samsung / OnePlus / Oppo / Vivo — see [ADMIN_GUIDE.md §7.7](ADMIN_GUIDE.md) |
| ABI | **arm64-v8a only** — the bundled `libproxyagent.so` and CMake-built `libagentsplice.so` ship just this ABI; armv7 / x86 devices fail to start | — |
| SIM | with mobile data | — |
| Root (Magisk / SuperSU) | — | needed for IP rotation to actually change the IP (RIL restart, RAT switch, APN swap, IMEI rotation are all root-only). Without root the airplane toggle still fires but the operator usually holds the PDP context — rotation runs vacuously. See [ADMIN_GUIDE.md §4](ADMIN_GUIDE.md) |
| `WRITE_SECURE_SETTINGS` | — | partial root substitute: enables the airplane toggle during IP rotation (still won't change IP without root) and the in-app one-tap `mobile_data_always_on=1` fix for Wi-Fi-return preflight. Grant via `adb shell pm grant com.proxyagent.app android.permission.WRITE_SECURE_SETTINGS` |
| Battery whitelist | — | recommended for long sessions — tap `ALLOW BACKGROUND` on the main screen |

## Status

Active development. NATIVE engine is the production path; BINARY
is kept for testing/comparison and slated for removal once NATIVE
has enough field hours (see `ARCHITECTURE.md` §Agent engines).
