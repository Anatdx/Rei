package com.anatdx.rei.ui.modules

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import com.anatdx.rei.R
import androidx.compose.ui.unit.dp
import com.anatdx.rei.core.io.UriFiles
import com.anatdx.rei.core.reid.ReidClient
import com.anatdx.rei.core.root.RootAccessState
import com.anatdx.rei.ui.components.PullToRefreshBox
import com.anatdx.rei.ui.components.ReiCard
import me.weishu.kernelsu.ui.webui.WebUIActivity
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.foundation.lazy.LazyColumn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.lifecycle.ViewModel
import java.util.zip.ZipFile

@Composable
fun ModulesScreen(rootAccessState: RootAccessState) {
    val viewModel = viewModel<ModulesViewModel>()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cachedModules by viewModel.modules.collectAsState()
    val cachedListError by viewModel.listError.collectAsState()

    var loading by rememberSaveable { mutableStateOf(false) }
    val modules = cachedModules ?: emptyList()
    val listError = cachedListError
    var opError by rememberSaveable { mutableStateOf<String?>(null) }

    var installZip by rememberSaveable { mutableStateOf("") }
    var installZipLabel by rememberSaveable { mutableStateOf("") }
    var pendingInstall by rememberSaveable { mutableStateOf<PendingInstall?>(null) }
    var showInstallConfirm by rememberSaveable { mutableStateOf(false) }
    var installInProgress by rememberSaveable { mutableStateOf(false) }
    var installResult by rememberSaveable { mutableStateOf<Pair<Int, String>?>(null) }

    fun canUseRoot(): Boolean = rootAccessState is RootAccessState.Granted

    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                UriFiles.copyToCache(ctx, uri, subDir = "picked/modules", fallbackName = "module.zip")
            }
            if (f != null) {
                installZip = f.absolutePath
                installZipLabel = f.name
                pendingInstall = withContext(Dispatchers.IO) { scanModuleZip(f.absolutePath) }
                showInstallConfirm = true
            }
        }
    }

    LaunchedEffect(installInProgress) {
        if (!installInProgress || installZip.isBlank()) return@LaunchedEffect
        val zipPath = installZip
        val r = ReidClient.exec(ctx, listOf("module", "install", zipPath), timeoutMs = 180_000L)
        installResult = r.exitCode to r.output
        installInProgress = false
    }

    suspend fun refresh() {
        if (!canUseRoot()) {
            viewModel.setListError("no_root")
            return
        }
        loading = true
        viewModel.setListError(null)
        val r = ReidClient.exec(ctx, listOf("module", "list"), timeoutMs = 30_000L)
        if (r.exitCode != 0) {
            viewModel.setListError("exit=${r.exitCode}\n${r.output}")
            viewModel.setModules(emptyList())
        } else {
            runCatching {
                val list = parseModuleListJson(r.output)
                viewModel.setModules(
                    list.sortedWith(
                        compareBy<ModuleEntry> { m ->
                            when {
                                m.enabled && m.metamodule -> 0
                                m.enabled -> 1
                                else -> 2
                            }
                        }.thenBy { it.name.lowercase() }
                    )
                )
            }.onFailure { t ->
                viewModel.setListError(t.javaClass.simpleName)
                viewModel.setModules(emptyList())
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

    // 有缓存时直接展示；无缓存或获得 root 后自动加载
    LaunchedEffect(canUseRoot(), cachedModules) {
        if (cachedModules != null) loading = false
        else if (canUseRoot()) scope.launch { refresh() }
    }

    PullToRefreshBox(
        refreshing = loading,
        onRefresh = { scope.launch { refresh() } },
    ) {
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
                            headlineContent = { Text(stringResource(R.string.modules_title)) },
                            supportingContent = {
                                Text(
                                    if (canUseRoot()) {
                                        if (loading) stringResource(R.string.modules_loading)
                                        else stringResource(R.string.modules_loaded_count, modules.size)
                                    } else {
                                        stringResource(R.string.modules_no_root)
                                    }
                                )
                            },
                            leadingContent = { Icon(Icons.Outlined.Extension, contentDescription = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                        if (listError != null) {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.modules_read_failed)) },
                                supportingContent = { Text(listError ?: "") },
                                leadingContent = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                        if (opError != null) {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.modules_op_failed)) },
                                supportingContent = { Text(opError ?: "") },
                                leadingContent = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
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
                                        val it = Intent(ctx, WebUIActivity::class.java)
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
                                    contentDescription = if (m.remove) stringResource(R.string.modules_undo_uninstall) else stringResource(R.string.modules_uninstall),
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
                                text = if (m.update) stringResource(R.string.modules_has_update) else "",
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
            onClick = { pickZip.launch("*/*") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
        }

        if (showInstallConfirm && pendingInstall != null) {
            val p = pendingInstall!!
            val nameLine = p.name.takeIf { it.isNotBlank() } ?: p.id.ifBlank { installZipLabel }
            AlertDialog(
                onDismissRequest = {
                    showInstallConfirm = false
                    installZip = ""
                    installZipLabel = ""
                    pendingInstall = null
                },
                title = { Text(stringResource(R.string.modules_install_confirm_title)) },
                text = { Text(stringResource(R.string.modules_install_confirm_message, nameLine)) },
                confirmButton = {
                    Button(
                        onClick = {
                            showInstallConfirm = false
                            if (canUseRoot() && installZip.isNotBlank()) installInProgress = true
                        },
                    ) {
                        Text(stringResource(R.string.modules_install_install_btn))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showInstallConfirm = false
                            installZip = ""
                            installZipLabel = ""
                            pendingInstall = null
                        },
                    ) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }

        if (installInProgress) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.modules_install_running))
                }
            }
        }

        installResult?.let { (code, output) ->
            val success = code == 0
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                ) {
                    Text(
                        text = if (success) stringResource(R.string.modules_install_success)
                        else stringResource(R.string.modules_install_failed, code),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (success) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.modules_install_output),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), MaterialTheme.shapes.medium)
                            .padding(12.dp),
                    ) {
                        Text(
                            text = output.ifBlank { "(no output)" },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            installResult = null
                            installZip = ""
                            installZipLabel = ""
                            pendingInstall = null
                            scope.launch { refresh() }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.modules_install_close))
                    }
                }
            }
        }
        }
    }
}

internal data class ModuleEntry(
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
    val metamodule: Boolean = false,
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
            metamodule = o.boolCompat("metamodule"),
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

/** 模块列表缓存：切回模块页时直接展示，下拉刷新更新 */
internal class ModulesViewModel : ViewModel() {
    private val _modules = MutableStateFlow<List<ModuleEntry>?>(null)
    val modules: StateFlow<List<ModuleEntry>?> = _modules.asStateFlow()
    private val _listError = MutableStateFlow<String?>(null)
    val listError: StateFlow<String?> = _listError.asStateFlow()

    fun setModules(list: List<ModuleEntry>) { _modules.value = list }
    fun setListError(err: String?) { _listError.value = err }
}

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

