package com.xiaozhi.client.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.xiaozhi.client.R

/**
 * The five themes mirror `xiaozhi-web/src/index.css` (`html[data-theme=…]`).
 * The palette fields map 1:1 to the CSS custom properties, so porting the web
 * look is a matter of consuming [XiaozhiPalette] instead of hardcoded colors.
 */
enum class ThemeId(val wire: String, val label: String) {
    DARK("dark", "Dark"),
    BEIGE("beige", "Beige"),
    BLUE("blue", "Blue"),
    GREEN("green", "Green"),
    PINK("pink", "Pink");

    companion object {
        val ALL: List<ThemeId> = entries
        fun fromWire(w: String): ThemeId = entries.firstOrNull { it.wire == w } ?: DARK
    }
}

data class XiaozhiPalette(
    val name: String,
    val bg: Color,
    val bg2: Color,
    val card: Color,
    val line: Color,
    val text: Color,
    val dim: Color,
    val accent: Color,
    val accent2: Color,
    val danger: Color,
    val ok: Color,
    val warn: Color,
    val panel: Color,
    val userBubbleBg: Color,
    val userBubbleLine: Color,
    /** Optional full-bleed background image for light themes (web uses jpegs). */
    val backgroundRes: Int? = null,
    val darkTheme: Boolean = false,
)

private val DarkPalette = XiaozhiPalette(
    name = "dark",
    bg = Color(0xFF0B1220),
    bg2 = Color(0xFF101A2E),
    card = Color(0xFF16233C),
    line = Color(0xFF23334F),
    text = Color(0xFFE6EDF7),
    dim = Color(0xFF8FA3C0),
    accent = Color(0xFF38BDF8),
    accent2 = Color(0xFF22D3EE),
    danger = Color(0xFFF87171),
    ok = Color(0xFF4ADE80),
    warn = Color(0xFFFBBF24),
    panel = Color(0xF0101A2E),
    userBubbleBg = Color(0x4D1D4ED8),
    userBubbleLine = Color(0x801D4ED8),
    darkTheme = true,
)

private val BeigePalette = XiaozhiPalette(
    name = "beige",
    bg = Color(0xFFF3EAD9),
    bg2 = Color(0xFFEFE3CC),
    card = Color(0xB8FFFAF0),
    line = Color(0xFFD8C9A8),
    text = Color(0xFF3A2F1F),
    dim = Color(0xFF8A7A58),
    accent = Color(0xFFB45309),
    accent2 = Color(0xFFD97706),
    danger = Color(0xFFB91C1C),
    ok = Color(0xFF15803D),
    warn = Color(0xFFA16207),
    panel = Color(0xC7FFFAF0),
    userBubbleBg = Color(0x1FB45309),
    userBubbleLine = Color(0x70B45309),
    backgroundRes = R.drawable.bg_beige,
)

private val BluePalette = XiaozhiPalette(
    name = "blue",
    bg = Color(0xFFDBEAFE),
    bg2 = Color(0xFFD4E6FB),
    card = Color(0xA8FFFFFF),
    line = Color(0xFFA5C8EE),
    text = Color(0xFF1E3A5F),
    dim = Color(0xFF5B7EA6),
    accent = Color(0xFF2563EB),
    accent2 = Color(0xFF0891B2),
    danger = Color(0xFFB91C1C),
    ok = Color(0xFF15803D),
    warn = Color(0xFFA16207),
    panel = Color(0xC7EFF6FF),
    userBubbleBg = Color(0x1F2563EB),
    userBubbleLine = Color(0x702563EB),
    backgroundRes = R.drawable.bg_blue,
)

private val GreenPalette = XiaozhiPalette(
    name = "green",
    bg = Color(0xFFDCF0E0),
    bg2 = Color(0xFFD3E9D8),
    card = Color(0xA8FFFFFF),
    line = Color(0xFFA8D5B0),
    text = Color(0xFF1F3D27),
    dim = Color(0xFF5D8067),
    accent = Color(0xFF16A34A),
    accent2 = Color(0xFF0D9488),
    danger = Color(0xFFB91C1C),
    ok = Color(0xFF15803D),
    warn = Color(0xFFA16207),
    panel = Color(0xC7F0FCF2),
    userBubbleBg = Color(0x1A16A34A),
    userBubbleLine = Color(0x7016A34A),
    backgroundRes = R.drawable.bg_green,
)

private val PinkPalette = XiaozhiPalette(
    name = "pink",
    bg = Color(0xFFFDE2E8),
    bg2 = Color(0xFFFADBE3),
    card = Color(0xA8FFFFFF),
    line = Color(0xFFE7B7C6),
    text = Color(0xFF4A1F2B),
    dim = Color(0xFFA06A78),
    accent = Color(0xFFDB2777),
    accent2 = Color(0xFFEC4899),
    danger = Color(0xFFB91C1C),
    ok = Color(0xFF15803D),
    warn = Color(0xFFA16207),
    panel = Color(0xC7FFF5F8),
    userBubbleBg = Color(0x1ADB2777),
    userBubbleLine = Color(0x70DB2777),
    backgroundRes = R.drawable.bg_pink,
)

val XiaozhiPalettes: Map<ThemeId, XiaozhiPalette> = mapOf(
    ThemeId.DARK to DarkPalette,
    ThemeId.BEIGE to BeigePalette,
    ThemeId.BLUE to BluePalette,
    ThemeId.GREEN to GreenPalette,
    ThemeId.PINK to PinkPalette,
)

/** The active theme palette; set by [XiaozhiTheme]. */
val LocalXiaozhiPalette = staticCompositionLocalOf<XiaozhiPalette> { DarkPalette }

@Composable
fun palette(): XiaozhiPalette = LocalXiaozhiPalette.current

private fun xiaozhiColorScheme(p: XiaozhiPalette): ColorScheme =
    if (p.darkTheme) darkColorScheme(
        primary = p.accent,
        onPrimary = p.bg,
        secondary = p.accent2,
        onSecondary = p.bg,
        background = p.bg,
        onBackground = p.text,
        surface = p.card,
        onSurface = p.text,
        surfaceVariant = p.card,
        onSurfaceVariant = p.dim,
        error = p.danger,
        onError = p.bg,
    ) else lightColorScheme(
        primary = p.accent,
        onPrimary = Color.White,
        secondary = p.accent2,
        onSecondary = Color.White,
        background = p.bg,
        onBackground = p.text,
        surface = p.card,
        onSurface = p.text,
        surfaceVariant = p.line,
        onSurfaceVariant = p.dim,
        error = p.danger,
        onError = Color.White,
    )

@Composable
fun XiaozhiTheme(
    palette: XiaozhiPalette,
    content: @Composable () -> Unit,
) {
    val colorScheme = xiaozhiColorScheme(palette)
    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
    ) {
        CompositionLocalProvider(LocalXiaozhiPalette provides palette) {
            content()
        }
    }
}

// Backward-compat aliases for code that predates the palette refactor. These
// resolve against the dark palette (the default theme) and will be removed as
// screens migrate to LocalXiaozhiPalette.
val PastelBlueBg: Color get() = DarkPalette.bg
val CloudWhite: Color get() = Color(0xFFFFFFFF)
val PastelBlueBubble: Color get() = DarkPalette.accent.copy(alpha = 0.18f)
val ReceivedBubble: Color get() = DarkPalette.card
val SentBubble: Color get() = DarkPalette.userBubbleBg
val DeepBlueText: Color get() = DarkPalette.text
val HeartAccentBlue: Color get() = DarkPalette.accent
val TopBarBlue: Color get() = DarkPalette.panel