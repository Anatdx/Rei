package com.anatdx.rei.ui.logs

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.anatdx.rei.R
import com.anatdx.rei.core.log.ReiLog
import com.anatdx.rei.core.log.ReiLogLevel
import com.anatdx.rei.ui.components.ReiCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LogsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("all") }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }

    suspend fun refresh() {
        lines = withContext(Dispatchers.IO) { ReiLog.read(ctx, maxLines = 3000) }
    }

    LaunchedEffect(Unit) { refresh() }

    val filtered = remember(lines, filter) {
        lines.filter { line ->
            when (filter) {
                "all" -> line.contains("[I]") || line.contains("[W]") || line.contains("[E]")
                "info" -> line.contains("[I]")
                "warning" -> line.contains("[W]")
                "error" -> line.contains("[E]")
                else -> true
            }
        }
    }

    LazyColumn(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            item {
                ReiCard {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.logs_title)) },
                        supportingContent = {
                            Text(
                                if (lines.isEmpty()) stringResource(R.string.logs_empty)
                                else stringResource(R.string.logs_count, lines.size)
                            )
                        },
                        leadingContent = { Icon(Icons.AutoMirrored.Outlined.Article, contentDescription = null) },
                        trailingContent = {
                            Row {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        ReiLog.clear(ctx)
                                        refresh()
                                    }
                                },
                            ) { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) }
                            IconButton(
                                onClick = {
                                    val f = ReiLog.file(ctx)
                                    runCatching {
                                        f.parentFile?.mkdirs()
                                        if (!f.exists()) f.writeText("")
                                    }
                                    ReiLog.append(ctx, ReiLogLevel.I, "ui", "share logs")
                                    val uri = FileProvider.getUriForFile(
                                        ctx,
                                        "${ctx.packageName}.fileprovider",
                                        f,
                                    )
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    ctx.startActivity(Intent.createChooser(intent, ctx.getString(R.string.logs_share)))
                                },
                            ) { Icon(Icons.Outlined.Share, contentDescription = null) }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("all" to R.string.logs_filter_all, "info" to R.string.logs_filter_info, "warning" to R.string.logs_filter_warning, "error" to R.string.logs_filter_error).forEach { (key, res) ->
                        FilterChip(
                            selected = filter == key,
                            onClick = { filter = key },
                            label = { Text(stringResource(res)) },
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.logs_filter_current, stringResource(
                        when (filter) { "all" -> R.string.logs_filter_all; "info" -> R.string.logs_filter_info; "warning" -> R.string.logs_filter_warning; "error" -> R.string.logs_filter_error; else -> R.string.logs_filter_all }
                    )),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(filtered.size) { idx ->
            val line = filtered[filtered.size - 1 - idx]
            ReiCard {
                ListItem(
                    headlineContent = { Text(line.take(48)) },
                    supportingContent = { Text(line) },
                    leadingContent = { Icon(Icons.AutoMirrored.Outlined.Article, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
        }
}

