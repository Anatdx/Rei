package me.weishu.kernelsu.ui.util

import com.topjohnwu.superuser.Shell

fun createRootShell(mountMaster: Boolean): Shell {
    // mountMaster kept for upstream API shape
    return Shell.getShell()
}

inline fun <T> withNewRootShell(mountMaster: Boolean, block: Shell.() -> T): T {
    val shell = createRootShell(mountMaster)
    return shell.block()
}
