package com.anatdx.rei.core.reid

import android.content.Context
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

                // KernelSU only recognizes /data/adb/ksud. Replace it with a hardlink to reid.
                // If an existing ksud is different, back it up first.
                append("if [ -e '").append(ksud).append("' ] && ! cmp -s '").append(dstReid).append("' '").append(ksud).append("'; then ")
                append("mv '").append(ksud).append("' '").append(ksud).append(".bak.$(date +%s)'; ")
                append("fi; ")
                append("rm -f '").append(ksud).append("'; ")
                append("ln -f '").append(dstReid).append("' '").append(ksud).append("'; ")

                append("(restorecon -F '").append(dstReid).append("' '").append(ksud).append("' 2>/dev/null || true); ")

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

