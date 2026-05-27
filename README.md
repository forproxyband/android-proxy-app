# Proxy Agent (Android)

[![Build APK](https://github.com/forproxyband/android-proxy-app/actions/workflows/build.yml/badge.svg)](https://github.com/forproxyband/android-proxy-app/actions/workflows/build.yml)

Turns an Android phone into a **mobile/residential proxy node**. The
phone dials home to a registrator pool, and whoever holds the agent
key can route proxy traffic out through this phone's cellular IP.

## What it actually does

- Keeps a persistent uplink to a registrator (auto-negotiated:
  **QUIC → TCP+yamux → WebSocket** fallback).
- Forwards incoming proxy requests through the phone's cellular
  network — so external clients see the phone's mobile exit IP.
- **Rotates the cellular IP** on demand or on server-side trigger:
  airplane-mode toggle → RAT switch (LTE↔GSM) → optional APN swap
  / IMEI rotation. Root makes this much more reliable.
- **Wi-Fi return** (optional): the agent↔registrator control link
  can ride Wi-Fi while target dials still go through cellular —
  saves mobile data without leaking the Wi-Fi IP to targets.
- Auto-stops on low battery / no-internet, auto-restarts after app
  updates (when OEM doesn't block the broadcast).

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
service), three swappable agent engines (NATIVE Kotlin port —
default; BINARY subprocess; AAR in-process Go — legacy),
file-based IPC through `filesDir`, no Binder.

Full write-up — **[ARCHITECTURE.md](ARCHITECTURE.md)**:
- Process model + IPC files
- Agent engines (NATIVE / BINARY / AAR trade-offs)
- TCP fast path (kernel `splice(2)` shim, NIO fallback)
- IP rotation algorithm + interrupted-cycle recovery
- Wi-Fi return relay (split-routing, self-test, OEM caveats)
- Auto-stop watchdog
- Surviving an app update (heartbeat-staleness, auto-restart)

Binary protocol + SDK runtime surface — **[BINARIES.md](BINARIES.md)**.

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
| Android | 5.0+ (API 21) | 14+ for full FGS exemption coverage |
| SIM | with mobile data | — |
| Root (Magisk) | — | needed for IP rotation, APN swap, RAT switch, IMEI rotation |
| `WRITE_SECURE_SETTINGS` | — | enables airplane-mode toggle without root: `adb shell pm grant com.proxyagent.app android.permission.WRITE_SECURE_SETTINGS` |

## Status

Active development. NATIVE engine is the production path; BINARY
and AAR are kept for testing/comparison and slated for removal once
NATIVE has enough field hours (see `ARCHITECTURE.md` §Agent engines).
