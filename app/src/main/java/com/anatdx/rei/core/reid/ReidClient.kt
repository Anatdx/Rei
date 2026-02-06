package com.anatdx.rei.core.reid

import android.content.Context
import com.anatdx.rei.ReiApplication
import com.anatdx.rei.core.auth.ReiKeyHelper
import com.anatdx.rei.core.log.ReiLog
import com.anatdx.rei.core.log.ReiLogLevel
import io.murasaki.Murasaki
import io.murasaki.server.IMurasakiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

data class ReidExecResult(
    val exitCode: Int,
    val output: String,
)

object ReidClient {
    suspend fun execDirect(context: Context, args: List<String>, timeoutMs: Long = 30_000L): ReidExecResult {
        return withContext(Dispatchers.IO) {
            val exe = extractFromApk(context, "libreid.so")
                ?: return@withContext ReidExecResult(127, "no_libreid")

            val cmd = buildList {
                add(exe.absolutePath)
                addAll(args)
            }
            return@withContext runCatching {
                val p = ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start()
                val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!done) {
                    p.destroy()
                    return@runCatching ReidExecResult(124, "timeout")
                }
                val out = runCatching { p.inputStream.bufferedReader().readText().trim() }.getOrDefault("")
                val res = ReidExecResult(p.exitValue(), out)
                ReiLog.append(
                    context,
                    if (res.exitCode == 0) ReiLogLevel.I else ReiLogLevel.W,
                    "reid",
                    "(direct) ${args.joinToString(" ")} (exit=${res.exitCode})\n${res.output.take(2000)}",
                )
                res
            }.getOrElse {
                ReiLog.append(context, ReiLogLevel.E, "reid", "(direct) exception:${it.javaClass.simpleName}")
                ReidExecResult(1, it.javaClass.simpleName)
            }
        }
    }

    /** Run reid binary (set-root-impl, allowlist, etc.). Args escaped in single su -c to avoid sh/$@ splitting. */
    suspend fun execReid(context: Context, args: List<String>, timeoutMs: Long = 30_000L): ReidExecResult {
        return withContext(Dispatchers.IO) {
            val reidArgs = args.joinToString(" ") { shellEscape(it) }
            val cmd = "if [ -x /data/adb/reid ]; then exec /data/adb/reid $reidArgs; else echo no_reid; exit 127; fi"
            runShellSu(cmd, context, args, timeoutMs)
        }
    }

    /** Run current backend (ksud/apd/reid). Prefer Murasaki Binder for profile/allowlist; else shell. */
    suspend fun exec(context: Context, args: List<String>, timeoutMs: Long = 30_000L): ReidExecResult {
        return withContext(Dispatchers.IO) {
            val murasakiResult = tryMurasakiForProfile(context, args)
            if (murasakiResult != null) {
                murasakiResult
            } else {
                val backendArgs = args.joinToString(" ") { shellEscape(it) }
                if (ReiApplication.superKey.isEmpty()) {
                    ReiKeyHelper.readSuperKey().takeIf { it.isNotEmpty() }?.let { ReiApplication.superKey = it }
                }
                val apdSuperkey = ReiApplication.superKey
                val apdKeyArg = if (apdSuperkey.isNotEmpty()) " --superkey ${shellEscape(apdSuperkey)}" else ""
                val useApd = ReiApplication.rootImplementation == ReiApplication.VALUE_ROOT_IMPL_APATCH
                val backendPath = if (useApd) "/data/adb/apd" else "/data/adb/ksud"
                val keyArg = if (useApd) apdKeyArg else ""
                val cmd = "if [ -x $backendPath ]; then exec $backendPath$keyArg $backendArgs; elif [ -x /data/adb/reid ]; then exec /data/adb/reid $backendArgs; else echo no_installed_backend; exit 127; fi"
                runShellSu(cmd, context, args, timeoutMs)
            }
        }
    }

    /**
     * Try to handle profile/allowlist via Murasaki Binder (no shell).
     * Returns result if handled, null to fallback to exec shell.
     */
    private fun tryMurasakiForProfile(context: Context, args: List<String>): ReidExecResult? {
        if (args.isEmpty()) return null
        val service: IMurasakiService? = runCatching {
            Murasaki.init(context.packageName)
            Murasaki.getMurasakiService()
        }.getOrNull() ?: return null

        return runCatching {
            when {
                args.size >= 2 && args[0] == "profile" && args[1] == "allowlist" -> {
                    val uids = service?.getRootUids() ?: return@runCatching null
                    ReidExecResult(0, "[${uids.joinToString(",")}]")
                }
                args.size >= 2 && args[0] == "profile" && args[1] == "denylist" -> {
                    val uids = service?.getDenyUids() ?: return@runCatching null
                    ReidExecResult(0, "[${uids.joinToString(",")}]")
                }
                args.size >= 5 && args[0] == "profile" && args[1] == "set-allow" -> {
                    val uid = args[2].toIntOrNull() ?: return@runCatching null
                    val pkg = args[3]
                    val allowSu = args[4] == "1"
                    val json = """{"name":"${pkg.replace("\"", "\\\"")}","currentUid":$uid,"allowSu":$allowSu,"umountModules":${!allowSu}}"""
                    val ok = service?.setAppProfile(uid, json) == true
                    if (ok) ReidExecResult(0, "") else ReidExecResult(1, "setAppProfile failed")
                }
                args.size >= 4 && args[0] == "allowlist" && args[1] == "grant" -> {
                    val uid = args[2].toIntOrNull() ?: return@runCatching null
                    val pkg = args[3]
                    val json = """{"name":"${pkg.replace("\"", "\\\"")}","currentUid":$uid,"allowSu":true}"""
                    val ok = service?.setAppProfile(uid, json) == true
                    if (ok) ReidExecResult(0, "") else ReidExecResult(1, "setAppProfile failed")
                }
                else -> null
            }
        }.getOrElse { e ->
            ReiLog.append(context, ReiLogLevel.W, "reid", "Murasaki profile: ${e.message}")
            null
        }
    }

    /** Run command with root via su -c (same as apd: authorized apps run su to get shell). */
    private fun runShellSu(cmd: String, context: Context, args: List<String>, timeoutMs: Long): ReidExecResult {
        return runCatching {
            val p = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!done) {
                p.destroy()
                return@runCatching ReidExecResult(124, "timeout")
            }
            val out = runCatching { p.inputStream.bufferedReader().readText().trim() }.getOrDefault("")
            val res = ReidExecResult(p.exitValue(), out)
            ReiLog.append(
                context,
                if (res.exitCode == 0) ReiLogLevel.I else ReiLogLevel.W,
                "reid",
                "${args.joinToString(" ")} (exit=${res.exitCode})\n${res.output.take(2000)}",
            )
            res
        }.getOrElse {
            ReiLog.append(context, ReiLogLevel.E, "reid", "exception:${it.javaClass.simpleName}")
            ReidExecResult(1, it.javaClass.simpleName)
        }
    }

    private fun shellEscape(s: String): String {
        val safe = s.replace("'", "'\\''")
        return "'$safe'"
    }

    private fun extractFromApk(context: Context, soName: String): File? {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "arm64-v8a" }
        // Prefer system-extracted native lib
        context.applicationInfo.nativeLibraryDir?.let { libDir ->
            val f = File(libDir, soName)
            if (f.exists() && f.length() > 0L) return f
        }
        val apk = context.applicationInfo.sourceDir?.takeIf { it.isNotBlank() } ?: return null
        val entryName = "lib/$abi/$soName"
        val outDir = File(context.getDir("lib", Context.MODE_PRIVATE), abi).apply { mkdirs() }
        val out = File(outDir, soName)
        if (out.exists() && out.length() > 0L) return out
        return runCatching {
            ZipFile(apk).use { z ->
                val e = z.getEntry(entryName) ?: return null
                z.getInputStream(e).use { input ->
                    out.outputStream().use { os -> input.copyTo(os) }
                }
            }
            out.takeIf { it.exists() && it.length() > 0L }
        }.getOrNull()
    }
}

