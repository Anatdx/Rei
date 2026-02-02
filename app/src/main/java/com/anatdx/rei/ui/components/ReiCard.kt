package com.anatdx.rei.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import com.anatdx.rei.ui.theme.LocalReiChromeStyle
import com.anatdx.rei.ui.theme.chromeEdgeColor
import com.anatdx.rei.ui.theme.chromeSurfaceColor

@Composable
fun ReiCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    content: @Composable () -> Unit,
) {
    val chrome = LocalReiChromeStyle.current
    val darkTheme = isSystemInDarkTheme()
    val bg = chromeSurfaceColor(
        base = MaterialTheme.colorScheme.surface,
        darkTheme = darkTheme,
        level = chrome.level,
    )
    val edge = chromeEdgeColor(
        base = MaterialTheme.colorScheme.outlineVariant,
        darkTheme = darkTheme,
        level = chrome.level,
    )
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(
            // Use a solid surface to avoid "grey outline" artifacts on some devices/themes.
            containerColor = bg,
        ),
        // Keep cards flat to avoid "grey border" look on some devices/themes.
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, edge),
        content = { content() },
    )
}

