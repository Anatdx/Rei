package com.anatdx.rei.core.reid

import android.content.Context
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

object ReidLauncher {
    suspend fun start(context: Context): ReidStartResult {
        return withContext(Dispatchers.IO) {
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "arm64-v8a" }

            val reid = locateOrExtract(context, abi, "libreid.so")
                ?: return@withContext ReidStartResult.Failed("no_libreid")

            val srcReid = reid.absolutePath

            val dstReid = "/data/adb/reid"
            val ksud = "/data/adb/ksud"
            val apd = "/data/adb/apd"
            val uid = android.os.Process.myUid()
            val pkg = context.packageName

            val installCmd = buildString {
                fun esc(s: String) = s.replace("'", "'\\''")

                append("set -e; ")
                append("mkdir -p /data/adb; ")

                // Install/update reid only if content differs.
                append("SRC_REID='").append(esc(srcReid)).append("'; ")
                append("DST_REID='").append(dstReid).append("'; ")
                append("if [ ! -f \"\$DST_REID\" ] || ! cmp -s \"\$SRC_REID\" \"\$DST_REID\"; then ")
                append("cp -f \"\$SRC_REID\" '${dstReid}.new'; ")
                append("chmod 0755 '${dstReid}.new'; ")
                append("mv -f '${dstReid}.new' \"\$DST_REID\"; ")
                append("fi; ")

                // ksud/apd 二选一：不活跃的移到 /data/adb/rei/*.bak，切换时移回，避免 rm 丢 root
                val useApd = ReiApplication.rootImplementation == ReiApplication.VALUE_ROOT_IMPL_APATCH
                val reiDir = "/data/adb/rei"
                append("mkdir -p '").append(reiDir).append("'; ")
                if (useApd) {
                    append("[ -f '").append(ksud).append("' ] && mv -f '").append(ksud).append("' '").append(reiDir).append("/ksud.bak'; ")
                    append("[ -f '").append(reiDir).append("/apd.bak' ] && mv -f '").append(reiDir).append("/apd.bak' '").append(apd).append("' || ln -f '").append(dstReid).append("' '").append(apd).append("'; ")
                } else {
                    append("[ -f '").append(apd).append("' ] && mv -f '").append(apd).append("' '").append(reiDir).append("/apd.bak'; ")
                    append("[ -f '").append(reiDir).append("/ksud.bak' ] && mv -f '").append(reiDir).append("/ksud.bak' '").append(ksud).append("' || ln -f '").append(dstReid).append("' '").append(ksud).append("'; ")
                }
                val daemonBin = if (useApd) apd else ksud
                append("(restorecon -F '").append(dstReid).append("' '").append(daemonBin).append("' 2>/dev/null || true); ")
                append("mkdir -p /data/adb/ksu/bin; ln -sf '").append(daemonBin).append("' /data/adb/ksu/bin/ksud; ")

                // KSU 时：让内核识别 Rei 为 manager 并允许本 UID
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

            // Start daemon only when it's not already running.
            val startCmd = "sh -c '(pidof reid >/dev/null 2>&1) || ($dstReid daemon >/dev/null 2>&1 &)'"
            val started = RootShell.exec(startCmd, timeoutMs = 5_000L)
            if (started.exitCode == 0) ReidStartResult.Started
            else ReidStartResult.Failed("start_failed:${started.exitCode}:${started.output.take(160)}")
        }
    }

    private fun locateOrExtract(context: Context, abi: String, soName: String): File? {
        // 1) If system extracted native libs, use them directly.
        context.applicationInfo.nativeLibraryDir?.let { libDir ->
            val f = File(libDir, soName)
            if (f.exists() && f.length() > 0L) return f
        }

        // 2) Otherwise, extract from APK: lib/<abi>/<soName>
        val apk = context.applicationInfo.sourceDir?.takeIf { it.isNotBlank() } ?: return null
        val entryName = "lib/$abi/$soName"
        val outDir = File(context.codeCacheDir, "reid_extract/$abi").apply { mkdirs() }
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

