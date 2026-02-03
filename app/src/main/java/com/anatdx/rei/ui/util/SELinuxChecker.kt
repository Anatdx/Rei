package com.anatdx.rei.ui.util

import android.content.Context
import com.topjohnwu.superuser.io.SuFile

fun getSELinuxStatus(context: Context): String = SuFile("/sys/fs/selinux/enforce").run {
    when {
        !exists() -> "Disabled"
        !isFile -> "Unknown"
        !canRead() -> "Enforcing"
        else -> when (runCatching { newInputStream() }.getOrNull()?.bufferedReader()
            ?.use { it.runCatching { readLine() }.getOrNull()?.trim()?.toIntOrNull() }) {
            1 -> "Enforcing"
            0 -> "Permissive"
            else -> "Unknown"
        }
    }
}
