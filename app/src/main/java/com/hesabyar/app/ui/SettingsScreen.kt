package com.hesabyar.app.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat

@Composable
fun SettingsScreen(vm: MainViewModel, regionType: CalendarType) {
    val ctx = LocalContext.current
    val lang = currentLang()
    val ts = ThemeStore.settings
    val fiscal by vm.fiscal.collectAsState()
    val deviceClock by vm.deviceClock.collectAsState()

    val weatherState = WeatherStore.state
    val place = (weatherState as? WeatherState.Ok)?.place ?: WeatherStore.savedPlace(ctx)

    var showManual by rememberSaveable { mutableStateOf(false) }
    val gps = rememberGpsAction(
        label = tr("موقعیت من", "My location", "موقعي"),
        onDone = {},
        onDenied = { showManual = true }
    )

    val modeLabels = listOf(
        tr("بر اساس گوشی", "Follow phone", "حسب الهاتف"),
        tr("روشن", "Light", "فاتح"),
        tr("تاریک", "Dark", "داكن")
    )
    val fontLabels = listOf(
        tr("وزیرمتن (پیش‌فرض)", "Vazirmatn (default)", "وزير متن (افتراضي)"),
        tr("ساده", "Sans", "بسيط"),
        tr("سریف", "Serif", "سيريف"),
        tr("ماشینی", "Monospace", "أحادي")
    )
    val sizeLabels = listOf(
        tr("کوچک", "Small", "صغير"),
        tr("متوسط", "Medium", "متوسط"),
        tr("بزرگ", "Large", "كبير"),
        tr("خیلی بزرگ", "Extra large", "كبير جدًا")
    )
    val cornerLabels = listOf(
        tr("تیز", "Sharp", "حاد"),
        tr("معمولی", "Normal", "عادي"),
        tr("گرد", "Round", "دائري")
    )
    val timeLabels = listOf(
        tr("خودکار (بر اساس زبان)", "Automatic (by language)", "تلقائي (حسب اللغة)"),
        tr("تنظیمات گوشی", "Phone settings", "إعدادات الهاتف")
    )
    val fiscalValues = listOf(CalendarType.GREGORIAN, CalendarType.PERSIAN, CalendarType.HIJRI)
    val fiscalLabels = listOf(
        tr("میلادی", "Gregorian", "ميلادي"),
        tr("شمسی", "Persian", "شمسي"),
        tr("قمری", "Hijri", "هجري")
    )
    val langIndex = when (lang) {
        "fa" -> 0
        "ar" -> 2
        else -> 1
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionCard(title = tr("زبان", "Language", "اللغة")) {
            ChoiceRow(
                labels = listOf("فارسی", "English", "العربية"),
                selected = langIndex
            ) { i ->
                val tag = when (i) {
                    0 -> "fa"
                    2 -> "ar"
                    else -> "en"
                }
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }
        }

        SectionCard(title = tr("حالت نمایش", "Display mode", "وضع العرض")) {
            ChoiceRow(labels = modeLabels, selected = ts.mode) { i ->
                ThemeStore.save(ctx, ts.copy(mode = i))
            }
        }

        SectionCard(title = tr("طرح رنگی", "Color scheme", "نظام الألوان")) {
            PaletteRow(
                selected = ts.paletteIndex,
                lang = lang
            ) { i ->
                ThemeStore.save(ctx, ts.copy(paletteIndex = i))
            }
        }

        SectionCard(title = tr("فونت", "Font", "الخط")) {
            ChoiceRow(labels = fontLabels, selected = ts.fontIndex) { i ->
                ThemeStore.save(ctx, ts.copy(fontIndex = i))
            }
        }

        SectionCard(title = tr("اندازه فونت", "Font size", "حجم الخط")) {
            ChoiceRow(labels = sizeLabels, selected = ts.scaleIndex) { i ->
                ThemeStore.save(ctx, ts.copy(scaleIndex = i))
            }
        }

        SectionCard(title = tr("شکل کارت‌ها", "Card shape", "شكل البطاقات")) {
            ChoiceRow(labels = cornerLabels, selected = ts.cornerIndex) { i ->
                ThemeStore.save(ctx, ts.copy(cornerIndex = i))
            }
        }

        SectionCard(title = tr("زمان و تاریخ هدر", "Header date and time", "تاريخ ووقت الرأس")) {
            ChoiceRow(
                labels = timeLabels,
                selected = if (deviceClock) 1 else 0
            ) { i ->
                vm.setDeviceClock(i == 1)
            }
        }

        SectionCard(title = tr("ساعت ۱۲ یا ۲۴ ساعته", "12 or 24-hour clock", "ساعة ١٢ أو ٢٤")) {
            ClockFormatRow()
        }

        SectionCard(title = tr("تقویم سال مالی", "Fiscal calendar", "تقويم السنة المالية")) {
            ChoiceRow(
                labels = fiscalLabels,
                selected = fiscalValues.indexOf(fiscal).coerceAtLeast(0)
            ) { i ->
                vm.setFiscal(fiscalValues[i])
            }
        }

        SectionCard(title = tr("آب‌وهوا و موقعیت", "Weather and location", "الطقس والموقع")) {
            Text(
                text = tr("شهر فعلی: ", "Current city: ", "المدينة الحالية: ") +
                    place.ifBlank { tr("تنظیم نشده", "Not set", "غير محدد") },
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = gps, modifier = Modifier.fillMaxWidth()) {
                Text(tr("استفاده از موقعیت من (GPS)", "Use my location (GPS)", "استخدام موقعي (GPS)"))
            }
            OutlinedButton(onClick = { showManual = true }, modifier = Modifier.fillMaxWidth()) {
                Text(tr("جست‌وجوی شهر", "Search city", "البحث عن مدينة"))
            }
        }

        BackupSection()

        OutlinedButton(
            onClick = { ThemeStore.save(ctx, ThemeSettings()) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(tr("بازگشت به تنظیمات پیش‌فرض", "Reset to defaults", "استعادة الإعدادات الافتراضية"))
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showManual) {
        ManualCityDialog(
            onDismiss = { showManual = false },
            onSaved = { showManual = false }
        )
    }
}

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

@Composable
private fun ChoiceRow(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
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
