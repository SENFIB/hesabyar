package com.hesabyar.app.ui

import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.os.LocaleListCompat

private const val PAGE_HOME = ""

private val FISCAL_VALUES = listOf(CalendarType.GREGORIAN, CalendarType.PERSIAN, CalendarType.HIJRI)

private fun parentPage(page: String): String =
    if (page.startsWith("part_")) "colors" else PAGE_HOME

private fun chevronFor(lang: String): ImageVector =
    if (lang == "en") Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowLeft

// ---------- صفحه اصلی تنظیمات و زیرصفحه‌ها ----------

@Composable
fun SettingsScreen(vm: MainViewModel, regionType: CalendarType) {
    var page by rememberSaveable { mutableStateOf(PAGE_HOME) }
    BackHandler(enabled = page != PAGE_HOME) { page = parentPage(page) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (page != PAGE_HOME) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { page = parentPage(page) }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = null)
                }
                Text(
                    text = pageTitle(page),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when {
                page == PAGE_HOME -> SettingsHome(vm = vm, onOpen = { page = it })
                page == "look" -> LookPage()
                page == "colors" -> ColorsPage(onOpen = { page = it })
                page.startsWith("part_") -> ThemePart.fromKey(page)?.let { PartPage(it) }
                page == "lang" -> LanguagePage()
                page == "lock" -> LockPage()
                page == "clock" -> ClockPage(vm)
                page == "fiscal" -> FiscalPage(vm)
                page == "weather" -> WeatherPage()
                page == "backup" -> BackupSection()
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun pageTitle(page: String): String {
    val part = ThemePart.fromKey(page)
    if (part != null) return part.label.of(currentLang())
    return when (page) {
        "look" -> tr("ظاهر", "Appearance", "المظهر")
        "colors" -> tr("شخصی‌سازی رنگ‌ها", "Colors", "تخصيص الألوان")
        "lang" -> tr("زبان", "Language", "اللغة")
        "lock" -> tr("رمز و قفل", "Password and lock", "كلمة المرور والقفل")
        "clock" -> tr("ساعت و تاریخ", "Clock and date", "الساعة والتاريخ")
        "fiscal" -> tr("تقویم سال مالی", "Fiscal calendar", "تقويم السنة المالية")
        "weather" -> tr("آب‌وهوا و موقعیت", "Weather and location", "الطقس والموقع")
        "backup" -> tr("پشتیبان‌گیری و بازیابی", "Backup and restore", "النسخ الاحتياطي والاستعادة")
        else -> ""
    }
}

@Composable
private fun delayLabels(): List<String> = listOf(
    tr("فوری (بعد از خروج)", "Immediately (after leaving)", "فوراً (بعد المغادرة)"),
    tr("بعد از ۱ دقیقه", "After 1 minute", "بعد دقيقة"),
    tr("بعد از ۵ دقیقه", "After 5 minutes", "بعد 5 دقائق"),
    tr("بعد از ۱۵ دقیقه", "After 15 minutes", "بعد 15 دقيقة"),
    tr("بعد از ۱ ساعت", "After 1 hour", "بعد ساعة")
)

@Composable
private fun fiscalLabels(): List<String> = listOf(
    tr("میلادی", "Gregorian", "ميلادي"),
    tr("شمسی", "Persian", "شمسي"),
    tr("قمری", "Hijri", "هجري")
)

@Composable
private fun timeSourceLabels(): List<String> = listOf(
    tr("خودکار (بر اساس زبان)", "Automatic (by language)", "تلقائي (حسب اللغة)"),
    tr("تنظیمات گوشی", "Phone settings", "إعدادات الهاتف")
)

@Composable
private fun SettingsHome(vm: MainViewModel, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val lang = currentLang()
    val ts = ThemeStore.settings
    val fiscal by vm.fiscal.collectAsState()
    val deviceClock by vm.deviceClock.collectAsState()
    val is24 by ClockFormat.is24h.collectAsState()
    val place = (WeatherStore.state as? WeatherState.Ok)?.place ?: WeatherStore.savedPlace(ctx)

    val modeName = MODE_LABELS.labelsFor(lang)[ts.mode.coerceIn(0, MODE_LABELS.lastIndex)]
    val fontName = FONT_LABELS.labelsFor(lang)[ts.fontIndex.coerceIn(0, FONT_LABELS.lastIndex)]
    val paletteName = PALETTES[ts.paletteIndex.coerceIn(0, PALETTES.lastIndex)].name.of(lang)
    val langName = when (lang) {
        "fa" -> "فارسی"
        "ar" -> "العربية"
        else -> "English"
    }

    val lockOn = PinStore.isEnabled(ctx)
    val delays = delayLabels()
    val lockSub = if (lockOn) {
        tr("فعال", "On", "مفعّل") + " · " + delays[PinStore.delayIndex(ctx)]
    } else {
        tr("غیرفعال", "Off", "معطّل")
    }

    val sourceLabels = timeSourceLabels()
    val clockSub = (if (deviceClock) sourceLabels[1] else sourceLabels[0]) + " · " +
        (if (is24) tr("۲۴ ساعته", "24-hour", "24 ساعة") else tr("۱۲ ساعته", "12-hour", "12 ساعة"))

    val fiscalNames = fiscalLabels()
    val fiscalSub = fiscalNames[FISCAL_VALUES.indexOf(fiscal).coerceAtLeast(0)]

    val placeSub = place.ifBlank { tr("تنظیم نشده", "Not set", "غير محدد") }

    NavCard(
        title = tr("ظاهر", "Appearance", "المظهر"),
        subtitle = "$modeName · $fontName"
    ) { onOpen("look") }

    NavCard(
        title = tr("شخصی‌سازی رنگ‌ها", "Colors", "الألوان"),
        subtitle = tr("طرح: ", "Scheme: ", "النظام: ") + paletteName
    ) { onOpen("colors") }

    NavCard(title = tr("زبان", "Language", "اللغة"), subtitle = langName) { onOpen("lang") }

    NavCard(
        title = tr("رمز و قفل", "Password and lock", "كلمة المرور والقفل"),
        subtitle = lockSub
    ) { onOpen("lock") }

    NavCard(
        title = tr("ساعت و تاریخ", "Clock and date", "الساعة والتاريخ"),
        subtitle = clockSub
    ) { onOpen("clock") }

    NavCard(
        title = tr("تقویم سال مالی", "Fiscal calendar", "تقويم السنة المالية"),
        subtitle = fiscalSub
    ) { onOpen("fiscal") }

    NavCard(
        title = tr("آب‌وهوا و موقعیت", "Weather and location", "الطقس والموقع"),
        subtitle = placeSub
    ) { onOpen("weather") }

    NavCard(
        title = tr("پشتیبان‌گیری و بازیابی", "Backup and restore", "النسخ الاحتياطي والاستعادة"),
        subtitle = tr("خروجی و بازیابی فایل", "Export and restore a file", "تصدير واستعادة ملف")
    ) { onOpen("backup") }

    OutlinedButton(
        onClick = { ThemeStore.save(ctx, ThemeSettings()) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(tr("بازگشت به تنظیمات پیش‌فرض", "Reset to defaults", "استعادة الإعدادات الافتراضية"))
    }
}

@Composable
private fun NavCard(title: String, subtitle: String, onClick: () -> Unit) {
    val lang = currentLang()
    Card(modifier = Modifier
        .fillMaxWidth()
        .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                imageVector = chevronFor(lang),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------- ظاهر ----------

@Composable
private fun LookPage() {
    val ctx = LocalContext.current
    val lang = currentLang()
    val ts = ThemeStore.settings
    val modeLabels = MODE_LABELS.labelsFor(lang)
    val fontLabels = FONT_LABELS.labelsFor(lang)
    val sizeLabels = SCALE_LABELS.labelsFor(lang)
    val cornerLabels = CORNER_LABELS.labelsFor(lang)
    val tabLabels = listOf(
        tr("پایین صفحه", "Bottom of screen", "أسفل الشاشة"),
        tr("زیر هدر", "Under header", "تحت الرأس")
    )

    SectionCard(tr("حالت نمایش", "Display mode", "وضع العرض")) {
        ChoiceRow(labels = modeLabels, selected = ts.mode) { i ->
            ThemeStore.save(ctx, ts.copy(mode = i))
        }
    }

    SectionCard(tr("فونت کلی", "Overall font", "الخط العام")) {
        ChoiceRow(labels = fontLabels, selected = ts.fontIndex) { i ->
            ThemeStore.save(ctx, ts.copy(fontIndex = i))
        }
    }

    SectionCard(tr("اندازه فونت کلی", "Overall font size", "حجم الخط العام")) {
        ChoiceRow(labels = sizeLabels, selected = ts.scaleIndex) { i ->
            ThemeStore.save(ctx, ts.copy(scaleIndex = i))
        }
    }

    SectionCard(tr("شکل کارت‌ها", "Card shape", "شكل البطاقات")) {
        ChoiceRow(labels = cornerLabels, selected = ts.cornerIndex) { i ->
            ThemeStore.save(ctx, ts.copy(cornerIndex = i))
        }
    }

    SectionCard(tr("جای نوار تب‌ها", "Tab bar position", "موضع شريط التبويب")) {
        ChoiceRow(labels = tabLabels, selected = if (ts.tabsTop) 1 else 0) { i ->
            ThemeStore.save(ctx, ts.copy(tabsTop = i == 1))
        }
    }
}

// ---------- شخصی‌سازی رنگ‌ها ----------

@Composable
private fun ColorsPage(onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val lang = currentLang()
    val ts = ThemeStore.settings
    val chevron = chevronFor(lang)

    val customFontText = tr(" · فونت جدا", " · own font", " · خط مستقل")
    val colorDefault = tr("رنگ: پیش‌فرض طرح", "Color: scheme default", "اللون: افتراضي النظام")
    val colorCustom = tr("رنگ سفارشی", "Custom color", "لون مخصص")

    SectionCard(tr("طرح کلی", "Overall scheme", "النظام العام")) {
        PaletteRow(selected = ts.paletteIndex, lang = lang) { i ->
            ThemeStore.save(ctx, ts.copy(paletteIndex = i))
        }
        Text(
            text = tr(
                "با انتخاب طرح، رنگ همه بخش‌ها عوض می‌شود، مگر آنکه برای بخشی رنگ جدا انتخاب کرده باشید.",
                "Choosing a scheme changes all parts, unless you picked a separate color for a part.",
                "عند اختيار نظام، تتغير ألوان جميع الأجزاء ما لم تختر لونًا منفصلًا لجزء ما."
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    SectionCard(tr("بخش‌های برنامه", "App parts", "أجزاء التطبيق")) {
        ThemePart.entries.forEach { part ->
            val ps = ts.partOf(part)
            val preview = swatchOf(ps.colorIndex) ?: MaterialTheme.colorScheme.primary
            val colorSub = if (ps.colorIndex < 0) colorDefault else colorCustom
            val fontSub = if (part.supportsFont && (ps.fontIndex >= 0 || ps.scaleIndex >= 0)) customFontText else ""
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onOpen("part_${part.name}") }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(preview)
                        .border(1.dp, Color.Gray.copy(alpha = 0.5f), CircleShape)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(part.label.of(lang), style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = colorSub + fontSub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = chevron,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    OutlinedButton(
        onClick = { ThemeStore.save(ctx, ts.copy(parts = emptyMap())) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(tr("بازگرداندن همه بخش‌ها به پیش‌فرض", "Reset all parts to default", "إعادة كل الأجزاء إلى الافتراضي"))
    }
}

@Composable
private fun PartPage(part: ThemePart) {
    val ctx = LocalContext.current
    val lang = currentLang()
    val ts = ThemeStore.settings
    val ps = ts.partOf(part)
    val save: (PartStyle) -> Unit = { ThemeStore.save(ctx, ts.withPart(part, it)) }

    val defaultColor = tr("پیش‌فرض طرح", "Scheme default", "افتراضي النظام")
    val sameAsAll = tr("همان کلی", "Same as overall", "كما في الإعداد العام")
    val fontNames = listOf(sameAsAll) + FONT_LABELS.labelsFor(lang)
    val sizeNames = listOf(sameAsAll) + SCALE_LABELS.labelsFor(lang)
    val resetText = tr("بازگشت این بخش به پیش‌فرض", "Reset this part to default", "إعادة هذا الجزء إلى الافتراضي")

    SectionCard(tr("رنگ", "Color", "اللون")) {
        FilterChip(
            selected = ps.colorIndex < 0,
            onClick = { save(ps.copy(colorIndex = -1)) },
            label = { Text(defaultColor) }
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SWATCHES.forEachIndexed { i, c ->
                val selected = ps.colorIndex == i
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Gray.copy(alpha = 0.5f),
                            shape = CircleShape
                        )
                        .clickable { save(ps.copy(colorIndex = i)) }
                )
            }
        }
    }

    if (part.supportsFont) {
        SectionCard(tr("فونت این بخش", "Font of this part", "خط هذا الجزء")) {
            ChoiceRow(labels = fontNames, selected = ps.fontIndex + 1) { i ->
                save(ps.copy(fontIndex = i - 1))
            }
        }
        SectionCard(tr("اندازه متن این بخش", "Text size of this part", "حجم نص هذا الجزء")) {
            ChoiceRow(labels = sizeNames, selected = ps.scaleIndex + 1) { i ->
                save(ps.copy(scaleIndex = i - 1))
            }
        }
    }

    OutlinedButton(
        onClick = { save(PartStyle()) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(resetText)
    }
}

// ---------- زبان ----------

@Composable
private fun LanguagePage() {
    val lang = currentLang()
    val langIndex = when (lang) {
        "fa" -> 0
        "ar" -> 2
        else -> 1
    }
    SectionCard(tr("زبان برنامه", "App language", "لغة التطبيق")) {
        ChoiceRow(labels = listOf("فارسی", "English", "العربية"), selected = langIndex) { i ->
            val tag = when (i) {
                0 -> "fa"
                2 -> "ar"
                else -> "en"
            }
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        }
    }
}
