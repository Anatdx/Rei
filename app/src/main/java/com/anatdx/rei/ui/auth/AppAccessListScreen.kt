package com.anatdx.rei.ui.auth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.anatdx.rei.ApNatives
import com.anatdx.rei.ReiApplication
import com.anatdx.rei.core.reid.ReidClient
import com.anatdx.rei.core.reid.ReidExecResult
import com.anatdx.rei.ui.components.PullToRefreshBox
import com.anatdx.rei.ui.components.ReiCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class AppEntry(
    val packageName: String,
    val uid: Int,
    val granted: Boolean,
    val isSystem: Boolean,
)

@Composable
fun AppAccessListScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var stats by remember { mutableStateOf(AuthStats()) }
    var search by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Set<String>>(emptySet()) }

    suspend fun refresh() {
        loading = true
        val r = withContext(Dispatchers.IO) { queryManagerViewDirect(ctx) }
        apps = r.entries
        stats = r.stats
        lastError = r.error
        loading = false
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    val filtered = remember(apps, search, showSystemApps) {
        val q = search.trim()
        apps.asSequence()
            .filter { a ->
                if (q.isBlank()) true
                else a.packageName.contains(q, ignoreCase = true)
            }
            .filter { a -> showSystemApps || !a.isSystem }
            .sortedBy { it.packageName.lowercase() }
            .toList()
    }

    val visibleGranted = remember(filtered) { filtered.count { it.granted } }

    fun keyOf(a: AppEntry): String = "${a.uid}:${a.packageName}"

    fun updateStatsFrom(list: List<AppEntry>) {
        val granted = list.count { it.granted }
        stats = stats.copy(allowlistCount = granted, grantedCount = granted)
    }

    fun requestToggle(app: AppEntry) {
        val key = keyOf(app)
        if (pending.contains(key)) return
        val uid = app.uid.toString()
        val pkg = app.packageName
        val to = !app.granted

        // Optimistic UI update; silent execution.
        val newApps = apps.map {
            if (it.packageName == pkg && it.uid == app.uid) it.copy(granted = !app.granted) else it
        }
        apps = newApps
        updateStatsFrom(newApps)
        pending = pending + key

        scope.launch {
            pending = pending - key
            // 优先：ksud profile set-allow；其次：apd allowlist grant/revoke；备用：JNI ApNatives
            var result = runCatching {
                ReidClient.exec(ctx, listOf("profile", "set-allow", uid, pkg, if (to) "1" else "0"), timeoutMs = 10_000L)
            }.getOrElse { ReidExecResult(1, it.message.orEmpty()) }
            if (result.exitCode != 0) {
                val apdArgs = if (to) listOf("allowlist", "grant", uid, pkg) else listOf("allowlist", "revoke", uid)
                result = runCatching {
                    ReidClient.exec(ctx, apdArgs, timeoutMs = 10_000L)
                }.getOrElse { ReidExecResult(1, it.message.orEmpty()) }
            }
            if (result.exitCode != 0) {
                val superKey = ReiApplication.superKey
                if (superKey.isNotEmpty() && ApNatives.ready(superKey)) {
                    val rc = if (to) ApNatives.grantSu(superKey, app.uid, 0, null)
                    else ApNatives.revokeSu(superKey, app.uid)
                    if (rc == 0L) {
                        lastError = null
                        refresh()
                        return@launch
                    }
                }
                val reverted = apps.map {
                    if (it.packageName == pkg && it.uid == app.uid) it.copy(granted = app.granted) else it
                }
                apps = reverted
                updateStatsFrom(reverted)
                lastError = result.output.ifBlank { "授权操作失败 (exit=${result.exitCode})" }.take(200)
            } else {
                lastError = null
                refresh()
            }
        }
    }

    PullToRefreshBox(
        refreshing = loading,
        onRefresh = { scope.launch { refresh() } },
    ) {
        LazyColumn(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            item {
                ReiCard {
                ListItem(
                    headlineContent = { Text("应用授权列表") },
                    supportingContent = {
                        Text(
                            if (loading) "正在读取应用列表…"
                            else "可见 ${filtered.size}/${apps.size}（可见已授权 $visibleGranted；allowlist=${stats.allowlistCount}）"
                        )
                    },
                    leadingContent = { Icon(Icons.Outlined.Shield, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                if (!lastError.isNullOrBlank()) {
                    Text(
                        text = lastError.orEmpty().take(200),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        singleLine = true,
                        label = { Text("搜索（名称 / 包名）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "显示系统应用",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = showSystemApps,
                            onCheckedChange = { showSystemApps = it },
                        )
                    }
                }
            }
        }

        items(filtered.size) { idx ->
            val app = filtered[idx]
            val key = keyOf(app)
            ReiCard {
                ListItem(
                    headlineContent = { Text(app.packageName) },
                    supportingContent = {
                        Text(
                            if (app.isSystem) "系统应用 · uid=${app.uid}" else "用户应用 · uid=${app.uid}"
                        )
                    },
                    leadingContent = { AppIcon(pkg = app.packageName) },
                    trailingContent = {
                        Switch(
                            checked = app.granted,
                            enabled = !pending.contains(key),
                            onCheckedChange = { requestToggle(app) },
                        )
                    },
                    modifier = Modifier.clickable {
                        requestToggle(app)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
    }
}

/** 解析 ksud profile allowlist 输出的 JSON 数组 [ uid, ... ] */
private fun parseProfileAllowlistJson(output: String): Set<Int> {
    return output
        .replace("[", " ")
        .replace("]", " ")
        .split(",", "\n")
        .map { it.trim() }
        .mapNotNull { it.toIntOrNull() }
        .toSet()
}

private data class AuthStats(
    val allowlistCount: Int = 0,
    val grantedCount: Int = 0,
)

private data class ManagerViewResult(
    val entries: List<AppEntry>,
    val stats: AuthStats,
    val error: String? = null,
)

private suspend fun queryManagerViewDirect(ctx: Context): ManagerViewResult {
    val pkgs = queryInstalledPackages(ctx)
    if (pkgs.isEmpty()) {
        return ManagerViewResult(entries = emptyList(), stats = AuthStats(), error = "未能读取本机应用列表")
    }

    // 优先：ksud profile allowlist；其次：apd allowlist get；备用：JNI ApNatives.suUids
    var allow = emptySet<Int>()
    var allowError: String? = null
    val profileResult = runCatching {
        ReidClient.exec(ctx, listOf("profile", "allowlist"), timeoutMs = 10_000L)
    }.getOrElse { ReidExecResult(1, it.message.orEmpty()) }
    if (profileResult.exitCode == 0) {
        allow = parseProfileAllowlistJson(profileResult.output)
    } else {
        val apdResult = runCatching {
            ReidClient.exec(ctx, listOf("allowlist", "get"), timeoutMs = 10_000L)
        }.getOrElse { ReidExecResult(1, it.message.orEmpty()) }
        if (apdResult.exitCode == 0) {
            allow = parseProfileAllowlistJson(apdResult.output)
        } else {
            allowError = profileResult.output.ifBlank { apdResult.output }.take(200)
            val superKey = ReiApplication.superKey
            if (superKey.isNotEmpty() && ApNatives.ready(superKey)) {
                allow = ApNatives.suUids(superKey).toSet()
                allowError = null
            }
        }
    }

    val out = pkgs.map { p ->
        AppEntry(
            packageName = p.packageName,
            uid = p.uid,
            isSystem = p.isSystem,
            granted = allow.contains(p.uid),
        )
    }
    val granted = out.count { it.granted }
    val stats = AuthStats(allowlistCount = allow.size, grantedCount = granted)

    val err = allowError
    return ManagerViewResult(entries = out, stats = stats, error = err)
}

private data class InstalledPkg(
    val packageName: String,
    val uid: Int,
    val isSystem: Boolean,
)

private suspend fun queryInstalledPackages(ctx: Context): List<InstalledPkg> {
    return withContext(Dispatchers.IO) {
        runCatching {
            val pm = ctx.packageManager
            val out = ArrayList<InstalledPkg>(256)
            @Suppress("DEPRECATION")
            val pkgs = pm.getInstalledPackages(0)
            for (p in pkgs) {
                val pkg = p.packageName?.trim().orEmpty()
                val ai = p.applicationInfo
                val uid = ai?.uid ?: -1
                val isSystem = ai != null && (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                if (pkg.isNotBlank() && uid >= 0) out += InstalledPkg(packageName = pkg, uid = uid, isSystem = isSystem)
            }
            out
        }.getOrDefault(emptyList())
    }
}

@Composable
private fun AppIcon(pkg: String) {
    val ctx = LocalContext.current
    val iconBmp = produceState<Bitmap?>(initialValue = null, key1 = pkg) {
        value = withContext(Dispatchers.IO) { loadAppIconBitmap(ctx, pkg) }
    }.value

    if (iconBmp != null) {
        androidx.compose.foundation.Image(
            bitmap = iconBmp.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
    } else {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .padding(0.dp),
        )
    }
}

private fun loadAppIconBitmap(ctx: Context, pkg: String): Bitmap? {
    return try {
        val pm = ctx.packageManager
        val drawable = pm.getApplicationIcon(pkg)
        drawableToBitmap(drawable)
    } catch (_: Throwable) {
        null
    }
}

private fun drawableToBitmap(drawable: Drawable): Bitmap? {
    if (drawable is BitmapDrawable) return drawable.bitmap
    val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
    val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bmp
}

