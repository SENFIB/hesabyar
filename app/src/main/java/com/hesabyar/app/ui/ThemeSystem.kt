package com.hesabyar.app.ui
import androidx.compose.ui.graphics.luminance
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

// ---------- متن چندزبانه ----------

class Loc(val fa: String, val en: String, val ar: String) {
    fun of(lang: String): String = when (lang) {
        "fa" -> fa
        "ar" -> ar
        else -> en
    }
}

fun List<Loc>.labelsFor(lang: String): List<String> = map { it.of(lang) }

@Composable
fun currentLang(): String = LocalConfiguration.current.locales[0].language

@Composable
fun tr(fa: String, en: String, ar: String): String = Loc(fa, en, ar).of(currentLang())

// ---------- طرح‌های رنگی ----------

class ModeColors(
    val primary: Color,
    val container: Color,
    val onContainer: Color,
    val surface: Color,
    val bgTop: Color,
    val bgBottom: Color
)

class Palette(val name: Loc, val light: ModeColors, val dark: ModeColors)

val PALETTES = listOf(
    Palette(
        Loc("زمرد", "Emerald", "زمردي"),
        light = ModeColors(
            Color(0xFF0F766E), Color(0xFFCCEBE6), Color(0xFF0B3B36),
            Color(0xFFFFFFFF), Color(0xFFE3F3EF), Color(0xFFF6FBFA)
        ),
        dark = ModeColors(
            Color(0xFF5EEAD4), Color(0xFF134E48), Color(0xFFCCFBF1),
            Color(0xFF1E293B), Color(0xFF0B1F2A), Color(0xFF111827)
        )
    ),
    Palette(
        Loc("اقیانوس", "Ocean", "محيط"),
        light = ModeColors(
            Color(0xFF1D4ED8), Color(0xFFDBEAFE), Color(0xFF1E3A8A),
            Color(0xFFFFFFFF), Color(0xFFE0ECFF), Color(0xFFF5F9FF)
        ),
        dark = ModeColors(
            Color(0xFF93C5FD), Color(0xFF1E3A8A), Color(0xFFDBEAFE),
            Color(0xFF1E293B), Color(0xFF0B1A33), Color(0xFF0F172A)
        )
    ),
    Palette(
        Loc("بنفش", "Violet", "بنفسجي"),
        light = ModeColors(
            Color(0xFF7C3AED), Color(0xFFEDE9FE), Color(0xFF4C1D95),
            Color(0xFFFFFFFF), Color(0xFFEFE9FF), Color(0xFFFAF8FF)
        ),
        dark = ModeColors(
            Color(0xFFC4B5FD), Color(0xFF4C1D95), Color(0xFFEDE9FE),
            Color(0xFF241B3D), Color(0xFF1A1333), Color(0xFF0F0B1F)
        )
    ),
    Palette(
        Loc("غروب", "Sunset", "غروب"),
        light = ModeColors(
            Color(0xFFEA580C), Color(0xFFFFEDD5), Color(0xFF7C2D12),
            Color(0xFFFFFFFF), Color(0xFFFFEDD5), Color(0xFFFFFBF5)
        ),
        dark = ModeColors(
            Color(0xFFFDBA74), Color(0xFF7C2D12), Color(0xFFFFEDD5),
            Color(0xFF2A1F14), Color(0xFF2A1709), Color(0xFF1A1108)
        )
    ),
    Palette(
        Loc("گل سرخ", "Rose", "وردي"),
        light = ModeColors(
            Color(0xFFBE123C), Color(0xFFFFE4E6), Color(0xFF881337),
            Color(0xFFFFFFFF), Color(0xFFFFE4E8), Color(0xFFFFF7F8)
        ),
        dark = ModeColors(
            Color(0xFFFDA4AF), Color(0xFF881337), Color(0xFFFFE4E6),
            Color(0xFF2D1821), Color(0xFF2A0E16), Color(0xFF1A0B10)
        )
    )
)

// ---------- بخش‌های قابل شخصی‌سازی ----------

enum class ThemePart(val label: Loc, val supportsFont: Boolean) {
    HEADER(Loc("هدر بالا", "Top header", "الرأس العلوي"), true),
    TABS(Loc("نوار تب‌ها", "Tab bar", "شريط التبويب"), true),
    CARDS(Loc("کارت‌ها", "Cards", "البطاقات"), false),
    BUTTONS(Loc("دکمه‌ها", "Buttons", "الأزرار"), false),
    TEXT(Loc("متن‌های صفحه", "Page text", "نص الصفحة"), true),
    BACKGROUND(Loc("پس‌زمینه", "Background", "الخلفية"), false);

    companion object {
        fun fromKey(key: String): ThemePart? = ThemePart.entries.firstOrNull { "part_${it.name}" == key }
    }
}

// رنگ‌های آماده برای انتخاب در هر بخش
val SWATCHES: List<Color> = listOf(
    Color(0xFF0F766E), Color(0xFF16A34A), Color(0xFF1D4ED8), Color(0xFF0EA5E9),
    Color(0xFF4F46E5), Color(0xFF7C3AED), Color(0xFFDB2777), Color(0xFFBE123C),
    Color(0xFFEA580C), Color(0xFFD97706), Color(0xFF334155), Color(0xFF111827),
    Color(0xFF78350F), Color(0xFF6B7280), Color(0xFFFFFFFF), Color(0xFF0B1220)
)

fun swatchOf(index: Int): Color? = SWATCHES.getOrNull(index)

// تنظیم یک بخش: -1 یعنی همان مقدار کلی
data class PartStyle(
    val colorIndex: Int = -1,
    val fontIndex: Int = -1,
    val scaleIndex: Int = -1
)

// ---------- گزینه‌های فونت، اندازه، گوشه و تم ----------

val FONT_FAMILIES: List<FontFamily> = listOf(
    AppFontFamily,
    FontFamily.SansSerif,
    FontFamily.Serif,
    FontFamily.Monospace
)
val FONT_LABELS = listOf(
    Loc("وزیرمتن (پیش‌فرض)", "Vazirmatn (default)", "وزير متن"),
    Loc("ساده", "Sans", "بسيط"),
    Loc("سریف", "Serif", "سيريف"),
    Loc("ماشینی", "Monospace", "أحادي")
)

val FONT_SCALES = listOf(0.85f, 1f, 1.15f, 1.3f)
val SCALE_LABELS = listOf(
    Loc("کوچک", "Small", "صغير"),
    Loc("متوسط", "Medium", "متوسط"),
    Loc("بزرگ", "Large", "كبير"),
    Loc("خیلی بزرگ", "Extra large", "كبير جدا")
)

val CORNERS = listOf(4, 16, 28)
val CORNER_LABELS = listOf(
    Loc("تیز", "Sharp", "حاد"),
    Loc("معمولی", "Normal", "عادي"),
    Loc("گرد", "Round", "دائري")
)

val MODE_LABELS = listOf(
    Loc("بر اساس گوشی", "Follow phone", "حسب الهاتف"),
    Loc("روشن", "Light", "فاتح"),
    Loc("تاریک", "Dark", "داكن")
)

// ---------- تنظیمات ذخیره‌شده ----------

data class ThemeSettings(
    val paletteIndex: Int = 0,
    val mode: Int = 0,        // 0 = بر اساس گوشی، 1 = روشن، 2 = تاریک
    val fontIndex: Int = 0,
    val scaleIndex: Int = 1,
    val cornerIndex: Int = 1,
    val tabsTop: Boolean = false,
    val parts: Map<ThemePart, PartStyle> = emptyMap()
) {
    fun partOf(part: ThemePart): PartStyle = parts[part] ?: PartStyle()

    fun withPart(part: ThemePart, style: PartStyle): ThemeSettings =
        copy(parts = parts + (part to style))
}

object ThemeStore {
    private const val PREFS = "hesabyar_theme"

    var settings by mutableStateOf(ThemeSettings())
        private set

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        settings = ThemeSettings(
            paletteIndex = p.getInt("palette", 0),
            mode = p.getInt("mode", 0),
            fontIndex = p.getInt("font", 0),
            scaleIndex = p.getInt("scale", 1),
            cornerIndex = p.getInt("corner", 1),
            tabsTop = p.getBoolean("tabs_top", false),
            parts = ThemePart.entries.associateWith { part ->
                val n = part.name.lowercase()
                PartStyle(
                    colorIndex = p.getInt("p_${n}_c", -1),
                    fontIndex = p.getInt("p_${n}_f", -1),
                    scaleIndex = p.getInt("p_${n}_s", -1)
                )
            }
        )
    }

    fun save(context: Context, value: ThemeSettings) {
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("palette", value.paletteIndex)
            .putInt("mode", value.mode)
            .putInt("font", value.fontIndex)
            .putInt("scale", value.scaleIndex)
            .putInt("corner", value.cornerIndex)
            .putBoolean("tabs_top", value.tabsTop)
        ThemePart.entries.forEach { part ->
            val n = part.name.lowercase()
            val s = value.partOf(part)
            e.putInt("p_${n}_c", s.colorIndex)
            e.putInt("p_${n}_f", s.fontIndex)
            e.putInt("p_${n}_s", s.scaleIndex)
        }
        e.apply()
        settings = value
    }
}

// ---------- کمک‌ها ----------

@Composable
fun isDarkNow(): Boolean {
    return when (ThemeStore.settings.mode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
}

@Composable
fun currentModeColors(): ModeColors {
    val palette = PALETTES[ThemeStore.settings.paletteIndex.coerceIn(0, PALETTES.lastIndex)]
    return if (isDarkNow()) palette.dark else palette.light
}

// رنگ متن خوانا روی یک رنگ زمینه
fun onColorFor(c: Color): Color = if (c.luminance() > 0.5f) Color(0xFF111827) else Color.White

// رنگ بخش: اگر کاربر رنگ انتخاب کرده همان، وگرنه مقدار پیش‌فرض
fun partColor(part: ThemePart, fallback: Color): Color =
    swatchOf(ThemeStore.settings.partOf(part).colorIndex) ?: fallback

private fun bgPair(mc: ModeColors, dark: Boolean): Pair<Color, Color> {
    val c = swatchOf(ThemeStore.settings.partOf(ThemePart.BACKGROUND).colorIndex)
        ?: return mc.bgTop to mc.bgBottom
    return if (dark) {
        lerp(c, Color.Black, 0.35f) to lerp(c, Color.Black, 0.7f)
    } else {
        lerp(c, Color.White, 0.3f) to c
    }
}

fun typographyFor(family: FontFamily): Typography {
    val b = Typography()
    fun TextStyle.f(): TextStyle = copy(fontFamily = family)
    return Typography(
        displayLarge = b.displayLarge.f(),
        displayMedium = b.displayMedium.f(),
        displaySmall = b.displaySmall.f(),
        headlineLarge = b.headlineLarge.f(),
        headlineMedium = b.headlineMedium.f(),
        headlineSmall = b.headlineSmall.f(),
        titleLarge = b.titleLarge.f(),
        titleMedium = b.titleMedium.f(),
        titleSmall = b.titleSmall.f(),
        bodyLarge = b.bodyLarge.f(),
        bodyMedium = b.bodyMedium.f(),
        bodySmall = b.bodySmall.f(),
        labelLarge = b.labelLarge.f(),
        labelMedium = b.labelMedium.f(),
        labelSmall = b.labelSmall.f()
    )
}

// ظاهر شیشه‌ای: گرادیان نیمه‌شفاف و حاشیه روشن
fun Modifier.glass(base: Color, shape: Shape): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(base.copy(alpha = 0.92f), base.copy(alpha = 0.78f))))
    .border(
        width = 1.dp,
        brush = Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.06f))
        ),
        shape = shape
    )

// تم کامل برنامه: رنگ، فونت، اندازه، شکل گوشه‌ها
@Composable
fun HesabyarTheme(content: @Composable () -> Unit) {
    val s = ThemeStore.settings
    val dark = isDarkNow()
    val mc = currentModeColors()

    val primaryOverride = swatchOf(s.partOf(ThemePart.BUTTONS).colorIndex)
    val cardOverride = swatchOf(s.partOf(ThemePart.CARDS).colorIndex)
    val textOverride = swatchOf(s.partOf(ThemePart.TEXT).colorIndex)

    val primary = primaryOverride ?: mc.primary
    val onPrimary = primaryOverride?.let { onColorFor(it) }
        ?: if (dark) Color(0xFF0B1220) else Color.White
    val surface = cardOverride?.copy(alpha = 0.88f) ?: mc.surface.copy(alpha = 0.85f)
    val defaultOn = if (dark) Color(0xFFE5E7EB) else Color(0xFF111827)
    val onSurface = textOverride
        ?: cardOverride?.let { onColorFor(it) }
        ?: defaultOn
    val onSurfaceVariant = textOverride?.copy(alpha = 0.75f)
        ?: if (dark) Color(0xFF9CA3AF) else Color(0xFF4B5563)
    val onBackground = textOverride ?: defaultOn
    val bgBottom = bgPair(mc, dark).second

    val scheme = if (dark) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = mc.container,
            onPrimaryContainer = mc.onContainer,
            background = bgBottom,
            surface = surface,
            onBackground = onBackground,
            onSurface = onSurface,
            onSurfaceVariant = onSurfaceVariant
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = mc.container,
            onPrimaryContainer = mc.onContainer,
            background = bgBottom,
            surface = surface,
            onBackground = onBackground,
            onSurface = onSurface,
            onSurfaceVariant = onSurfaceVariant
        )
    }

    val family = FONT_FAMILIES[s.fontIndex.coerceIn(0, FONT_FAMILIES.lastIndex)]
    val scale = FONT_SCALES[s.scaleIndex.coerceIn(0, FONT_SCALES.lastIndex)]
    val radius = CORNERS[s.cornerIndex.coerceIn(0, CORNERS.lastIndex)].dp

    val shapes = Shapes(
        extraSmall = RoundedCornerShape(radius / 2),
        small = RoundedCornerShape(radius / 2),
        medium = RoundedCornerShape(radius),
        large = RoundedCornerShape(radius),
        extraLarge = RoundedCornerShape(radius)
    )

    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * scale)
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typographyFor(family),
            shapes = shapes,
            content = content
        )
    }
}

// اعمال فونت و اندازه مخصوص یک بخش روی محتوای داخلش
@Composable
fun ThemePartScope(part: ThemePart, content: @Composable () -> Unit) {
    val ps = ThemeStore.settings.partOf(part)
    if (ps.fontIndex < 0 && ps.scaleIndex < 0) {
        content()
        return
    }
    val typo = if (ps.fontIndex >= 0) {
        typographyFor(FONT_FAMILIES[ps.fontIndex.coerceIn(0, FONT_FAMILIES.lastIndex)])
    } else {
        MaterialTheme.typography
    }
    val extra = if (ps.scaleIndex >= 0) {
        FONT_SCALES[ps.scaleIndex.coerceIn(0, FONT_SCALES.lastIndex)]
    } else {
        1f
    }
    val outerDensity = LocalDensity.current
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        typography = typo,
        shapes = MaterialTheme.shapes
    ) {
        CompositionLocalProvider(
            LocalDensity provides Density(outerDensity.density, outerDensity.fontScale * extra)
        ) {
            content()
        }
    }
}

// پس‌زمینه گرادیان با دو لکه نوری نرم برای حس شیشه‌ای
@Composable
fun AppBackground(content: @Composable () -> Unit) {
    val mc = currentModeColors()
    val dark = isDarkNow()
    val (top, bottom) = bgPair(mc, dark)
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(top, bottom)))
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = (-90).dp, y = (-70).dp)
                .size(300.dp)
                .background(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.28f), Color.Transparent)),
                    CircleShape
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 90.dp, y = 80.dp)
                .size(320.dp)
                .background(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.16f), Color.Transparent)),
                    CircleShape
                )
        )
        content()
    }
}
