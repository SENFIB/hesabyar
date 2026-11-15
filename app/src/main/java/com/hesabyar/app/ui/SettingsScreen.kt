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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.hesabyar.app.R

@Composable
fun SettingsScreen(vm: MainViewModel, regionType: CalendarType) {
    val context = LocalContext.current
    val lang = currentLang()
    val fiscalSaved by vm.fiscal.collectAsState()
    val deviceClock by vm.deviceClock.collectAsState()
    val currentFiscal = fiscalSaved ?: regionType
    val s = ThemeStore.settings
    val weather = WeatherStore.state

    var showManual by remember { mutableStateOf(false) }
    var gpsMessage by remember { mutableStateOf<String?>(null) }

    val gpsLabel = tr("موقعیت من", "My location", "موقعي")
    val gpsFail = tr(
        "GPS فعال نیست یا اجازه داده نشد. شهر را دستی وارد کنید.",
        "GPS is off or permission was denied. Enter the city manually.",
        "GPS غير مفعّل أو لم يُسمح به. أدخل المدينة يدوياً."
    )
    val placeName = (weather as? WeatherState.Ok)?.place
        ?: WeatherStore.savedPlace(context)
        ?: "—"

    val gps = rememberGpsAction(gpsLabel) { ok ->
        gpsMessage = if (ok) null else gpsFail
    }

    val modeLabels = MODE_LABELS.labelsFor(lang)
    val fontLabels = FONT_LABELS.labelsFor(lang)
    val scaleLabels = SCALE_LABELS.labelsFor(lang)
    val cornerLabels = CORNER_LABELS.labelsFor(lang)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // زبان
        SectionCard(tr("زبان", "Language", "اللغة")) {
            OptionRow(label = "فارسی", selected = lang == "fa") { setLanguage("fa") }
            OptionRow(label = "English", selected = lang == "en") { setLanguage("en") }
            OptionRow(label = "العربية", selected = lang == "ar") { setLanguage("ar") }
        }

        // تم روشن / تاریک
        SectionCard(tr("حالت نمایش", "Display mode", "وضع العرض")) {
            ChoiceRow(options = modeLabels, selected = s.mode) { i ->
                ThemeStore.save(context, s.copy(mode = i))
            }
        }

        // طرح رنگی
        SectionCard(tr("طرح رنگی", "Color scheme", "المخطط اللوني")) {
            PaletteRow(selected = s.paletteIndex, lang = lang) { i ->
                ThemeStore.save(context, s.copy(paletteIndex = i))
            }
        }

        // فونت
        SectionCard(tr("فونت", "Font", "الخط")) {
            ChoiceRow(options = fontLabels, selected = s.fontIndex) { i ->
                ThemeStore.save(context, s.copy(fontIndex = i))
            }
        }

        // اندازه فونت
        SectionCard(tr("اندازه فونت", "Font size", "حجم الخط")) {
            ChoiceRow(options = scaleLabels, selected = s.scaleIndex) { i ->
                ThemeStore.save(context, s.copy(scaleIndex = i))
            }
        }

        // شکل گوشه کارت‌ها
        SectionCard(tr("شکل کارت‌ها", "Card shape", "شكل البطاقات")) {
            ChoiceRow(options = cornerLabels, selected = s.cornerIndex) { i ->
                ThemeStore.save(context, s.copy(cornerIndex = i))
            }
        }

        // زمان و تاریخ هدر
        SectionCard(tr("زمان و تاریخ هدر", "Header date and time", "الوقت والتاريخ في الرأس")) {
            OptionRow(
                label = tr("خودکار (بر اساس زبان)", "Automatic (based on language)", "تلقائي (حسب اللغة)"),
                selected = !deviceClock
            ) { vm.setDeviceClock(false) }
            OptionRow(
                label = tr("تنظیمات گوشی", "Phone settings", "إعدادات الهاتف"),
                selected = deviceClock
            ) { vm.setDeviceClock(true) }
        }

        // تقویم سال مالی
        SectionCard(tr("تقویم سال مالی", "Fiscal year calendar", "التقويم المالي")) {
            Text(
                text = stringResource(R.string.fiscal_hint),
                style = MaterialTheme.typography.bodySmall
            )
            CalendarType.entries.forEach { type ->
                OptionRow(
                    label = stringResource(type.label),
                    selected = currentFiscal == type
                ) { vm.setFiscal(type) }
            }
        }

        // موقعیت آب‌وهوا
        SectionCard(tr("آب‌وهوا و موقعیت", "Weather and location", "الطقس والموقع")) {
            Text(
                text = tr("شهر فعلی: ", "Current city: ", "المدينة الحالية: ") + placeName,
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = gps) {
                    Text(tr("GPS", "GPS", "GPS"))
                }
                OutlinedButton(onClick = { showManual = true }) {
                    Text(tr("وارد کردن شهر", "Enter city", "إدخال المدينة"))
                }
            }
            gpsMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }

        // بازگشت به پیش‌فرض
        OutlinedButton(
            onClick = { ThemeStore.save(context, ThemeSettings()) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(tr("بازگشت به تنظیمات پیش‌فرض", "Reset to defaults", "استعادة الإعدادات الافتراضية"))
        }
    }

    if (showManual) {
        ManualCityDialog(
            onDismiss = { showManual = false },
            onSaved = { showManual = false }
        )
    }
}

private fun setLanguage(tag: String) {
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label)
    }
}

@Composable
private fun ChoiceRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEachIndexed { i, label ->
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
        PALETTES.forEachIndexed { i, p ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable { onSelect(i) }
                    .padding(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(p.light.primary)
                        .border(
                            width = if (i == selected) 3.dp else 0.dp,
                            color = MaterialTheme.colorScheme.onSurface,
                            shape = CircleShape
                        )
                )
                Text(p.name.of(lang), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
