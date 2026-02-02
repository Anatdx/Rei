package com.anatdx.rei.ui.theme

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.util.lerp as lerpFloat
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

data class BackgroundConfig(
    val enabled: Boolean,
    val path: String?,
    val alpha: Float,
    val dim: Float,
)

data class ChromeStyleConfig(
    // 0..1, drives BOTH alpha and brightness together.
    val level: Float,
)

val LocalReiChromeStyle = staticCompositionLocalOf { ChromeStyleConfig(level = 0.86f) }

object ReiStyleBus {
    val nonce = MutableStateFlow(0)
    fun notifyChanged() {
        nonce.value = nonce.value + 1
    }
}

object BackgroundPrefs {
    private const val PREF = "rei_style"

    private const val KEY_BG_ENABLED = "custom_background_enabled"
    private const val KEY_BG_PATH = "custom_background_path"
    private const val KEY_BG_ALPHA = "custom_background_alpha"
    private const val KEY_BG_DIM = "custom_background_dim"

    private const val KEY_CHROME_LEVEL = "chrome_level"

    fun load(context: Context): BackgroundConfig {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val path = prefs.getString(KEY_BG_PATH, null)?.takeIf { it.isNotBlank() }
        return BackgroundConfig(
            enabled = prefs.getBoolean(KEY_BG_ENABLED, false),
            path = path,
            alpha = prefs.getFloat(KEY_BG_ALPHA, 0.92f),
            dim = prefs.getFloat(KEY_BG_DIM, 0.35f),
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_BG_ENABLED, enabled)
            .apply()
        ReiStyleBus.notifyChanged()
    }

    fun setPath(context: Context, path: String?) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_BG_PATH, path ?: "")
            .apply()
        ReiStyleBus.notifyChanged()
    }

    fun setAlpha(context: Context, alpha: Float) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_BG_ALPHA, alpha)
            .apply()
        ReiStyleBus.notifyChanged()
    }

    fun setDim(context: Context, dim: Float) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_BG_DIM, dim)
            .apply()
        ReiStyleBus.notifyChanged()
    }

    fun loadChromeStyle(context: Context): ChromeStyleConfig {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return ChromeStyleConfig(
            level = prefs.getFloat(KEY_CHROME_LEVEL, 0.86f),
        )
    }

    fun setChromeLevel(context: Context, level: Float) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_CHROME_LEVEL, level)
            .apply()
        ReiStyleBus.notifyChanged()
    }
}

object BackgroundManager {
    private const val FILE_NAME = "custom_background"

    fun saveBackground(context: Context, source: Uri): String? {
        return try {
            val input = context.contentResolver.openInputStream(source) ?: return null
            val target = File(context.filesDir, FILE_NAME)
            target.outputStream().use { out ->
                input.use { it.copyTo(out) }
            }
            target.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    fun clearBackground(context: Context) {
        runCatching {
            val target = File(context.filesDir, FILE_NAME)
            if (target.exists()) target.delete()
        }
    }
}

@Composable
fun rememberBackgroundConfig(context: Context): BackgroundConfig {
    val n by ReiStyleBus.nonce.collectAsState()
    return remember(n) { BackgroundPrefs.load(context) }
}

@Composable
fun rememberChromeStyleConfig(context: Context): ChromeStyleConfig {
    val n by ReiStyleBus.nonce.collectAsState()
    return remember(n) { BackgroundPrefs.loadChromeStyle(context) }
}

@Composable
fun ReiBackgroundLayer(
    config: BackgroundConfig,
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        val base = if (darkTheme) Color(0xFF0B0E14) else Color(0xFFF6F8FF)
        val accent1 = if (darkTheme) Color(0xFF1A237E) else Color(0xFFB5C9FF)
        val accent2 = if (darkTheme) Color(0xFF0E1530) else Color(0xFFEAF0FF)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(accent1.copy(alpha = 0.28f), accent2.copy(alpha = 0.0f), base),
                        radius = 1400f,
                    )
                )
        )

        val model = resolveBackgroundModel(config.path)
        if (config.enabled && model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(alpha = config.alpha),
            )
        }

        if (config.enabled) {
            val overlay = if (darkTheme) Color.Black else Color.White
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                overlay.copy(alpha = config.dim),
                                overlay.copy(alpha = config.dim * 0.7f),
                            )
                        )
                    )
            )
        }

        content()
    }
}

private fun resolveBackgroundModel(path: String?): Any? {
    val p = path?.takeIf { it.isNotBlank() } ?: return null
    if (p.startsWith("/")) {
        val f = File(p)
        return if (f.exists() && f.length() > 0L) f else null
    }
    return p
}

/**
 * Unified "glass/chrome" color: alpha + brightness moves together.
 * - level: 0..1 (lower = more transparent/darker, higher = more opaque/brighter)
 */
fun chromeSurfaceColor(base: Color, darkTheme: Boolean, level: Float): Color {
    val l = level.coerceIn(0.0f, 1.0f)
    val alpha = lerpFloat(0.10f, 0.92f, l)

    // Brightness adjustment (subtle): pull towards white when stronger.
    val brighten = lerpFloat(0.0f, if (darkTheme) 0.18f else 0.10f, l)
    val darken = lerpFloat(if (darkTheme) 0.12f else 0.06f, 0.0f, l)

    var c = base
    c = lerpColor(c, Color.White, brighten)
    c = lerpColor(c, Color.Black, darken)
    return c.copy(alpha = alpha)
}

fun chromeEdgeColor(base: Color, darkTheme: Boolean, level: Float): Color {
    val l = level.coerceIn(0.0f, 1.0f)
    val alpha = lerpFloat(0.06f, 0.28f, l)
    val brighten = lerpFloat(0.0f, if (darkTheme) 0.22f else 0.14f, l)
    val c = lerpColor(base, Color.White, brighten)
    return c.copy(alpha = alpha)
}

