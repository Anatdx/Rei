package com.anatdx.rei.core.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class RootResult {
    data class Granted(val stdout: String) : RootResult()
    data class Denied(val reason: String) : RootResult()
}

data class RootExecResult(
    val exitCode: Int,
    val output: String,
)

object RootShell {
    suspend fun request(timeoutMs: Long = 8_000L): RootResult {
        return withContext(Dispatchers.IO) {
            try {
                val p = ProcessBuilder("su", "-c", "id")
                    .redirectErrorStream(true)
                    .start()

                val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!done) {
                    p.destroy()
                    return@withContext RootResult.Denied("timeout")
                }

                val out = runCatching { p.inputStream.bufferedReader().readText().trim() }.getOrDefault("")
                if (p.exitValue() == 0) RootResult.Granted(out) else RootResult.Denied(out.ifBlank { "denied" })
            } catch (e: IOException) {
                RootResult.Denied("no_su")
            } catch (t: Throwable) {
                RootResult.Denied(t.javaClass.simpleName)
            }
        }
    }

    suspend fun exec(command: String, timeoutMs: Long = 30_000L): RootExecResult {
        return withContext(Dispatchers.IO) {
            return@withContext runCatching {
                val p = ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start()
                val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!done) {
                    p.destroy()
                    return@runCatching RootExecResult(124, "timeout")
                }
                val out = runCatching { p.inputStream.bufferedReader().readText().trim() }.getOrDefault("")
                RootExecResult(p.exitValue(), out)
            }.getOrElse { t ->
                RootExecResult(1, t.javaClass.simpleName)
            }
        }
    }
}

