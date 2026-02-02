package com.anatdx.rei.core.io

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

object UriFiles {
    fun copyToCache(
        context: Context,
        uri: Uri,
        subDir: String,
        fallbackName: String,
    ): File? {
        val dir = File(context.cacheDir, subDir).apply { mkdirs() }
        val name = (queryDisplayName(context, uri)?.takeIf { it.isNotBlank() } ?: fallbackName)
            .sanitizeFileName()
        val out = File(dir, name)
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            out.takeIf { it.exists() && it.length() > 0L }
        }.getOrNull()
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        var c: Cursor? = null
        return try {
            c = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (c != null && c.moveToFirst()) c.getString(0) else null
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { c?.close() }
        }
    }
}

private fun String.sanitizeFileName(): String {
    val trimmed = trim().ifBlank { "file" }
    return trimmed
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .take(120)
}

