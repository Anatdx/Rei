package com.anatdx.rei.ui.patches

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.system.Os
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.anatdx.rei.R
import com.anatdx.rei.ReiApplication
import com.anatdx.rei.core.auth.ReiKeyHelper
import com.anatdx.rei.core.io.UriFiles
import com.anatdx.rei.core.root.RootShell
import com.anatdx.rei.ui.components.ReiCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class PatchMode {
    SELECT_FILE,
    DIRECT_INSTALL,
    INSTALL_INACTIVE
}

private sealed class PatchResult {
    data class Success(val message: String, val outputFile: File?) : PatchResult()
    data class Error(val message: String) : PatchResult()
}

@Composable
fun PatchesScreen(
    rootGranted: Boolean,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var patchMode by rememberSaveable { mutableStateOf(PatchMode.SELECT_FILE) }
    var selectedUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    
    var patching by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf("") }
    var resultFile by remember { mutableStateOf<File?>(null) }

    // Check if A/B device
    val isAbDevice = remember { 
        val slot = try {
            val p = Runtime.getRuntime().exec("getprop ro.boot.slot_suffix")
            p.inputStream.bufferedReader().readText().trim()
        } catch (e: Exception) { "" }
        slot.isNotEmpty()
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            selectedUri = uri
            selectedName = UriFiles.getFileName(ctx, uri) ?: "boot.img"
            logs = ""
            resultFile = null
        }
    }

    val saveImage = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null && resultFile != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openOutputStream(uri)?.use { output ->
                        resultFile!!.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                    val msg = ctx.getString(R.string.patches_saved, uri.path)
                    withContext(Dispatchers.Main) {
                        logs += "\n$msg"
                    }
                } catch (e: Exception) {
                    val msg = ctx.getString(R.string.patches_error_save, e.message)
                    withContext(Dispatchers.Main) {
                        logs += "\n$msg"
                    }
                }
            }
        }
    }

    LaunchedEffect(logs) {
        if (logs.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    fun runPatch() {
        if (patchMode == PatchMode.SELECT_FILE && selectedUri == null) return
        patching = true
        logs = ctx.getString(R.string.patches_starting) + "\n"
        resultFile = null
        
        scope.launch(Dispatchers.IO) {
            val res = patchBootImage(
                context = ctx,
                mode = patchMode,
                sourceUri = selectedUri,
                superKey = ReiApplication.superKey,
                hasRoot = rootGranted,
                onLog = { msg -> withContext(Dispatchers.Main) { logs += "$msg\n" } }
            )
            
            withContext(Dispatchers.Main) {
                patching = false
                when (res) {
                    is PatchResult.Success -> {
                        logs += "\n${res.message}"
                        resultFile = res.outputFile
                    }
                    is PatchResult.Error -> {
                        logs += "\nError: ${res.message}"
                    }
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
            ListItem(
                headlineContent = { Text(stringResource(R.string.patches_kp_title)) },
                supportingContent = { Text(stringResource(R.string.patches_kp_desc)) },
                leadingContent = { Icon(Icons.Outlined.Build, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }

        SuperKeyInputCard()

        ReiCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.patches_select_mode),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(8.dp))

                // Mode Selection
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { if (!patching) patchMode = PatchMode.SELECT_FILE }
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = patchMode == PatchMode.SELECT_FILE,
                            onClick = { if (!patching) patchMode = PatchMode.SELECT_FILE }
                        )
                        Text(stringResource(R.string.patches_select_file))
                    }

                    if (rootGranted) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { if (!patching) patchMode = PatchMode.DIRECT_INSTALL }
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = patchMode == PatchMode.DIRECT_INSTALL,
                                onClick = { if (!patching) patchMode = PatchMode.DIRECT_INSTALL }
                            )
                            Text(stringResource(R.string.patches_direct_install))
                        }

                        if (isAbDevice) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { if (!patching) patchMode = PatchMode.INSTALL_INACTIVE }
                            ) {
                                androidx.compose.material3.RadioButton(
                                    selected = patchMode == PatchMode.INSTALL_INACTIVE,
                                    onClick = { if (!patching) patchMode = PatchMode.INSTALL_INACTIVE }
                                )
                                Text(stringResource(R.string.patches_install_inactive))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                if (patchMode == PatchMode.SELECT_FILE) {
                    OutlinedTextField(
                        value = selectedName ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.patches_boot_image)) },
                        trailingIcon = {
                            IconButton(onClick = { pickImage.launch("application/octet-stream") }) {
                                Icon(Icons.Outlined.FolderOpen, contentDescription = "Select")
                            }
                        },
                        modifier = Modifier.fillMaxWidth().clickable { pickImage.launch("application/octet-stream") },
                        enabled = !patching
                    )
                    Spacer(Modifier.height(16.dp))
                }
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { runPatch() },
                        enabled = !patching && (patchMode != PatchMode.SELECT_FILE || selectedUri != null) && ReiApplication.superKey.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (patching) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            val textId = when (patchMode) {
                                PatchMode.SELECT_FILE -> R.string.patches_patch_btn
                                else -> R.string.patches_patch_flash_btn
                            }
                            Text(stringResource(textId))
                        }
                    }
                    
                    if (resultFile != null && patchMode == PatchMode.SELECT_FILE) {
                        IconButton(onClick = { 
                            saveImage.launch("patched_boot.img")
                        }) {
                            Icon(Icons.Outlined.Save, contentDescription = stringResource(R.string.patches_save))
                        }
                    }
                }
            }
        }

        if (logs.isNotEmpty()) {
            ReiCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.patches_logs),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = logs,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        Spacer(Modifier.height(16.dp))
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
        }
    }
}

private suspend fun patchBootImage(
    context: Context,
    mode: PatchMode,
    sourceUri: Uri?,
    superKey: String,
    hasRoot: Boolean,
    onLog: suspend (String) -> Unit
): PatchResult {
    val patchDir = File(context.cacheDir, "patch").apply {
        deleteRecursively()
        mkdirs()
    }
    
    onLog("Preparing environment...")
    
    // 1. Extract scripts
    listOf("boot_patch.sh", "boot_extract.sh", "util_functions.sh").forEach { name ->
        try {
            context.assets.open(name).use { input ->
                File(patchDir, name).outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            return PatchResult.Error("Failed to extract $name: ${e.message}")
        }
    }
    
    // 2. Prepare tools (kptools, magiskboot, kpimg)
    val kpimgFile = File(patchDir, "kpimg")
    try {
        context.assets.open("kpimg").use { input ->
            kpimgFile.outputStream().use { output -> input.copyTo(output) }
        }
    } catch (e: Exception) {
        return PatchResult.Error("Failed to extract kpimg: ${e.message}")
    }
    
    val libDir = File(context.applicationInfo.nativeLibraryDir)
    val tools = mapOf(
        "libkptools.so" to "kptools",
        "libmagiskboot.so" to "magiskboot"
    )

    fun symlinkOrCopy(libFile: File, toolFile: File): Boolean {
        if (!libFile.exists()) return false
        return try {
            android.system.Os.symlink(libFile.absolutePath, toolFile.absolutePath)
            true
        } catch (e: Exception) {
            try {
                libFile.copyTo(toolFile, overwrite = true)
                toolFile.setExecutable(true)
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    for ((libName, toolName) in tools) {
        val libFile = File(libDir, libName)
        val toolFile = File(patchDir, toolName)
        var prepared = symlinkOrCopy(libFile, toolFile)
        if (!prepared) {
            try {
                val apkPath = context.applicationInfo.sourceDir
                java.util.zip.ZipFile(apkPath).use { zip ->
                    val entry = zip.getEntry("lib/arm64-v8a/$libName")
                    if (entry != null) {
                        zip.getInputStream(entry).use { input ->
                            toolFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        toolFile.setExecutable(true)
                        prepared = true
                    }
                }
            } catch (_: Exception) { }
        }
        if (!prepared) return PatchResult.Error("Missing dependency: $libName. App must be built with these libs.")
    }

    val busyboxFile = File(patchDir, "busybox")
    val libBusybox = File(libDir, "libbusybox.so")
    if (libBusybox.exists()) {
        if (!symlinkOrCopy(libBusybox, busyboxFile)) {
            libBusybox.copyTo(busyboxFile, overwrite = true)
            busyboxFile.setExecutable(true)
        }
    } else {
        onLog("Warning: libbusybox.so not found. Trying system busybox...")
    }

    // Env vars
    val env = mutableMapOf<String, String>()
    env["ASH_STANDALONE"] = "1"
    env["LD_LIBRARY_PATH"] = patchDir.absolutePath

    // 3. Prepare boot.img
    // If SELECT_FILE: copy from Uri
    // If DIRECT/INACTIVE: run boot_extract.sh
    val bootImgPath: String
    if (mode == PatchMode.SELECT_FILE) {
        if (sourceUri == null) return PatchResult.Error("No file selected")
        val bootImgFile = File(patchDir, "boot.img")
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                bootImgFile.outputStream().use { output -> input.copyTo(output) }
            }
            bootImgPath = "boot.img" // Relative to patchDir when running script
        } catch (e: Exception) {
            return PatchResult.Error("Failed to copy boot image: ${e.message}")
        }
    } else {
        if (!hasRoot) return PatchResult.Error("Root required for direct install")
        onLog("Extracting boot image path...")
        val installNext = if (mode == PatchMode.INSTALL_INACTIVE) "true" else "false"
        
        val extractCmd = if (busyboxFile.exists()) {
            "cd ${patchDir.absolutePath} && ./busybox sh boot_extract.sh $installNext"
        } else {
            "cd ${patchDir.absolutePath} && sh boot_extract.sh $installNext"
        }
        
        val res = RootShell.exec(extractCmd, timeoutMs = 15_000)
        if (res.exitCode != 0) {
            onLog(res.output)
            return PatchResult.Error("Failed to find boot image: exit ${res.exitCode}")
        }
        
        // Parse BOOTIMAGE=... from output
        val output = res.output
        val match = Regex("BOOTIMAGE=(.+)").find(output)
        if (match != null) {
            bootImgPath = match.groupValues[1].trim()
            onLog("Found boot image: $bootImgPath")
        } else {
            onLog(output)
            return PatchResult.Error("Could not determine boot image path from script output")
        }
    }
    
    onLog("Tools prepared. Starting patch script...")
    
    // 4. Run script
    val flashToDevice = (mode != PatchMode.SELECT_FILE)
    val cmd = if (busyboxFile.exists()) {
        "cd ${patchDir.absolutePath} && ./busybox sh boot_patch.sh \"$superKey\" \"$bootImgPath\" $flashToDevice"
    } else {
        "cd ${patchDir.absolutePath} && sh boot_patch.sh \"$superKey\" \"$bootImgPath\" $flashToDevice"
    }
    
    val result = if (hasRoot) {
        RootShell.exec(cmd, timeoutMs = 120_000)
    } else {
        try {
            val pb = ProcessBuilder("sh", "-c", cmd)
            pb.directory(patchDir)
            pb.environment().putAll(env)
            pb.redirectErrorStream(true)
            val p = pb.start()
            val output = p.inputStream.bufferedReader().readText()
            p.waitFor()
            com.anatdx.rei.core.root.RootExecResult(p.exitValue(), output)
        } catch (e: Exception) {
            return PatchResult.Error(context.getString(R.string.patches_error_exec, e.message))
        }
    }
    
    onLog(result.output)
    
    if (result.exitCode != 0) {
        return PatchResult.Error(context.getString(R.string.patches_error_script, result.exitCode))
    }
    
    if (mode == PatchMode.INSTALL_INACTIVE) {
        // 5. Post-process for Inactive Slot: Switch slot
        onLog("Switching active slot...")
        
        val bootctlCmd = "bootctl set-active-boot-slot $(if [ \"$(bootctl get-current-slot)\" = \"0\" ]; then echo 1; else echo 0; fi)"
        
        // We can do it in a shell script command
        val switchCmd = "bootctl set-active-boot-slot $(if [ \"$(bootctl get-current-slot)\" == \"0\" ]; then echo 1; else echo 0; fi)"
        val switchRes = RootShell.exec(switchCmd)
        onLog("Switch slot result: ${switchRes.exitCode}")
        if (switchRes.exitCode == 0) {
             onLog("Slot switched. Setting up boot marker...")
             // Write post_ota.sh to mark boot successful on next reboot
             // This is important for A/B to not revert
             // Simplified marker script
             val markerCmd = "mkdir -p /data/adb/post-fs-data.d && " +
                 "echo 'bootctl mark-boot-successful' > /data/adb/post-fs-data.d/post_ota.sh && " +
                 "echo 'rm -f /data/adb/post-fs-data.d/post_ota.sh' >> /data/adb/post-fs-data.d/post_ota.sh && " +
                 "chmod 755 /data/adb/post-fs-data.d/post_ota.sh"
             RootShell.exec(markerCmd)
             onLog("Boot marker setup done.")
        } else {
            onLog("Failed to switch slot. You may need to switch manually.")
        }
    }
    
    if (flashToDevice) {
        return PatchResult.Success(context.getString(R.string.patches_flash_complete), null)
    } else {
        val newBoot = File(patchDir, "new-boot.img")
        if (newBoot.exists()) {
            return PatchResult.Success(context.getString(R.string.patches_success), newBoot)
        } else {
            return PatchResult.Error("new-boot.img not found after success message?")
        }
    }
}
