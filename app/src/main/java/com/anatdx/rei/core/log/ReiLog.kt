package com.anatdx.rei.core.log

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ReiLogLevel { I, W, E }

object ReiLog {
    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun append(context: Context, level: ReiLogLevel, tag: String, message: String) {
        runCatching {
            val dir = File(context.filesDir, "logs").apply { mkdirs() }
            val f = File(dir, "rei.log")
            val ts = tsFmt.format(Date())
            val line = buildString {
                append(ts)
                append(" [").append(level.name).append("] ")
                append(tag)
                append(": ")
                append(message.replace("\r", "").trimEnd())
                append('\n')
            }
            f.appendText(line)
        }
    }

    fun read(context: Context, maxLines: Int = 2000): List<String> {
        val f = File(File(context.filesDir, "logs"), "rei.log")
        if (!f.exists()) return emptyList()
        val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
        return if (lines.size <= maxLines) lines else lines.takeLast(maxLines)
    }

    fun clear(context: Context) {
        runCatching {
            val f = File(File(context.filesDir, "logs"), "rei.log")
            if (f.exists()) f.writeText("")
        }
    }

    fun file(context: Context): File = File(File(context.filesDir, "logs"), "rei.log")
}

