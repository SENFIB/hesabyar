package com.hesabyar.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun WeatherScreen(vm: MainViewModel, regionType: CalendarType) {
    val ctx = LocalContext.current
    val lang = currentLang()
    val state = WeatherStore.state
    val hasLoc = WeatherStore.hasLocation(ctx)
    val unitF = WeatherStore.fahrenheit
    var showManual by rememberSaveable { mutableStateOf(false) }
    var gpsBusy by remember { mutableStateOf(false) }
    var gpsReason by remember { mutableStateOf<GpsFail?>(null) }

    LaunchedEffect(Unit) { WeatherStore.loadUnit(ctx) }

    // همه متن‌ها اینجا ساخته می‌شوند
    val tMyLocation = tr("موقعیت من", "My location", "موقعي")
    val tLocating = tr("در حال دریافت موقعیت…", "Getting your location…", "جارٍ تحديد موقعك…")
    val tMsgOff = tr(
        "GPS یا موقعیت گوشی خاموش است. آن را روشن کنید یا شهر را دستی وارد کنید.",
        "GPS or location services are off. Turn them on or type your city.",
        "خدمات الموقع متوقفة. شغّلها أو اكتب اسم المدينة يدوياً."
    )
    val tMsgNotFound = tr(
        "موقعیت پیدا نشد. کمی بعد دوباره امتحان کنید یا شهر را دستی وارد کنید.",
        "Location not found. Try again shortly or type your city.",
        "لم يُعثر على الموقع. حاول مرة أخرى لاحقاً أو اكتب المدينة."
    )
    val tMsgDenied = tr(
        "اجازه موقعیت داده نشد. می‌توانید شهر را دستی وارد کنید.",
        "Location permission was not granted. You can type your city.",
        "لم يُمنح إذن الموقع. يمكنك كتابة المدينة يدوياً."
    )
    val tOpenSettings = tr("باز کردن تنظیمات موقعیت", "Open location settings", "فتح إعدادات الموقع")
    val tUnitC = tr("سلسیوس (°C)", "Celsius (°C)", "مئوية (°C)")
    val tUnitF = tr("فارنهایت (°F)", "Fahrenheit (°F)", "فهرنهايت (°F)")
    val tPlaceUnknown = tr("موقعیت نامشخص", "Unknown place", "موقع غير معروف")
    val tNoCity = tr("شهری انتخاب نشده است.", "No city selected.", "لم تُحدد مدينة.")
    val tLoading = tr("در حال دریافت اطلاعات...", "Loading...", "جارٍ التحميل...")
    val tErrorLoad = tr(
        "دریافت اطلاعات ممکن نیست. اتصال اینترنت را بررسی کنید.",
        "Could not load weather. Check your internet connection.",
        "تعذّر تحميل الطقس. تحقّق من اتصال الإنترنت."
    )
    val tGpsBtn = tr("استفاده از موقعیت من (GPS)", "Use my location (GPS)", "استخدام موقعي (GPS)")
    val tSearchBtn = tr("جست‌وجوی شهر", "Search city", "البحث عن مدينة")
    val tRefresh = tr("به‌روزرسانی", "Refresh", "تحديث")
    val tNext24 = tr("۲۴ ساعت آینده", "Next 24 hours", "الساعات الـ24 القادمة")
    val tForecastTitle = tr("پیش‌بینی ۵ روز آینده", "5-day forecast", "توقعات 5 أيام")

    val gps = rememberGpsAction(
        label = tMyLocation,
        onStart = {
            gpsBusy = true
            gpsReason = null
        },
        onDone = {
            gpsBusy = false
            gpsReason = null
        },
        onDenied = { showManual = true },
        onReason = { r ->
            gpsBusy = false
            gpsReason = r
        }
    )

    val gpsMessage: String? = when (gpsReason) {
        GpsFail.SERVICES_OFF -> tMsgOff
        GpsFail.NOT_FOUND -> tMsgNotFound
        GpsFail.DENIED -> tMsgDenied
        null -> null
    }

    val openLocationSettings: () -> Unit = {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // کارت اصلی: وضعیت فعلی
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    when (state) {
                        is WeatherState.Ok -> {
                            Text(
                                text = state.place.ifBlank { tPlaceUnknown },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                WeatherGlyph(code = state.info.code, size = 72.dp)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = tempText(state.info.current, unitF),
                                        style = MaterialTheme.typography.displayMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = conditionWord(state.info.code, lang),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                            }
                            state.info.days.firstOrNull()?.let { today ->
                                Text(
                                    text = "▼ " + tempText(today.min, unitF) + "   ▲ " + tempText(today.max, unitF),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                        WeatherState.None -> Text(tNoCity)
                        WeatherState.Loading -> Text(tLoading)
                        WeatherState.Error -> Text(tErrorLoad, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        // واحد دما
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = !unitF,
                    onClick = { WeatherStore.setFahrenheit(ctx, false) },
                    label = { Text(tUnitC, maxLines = 1) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = unitF,
                    onClick = { WeatherStore.setFahrenheit(ctx, true) },
                    label = { Text(tUnitF, maxLines = 1) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // دکمه‌های موقعیت
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = gps,
                    enabled = !gpsBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(tGpsBtn)
                }
                OutlinedButton(
                    onClick = { showManual = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(tSearchBtn)
                }
                if (hasLoc) {
                    OutlinedButton(
                        onClick = { WeatherStore.refresh(ctx) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(tRefresh)
                    }
                }
            }
        }

        // وضعیت گرفتن موقعیت و دلیل خطا
        if (gpsBusy || gpsMessage != null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (gpsBusy) {
                            Text(tLocating, style = MaterialTheme.typography.bodyMedium)
                        } else if (gpsMessage != null) {
                            Text(gpsMessage, color = MaterialTheme.colorScheme.error)
                            if (gpsReason == GpsFail.SERVICES_OFF) {
                                OutlinedButton(onClick = openLocationSettings) {
                                    Text(tOpenSettings)
                                }
                            }
                        }
                    }
                }
            }
        }

        // ساعت‌های ۲۴ ساعت آینده
        if (state is WeatherState.Ok && state.info.hours.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(tNext24, style = MaterialTheme.typography.titleMedium)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(state.info.hours) { h ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(h.time, style = MaterialTheme.typography.bodySmall)
                                    WeatherGlyph(code = h.code, size = 28.dp)
                                    Text(
                                        tempText(h.temp, unitF),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // پیش‌بینی ۵ روز
        if (state is WeatherState.Ok) {
            item {
                Text(tForecastTitle, style = MaterialTheme.typography.titleMedium)
            }
            items(state.info.days) { day ->
                ForecastRow(day = day, regionType = regionType, lang = lang, unitF = unitF)
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

@Composable
private fun ForecastRow(day: DayForecast, regionType: CalendarType, lang: String, unitF: Boolean) {
    // تاریخ پیش‌بینی روی UTC حساب می‌شود تا روز جابه‌جا نشود
    val utcZone = android.icu.util.TimeZone.getTimeZone("UTC")
    val dateText = formatWith(day.dateMillis, "EEEE d MMM", regionType.locale, utcZone)
    val rangeText = "▼ " + tempText(day.min, unitF) + "  ▲ " + tempText(day.max, unitF)
    val word = conditionWord(day.code, lang)
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WeatherGlyph(code = day.code, size = 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(dateText, style = MaterialTheme.typography.titleSmall)
                Text(word, style = MaterialTheme.typography.bodyMedium)
            }
            Text(rangeText, fontWeight = FontWeight.Bold)
        }
    }
}

// پنجره اول اجرا: GPS یا ورود دستی
@Composable
fun LocationSetupDialog(onDone: () -> Unit) {
    var showManual by remember { mutableStateOf(false) }
    var reasonText by remember { mutableStateOf<String?>(null) }

    val tMyLocation = tr("موقعیت من", "My location", "موقعي")
    val tOff = tr(
        "GPS یا موقعیت گوشی خاموش است.",
        "GPS or location services are off.",
        "خدمات الموقع متوقفة."
    )
    val tTitle = tr("موقعیت برای آب‌وهوا", "Location for weather", "الموقع للطقس")
    val tBody = tr(
        "برای نمایش دما و وضعیت هوا، موقعیت شما لازم است. می‌توانید اجازه GPS بدهید یا نام شهر را دستی وارد کنید.",
        "Your location is needed to show the temperature. Allow GPS or type your city name.",
        "موقعك مطلوب لعرض درجة الحرارة. يمكنك السماح بـ GPS أو كتابة اسم المدينة."
    )
    val tManual = tr("ورود دستی نام شهر", "Enter city manually", "إدخال المدينة يدوياً")
    val tAllowGps = tr("اجازه GPS", "Allow GPS", "السماح بـ GPS")
    val tLater = tr("بعداً", "Later", "لاحقاً")

    val gps = rememberGpsAction(
        label = tMyLocation,
        onDone = onDone,
        onDenied = { showManual = true },
        onReason = { r ->
            reasonText = if (r == GpsFail.SERVICES_OFF) tOff else null
        }
    )

    if (showManual) {
        ManualCityDialog(onDismiss = onDone, onSaved = onDone)
    } else {
        AlertDialog(
            onDismissRequest = onDone,
            title = { Text(tTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tBody)
                    reasonText?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { showManual = true }) {
                        Text(tManual)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = gps) {
                    Text(tAllowGps)
                }
            },
            dismissButton = {
                TextButton(onClick = onDone) {
                    Text(tLater)
                }
            }
        )
    }
}

// جست‌وجوی شهر در سراسر جهان: با تایپ، لیست زیر کادر ظاهر می‌شود و با زدن هر شهر ذخیره می‌شود
@Composable
fun ManualCityDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val lang = currentLang()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CityResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }

    val tTitle = tr("نام شهر را بنویسید", "Type a city name", "اكتب اسم المدينة")
    val tLabel = tr("نام شهر", "City name", "اسم المدينة")
    val tSearching = tr("در حال جست‌وجو...", "Searching...", "جارٍ البحث...")
    val tNotFound = tr(
        "شهری پیدا نشد یا اتصال اینترنت برقرار نیست.",
        "No city found, or no internet connection.",
        "لم يُعثر على مدينة، أو لا يوجد اتصال بالإنترنت."
    )
    val tClose = tr("بستن", "Close", "إغلاق")

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            results = emptyList()
            searching = false
            searched = false
            return@LaunchedEffect
        }
        searching = true
        delay(600)
        results = searchCities(ctx, q, lang)
        searching = false
        searched = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(tLabel) }
                )

                if (searching) {
                    Text(tSearching, style = MaterialTheme.typography.bodySmall)
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    results.forEach { city ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    WeatherStore.saveCity(ctx, city.lat, city.lon, city.name)
                                    onSaved()
                                }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(city.name, style = MaterialTheme.typography.titleSmall)
                            if (city.subtitle.isNotBlank()) {
                                Text(city.subtitle, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                if (searched && !searching && results.isEmpty()) {
                    Text(tNotFound, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(tClose)
            }
        }
    )
}
