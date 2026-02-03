package com.anatdx.rei.core.reid

import android.content.Context
import com.anatdx.rei.core.log.ReiLog
import com.anatdx.rei.core.log.ReiLogLevel
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

    /** 使用 reid 本体执行（用于 set-root-impl 等 reid 专属命令） */
    suspend fun execReid(context: Context, args: List<String>, timeoutMs: Long = 30_000L): ReidExecResult {
        return withContext(Dispatchers.IO) {
            val escapedArgs = args.joinToString(" ") { shellEscape(it) }
            val cmd = "sh -c 'if [ -x /data/adb/reid ]; then /data/adb/reid $escapedArgs; else echo no_reid; exit 127; fi'"
            runShellSu(cmd, context, args, timeoutMs)
        }
    }

    suspend fun exec(context: Context, args: List<String>, timeoutMs: Long = 30_000L): ReidExecResult {
        return withContext(Dispatchers.IO) {
            val escapedArgs = args.joinToString(" ") { shellEscape(it) }
            val cmd = buildString {
                append("sh -c '")
                append("if [ -x /data/adb/ksud ]; then /data/adb/ksud ")
                append(escapedArgs)
                append("; ")
                append("elif [ -x /data/adb/apd ]; then /data/adb/apd ")
                append(escapedArgs)
                append("; ")
                append("elif [ -x /data/adb/reid ]; then /data/adb/reid ")
                append(escapedArgs)
                append("; ")
                append("else echo no_installed_backend; exit 127; fi")
                append("'")
            }

            return@withContext runShellSu(cmd, context, args, timeoutMs)
        }
    }

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
        val apk = context.applicationInfo.sourceDir?.takeIf { it.isNotBlank() } ?: return null
        val entryName = "lib/$abi/$soName"
        val outDir = File(context.codeCacheDir, "reid_extract/$abi").apply { mkdirs() }
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

