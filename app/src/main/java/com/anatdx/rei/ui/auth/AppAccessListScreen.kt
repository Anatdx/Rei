package com.anatdx.rei.ui.auth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anatdx.rei.R
import com.anatdx.rei.ReiApplication
import com.anatdx.rei.core.reid.ReidClient
import com.anatdx.yukisu.Natives as YukiSuNatives
import com.anatdx.rei.core.reid.ReidExecResult
import com.anatdx.rei.ui.components.PullToRefreshBox
import com.anatdx.rei.ui.components.ReiCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.lifecycle.ViewModel

private data class AppEntry(
    val packageName: String,
    val appName: String,
    val uid: Int,
    val granted: Boolean,
    val excluded: Boolean = false,
    val isSystem: Boolean,
)

@Composable
fun AppAccessListScreen() {
    val viewModel = viewModel<AppAccessViewModel>()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cachedEntries by viewModel.entries.collectAsState()
    val cachedStats by viewModel.stats.collectAsState()
    val cachedError by viewModel.error.collectAsState()
    var apps by remember(cachedEntries) { mutableStateOf(cachedEntries ?: emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var stats by remember(cachedStats) { mutableStateOf(cachedStats ?: AuthStats()) }
    var search by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var lastError by remember(cachedError) { mutableStateOf(cachedError) }
    var pending by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expandedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }

    suspend fun refresh() {
        loading = true
        val r = withContext(Dispatchers.IO) { queryManagerViewDirect(ctx) }
        viewModel.setEntries(r.entries)
        viewModel.setStats(r.stats)
        viewModel.setError(r.error)
        apps = r.entries
        stats = r.stats
        lastError = r.error
        loading = false
    }

    // 有缓存时直接展示；无缓存时开屏自动加载
    LaunchedEffect(cachedEntries) {
        if (cachedEntries != null) loading = false
        else scope.launch { refresh() }
    }

    val filtered = remember(apps, search, showSystemApps) {
        val q = search.trim()
        apps.asSequence()
            .filter { a ->
                if (q.isBlank()) true
                else a.packageName.contains(q, ignoreCase = true) || a.appName.contains(q, ignoreCase = true)
            }
            .filter { a -> showSystemApps || !a.isSystem }
            .sortedWith(compareBy({ !it.granted }, { it.appName.lowercase() }))
            .toList()
    }

    val visibleGranted = remember(filtered) { filtered.count { it.granted } }

    fun keyOf(a: AppEntry): String = "${a.uid}:${a.packageName}"

    fun updateStatsFrom(list: List<AppEntry>) {
        val granted = list.count { it.granted }
        val excluded = list.count { it.excluded }
        stats = stats.copy(allowlistCount = granted, grantedCount = granted, denylistCount = excluded)
    }

    fun requestToggleExclude(app: AppEntry, toExclude: Boolean) {
        val key = "exclude:${keyOf(app)}"
        if (pending.contains(key)) return
        val uidStr = app.uid.toString()
        val pkg = app.packageName
        
        // Exclude => no root. Un-exclude => only change excluded, do not touch granted.
        val newGranted = if (toExclude) false else app.granted
        val newExcluded = toExclude

        val newApps = apps.map {
            if (it.packageName == pkg && it.uid == app.uid)
                it.copy(excluded = newExcluded, granted = newGranted)
            else it
        }
        apps = newApps
        updateStatsFrom(newApps)
        pending = pending + key
        scope.launch {
            val jniOk = runCatching {
                YukiSuNatives.setAppProfile(
                    YukiSuNatives.Profile(
                        name = pkg,
                        currentUid = app.uid,
                        uid = app.uid,
                        allowSu = newGranted,
                        nonRootUseDefault = false,
                        umountModules = newExcluded
                    )
                )
            }.getOrElse { false }
            
            // reid exec fallback for exclude is risky if we don't have a specific command for umount/denylist.
            // set-allow only touches allowlist. If we are excluding (toExclude=true), set-allow 0 is partially correct (revokes root).
            // But if we are un-excluding, set-allow 1 is WRONG (grants root).
            // So we only fallback if toExclude=true (revoke root).
            val result = if (jniOk) ReidExecResult(0, "")
            else if (toExclude) runCatching {
                ReidClient.exec(ctx, listOf("profile", "set-allow", uidStr, pkg, "0"), timeoutMs = 10_000L)
            }.getOrElse { ReidExecResult(1, it.message.orEmpty()) }
            else ReidExecResult(1, "JNI failed and no fallback for un-exclude")

            pending = pending - key
            if (result.exitCode != 0) {
                val reverted = apps.map {
                    if (it.packageName == pkg && it.uid == app.uid) it.copy(excluded = app.excluded, granted = app.granted) else it
                }
                apps = reverted
                updateStatsFrom(reverted)
                lastError = when {
                    result.output.contains("errno=1") -> ctx.getString(R.string.app_access_exclude_errno_daemon)
                    else -> result.output.ifBlank { ctx.getString(R.string.app_access_auth_failed, result.exitCode) }.take(200)
                }
            } else {
                lastError = null
                refresh()
            }
        }
    }

    fun requestToggle(app: AppEntry) {
        val key = keyOf(app)
        if (pending.contains(key)) return
        val uidStr = app.uid.toString()
        val pkg = app.packageName
        val to = !app.granted

        // Root => not excluded. Revoke root => only change granted, do not touch excluded.
        val newGranted = to
        val newExcluded = if (to) false else app.excluded

        val newApps = apps.map {
            if (it.packageName == pkg && it.uid == app.uid)
                it.copy(granted = newGranted, excluded = newExcluded)
            else it
        }
        apps = newApps
        updateStatsFrom(newApps)
        pending = pending + key

        scope.launch {
            pending = pending - key
            val jniOk = runCatching {
                YukiSuNatives.setAppProfile(
                    YukiSuNatives.Profile(
                        name = pkg,
                        currentUid = app.uid,
                        uid = app.uid,
                        allowSu = newGranted,
                        nonRootUseDefault = false,
                        umountModules = newExcluded
                    )
                )
            }.getOrElse { false }
            
            val result = if (jniOk) ReidExecResult(0, "")
            else runCatching {
                // Fallback: set-allow handles allowSu. It does NOT handle umountModules.
                // If we are granting (to=true), set-allow 1 is fine (and hopefully backend clears denylist?).
                // If we are revoking (to=false), set-allow 0 is fine.
                // But this fallback might de-sync umountModules state if backend doesn't handle linkage.
                ReidClient.exec(ctx, listOf("profile", "set-allow", uidStr, pkg, if (to) "1" else "0"), timeoutMs = 10_000L)
            }.getOrElse { ReidExecResult(1, it.message.orEmpty()) }
            
            if (result.exitCode != 0) {
                val reverted = apps.map {
                    if (it.packageName == pkg && it.uid == app.uid) it.copy(granted = app.granted, excluded = app.excluded) else it
                }
                apps = reverted
                updateStatsFrom(reverted)
                lastError = result.output.ifBlank { ctx.getString(R.string.app_access_auth_failed, result.exitCode) }.take(200)
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
                    headlineContent = { Text(stringResource(R.string.app_access_list_title)) },
                    supportingContent = {
                        Column {
                            Text(
                                text = stringResource(R.string.app_access_murasaki_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (loading) stringResource(R.string.app_access_loading)
                                else stringResource(R.string.app_access_visible_stats, filtered.size, apps.size, visibleGranted, stats.allowlistCount, stats.denylistCount)
                            )
                        }
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
                        label = { Text(stringResource(R.string.app_access_search_hint)) },
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
                            text = stringResource(R.string.app_access_show_system),
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
            val excludeKey = "exclude:$key"
            ReiCard {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text(app.appName) },
                        supportingContent = {
                            Column {
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    if (app.isSystem) stringResource(R.string.app_access_system_app, app.uid) else stringResource(R.string.app_access_user_app, app.uid)
                                )
                                if (app.excluded) {
                                    Text(
                                        stringResource(R.string.app_access_excluded_label),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
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
                            if (app.granted) requestToggle(app)
                            else expandedKeys = if (key in expandedKeys) expandedKeys - key else expandedKeys + key
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    AnimatedVisibility(
                        visible = !app.granted && expandedKeys.contains(key),
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.app_access_exclude_mod_switch),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = app.excluded,
                                enabled = !pending.contains(excludeKey),
                                onCheckedChange = { requestToggleExclude(app, it) },
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
    }
}

/** Parse ksud profile allowlist JSON array [ uid, ... ]. */
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
    val denylistCount: Int = 0,
)

/** 授权列表缓存：切回授权页时直接展示，下拉刷新更新 */
private class AppAccessViewModel : ViewModel() {
    private val _entries = MutableStateFlow<List<AppEntry>?>(null)
    val entries: StateFlow<List<AppEntry>?> = _entries.asStateFlow()
    private val _stats = MutableStateFlow<AuthStats?>(null)
    val stats: StateFlow<AuthStats?> = _stats.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun setEntries(list: List<AppEntry>) { _entries.value = list }
    fun setStats(s: AuthStats) { _stats.value = s }
    fun setError(e: String?) { _error.value = e }
}

private data class ManagerViewResult(
    val entries: List<AppEntry>,
    val stats: AuthStats,
    val error: String? = null,
)

private suspend fun queryManagerViewDirect(ctx: Context): ManagerViewResult {
    val pkgs = queryInstalledPackages(ctx)
    if (pkgs.isEmpty()) {
        return ManagerViewResult(entries = emptyList(), stats = AuthStats(), error = ctx.getString(R.string.app_access_error_packages))
    }

    // Prefer YukiSU kernelsu JNI (libkernelsu from YukiSU); fallback to reid exec
    var allow = emptySet<Int>()
    var deny = emptySet<Int>()
    var useJni = false
    var allowError: String? = null
    runCatching {
        if (YukiSuNatives.isManager) {
            val arr = YukiSuNatives.allowList
            if (arr.isNotEmpty()) allow = arr.toSet()
            useJni = true
        }
    }
    
    if (!useJni) {
        val profileResult = runCatching {
            ReidClient.exec(ctx, listOf("profile", "allowlist"), timeoutMs = 10_000L)
        }.getOrElse { ReidExecResult(1, it.message.orEmpty()) }
        if (profileResult.exitCode == 0) {
            allow = parseProfileAllowlistJson(profileResult.output)
        } else {
            allowError = profileResult.output.take(200)
        }
    
        // Deny list (exclude list): KernelSU profile denylist (only needed if JNI unused)
        val denylistResult = runCatching {
            ReidClient.exec(ctx, listOf("profile", "denylist"), timeoutMs = 10_000L)
        }.getOrElse { ReidExecResult(1, it.message.orEmpty()) }
        if (denylistResult.exitCode == 0) {
            deny = parseProfileAllowlistJson(denylistResult.output)
        }
    }

    // Root cannot be excluded: if in allowlist, never show as excluded.
    val out = pkgs.map { p ->
        val granted = allow.contains(p.uid)
        val isExcluded = if (granted) false else {
            if (useJni) runCatching { YukiSuNatives.uidShouldUmount(p.uid) }.getOrDefault(false)
            else deny.contains(p.uid)
        }
        AppEntry(
            packageName = p.packageName,
            appName = p.appName,
            uid = p.uid,
            isSystem = p.isSystem,
            granted = granted,
            excluded = isExcluded,
        )
    }
    val granted = out.count { it.granted }
    val excludedCount = out.count { it.excluded }
    val stats = AuthStats(allowlistCount = allow.size, grantedCount = granted, denylistCount = excludedCount)

    val err = allowError
    return ManagerViewResult(entries = out, stats = stats, error = err)
}

private data class InstalledPkg(
    val packageName: String,
    val appName: String,
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
                val appName = ai?.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { pkg }
                if (pkg.isNotBlank() && uid >= 0) out += InstalledPkg(packageName = pkg, appName = appName, uid = uid, isSystem = isSystem)
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

