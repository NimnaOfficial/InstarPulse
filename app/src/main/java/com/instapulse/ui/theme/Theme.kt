package com.instapulse.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = IgPink,
    secondary = RecentsCyan,
    tertiary = LoyalEmerald,
    background = CanvasBg,
    surface = CardBg,
    onPrimary = TextPrimary,
    onSecondary = CanvasBg,
    onTertiary = TextPrimary,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    error = TraitorCrimson
)

@Composable
fun InstaPulseTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = CanvasBg.toArgb()
                window.navigationBarColor = CanvasBg.toArgb()
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = false
                controller.isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

/**
 * Deep Space Canvas background with subtle top ambient aurora radial glow.
 * Zero-allocation drawWithCache.
 */
fun Modifier.ambientAuroraBackground(): Modifier = this.drawWithCache {
    val radialBrush = Brush.radialGradient(
        colors = listOf(AuroraTop, AuroraMid, CanvasBg),
        center = Offset(size.width * 0.5f, 0f),
        radius = size.width * 1.25f
    )
    onDrawBehind {
        drawRect(color = CanvasBg)
        drawRect(brush = radialBrush, size = Size(size.width, size.height * 0.65f))
    }
}

/**
 * Obsidian Hyper-Glass surface modifier with zero runtime memory allocations.
 * Paints:
 * 1. Vertical gradient (#131622 -> #0D0F18)
 * 2. Top specular highlight line (rgba(255,255,255,0.14))
 * 3. 1.2dp border (animated neon gradient when isSelected, or accentColor, or subtle glass border)
 */
fun Modifier.hyperGlassCard(
    accentColor: Color? = null,
    isSelected: Boolean = false,
    cornerRadius: Dp = 16.dp
): Modifier = this
    .clip(RoundedCornerShape(cornerRadius))
    .drawWithCache {
        val surfaceGradient = Brush.verticalGradient(
            colors = listOf(SurfaceDarkStart, SurfaceDarkEnd)
        )
        val specularBrush = Brush.horizontalGradient(
            colors = listOf(
                Color.Transparent,
                SpecularHighlight,
                Color.Transparent
            )
        )
        val neonBorderBrush = if (isSelected) {
            Brush.horizontalGradient(IgPulseGradient)
        } else if (accentColor != null) {
            Brush.horizontalGradient(listOf(accentColor, accentColor.copy(alpha = 0.6f)))
        } else {
            Brush.horizontalGradient(listOf(CardBorder, CardBorder))
        }
        val strokeWidth = if (isSelected || accentColor != null) 1.2.dp.toPx() else 1.dp.toPx()
        val rPx = cornerRadius.toPx()

        onDrawBehind {
            // 1. Surface gradient fill
            drawRoundRect(
                brush = surfaceGradient,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(rPx, rPx)
            )

            // 2. Specular top light line
            drawLine(
                brush = specularBrush,
                start = Offset(rPx * 0.5f, 1f),
                end = Offset(size.width - rPx * 0.5f, 1f),
                strokeWidth = 1.dp.toPx()
            )

            // 3. Border
            drawRoundRect(
                brush = neonBorderBrush,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(rPx, rPx),
                style = Stroke(width = strokeWidth)
            )
        }
    }
