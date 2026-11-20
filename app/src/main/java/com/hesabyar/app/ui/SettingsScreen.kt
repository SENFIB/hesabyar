package com.hesabyar.app.ui

import android.content.Context
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.os.LocaleListCompat

private const val PAGE_HOME = ""
private const val PREFS_NAME = "hesabyar_prefs"
private const val FISCAL_AUTO_KEY = "fiscal_auto"
private val FISCAL_VALUES = listOf(CalendarType.GREGORIAN, CalendarType.PERSIAN, CalendarType.HIJRI)

// ---------- تقویم سال مالی: حالت خودکار (بر اساس زبان) ----------
fun fiscalAutoOn(ctx: Context): Boolean =
    ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(FISCAL_AUTO_KEY, true)

fun setFiscalAutoOn(ctx: Context, on: Boolean) {
    ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(FISCAL_AUTO_KEY, on)
        .apply()
}

private fun parentPage(page: String): String =
    if (page.startsWith("part_")) "colors" else PAGE_HOME

// فلش «به جلو»: در فارسی/عربی (RTL) به چپ و در انگلیسی (LTR) به راست
@Composable
private fun chevronIcon(): ImageVector =
    if (LocalLayoutDirection.current == LayoutDirection.Rtl) Icons.Filled.KeyboardArrowLeft
    else Icons.Filled.KeyboardArrowRight

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
    val fiscalAutoLabel = tr("بر اساس زبان", "Based on language", "حسب اللغة")
    val fiscalSub = (if (fiscalAutoOn(ctx)) fiscalAutoLabel + " · " else "") +
            fiscalNames[FISCAL_VALUES.indexOf(fiscal).coerceAtLeast(0)]

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

    // فوتر: نسخه برنامه، کد، اندروید و مدل گوشی
    AppVersionFooter()
}

@Composable
private fun NavCard(title: String, subtitle: String, onClick: () -> Unit) {
    val chevron = chevronIcon()
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
                imageVector = chevron,
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
    val chevron = chevronIcon()
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

// ---------- رمز و قفل ----------
@Composable
private fun LockPage() {
    val ctx = LocalContext.current
    val delays = delayLabels()
    var enabled by remember { mutableStateOf(PinStore.isEnabled(ctx)) }
    var delayIdx by remember { mutableStateOf(PinStore.delayIndex(ctx)) }
    var showSetPin by rememberSaveable { mutableStateOf(false) }
    var showCheckPin by rememberSaveable { mutableStateOf(false) }
    val checkTitle = tr(
        "برای خاموش کردن قفل، رمز فعلی را وارد کنید",
        "Enter the current password to turn the lock off",
        "أدخل كلمة المرور الحالية لإيقاف القفل"
    )

    SectionCard(tr("قفل برنامه", "App lock", "قفل التطبيق")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tr("قفل با رمز عبور", "Lock with password", "القفل بكلمة المرور"),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = enabled,
                onCheckedChange = { wantOn ->
                    if (wantOn) showSetPin = true else showCheckPin = true
                }
            )
        }
        Text(
            text = tr(
                "اختیاری است. اگر خاموش باشد، برنامه بدون رمز باز می‌شود.",
                "Optional. When off, the app opens without a password.",
                "اختياري. عند الإيقاف يفتح التطبيق بدون كلمة مرور."
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (enabled) {
            Text(
                text = tr(
                    "با اثر انگشت باز می‌شود. رمز عبور فقط با دکمه «ورود با رمز عبور» استفاده می‌شود.",
                    "Opens with fingerprint. The password is used only with the “Use password” button.",
                    "يُفتح بالبصمة. تُستخدم كلمة المرور فقط بزر «استخدام كلمة المرور»."
                ),
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(
                onClick = { showSetPin = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(tr("تغییر رمز عبور", "Change password", "تغيير كلمة المرور"))
            }
        }
    }

    if (enabled) {
        SectionCard(tr("زمان قفل شدن", "Lock timing", "وقت القفل")) {
            Text(
                text = tr(
                    "بعد از بسته شدن یا رفتن برنامه به پس‌زمینه:",
                    "After the app is closed or sent to the background:",
                    "بعد إغلاق التطبيق أو نقله إلى الخلفية:"
                ),
                style = MaterialTheme.typography.bodySmall
            )
            ChoiceRow(labels = delays, selected = delayIdx) { i ->
                PinStore.setDelayIndex(ctx, i)
                delayIdx = i
            }
        }
    }

    if (showSetPin) {
        Dialog(
            onDismissRequest = { showSetPin = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                PinSetup(
                    onDone = {
                        PinStore.setEnabled(ctx, true)
                        enabled = true
                        showSetPin = false
                    },
                    onCancel = { showSetPin = false }
                )
            }
        }
    }

    if (showCheckPin) {
        PinCheckDialog(
            title = checkTitle,
            onDismiss = { showCheckPin = false },
            onOk = {
                PinStore.setEnabled(ctx, false)
                enabled = false
                showCheckPin = false
            }
        )
    }
}

// ---------- ساعت و تاریخ ----------
@Composable
private fun ClockPage(vm: MainViewModel) {
    val deviceClock by vm.deviceClock.collectAsState()
    val sourceLabels = timeSourceLabels()

    SectionCard(tr("منبع تاریخ و ساعت هدر", "Header date and time source", "مصدر التاريخ والوقت في الرأس")) {
        ChoiceRow(labels = sourceLabels, selected = if (deviceClock) 1 else 0) { i ->
            vm.setDeviceClock(i == 1)
        }
    }
    SectionCard(tr("ساعت ۱۲ یا ۲۴ ساعته", "12 or 24-hour clock", "ساعة ١٢ أو ٢٤")) {
        ClockFormatRow()
    }
}

// ---------- تقویم سال مالی ----------
@Composable
private fun FiscalPage(vm: MainViewModel) {
    val ctx = LocalContext.current
    val lang = currentLang()
    val fiscal by vm.fiscal.collectAsState()
    var auto by remember { mutableStateOf(fiscalAutoOn(ctx)) }

    val autoLabel = tr("بر اساس زبان", "Based on language", "حسب اللغة")
    val labels = listOf(autoLabel) + fiscalLabels()
    val autoHint = tr(
        "تا وقتی یکی از تقویم‌ها را دستی انتخاب نکنید، تقویم سال مالی با زبان برنامه عوض می‌شود.",
        "Until you pick a calendar manually, the fiscal calendar follows the app language.",
        "حتى تختار تقويمًا يدوياً، يتبع تقويم السنة المالية لغة التطبيق."
    )

    val idx = FISCAL_VALUES.indexOf(fiscal)
    val selectedIndex = if (auto) 0 else (idx + 1).coerceAtLeast(1)

    SectionCard(tr("تقویم سال مالی", "Fiscal calendar", "تقويم السنة المالية")) {
        ChoiceRow(labels = labels, selected = selectedIndex) { i ->
            if (i == 0) {
                setFiscalAutoOn(ctx, true)
                auto = true
                vm.setFiscal(CalendarType.forLanguage(lang))
            } else {
                setFiscalAutoOn(ctx, false)
                auto = false
                vm.setFiscal(FISCAL_VALUES[i - 1])
            }
        }
        Text(
            text = autoHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------- آب‌وهوا و موقعیت (شامل به‌روزرسانی خودکار) ----------
@Composable
private fun WeatherPage() {
    val ctx = LocalContext.current
    val weatherState = WeatherStore.state
    val place = (weatherState as? WeatherState.Ok)?.place ?: WeatherStore.savedPlace(ctx)
    var showManual by rememberSaveable { mutableStateOf(false) }
    var autoHours by remember { mutableStateOf(WeatherAuto.hours(ctx)) }

    val gps = rememberGpsAction(
        label = tr("موقعیت من", "My location", "موقعي"),
        onDone = {},
        onDenied = { showManual = true }
    )

    val currentText = tr("شهر فعلی: ", "Current city: ", "المدينة الحالية: ") +
            place.ifBlank { tr("تنظیم نشده", "Not set", "غير محدد") }

    val autoOptions: List<Pair<Int, String>> = listOf(
        0 to tr("خاموش", "Off", "إيقاف"),
        1 to tr("هر ۱ ساعت", "Every 1 h", "كل ساعة"),
        2 to tr("هر ۲ ساعت", "Every 2 h", "كل ساعتين"),
        6 to tr("هر ۶ ساعت", "Every 6 h", "كل 6 ساعات"),
        12 to tr("هر ۱۲ ساعت", "Every 12 h", "كل 12 ساعة"),
        24 to tr("هر ۲۴ ساعت", "Every 24 h", "كل 24 ساعة")
    )
    val autoHint = tr(
        "هر چند ساعت یک بار، وقتی گوشی به اینترنت وصل باشد، اطلاعات هوا تازه می‌شود.",
        "Weather refreshes every few hours when the phone is online.",
        "يتم تحديث الطقس كل بضع ساعات عند توفر الإنترنت."
    )

    SectionCard(tr("شهر و موقعیت", "City and location", "المدينة والموقع")) {
        Text(currentText, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = gps, modifier = Modifier.fillMaxWidth()) {
            Text(tr("استفاده از موقعیت من (GPS)", "Use my location (GPS)", "استخدام موقعي (GPS)"))
        }
        OutlinedButton(onClick = { showManual = true }, modifier = Modifier.fillMaxWidth()) {
            Text(tr("جست‌وجوی شهر", "Search city", "البحث عن مدينة"))
        }
    }

    SectionCard(tr("به‌روزرسانی خودکار", "Auto update", "التحديث التلقائي")) {
        Text(autoHint, style = MaterialTheme.typography.bodySmall)
        autoOptions.chunked(3).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rowItems.forEach { (value, optionLabel) ->
                    FilterChip(
                        selected = autoHours == value,
                        onClick = {
                            autoHours = value
                            WeatherAuto.setHours(ctx, value)
                        },
                        label = { Text(optionLabel, maxLines = 1) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    if (showManual) {
        ManualCityDialog(
            onDismiss = { showManual = false },
            onSaved = { showManual = false }
        )
    }
}

// ---------- کمک‌های صفحه ----------
@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = {
                Text(title, style = MaterialTheme.typography.titleMedium)
                content()
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        labels.forEachIndexed { i, label ->
            FilterChip(
                selected = selected == i,
                onClick = { onSelect(i) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
private fun PaletteRow(selected: Int, lang: String, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PALETTES.forEachIndexed { i, palette ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable { onSelect(i) }
                    .padding(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(palette.light.primary)
                        .border(
                            width = if (selected == i) 3.dp else 0.dp,
                            color = MaterialTheme.colorScheme.onSurface,
                            shape = CircleShape
                        )
                )
                Text(
                    text = palette.name.of(lang),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
