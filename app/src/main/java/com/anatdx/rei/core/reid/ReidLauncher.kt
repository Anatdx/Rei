package com.anatdx.rei.core.reid

import android.content.Context
import android.util.Log
import com.anatdx.rei.ApNatives
import com.anatdx.rei.ReiApplication
import com.anatdx.rei.core.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.FileOutputStream
import java.io.File
import java.util.zip.ZipFile

sealed class ReidStartResult {
    data object Started : ReidStartResult()
    data class Failed(val reason: String) : ReidStartResult()
}

/** Reid install status for home UI */
sealed class ReidInstallStatus {
    data object Unknown : ReidInstallStatus()
    data object NotInstalled : ReidInstallStatus()
    data class Installed(val versionLine: String? = null) : ReidInstallStatus()
}

object ReidLauncher {
    /** APatch: notify kernel su path before install (no root required) */
    private const val LEGACY_SU_PATH = "/system/bin/su"

    /** Check if reid is installed; version by backend (apd/ksud) for home daemon name */
    suspend fun getInstallStatus(context: Context): ReidInstallStatus {
        return withContext(Dispatchers.IO) {
            val useApd = ReiApplication.rootImplementation == ReiApplication.VALUE_ROOT_IMPL_APATCH
            val cmd = if (useApd) {
                "[ -x /data/adb/apd ] && /data/adb/apd -V 2>/dev/null || echo NOT_INSTALLED"
            } else {
                "[ -x /data/adb/ksud ] && /data/adb/ksud version 2>/dev/null || [ -x /data/adb/reid ] && /data/adb/reid version 2>/dev/null || echo NOT_INSTALLED"
            }
            val r = RootShell.exec(cmd, timeoutMs = 5_000L)
            when {
                r.exitCode != 0 -> ReidInstallStatus.Unknown
                r.output.trim() == "NOT_INSTALLED" -> ReidInstallStatus.NotInstalled
                r.output.isBlank() -> ReidInstallStatus.Installed(versionLine = null)
                else -> ReidInstallStatus.Installed(versionLine = r.output.trim().lines().firstOrNull()?.take(80))
            }
        }
    }

    suspend fun start(context: Context): ReidStartResult {
        return withContext(Dispatchers.IO) {
            // APatch: sync su path to kernel (same as IcePatch installApatch first line)
            if (ReiApplication.rootImplementation == ReiApplication.VALUE_ROOT_IMPL_APATCH && ReiApplication.superKey.isNotEmpty()) {
                val ok = ApNatives.resetSuPath(ReiApplication.superKey, LEGACY_SU_PATH)
                Log.i("Rei", "resetSuPath(ReidLauncher start)=$ok")
            }

            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "arm64-v8a" }

            val reidBin = locateOrExtract(context, abi, "libreid.so")
                ?: return@withContext ReidStartResult.Failed("no_libreid")

            val srcReid = reidBin.absolutePath
            val dstReid = "/data/adb/reid"
            val apd = "/data/adb/apd"
            val ksud = "/data/adb/ksud"
            val uid = android.os.Process.myUid()
            val pkg = context.packageName
            val useApd = ReiApplication.rootImplementation == ReiApplication.VALUE_ROOT_IMPL_APATCH
            val reiDir = "/data/adb/rei"

            // Only create/update the daemon link for the current backend; do not remove the other (KSU vs KP may both be installed).
            val installCmd = buildString {
                fun esc(s: String) = s.replace("'", "'\\''")

                append("set -e; cd /data/adb || exit 1; ")
                append("mkdir -p /data/adb '").append(reiDir).append("'; ")

                append("SRC='").append(esc(srcReid)).append("'; ")
                append("if [ ! -f '").append(dstReid).append("' ] || ! cmp -s \"\$SRC\" '").append(dstReid).append("'; then ")
                append("cp -f \"\$SRC\" '").append(dstReid).append(".new'; chmod 0755 '").append(dstReid).append(".new'; mv -f '").append(dstReid).append(".new' '").append(dstReid).append("'; fi; ")
                if (useApd) {
                    append("rm -f '").append(apd).append("'; ln '").append(dstReid).append("' '").append(apd).append("'; ")
                } else {
                    append("rm -f '").append(ksud).append("'; ln '").append(dstReid).append("' '").append(ksud).append("'; ")
                }

                append("(restorecon -F '").append(dstReid).append("'")
                if (useApd) append(" '").append(apd).append("'") else append(" '").append(ksud).append("'")
                append(" 2>/dev/null || true); ")
                append("chmod 4755 '").append(dstReid).append("'")
                if (useApd) append(" '").append(apd).append("'") else append(" '").append(ksud).append("'")
                append("; ")

                val daemonBin = if (useApd) apd else ksud
                val backendBin = if (useApd) "/data/adb/ap/bin" else "/data/adb/ksu/bin"
                append("mkdir -p '").append(backendBin).append("'; rm -f '").append(backendBin).append("/ksud' '").append(backendBin).append("/apd'; ln -sf '").append(daemonBin).append("' '").append(backendBin).append("/ksud'; ")
                if (useApd) append("ln -sf '").append(daemonBin).append("' '").append(backendBin).append("/apd'; ")
                append("rm -f /data/adb/rei/bin; ln -sf '").append(backendBin).append("' /data/adb/rei/bin; ")

                if (useApd) {
                    append("mkdir -p /data/adb/ap; ")
                    append("touch /data/adb/ap/su_path; ")
                    append("[ -s /data/adb/ap/su_path ] || echo '").append(LEGACY_SU_PATH).append("' > /data/adb/ap/su_path; ")
                    append("killall reid 2>/dev/null; killall apd 2>/dev/null; true; ")
                }
                if (!useApd) {
                    append("('").append(ksud).append("' debug set-manager '").append(esc(pkg)).append("' >/dev/null 2>&1 || true); ")
                    append("('").append(ksud).append("' profile set-allow '").append(uid).append("' '").append(esc(pkg)).append("' 1 >/dev/null 2>&1 || true); ")
                }
                append("echo OK")
            }

            val install = RootShell.exec(installCmd, timeoutMs = 15_000L)
            if (install.exitCode != 0) {
                return@withContext ReidStartResult.Failed("install_failed:${install.exitCode}:${install.output.take(160)}")
            }

            // APatch: notify kernel again after install (keep su_path in sync)
            if (ReiApplication.rootImplementation == ReiApplication.VALUE_ROOT_IMPL_APATCH && ReiApplication.superKey.isNotEmpty()) {
                val ok = ApNatives.resetSuPath(ReiApplication.superKey, LEGACY_SU_PATH)
                Log.i("Rei", "resetSuPath(ReidLauncher after install)=$ok")
            }

            // Start daemon so argv[0] basename is apd/ksud (reid single binary dispatches by argv[0])
            val daemonBin = if (useApd) apd else ksud
            val startCmd = "sh -c '(pidof ${if (useApd) "apd" else "ksud"} >/dev/null 2>&1) || ($daemonBin daemon >/dev/null 2>&1 &)'"
            val started = RootShell.exec(startCmd, timeoutMs = 5_000L)
            if (started.exitCode == 0) ReidStartResult.Started
            else ReidStartResult.Failed("start_failed:${started.exitCode}:${started.output.take(160)}")
        }
    }

    /** Uninstall: stop daemon, remove /data/adb/reid, apd, ksud and /data/adb/rei */
    suspend fun uninstall(context: Context): ReidStartResult {
        return withContext(Dispatchers.IO) {
            val cmd = (
                "killall reid 2>/dev/null; killall apd 2>/dev/null; killall ksud 2>/dev/null; true; " +
                "rm -f /data/adb/apd /data/adb/ksud /data/adb/reid; " +
                "rm -rf /data/adb/rei; " +
                "echo OK"
            )
            val r = RootShell.exec(cmd, timeoutMs = 10_000L)
            if (r.exitCode == 0 && r.output.trim().contains("OK")) ReidStartResult.Started
            else ReidStartResult.Failed("uninstall_failed:${r.exitCode}:${r.output.take(160)}")
        }
    }

    private fun locateOrExtract(context: Context, abi: String, soName: String): File? {
        // 1) If system extracted native libs, use them directly.
        context.applicationInfo.nativeLibraryDir?.let { libDir ->
            val f = File(libDir, soName)
            if (f.exists() && f.length() > 0L) return f
        }

        // 2) Otherwise, extract from APK to app's private lib folder (data/data/<pkg>/lib/<abi>/)
        val apk = context.applicationInfo.sourceDir?.takeIf { it.isNotBlank() } ?: return null
        val entryName = "lib/$abi/$soName"
        val outDir = File(context.getDir("lib", Context.MODE_PRIVATE), abi).apply { mkdirs() }
        val out = File(outDir, soName)

        if (out.exists() && out.length() > 0L) return out

        runCatching {
            ZipFile(apk).use { z ->
                val e = z.getEntry(entryName) ?: return null
                z.getInputStream(e).use { input ->
                    BufferedInputStream(input).use { bis ->
                        FileOutputStream(out).use { fos ->
                            bis.copyTo(fos)
                            fos.fd.sync()
                        }
                    }
                }
            }
        }.getOrElse { return null }

        return out.takeIf { it.exists() && it.length() > 0L }
    }
}

