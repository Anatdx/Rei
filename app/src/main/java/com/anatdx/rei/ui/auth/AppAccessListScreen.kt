package com.anatdx.rei.ui.auth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.anatdx.rei.core.reid.ReidClient
import com.anatdx.rei.ui.components.ReiCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private data class AppEntry(
    val packageName: String,
    val uid: Int,
    val granted: Boolean,
)

@Composable
fun AppAccessListScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var stats by remember { mutableStateOf(AuthStats()) }
    var search by remember { mutableStateOf("") }
    var lastError by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        loading = true
        val r = withContext(Dispatchers.IO) { queryManagerView(ctx) }
        apps = r.entries
        stats = r.stats
        lastError = r.error
        loading = false
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    val filtered = remember(apps, search) {
        val q = search.trim()
        apps.asSequence()
            .filter { a ->
                if (q.isBlank()) true
                else a.packageName.contains(q, ignoreCase = true)
            }
            .sortedBy { it.packageName.lowercase() }
            .toList()
    }

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
                            else "已加载 ${filtered.size}/${apps.size}（已授权 ${stats.grantedCount} / allowlist=${stats.allowlistCount}）"
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
                }
            }
        }

        items(filtered.size) { idx ->
            val app = filtered[idx]
            ReiCard {
                ListItem(
                    headlineContent = { Text(app.packageName) },
                    supportingContent = { Text(app.packageName) },
                    leadingContent = { AppIcon(pkg = app.packageName) },
                    trailingContent = {
                        IconButton(
                            onClick = {
                                val uid = app.uid.toString()
                                val pkg = app.packageName
                                val to = if (app.granted) "0" else "1"
                                // Optimistic UI update; silent execution.
                                apps = apps.map {
                                    if (it.packageName == pkg && it.uid == app.uid) it.copy(granted = !app.granted) else it
                                }
                                scope.launch {
                                    val r = ReidClient.exec(ctx, listOf("profile", "set-allow", uid, pkg, to), timeoutMs = 8_000L)
                                    if (r.exitCode != 0) {
                                        // Revert on failure (still silent).
                                        apps = apps.map {
                                            if (it.packageName == pkg && it.uid == app.uid) it.copy(granted = app.granted) else it
                                        }
                                        lastError = "set-allow failed: exit=${r.exitCode}"
                                    } else {
                                        lastError = null
                                    }
                                }
                            },
                        ) {
                            Icon(
                                imageVector = if (app.granted) Icons.Outlined.CheckCircle else Icons.Outlined.Cancel,
                                contentDescription = null,
                                tint = if (app.granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    modifier = Modifier.clickable {
                        // Same as clicking the icon (keep behavior consistent).
                        val uid = app.uid.toString()
                        val pkg = app.packageName
                        val to = if (app.granted) "0" else "1"
                        apps = apps.map {
                            if (it.packageName == pkg && it.uid == app.uid) it.copy(granted = !app.granted) else it
                        }
                        scope.launch {
                            val r = ReidClient.exec(ctx, listOf("profile", "set-allow", uid, pkg, to), timeoutMs = 8_000L)
                            if (r.exitCode != 0) {
                                apps = apps.map {
                                    if (it.packageName == pkg && it.uid == app.uid) it.copy(granted = app.granted) else it
                                }
                                lastError = "set-allow failed: exit=${r.exitCode}"
                            } else {
                                lastError = null
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }

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

private suspend fun queryManagerView(ctx: Context): ManagerViewResult {
    val allowUids = queryAllowUids(ctx)
    val pkgs = queryPackagesFromReid(ctx)
    val entries = pkgs.map { (pkg, uid) -> AppEntry(packageName = pkg, uid = uid, granted = allowUids.contains(uid)) }
    val stats = AuthStats(allowlistCount = allowUids.size, grantedCount = entries.count { it.granted })
    val err = if (pkgs.isEmpty()) "后端未返回包列表（需要 Root/ksud 可用）" else null
    return ManagerViewResult(entries = entries, stats = stats, error = err)
}

private suspend fun queryAllowUids(ctx: Context): Set<Int> {
    val r = ReidClient.exec(ctx, listOf("profile", "allowlist"), timeoutMs = 8_000L)
    if (r.exitCode != 0) return emptySet()
    val arr = JSONArray(r.output.trim())
    val out = HashSet<Int>(arr.length())
    for (i in 0 until arr.length()) {
        val uid = arr.optInt(i, -1)
        if (uid >= 0) out.add(uid)
    }
    return out
}

private suspend fun queryPackagesFromReid(ctx: Context): List<Pair<String, Int>> {
    val r = ReidClient.exec(ctx, listOf("profile", "packages"), timeoutMs = 20_000L)
    if (r.exitCode != 0) return emptyList()
    val arr = JSONArray(r.output.trim())
    val out = ArrayList<Pair<String, Int>>(arr.length())
    for (i in 0 until arr.length()) {
        val o: JSONObject = arr.optJSONObject(i) ?: continue
        val pkg = o.optString("package").trim()
        val uid = o.optInt("uid", -1)
        if (pkg.isNotBlank() && uid >= 0) out += (pkg to uid)
    }
    return out
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

