package com.anatdx.rei.ui.patches

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.clickable
import com.anatdx.rei.ReiApplication
import com.anatdx.rei.core.auth.ReiKeyHelper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.anatdx.rei.R
import com.anatdx.rei.ui.components.ReiCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed class KpimgResult {
    data class Ok(val output: String) : KpimgResult()
    data class Err(val message: String) : KpimgResult()
}

private data class KpimgInfo(
    val version: String,
    val compileTime: String,
    val config: String,
)

private fun parseKpimgOutput(raw: String): KpimgInfo? {
    val lines = raw.lines()
    var inKpimg = false
    val map = mutableMapOf<String, String>()
    for (line in lines) {
        val trimmed = line.trim()
        when {
            trimmed.startsWith("[") && trimmed.endsWith("]") -> {
                inKpimg = trimmed.equals("[kpimg]", ignoreCase = true)
            }
            inKpimg && trimmed.contains("=") -> {
                val idx = trimmed.indexOf('=')
                val key = trimmed.substring(0, idx).trim()
                val value = trimmed.substring(idx + 1).trim()
                map[key] = value
            }
        }
    }
    val version = map["version"]?.ifBlank { null } ?: return null
    return KpimgInfo(
        version = version,
        compileTime = map["compile_time"]?.ifBlank { null } ?: "-",
        config = map["config"]?.ifBlank { null } ?: "-",
    )
}

private suspend fun runKpimgInfo(context: android.content.Context): KpimgResult =
    kotlinx.coroutines.withContext(Dispatchers.IO) {
        val patchDir = java.io.File(context.cacheDir, "patch").apply {
            deleteRecursively()
            mkdirs()
        }
        val kpimgFile = java.io.File(patchDir, "kpimg")
        val kptoolsFile = java.io.File(patchDir, "kptools")
        val apkPath = context.applicationInfo.sourceDir
        val kpimgOk: Boolean = runCatching {
            context.assets.open("kpimg").use { input ->
                kpimgFile.outputStream().use { output -> input.copyTo(output) }
            }
            true
        }.getOrElse {
            runCatching {
                java.util.zip.ZipFile(apkPath).use { zip ->
                    zip.getEntry("assets/kpimg")?.let { entry ->
                        zip.getInputStream(entry).use { input ->
                            kpimgFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        true
                    } ?: false
                }
            }.getOrElse { false }
        }
        if (!kpimgOk || !kpimgFile.exists()) {
            return@withContext KpimgResult.Err(context.getString(R.string.patches_error_no_kpimg))
        }
        val libDir = java.io.File(context.applicationInfo.nativeLibraryDir)
        val libKptools = java.io.File(libDir, "libkptools.so")
        val kptoolsOk = if (libKptools.exists()) {
            libKptools.copyTo(kptoolsFile, overwrite = true)
            true
        } else {
            runCatching {
                val abi = "arm64-v8a"
                java.util.zip.ZipFile(apkPath).use { zip ->
                    zip.getEntry("lib/$abi/libkptools.so")?.let { entry ->
                        zip.getInputStream(entry).use { input ->
                            kptoolsFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        true
                    } ?: false
                }
            }.getOrElse { false }
        }
        if (!kptoolsOk || !kptoolsFile.exists()) {
            return@withContext KpimgResult.Err(context.getString(R.string.patches_error_no_kptools))
        }
        val patchPath = patchDir.absolutePath
        val cmd = "cd $patchPath && chmod 700 kptools kpimg && LD_LIBRARY_PATH=$patchPath ./kptools -l -k kpimg"
        val result = com.anatdx.rei.core.root.RootShell.exec(cmd, timeoutMs = 15_000L)
        if (result.exitCode != 0) {
            return@withContext KpimgResult.Err("exit=${result.exitCode}\n${result.output}")
        }
        KpimgResult.Ok(result.output.ifBlank { context.getString(R.string.patches_no_output) })
    }

@Composable
fun PatchesScreen(
    rootGranted: Boolean,
) {
    val ctx = LocalContext.current
    var kpimgOutput by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(kpimgOutput) {
        kpimgOutput?.let { scrollState.animateScrollTo(scrollState.maxValue) }
    }

    fun doRefresh() {
        if (!rootGranted) {
            error = ctx.getString(R.string.patches_error_need_root)
        } else {
            loading = true
            error = null
            kpimgOutput = null
            scope.launch {
                val result = withContext(Dispatchers.IO) { runKpimgInfo(ctx) }
                loading = false
                when (result) {
                    is KpimgResult.Ok -> kpimgOutput = result.output
                    is KpimgResult.Err -> error = result.message
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        ReiCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.patches_kp_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.patches_kp_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SuperKeyInputCard()

        ReiCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.patches_view_kpimg),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { doRefresh() },
                    enabled = !loading,
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp).fillMaxWidth(0.3f),
                        )
                    } else {
                        Text(stringResource(R.string.patches_run_kptools))
                    }
                }
                error?.let { msg ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                kpimgOutput?.let { out ->
                    val parsed = parseKpimgOutput(out)
                    if (parsed != null) {
                        Spacer(Modifier.height(12.dp))
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(R.string.patches_kpimg_info),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(4.dp))
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.patches_version)) },
                                supportingContent = { Text(parsed.version) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.patches_compile_time)) },
                                supportingContent = { Text(parsed.compileTime) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.patches_config)) },
                                supportingContent = { Text(parsed.config) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Spacer(Modifier.height(if (parsed != null) 4.dp else 12.dp))
                    Text(
                        text = out,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SuperKeyInputCard() {
    var input by rememberSaveable { mutableStateOf(ReiApplication.superKey) }
    var keyVisible by rememberSaveable { mutableStateOf(false) }
    var validationError by rememberSaveable { mutableStateOf<String?>(null) }

    ReiCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.patches_superkey_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.patches_superkey_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        validationError = when {
                            it.isEmpty() -> null
                            !ReiKeyHelper.isValidSuperKey(it) -> ""
                            else -> null
                        }
                        if (ReiKeyHelper.isValidSuperKey(it)) {
                            ReiApplication.superKey = it
                            ReiKeyHelper.writeSuperKey(it)
                        }
                    },
                    label = { Text(stringResource(R.string.patches_superkey_label)) },
                    placeholder = { Text(stringResource(R.string.patches_superkey_placeholder)) },
                    visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.weight(1f),
                    isError = validationError != null,
                    supportingText = validationError?.let { { Text(stringResource(R.string.patches_superkey_validation)) } },
                )
                Icon(
                    imageVector = if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (keyVisible) stringResource(R.string.settings_visibility_hide) else stringResource(R.string.settings_visibility_show),
                    modifier = Modifier
                        .size(24.dp)
                        .padding(start = 8.dp)
                        .clickable { keyVisible = !keyVisible },
                )
            }
            if (ReiApplication.superKey.isNotEmpty() && ReiKeyHelper.isValidSuperKey(ReiApplication.superKey)) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.patches_auto_saved),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
