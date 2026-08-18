package com.example.familytree.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.example.familytree.data.I18n

/** 当前应用语言翻译器 */
val LocalI18n = staticCompositionLocalOf { I18n.ZH }

// ---------- 参考风格配色（来自用户提供的三张 UI 参考图） ----------
// 浅灰背景 + 白色圆角卡片、无描边；iOS 蓝主色；浅蓝选中态；彩色功能图标。

private val GlassLight = lightColorScheme(
    primary = Color(0xFF007AFF),            // iOS 蓝
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3F2FF),   // 浅蓝（选中态背景）
    onPrimaryContainer = Color(0xFF0A4B8C),
    secondary = Color(0xFF5856D6),          // 靛
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E8FF),
    onSecondaryContainer = Color(0xFF1C1B4B),
    tertiary = Color(0xFF34C759),           // iOS 绿
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD9F7E2),
    onTertiaryContainer = Color(0xFF0A3D1C),
    background = Color(0xFFF5F6F8),         // 浅灰底（联系人/Telegram 风格）
    onBackground = Color(0xFF1C1C1E),
    surface = Color(0xFFFFFFFF),            // 纯白卡片
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFEFEFF4),
    onSurfaceVariant = Color(0xFF8E8E93),   // 灰色副文字
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFCFCFD),
    surfaceContainer = Color(0xFFF9F9FB),   // 浅灰分组卡片
    surfaceContainerHigh = Color(0xFFF2F2F7),
    surfaceContainerHighest = Color(0xFFECECF1),
    outline = Color(0xFFC7C7CC),
    outlineVariant = Color(0xFFE5E5EA),
    error = Color(0xFFFF3B30),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    scrim = Color(0x66000000),
)

private val GlassDark = darkColorScheme(
    primary = Color(0xFF4DA3FF),
    onPrimary = Color(0xFF00325C),
    primaryContainer = Color(0xFF1C4A83),
    onPrimaryContainer = Color(0xFFD6EBFF),
    secondary = Color(0xFF9E9CF7),
    onSecondary = Color(0xFF1C1B4B),
    secondaryContainer = Color(0xFF494A8A),
    onSecondaryContainer = Color(0xFFE6E5FF),
    tertiary = Color(0xFF63E68F),
    onTertiary = Color(0xFF0A3D1C),
    tertiaryContainer = Color(0xFF338A50),
    onTertiaryContainer = Color(0xFFD7F6DD),
    background = Color(0xFF121214),
    onBackground = Color(0xFFE9EAEF),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFE9EAEF),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFF9A9AA2),
    surfaceContainerLowest = Color(0xFF161618),
    surfaceContainerLow = Color(0xFF1E1E20),
    surfaceContainer = Color(0xFF232326),
    surfaceContainerHigh = Color(0xFF29292C),
    surfaceContainerHighest = Color(0xFF303034),
    outline = Color(0xFF48484E),
    outlineVariant = Color(0xFF38383E),
    error = Color(0xFFFF6961),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF930E00),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0x99000000),
)

/** 圆角：参考图以 12~20px 为主，整体克制 */
private val GlassShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** 液态玻璃面板：半透明渐变 + 顶部高光 + 细描边 + 柔光投影 */
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(26.dp),
    dark: Boolean = false,
    base: Color = if (dark) Color(0xFF202128) else Color.White,
    alpha: Float = if (dark) 0.55f else 0.60f,
): Modifier = this
    .shadow(
        elevation = 14.dp,
        shape = shape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = 0.10f),
        spotColor = Color.Black.copy(alpha = 0.18f),
    )
    .background(
        brush = Brush.verticalGradient(
            listOf(base.copy(alpha = (alpha + 0.28f).coerceAtMost(0.96f)), base.copy(alpha = alpha)),
        ),
        shape = shape,
    )
    .background(
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = if (dark) 0.16f else 0.42f),
            0.42f to Color.White.copy(alpha = if (dark) 0.05f else 0.10f),
            1f to Color.Transparent,
        ),
        shape = shape,
    )
    .border(1.dp, Color.White.copy(alpha = if (dark) 0.16f else 0.60f), shape)

/** 页面底色：参考图为纯浅灰底（无渐变），深色模式为纯深灰 */
@Composable
fun LiquidGlassBackground(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(if (dark) Color(0xFF121214) else Color(0xFFF5F6F8)),
    ) {
        content()
    }
}

/**
 * 主题：液态玻璃风格（借鉴 Apple Liquid Glass）：
 * 柔和渐变背景、半透明磨砂面板、大圆角、细描边与高光。
 */
@Composable
fun FamilyTreeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) GlassDark else GlassLight,
        shapes = GlassShapes,
        content = content,
    )
}
