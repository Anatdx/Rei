package com.anatdx.rei.ui.auth

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anatdx.rei.core.auth.AppAuthStore
import com.anatdx.rei.ui.theme.ReiTheme
import com.anatdx.rei.ui.theme.ThemePreset

class AuthorizeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val req = AuthRequest.fromIntent(this, intent)

        setContent {
            ReiTheme(dynamicColor = true, preset = ThemePreset.IceBlue) {
                AuthorizeDialog(
                    request = req,
                    onDeny = {
                        setResult(RESULT_CANCELED, AuthResult(granted = false).toIntent())
                        finish()
                    },
                    onAllowOnce = { level ->
                        setResult(RESULT_OK, AuthResult(granted = true, level = level, remember = false).toIntent())
                        finish()
                    },
                    onAllowRemember = { level ->
                        AppAuthStore.setLevel(this@AuthorizeActivity, req.packageName, level)
                        setResult(RESULT_OK, AuthResult(granted = true, level = level, remember = true).toIntent())
                        finish()
                    },
                )
            }
        }
    }
}

enum class AuthLevel {
    Root,
    Shell,
    Basic;

    fun label(): String = when (this) {
        Root -> "Root"
        Shell -> "Shell"
        Basic -> "受限"
    }

    fun desc(): String = when (this) {
        Root -> "允许该应用获取完整 Root 权限"
        Shell -> "允许该应用以 Shell 级别执行命令"
        Basic -> "仅允许受限能力（当前等同不授予 su）"
    }
}

data class AuthRequest(
    val appName: String,
    val packageName: String,
    val uid: Int,
    val defaultLevel: AuthLevel,
) {
    companion object {
        const val EXTRA_APP_NAME = "rei.extra.APP_NAME"
        const val EXTRA_PACKAGE = "rei.extra.PACKAGE"
        const val EXTRA_UID = "rei.extra.UID"
        const val EXTRA_LEVEL = "rei.extra.LEVEL"

        fun fromIntent(ctx: Context, intent: Intent): AuthRequest {
            val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: "unknown"
            val uid = intent.getIntExtra(EXTRA_UID, -1)
            val levelOrdinal = intent.getIntExtra(EXTRA_LEVEL, AuthLevel.Root.ordinal)
            val level = AuthLevel.entries.getOrNull(levelOrdinal) ?: AuthLevel.Root

            val nameFromExtra = intent.getStringExtra(EXTRA_APP_NAME)
            val nameFromPm = resolveAppLabel(ctx, pkg)
            val name = nameFromExtra ?: nameFromPm ?: pkg

            return AuthRequest(
                appName = name,
                packageName = pkg,
                uid = uid,
                defaultLevel = level,
            )
        }

        private fun resolveAppLabel(ctx: Context, pkg: String): String? {
            return try {
                val pm = ctx.packageManager
                val appInfo = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(appInfo)?.toString()
            } catch (_: Throwable) {
                null
            }
        }
    }
}

data class AuthResult(
    val granted: Boolean,
    val level: AuthLevel = AuthLevel.Root,
    val remember: Boolean = false,
) {
    companion object {
        const val EXTRA_GRANTED = "rei.extra.GRANTED"
        const val EXTRA_LEVEL = "rei.extra.RESULT_LEVEL"
        const val EXTRA_REMEMBER = "rei.extra.REMEMBER"
    }

    fun toIntent(): Intent = Intent().apply {
        putExtra(EXTRA_GRANTED, granted)
        putExtra(EXTRA_LEVEL, level.ordinal)
        putExtra(EXTRA_REMEMBER, remember)
    }
}

@Composable
private fun AuthorizeDialog(
    request: AuthRequest,
    onDeny: () -> Unit,
    onAllowOnce: (AuthLevel) -> Unit,
    onAllowRemember: (AuthLevel) -> Unit,
) {
    BackHandler(onBack = onDeny)
    val scrim = Color.Black.copy(alpha = 0.45f)
    val noRipple = remember { MutableInteractionSource() }
    var selectedLevel by rememberSaveable { mutableStateOf(request.defaultLevel) }
    var rememberChoice by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scrim)
            .clickable(
                indication = null,
                interactionSource = noRipple,
                onClick = onDeny,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 18.dp)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = { },
                ),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Header(request)
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                Text(
                    text = "请求权限等级",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                LevelRow(
                    selected = selectedLevel,
                    onSelect = { selectedLevel = it },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = selectedLevel.desc(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = rememberChoice, onCheckedChange = { rememberChoice = it })
                    Text(
                        text = "记住选择（下次不再询问）",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Spacer(Modifier.height(14.dp))
                Actions(
                    rememberChoice = rememberChoice,
                    onDeny = onDeny,
                    onAllowOnce = { onAllowOnce(selectedLevel) },
                    onAllowRemember = { onAllowRemember(selectedLevel) },
                )
            }
        }
    }
}

@Composable
private fun Header(request: AuthRequest) {
    val ctx = LocalContext.current
    val icon = remember(request.packageName) { loadAppIconBitmap(ctx, request.packageName) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Image(
                bitmap = icon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = request.appName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = request.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (request.uid >= 0) {
                Text(
                    text = "UID: ${request.uid}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LevelRow(
    selected: AuthLevel,
    onSelect: (AuthLevel) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AuthLevel.entries.forEach { level ->
            val selectedNow = level == selected
            val colors = CardDefaults.outlinedCardColors(
                containerColor = if (selectedNow) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
            )
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = colors.containerColor,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable { onSelect(level) },
            ) {
                Text(
                    text = level.label(),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun Actions(
    rememberChoice: Boolean,
    onDeny: () -> Unit,
    onAllowOnce: () -> Unit,
    onAllowRemember: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onDeny) { Text("拒绝") }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onAllowOnce) { Text("仅此一次") }
        Button(
            onClick = { if (rememberChoice) onAllowRemember() else onAllowOnce() },
        ) { Text(if (rememberChoice) "允许并记住" else "允许") }
    }
}

private fun loadAppIconBitmap(ctx: Context, pkg: String): Bitmap? {
    return try {
        val pm = ctx.packageManager
        val drawable = pm.getApplicationIcon(pkg)
        when (drawable) {
            is BitmapDrawable -> drawable.bitmap
            else -> null
        }
    } catch (_: Throwable) {
        null
    }
}

