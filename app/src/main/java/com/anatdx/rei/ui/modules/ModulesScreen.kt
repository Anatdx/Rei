package com.anatdx.rei.ui.modules

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.anatdx.rei.core.io.UriFiles
import com.anatdx.rei.core.reid.ReidClient
import com.anatdx.rei.core.root.RootAccessState
import com.anatdx.rei.ui.components.ReiCard
import me.weishu.kernelsu.ui.webui.WebUIActivity
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.foundation.lazy.LazyColumn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipFile

@Composable
fun ModulesScreen(rootAccessState: RootAccessState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by rememberSaveable { mutableStateOf(false) }
    var modules by remember { mutableStateOf<List<ModuleEntry>>(emptyList()) }
    var listError by rememberSaveable { mutableStateOf<String?>(null) }
    var opError by rememberSaveable { mutableStateOf<String?>(null) }

    var installZip by rememberSaveable { mutableStateOf("") } // absolute path in app cache
    var installZipLabel by rememberSaveable { mutableStateOf("") }
    var pendingInstall by rememberSaveable { mutableStateOf<PendingInstall?>(null) }

    fun canUseRoot(): Boolean = rootAccessState is RootAccessState.Granted

    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                UriFiles.copyToCache(ctx, uri, subDir = "picked/modules", fallbackName = "module.zip")
            }
            if (f != null) {
                installZip = f.absolutePath
                installZipLabel = f.name
                pendingInstall = withContext(Dispatchers.IO) { scanModuleZip(f.absolutePath) }
            }
        }
    }

    suspend fun refresh() {
        if (!canUseRoot()) {
            listError = "no_root"
            return
        }
        loading = true
        listError = null
        val r = ReidClient.exec(ctx, listOf("module", "list"), timeoutMs = 30_000L)
        if (r.exitCode != 0) {
            listError = "exit=${r.exitCode}\n${r.output}"
            modules = emptyList()
        } else {
            runCatching {
                modules = parseModuleListJson(r.output)
            }.onFailure { t ->
                listError = t.javaClass.simpleName
                modules = emptyList()
            }
        }
        loading = false
    }

    fun runOp(args: List<String>, timeoutMs: Long = 120_000L, refreshAfter: Boolean = true) {
        scope.launch {
            if (!canUseRoot()) {
                opError = "no_root"
                return@launch
            }
            loading = true
            val r = ReidClient.exec(ctx, args, timeoutMs = timeoutMs)
            opError = if (r.exitCode == 0) null else "exit=${r.exitCode}"
            loading = false
            if (refreshAfter) refresh()
        }
    }

    LaunchedEffect(canUseRoot()) {
        if (canUseRoot()) refresh()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            item {
                ReiCard {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        ListItem(
                            headlineContent = { Text("模块") },
                            supportingContent = {
                                Text(
                                    if (canUseRoot()) {
                                        if (loading) "正在读取模块列表…" else "已加载 ${modules.size} 个模块"
                                    } else {
                                        "未获取 Root（模块管理需要 Root）"
                                    }
                                )
                            },
                            leadingContent = { Icon(Icons.Outlined.Extension, contentDescription = null) },
                            trailingContent = {
                                IconButton(
                                    enabled = canUseRoot() && !loading,
                                    onClick = { scope.launch { refresh() } },
                                ) { Icon(Icons.Outlined.Refresh, contentDescription = null) }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                        if (listError != null) {
                            ListItem(
                                headlineContent = { Text("读取失败") },
                                supportingContent = { Text(listError ?: "") },
                                leadingContent = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                        if (opError != null) {
                            ListItem(
                                headlineContent = { Text("操作失败") },
                                supportingContent = { Text(opError ?: "") },
                                leadingContent = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                    }
                }
            }

            if (installZip.isNotBlank()) {
                item {
                    ReiCard {
                        ListItem(
                            headlineContent = { Text("待安装模块") },
                            supportingContent = {
                                val p = pendingInstall
                                val line1 = p?.name?.takeIf { it.isNotBlank() } ?: installZipLabel.ifBlank { installZip }
                                val line2 = buildString {
                                    if (p?.id?.isNotBlank() == true) append(p.id)
                                    if (p?.version?.isNotBlank() == true) append(" · ").append(p.version)
                                    if (p?.author?.isNotBlank() == true) append(" · ").append(p.author)
                                }.ifBlank { installZipLabel.ifBlank { installZip } }
                                Text("$line1\n$line2")
                            },
                            leadingContent = { Icon(Icons.Outlined.Extension, contentDescription = null) },
                            trailingContent = {
                                IconButton(
                                    enabled = canUseRoot() && !loading,
                                    onClick = {
                                        installZip = ""
                                        installZipLabel = ""
                                        pendingInstall = null
                                    },
                                ) { Icon(Icons.Outlined.Close, contentDescription = null) }
                            },
                            modifier = Modifier.clickable(enabled = canUseRoot() && !loading) {
                                val zip = installZip.trim()
                                scope.launch {
                                    if (!canUseRoot() || loading) return@launch
                                    loading = true
                                    val r = ReidClient.exec(ctx, listOf("module", "install", zip), timeoutMs = 180_000L)
                                    opError = if (r.exitCode == 0) null else "exit=${r.exitCode}"
                                    loading = false
                                    if (r.exitCode == 0) {
                                        installZip = ""
                                        installZipLabel = ""
                                        pendingInstall = null
                                        refresh()
                                    }
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }

            items(modules.size) { idx ->
                val m = modules[idx]
                ReiCard {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        ListItem(
                            headlineContent = {
                                Text(
                                    m.name,
                                    textDecoration = if (m.remove) TextDecoration.LineThrough else null,
                                )
                            },
                            supportingContent = {
                                val ver = buildString {
                                    if (m.version.isNotBlank()) append(m.version) else append("unknown")
                                    if (m.author.isNotBlank()) append(" · ").append(m.author)
                                    append("\n").append(m.id)
                                }
                                Text(
                                    ver,
                                    textDecoration = if (m.remove) TextDecoration.LineThrough else null,
                                )
                            },
                            leadingContent = { Icon(Icons.Outlined.Extension, contentDescription = null) },
                            trailingContent = {
                                Switch(
                                    checked = m.enabled,
                                    enabled = canUseRoot() && !loading && !m.remove,
                                    onCheckedChange = { on ->
                                        runOp(listOf("module", if (on) "enable" else "disable", m.id))
                                    },
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                        if (m.description.isNotBlank()) {
                            Text(
                                text = m.description,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (m.web) {
                                IconButton(
                                    enabled = canUseRoot() && !loading && m.enabled && !m.update && !m.remove,
                                    onClick = {
                                        val it = android.content.Intent(ctx, WebUIActivity::class.java)
                                            .putExtra("id", m.id)
                                        ctx.startActivity(it)
                                    },
                                ) {
                                    Icon(Icons.Outlined.Language, contentDescription = "WebUI")
                                }
                            }

                            IconButton(
                                enabled = canUseRoot() && !loading,
                                onClick = {
                                    runOp(listOf("module", if (m.remove) "undo-uninstall" else "uninstall", m.id))
                                },
                            ) {
                                Icon(
                                    imageVector = if (m.remove) Icons.Outlined.Replay else Icons.Outlined.DeleteOutline,
                                    contentDescription = if (m.remove) "取消卸载" else "卸载",
                                )
                            }

                            if (m.action) {
                                IconButton(
                                    enabled = canUseRoot() && !loading && !m.remove,
                                    onClick = { runOp(listOf("module", "action", m.id), timeoutMs = 180_000L, refreshAfter = false) },
                                ) { Icon(Icons.Outlined.PlayArrow, contentDescription = "Action") }
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = if (m.update) "有更新" else "",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }

        FloatingActionButton(
            onClick = { pickZip.launch(arrayOf("*/*")) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
        }
    }
}

private data class ModuleEntry(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val enabled: Boolean,
    val update: Boolean,
    val remove: Boolean,
    val action: Boolean,
    val web: Boolean,
)

private fun parseModuleListJson(raw: String): List<ModuleEntry> {
    val arr = JSONArray(raw.trim())
    val out = ArrayList<ModuleEntry>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        out += ModuleEntry(
            id = o.optString("id"),
            name = o.optString("name").ifBlank { o.optString("id") },
            version = o.optString("version"),
            author = o.optString("author"),
            description = o.optString("description"),
            enabled = o.boolCompat("enabled"),
            update = o.boolCompat("update"),
            remove = o.boolCompat("remove"),
            action = o.boolCompat("action"),
            web = o.boolCompat("web"),
        )
    }
    return out
}

private fun JSONObject.boolCompat(key: String): Boolean {
    val v = opt(key)
    return when (v) {
        is Boolean -> v
        is String -> v.equals("true", ignoreCase = true) || v == "1"
        is Number -> v.toInt() != 0
        else -> optString(key).equals("true", ignoreCase = true) || optString(key) == "1"
    }
}

private data class PendingInstall(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
)

private fun scanModuleZip(path: String): PendingInstall? {
    return runCatching {
        ZipFile(path).use { z ->
            val entry = z.getEntry("module.prop") ?: run {
                val e = z.entries()
                var found: java.util.zip.ZipEntry? = null
                while (e.hasMoreElements()) {
                    val it = e.nextElement()
                    if (it.name == "module.prop" || it.name.endsWith("/module.prop")) {
                        found = it
                        break
                    }
                }
                found
            } ?: return null
            val text = z.getInputStream(entry).bufferedReader().use { it.readText() }
            val props = HashMap<String, String>()
            text.lineSequence().forEach { line ->
                val t = line.trim()
                if (t.isBlank() || t.startsWith("#")) return@forEach
                val idx = t.indexOf('=')
                if (idx <= 0) return@forEach
                val k = t.substring(0, idx).trim()
                val v = t.substring(idx + 1).trim()
                props[k] = v
            }
            PendingInstall(
                id = props["id"].orEmpty(),
                name = props["name"].orEmpty(),
                version = props["version"].orEmpty().ifBlank { props["versionCode"].orEmpty() },
                author = props["author"].orEmpty(),
            )
        }
    }.getOrNull()
}

