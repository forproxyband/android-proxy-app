package com.proxyagent.app

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

// Central helper for everything related to "does the proxy come back on its
// own after a reboot". Three independent obstacles, each with its own probe
// and (where possible) its own fix:
//
//  1. Battery / Doze whitelist — a Doze-restricted app can have its foreground
//     service killed and its BOOT_COMPLETED delivery deferred. Fixable without
//     root via the standard ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS dialog.
//
//  2. OEM autostart managers (MIUI / EMUI / ColorOS / FuntouchOS / OneUI …) —
//     these silently drop BOOT_COMPLETED even for battery-whitelisted apps.
//     No public API to query or set; the only non-root fix is to deep-link the
//     user into the vendor's "Autostart" screen so they flip the toggle by hand.
//
//  3. Lock screen (Direct Boot) — with a secure lock screen set, the stock
//     BOOT_COMPLETED broadcast (and the app's credential-encrypted storage)
//     only becomes available AFTER the first manual unlock. Until then the
//     proxy cannot start. We can only DETECT this and warn; truly starting
//     before unlock needs device-protected storage + a directBootAware
//     receiver (a separate, larger change — see the settings warning text).
//
// On rooted devices (the production fleet) obstacles 1 and 2 are moot: the
// Magisk/KernelSU boot script installed by installRootBootScript() runs as
// root at late boot, whitelists the app itself, and starts the agent via the
// already-exported RemoteControlReceiver — bypassing both Doze and the OEM
// autostart manager entirely.
object AutostartManager {

    private const val TAG = "ProxyAgent.Autostart"

    // Magisk / KernelSU both run every executable *.sh in this directory as
    // root, once, late in boot (after `sys.boot_completed`). This is the
    // vendor-agnostic "run something as root on every boot" hook.
    private const val SERVICE_D_DIR = "/data/adb/service.d"
    private const val BOOT_SCRIPT_NAME = "proxyagent-autostart.sh"
    private const val BOOT_SCRIPT_PATH = "$SERVICE_D_DIR/$BOOT_SCRIPT_NAME"

    // ── Lock screen (Direct Boot) ──────────────────────────────────────────

    // True when the user has a secure lock screen (PIN / pattern / password).
    // With one set, the proxy can only auto-start AFTER the first unlock
    // following a reboot — that's the case worth warning about.
    fun isDeviceSecure(context: Context): Boolean = try {
        (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
            ?.isDeviceSecure == true
    } catch (_: Throwable) { false }

    // ── Battery / Doze whitelist ───────────────────────────────────────────

    fun isBatteryWhitelisted(context: Context): Boolean = try {
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    } catch (_: Throwable) {
        // Pre-M has no Doze — treat as "fine".
        true
    }

    // Opens the per-app battery-optimization exemption dialog. Mirrors
    // MainActivity.requestBatteryWhitelist so both entry points behave the
    // same. Returns false if neither the direct-request nor the settings-list
    // intent could be launched (extremely rare).
    @Suppress("BatteryLife")
    fun openBatteryWhitelist(context: Context): Boolean {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (tryStart(context, direct)) return true
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return tryStart(context, list)
    }

    // ── OEM autostart manager ──────────────────────────────────────────────

    // True only for manufacturers known to ship an autostart manager that can
    // block BOOT_COMPLETED. Used to decide whether to even show the "Open
    // autostart settings" button — on a Pixel/stock device it's noise.
    fun hasOemAutostartManager(): Boolean {
        // Substring match over MANUFACTURER + BRAND, not an exact-set lookup:
        // several vendors report multi-word or parent-company strings that an
        // exact match would never hit — e.g. Transsion phones report
        // "TECNO MOBILE LIMITED" / "INFINIX MOBILITY LIMITED", HMD reports
        // "HMD Global" (brand "Nokia"), older LeEco reports "LeMobile". Redmi/
        // POCO report MANUFACTURER "Xiaomi" (brand carries the sub-brand), and
        // iQOO reports "vivo" — so those are covered by the parent tokens.
        val ids = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        return listOf(
            "xiaomi", "redmi", "poco", "huawei", "honor",
            "oppo", "realme", "oneplus", "vivo", "iqoo",
            "meizu", "asus", "samsung", "letv", "leeco", "lemobile",
            "hmd", "nokia", "tecno", "infinix", "itel", "transsion",
        ).any { ids.contains(it) }
    }

    // Candidate vendor "Autostart" / "Startup manager" screens, tried in
    // order. These ComponentNames are undocumented and drift between ROM
    // versions, so every launch is wrapped and we fall through to the next
    // candidate, ending at the app-details page (always present) so the button
    // never appears to do nothing.
    private val OEM_AUTOSTART_COMPONENTS = listOf(
        // Xiaomi / Redmi / POCO (MIUI / HyperOS)
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        // Huawei / Honor (EMUI / MagicOS)
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        // Oppo / Realme (ColorOS) — also covers OnePlus OxygenOS 12+ (ColorOS-based)
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        // Vivo / iQOO (FuntouchOS / OriginOS)
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.PurviewTabActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        // OnePlus (OxygenOS ≤11)
        "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        // Samsung (OneUI) — no true autostart toggle; deep-link to the
        // "never sleeping apps" checklist, then the battery landing.
        "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.usage.CheckableAppListActivity",
        "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
        "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        // Meizu (Flyme)
        "com.meizu.safe" to "com.meizu.safe.permission.SmartBGActivity",
        "com.meizu.safe" to "com.meizu.safe.permission.PermissionMainActivity",
        // Asus (ZenUI)
        "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity",
        "com.asus.mobilemanager" to "com.asus.mobilemanager.powersaver.PowerSaverSettings",
        "com.asus.mobilemanager" to "com.asus.mobilemanager.MainActivity",
        // Nokia / HMD (Evenwell power-saver)
        "com.evenwell.powersaving.g3" to
            "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity",
        // Transsion — Tecno / Infinix / itel (HiOS / XOS PhoneManager).
        // Community-cited, drift across builds — kept ahead of the app-details
        // fallback so they're tried but never block it.
        "com.transsion.phonemanager" to "com.itel.autobootmanager.activity.AutoBootMgrActivity",
        "com.cxzh.restrict" to "com.cxzh.restrict.MainActivity",
        // LeEco / LeTV
        "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity",
    )

    // Tries each vendor autostart screen, then falls back to this app's
    // system details page (where "Autostart"/"Battery" toggles usually also
    // live). Returns true once something launches.
    fun openOemAutostartSettings(context: Context): Boolean {
        for ((pkg, cls) in OEM_AUTOSTART_COMPONENTS) {
            val i = Intent().apply {
                component = ComponentName(pkg, cls)
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (tryStart(context, i)) {
                Log.i(TAG, "opened OEM autostart screen $pkg/$cls")
                return true
            }
        }
        // Universal fallback — the app's own settings page.
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return tryStart(context, details)
    }

    // ── Root boot script (guaranteed path on the rooted fleet) ─────────────

    // Cheap su probe. Blocks up to ~5s — call off the main thread.
    fun isRootAvailable(): Boolean = IpCycle.runRoot("true")

    // True if our boot script is already installed under service.d.
    // Blocks on su — call off the main thread.
    fun isRootBootScriptInstalled(): Boolean =
        IpCycle.runRoot("test -f $BOOT_SCRIPT_PATH")

    // Installs (or overwrites) the Magisk/KernelSU boot script that, on every
    // subsequent reboot, whitelists this app from Doze and starts the agent
    // via RemoteControlReceiver — independent of the OEM autostart manager and
    // battery settings. The connection key is baked into the script because
    // RemoteControlReceiver authenticates every command with it; the file is
    // chmod 700 under /data/adb (root-only readable), which is the same trust
    // boundary the rest of the root IP-rotation features already rely on.
    //
    // Blocks on su — call off the main thread. Returns true on success.
    fun installRootBootScript(context: Context, connectionKey: String): Boolean {
        if (connectionKey.isBlank()) {
            Log.w(TAG, "installRootBootScript: no connection key — refusing")
            return false
        }
        if (!keyUsableInBootScript(connectionKey)) {
            // A quote in the key would desync auth (see keyUsableInBootScript).
            // The UI rejects this earlier with a clear message; guard here too
            // so the public API can't produce a silently-broken script.
            Log.w(TAG, "installRootBootScript: key contains a quote — refusing")
            return false
        }
        val script = buildBootScript(context.packageName, connectionKey)
        // Stage the script in app-private storage first, then copy it into
        // place as root. Writing directly to /data/adb via `echo` through
        // `su -c` is brittle with multi-line content + quoting; a staged file
        // + cp sidesteps all shell-escaping concerns.
        val staged = java.io.File(context.cacheDir, BOOT_SCRIPT_NAME)
        return try {
            staged.writeText(script)
            val ok = IpCycle.runRoot(
                "mkdir -p $SERVICE_D_DIR && " +
                "cp '${staged.absolutePath}' $BOOT_SCRIPT_PATH && " +
                "chmod 700 $BOOT_SCRIPT_PATH && " +
                "chown 0:0 $BOOT_SCRIPT_PATH"
            )
            Log.i(TAG, "installRootBootScript: ${if (ok) "ok" else "failed"}")
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "installRootBootScript threw: ${t.message}")
            false
        } finally {
            try { staged.delete() } catch (_: Throwable) {}
        }
    }

    // Removes the boot script. Returns true only if the file is actually gone
    // afterwards (so a root-denied removal reports failure instead of a false
    // "removed"). `rm -f` alone always exits 0, hence the explicit re-check.
    // Blocks on su — call off the main thread.
    fun removeRootBootScript(): Boolean =
        IpCycle.runRoot("rm -f $BOOT_SCRIPT_PATH; [ ! -f $BOOT_SCRIPT_PATH ]")

    // A single quote in the key can't be represented in the script's
    // KEY='...' literal without desyncing auth (RemoteControlReceiver compares
    // against the raw stored key), which would make every boot broadcast fail
    // auth silently. Reject up front so the user gets a clear message instead
    // of a boot script that never starts anything. Keys are base64/hex in
    // practice, so this effectively never triggers.
    fun keyUsableInBootScript(key: String): Boolean = !key.contains('\'')

    // The script waits for boot to finish, exempts us from Doze, then retries
    // the start broadcast until the app's credential-encrypted storage is
    // unlocked (RemoteControlReceiver needs the stored key to authenticate,
    // and that key lives in CE storage which is locked until first unlock on
    // devices with a secure lock screen). On a lock-screen-free device the
    // very first attempt succeeds; on a locked device it succeeds right after
    // the user unlocks, within the retry window.
    private fun buildBootScript(pkg: String, key: String): String {
        // The key sits inside a single-quoted shell string and is re-quoted as
        // "$KEY" at broadcast time, so there is no shell-injection surface.
        // installRootBootScript already rejects keys containing a single quote
        // (which would desync auth), but strip defensively as a second line.
        val safeKey = key.replace("'", "")
        val action = "com.proxyagent.app.REMOTE_CONTROL"
        return buildString {
            append("#!/system/bin/sh\n")
            append("# ProxyAgent auto-start after reboot — installed by the app.\n")
            append("# Runs as root at late boot (Magisk/KernelSU service.d).\n")
            append("# Remove via Settings → 'Remove root autostart', or delete this file.\n")
            append("PKG=$pkg\n")
            append("KEY='$safeKey'\n")
            append("until [ \"\$(getprop sys.boot_completed)\" = \"1\" ]; do sleep 3; done\n")
            append("sleep 10\n")
            append("# Exempt from Doze / background limits so the FGS survives.\n")
            append("dumpsys deviceidle whitelist +\$PKG 2>/dev/null\n")
            append("cmd appops set \$PKG RUN_ANY_IN_BACKGROUND allow 2>/dev/null\n")
            append("# Retry until CE storage is unlocked and the key authenticates.\n")
            append("i=0\n")
            append("while [ \$i -lt 30 ]; do\n")
            append("  # Already up (e.g. the in-app BootReceiver won the race)? Done —\n")
            append("  # avoids a redundant file-wipe + duplicate start delivery.\n")
            append("  st=\$(am broadcast -n \$PKG/.RemoteControlReceiver")
            append(" -a $action --es cmd status --es key \"\$KEY\" 2>&1)\n")
            append("  case \"\$st\" in *\"status: running\"*|*\"status: starting\"*) exit 0;; esac\n")
            append("  # Not up yet — start it. boot_gate makes the app honor a\n")
            append("  # deliberate STOP (no was_running flag → it won't resurrect).\n")
            append("  out=\$(am broadcast -n \$PKG/.RemoteControlReceiver")
            append(" -a $action --es cmd start --ez boot_gate true --es key \"\$KEY\" 2>&1)\n")
            append("  case \"\$out\" in\n")
            append("    *\"ok: starting\"*) exit 0;;\n")
            append("    *\"skip:\"*) exit 0;;\n")
            append("  esac\n")
            append("  i=\$((i+1))\n")
            append("  sleep 10\n")
            append("done\n")
        }
    }

    // ── internal ───────────────────────────────────────────────────────────

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (t: Throwable) {
        Log.d(TAG, "tryStart failed for ${intent.component ?: intent.action}: ${t.message}")
        false
    }
}
