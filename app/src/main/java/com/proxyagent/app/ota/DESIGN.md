# OTA self-update — design

How the app updates itself over the air from the CRM's update channel.
This document is the load-bearing reference for anyone touching the
`ota/` package — read it before changing component boundaries or the
manifest contract.

The client is a **consumer** of a public distribution bucket. It polls a
JSON manifest, compares versions, downloads an encrypted build straight
from Cloudflare R2, decrypts it, verifies its hash, and hands it to the
system package installer. The CRM is involved only at publish time; the
download path never talks to the CRM.

The wire contract (R2 layout, manifest JSON, Blowfish encryption, SHA-256)
is owned by the CRM team and documented separately in
`mobile-app-ota-updates-ru.md`. That doc is the source of truth; this one
describes **our client** and only restates the contract where needed.

## Configuration

All coordinates come from `BuildConfig`, injected in `app/build.gradle.kts`
(overridable via env for staging/rotation):

| Field | Scope | Meaning |
|---|---|---|
| `OTA_BASE_URL` | shared (`defaultConfig`) | Public R2 base URL |
| `OTA_PLATFORM` | shared (`defaultConfig`) | `android` |
| `OTA_DEFAULT_CHANNEL` | shared (`defaultConfig`) | Channel used until the user picks one (`stable`) |
| `OTA_APP_ID` | **per build type** | This app's 24-hex ObjectId (its folder in R2) |
| `OTA_ENCRYPTION_KEY` | **per build type** | Blowfish key (UTF-8 bytes, embedded in client by contract) |

**Per-build-type apps.** `release` and `debug` are **separate CRM apps** —
each has its own `OTA_APP_ID` + `OTA_ENCRYPTION_KEY` (set in `buildTypes {}`)
**and its own APK signature** (release key vs debug key). A build must only
ever be offered OTA APKs from *its own* CRM app: an update signed by a
different key is rejected by Android (`App not installed`). Debug ships blank
values until its CRM app is registered, and `OtaConfig.isConfigured()`
(`appId` + `baseUrl` non-blank) then **disables OTA for that build** — the
main-screen widget hides, the worker no-ops, and `UpdatesActivity` shows
"OTA is not configured for this build."

`OtaConfig` reads these plus the user-selected channel (stored in the shared
`SharedPreferences("cfg")` under `ota_channel`) and builds every URL:

```
<base>/updates/app/<appId>/<platform>/current-versions.json
<base>/updates/app/<appId>/<platform>/versions-<channel>.json
<base>/updates/app/<appId>/<platform>/<fileName>        # encrypted build
```

## Component map

Read this before moving logic between files.

| File | Responsibility |
|---|---|
| `OtaTypes.kt` | `OtaChannel` (value class), `CurrentRelease`, `HistoryEntry`, `UpdateStatus`, typed exceptions. Pure, no Android deps. |
| `OtaConfig.kt` | BuildConfig coordinates + tracked channel; URL builders. |
| `OtaManifest.kt` | Manifest parsing (via the in-house `MiniJson`), channel discovery, version compare, `fileName` validation. Pure. |
| `OtaCrypto.kt` | Streaming Blowfish decrypt + SHA-256 (single pass). Pure JVM crypto. |
| `OtaClient.kt` | `HttpURLConnection` GETs: manifests (uncached) and build download (Range-resume, 404 handling). No auth. |
| `OtaManager.kt` | Orchestration: `check` / `history` / `currentRelease` / `prepare` (download → decrypt → verify → installable APK). Blocking; call off the main thread. |
| `ApkInstaller.kt` | FileProvider URI + system installer intent; "install unknown apps" grant flow. |
| `OtaExport.kt` | Saves a prepared APK to the public Downloads folder (no-root downgrade path): MediaStore on API 29+, legacy dir + `WRITE_EXTERNAL_STORAGE` on 23-28. |
| `RootInstaller.kt` | Silent install on rooted devices via `su -c "pm install -r -d -S <size>"` (APK streamed over stdin). Enables background auto-update and direct downgrades. |
| `UpdatesActivity.kt` | UI: channel picker (dynamic Spinner), status + "last checked" line, a primary **Install** button (the channel's current version), manual **Check**, and a collapsible **Downgrade** section listing older versions (each → save to Downloads). Progress dialog for the download. |
| `OtaUpdateWorker.kt` | Background periodic check → "update available" notification (dedup by build). |
| `OtaScheduler.kt` | Enqueues the periodic worker; `runOnceNow` test trigger. |
| `OtaNotifications.kt` | The update-available notification + its channel. |

`MiniJson` is reused from `com.proxyagent.app.nativeagent` (same-module
`internal`). No JSON library is added.

## Manifests (what the client reads)

- **`current-versions.json`** — array, at most one entry per channel; the
  authoritative "actual version" for each channel. Fields used:
  `channel`, `currentVersion`, `currentBuild`, `SHA256`, `fileName`,
  `releaseDate`. A channel **absent** from the array = no actual version
  (normal, not an error).
- **`versions-<channel>.json`** — array, full channel history (newest
  first). Fields used: `status` (`current`/`old`), `version`, `build`,
  `fileName`. May be empty (`[]`). **No `SHA256`** — see Integrity.

Unknown JSON fields are ignored (forward-compat). Entries missing a
required field — or whose `fileName` is not a 24-hex ObjectId — are
dropped.

## Version comparison — the load-bearing contract

The installed app's identity is its `versionCode` (= `GITHUB_RUN_NUMBER`,
see `app/build.gradle.kts`). The client compares **numerically**:

```
manifest currentBuild (Long)  >  BuildConfig.VERSION_CODE   →  update available
```

**The CRM `build` field MUST equal the APK's `versionCode`.** If they
diverge, a device perpetually re-"updates" to the same APK. Enforced by
convention on the publish side, not by the client — keep it true.

`currentBuild` may *decrease* between polls (admin rolled back the current
release); that is authoritative and handled — the widget just reports
"up to date" when the installed build is ≥ the channel's current.

## Channels are dynamic

Channels are **not** a closed set. The documented baseline is
`stable`/`beta`/`dev`, but channels can be dropped (only `stable`
remaining) or added later. Therefore:

- `OtaChannel` is an open string value class — an unknown id is preserved,
  never collapsed into a known channel.
- The channel picker is populated at runtime by
  `OtaManifest.discoverChannels()` = baseline ∪ channels that currently
  have a release ∪ the tracked channel.
- A missing manifest (404) → empty, not an error.

Limitation: there is **no channel index** in the contract, so a brand-new
channel that has history but **no** current release cannot be
auto-discovered — it appears once it gets a current release, or if it is a
baseline name. Surfacing "dormant" channels would need a contract change.

**Switch guard.** Switching to a channel whose current build is *older* than
the installed build (e.g. you were on beta ahead of stable) would strand the
user on a channel they can only downgrade from. `maybeSwitchChannel` detects
this (using the cached all-channels manifest) and warns with a "Switch anyway /
Cancel" dialog, reverting the spinner on cancel. Builds legitimately migrate
between channels, so it's a warning, not a hard block. When such a channel is
selected, the primary button reads **DOWNGRADE TO x** and routes through the
downgrade path (root-silent if available, else save-to-Downloads).

## Update flow

**Check** (widget on launch/resume, manual button, or background worker):

1. GET `current-versions.json` (uncached).
2. Find the tracked channel's record by the `channel` field.
3. `statusFor` → `Available` / `UpToDate` / `NoRelease`.

The widget and worker only **surface** an update (text/colour or a
notification). They never download.

**Install** (user-initiated, from `UpdatesActivity`):

1. Confirm (warns when hash is unpublished or the build is a downgrade).
2. Ensure the "install unknown apps" grant (API 26+); route to settings if
   missing.
3. `OtaManager.prepare(fileName, expectedSha256)`:
   - GET the encrypted build to `cacheDir/ota/<fileName>.enc` (Range-resume,
     404 → `BuildGoneException`).
   - Stream-decrypt Blowfish → `cacheDir/ota/<fileName>.apk`, computing
     SHA-256 in the same pass.
   - Verify SHA-256 against the manifest (when available); check APK magic;
     on mismatch delete and throw `IntegrityException`.
   - Delete the `.enc`; keep the `.apk` (immutable by `fileName`, reusable).
4. `ApkInstaller.install` → system installer via a FileProvider `content://`
   URI. `PackageReplacedReceiver` restarts `ProxyService` after replacement.

**Root (silent) path.** The install path is probed for root on each reload
(and in the worker). When `su` grants root, install/reinstall/downgrade all go
through `RootInstaller` — `pm install -r -d` — with **no user prompt**, and the
`-d` flag makes downgrades work directly. This is the path used on the
(rooted) target fleet.

**No-root downgrade** (selected build < installed `versionCode`): Android
refuses an in-place downgrade (`INSTALL_FAILED_VERSION_DOWNGRADE`), so instead
of installing, the decrypted+verified APK is saved to the public **Downloads**
folder (`OtaExport` — MediaStore on API 29+, legacy dir + `WRITE_EXTERNAL_STORAGE`
on 23-28). The user uninstalls the app and installs the saved file manually.
No API lets a non-privileged (non-root) app bypass the downgrade block.

**Background auto-update.** When the `ota_auto_update` toggle (Updates screen)
is on AND root is available, `OtaUpdateWorker` downloads → decrypts → verifies →
`RootInstaller.installSilently` — a fully unattended update in the background.
Without root (or toggle off) the worker only posts the "update available"
notification. Installing our own update replaces the app and kills the worker
process at commit; `PackageReplacedReceiver` restarts the service. On failure
the worker falls back to the notification.

## Integrity & security

- **Encryption:** `Blowfish/ECB/PKCS5Padding`, key = UTF-8 bytes of
  `OTA_ENCRYPTION_KEY` (no derivation). Streamed — builds can be 100+ MB.
- **Hash:** `SHA256` in the manifest is over the **decrypted** file and is
  checked **after decrypt, before install**. Only a channel's **current**
  release publishes a hash; older history entries carry none, so installing
  them falls back to an APK-structure check + the system installer's
  signature verification. The UI warns about this.
- **Signature:** an OTA APK must be signed with the same key as the
  installed app (Android rejects a mismatched-signer update). The
  distributed APK is the same signed CI release artifact.
- **`fileName` validation:** `fileName` is used as both a URL segment and a
  local file path. It is validated to be exactly 24 hex chars
  (`OtaManifest.isValidFileName`) at parse time *and* in `prepare`, to stop
  a hostile/compromised bucket from path-traversing out of `cacheDir/ota/`.

## Permissions

- `INTERNET` (already present) — download.
- `REQUEST_INSTALL_PACKAGES` — launch the installer; on API 26+ the user
  also grants per-app "install unknown apps" (`canRequestPackageInstalls` +
  `ACTION_MANAGE_UNKNOWN_APP_SOURCES`).
- `POST_NOTIFICATIONS` (already present, requested in `MainActivity`) — the
  background update notification (Android 13+).
- `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion=28`) — only the no-root downgrade
  "save to Downloads" path on API 23-28; API 29+ uses MediaStore, no grant.
- FileProvider: authority `${applicationId}.fileprovider`, path
  `cache-path name="ota" path="ota/"` in `res/xml/filepaths.xml`.
- Root (`su`) — optional; only for the silent-install / auto-update path.
  Not an Android permission — the su manager (Magisk) grants it.

## Background scheduling & auto-update

`OtaScheduler.schedule` (called from `MainActivity.onCreate`) enqueues a
unique periodic `OtaUpdateWorker` (6 h, requires network,
`ExistingPeriodicWorkPolicy.UPDATE`). WorkManager auto-initializes via its
default `androidx.startup` provider — no custom `Configuration`, no manifest
change.

The `ota_auto_update` toggle is always shown on the Updates screen. Enabling it
runs a **non-destructive root self-test** (`su -c id` — no install); if root is
absent the toggle reverts and the user is told it can't be enabled. Disabling
needs no test. The worker's behaviour:
- **off (default):** check + "update available" notification (deduped by build).
- **on + root available:** download → decrypt → verify →
  `RootInstaller.installSilently` — a fully unattended background update. On
  failure it falls back to the notification.

**Test trigger:** long-press the main-screen widget →
`OtaScheduler.runOnceNow` runs the worker immediately with `force=true`
(bypasses the notification dedup), so you don't wait for the 6 h / 15 min
minimum interval.

## Caching

A verified `.apk` is kept in `cacheDir/ota/` and reused on the next install
of the same `fileName` (build objects are immutable per `fileName`). The
`.enc` is always deleted after decrypt. `OtaManager.clearCache` wipes the
directory.

## Publishing a release (CRM side, for reference)

An admin uploads the signed release APK on the CRM "App updates" page,
choosing platform `android`, the channel, `version` = `versionName`, and
`build` = the APK's `versionCode` (see the contract above — these MUST
match). "Make current" (default on) puts it into `current-versions.json`
with its SHA-256. The CRM encrypts the file and regenerates all four
manifests. The client picks it up on the next check.

## Testing

- Pure logic (parsing, version compare, channel discovery, `fileName`
  validation, Blowfish round-trip + SHA-256) has JVM unit tests under
  `app/src/test/.../ota/`, run by `:app:testDebugUnitTest` in CI.
- Full flow: on `UpdatesActivity`, tap Install/Reinstall on any version —
  this exercises download → decrypt → verify → install regardless of
  version ordering.
- "Update available" signal requires the channel's `currentBuild` to be
  greater than the installed `versionCode`. Because CI builds are
  monotonic, test this by publishing a build with a higher number than the
  installed one.
- Local Gradle builds do not work in this project — CI is the build gate.

## Known limitations

- Range-resume in `OtaClient` is correct but currently inert: `prepare`
  deletes the partial `.enc` on every exit, so a partial download never
  survives to a later attempt.
- Dormant channels (history but no current release) are not
  auto-discovered (no channel index in the contract).
- Downgrades: on **rooted** devices `pm install -r -d` performs them silently.
  Without root, Android refuses an in-place downgrade — the action saves the
  APK to Downloads for a manual uninstall+install; nothing bypasses the block
  for a non-privileged app.
- Auto-update is root-only. On non-rooted devices no API allows unattended
  install, so the worker can only notify.
