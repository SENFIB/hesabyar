package com.hesabyar.app.ui

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
import kotlin.math.roundToInt

@Composable
fun WeatherScreen(vm: MainViewModel, regionType: CalendarType) {
    val ctx = LocalContext.current
    val lang = currentLang()
    val state = WeatherStore.state
    val hasLoc = WeatherStore.hasLocation(ctx)
    var showManual by rememberSaveable { mutableStateOf(false) }

    val gps = rememberGpsAction(
        label = tr("موقعیت من", "My location", "موقعي"),
        onDone = {},
        onDenied = { showManual = true }
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    when (state) {
                        is WeatherState.Ok -> {
                            Text(
                                text = state.place.ifBlank {
                                    tr("موقعیت نامشخص", "Unknown place", "موقع غير معروف")
                                },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${state.info.current.roundToInt()}°",
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = conditionText(state.info.code, lang),
                                style = MaterialTheme.typography.titleMedium
                            )
                            state.info.days.firstOrNull()?.let { today ->
                                Text(
                                    text = "▼ ${today.min.roundToInt()}°   ▲ ${today.max.roundToInt()}°",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                        WeatherState.None -> Text(
                            tr("شهری انتخاب نشده است.", "No city selected.", "لم تُحدد مدينة.")
                        )
                        WeatherState.Loading -> Text(
                            tr("در حال دریافت اطلاعات...", "Loading...", "جارٍ التحميل...")
                        )
                        WeatherState.Error -> Text(
                            tr(
                                "دریافت اطلاعات ممکن نیست. اتصال اینترنت را بررسی کنید.",
                                "Could not load weather. Check your internet connection.",
                                "تعذّر تحميل الطقس. تحقّق من اتصال الإنترنت."
                            ),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = gps, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("استفاده از موقعیت من (GPS)", "Use my location (GPS)", "استخدام موقعي (GPS)"))
                }
                OutlinedButton(onClick = { showManual = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("جست‌وجوی شهر", "Search city", "البحث عن مدينة"))
                }
                if (hasLoc) {
                    OutlinedButton(
                        onClick = { WeatherStore.refresh(ctx) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(tr("به‌روزرسانی", "Refresh", "تحديث"))
                    }
                }
            }
        }

        if (state is WeatherState.Ok && state.info.hours.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            tr("۲۴ ساعت آینده", "Next 24 hours", "الساعات الـ24 القادمة"),
                            style = MaterialTheme.typography.titleMedium
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(state.info.hours) { h ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(h.time, style = MaterialTheme.typography.bodySmall)
                                    Text(conditionIcon(h.code), style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${h.temp.roundToInt()}°",
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

        if (state is WeatherState.Ok) {
            item {
                Text(
                    tr("پیش‌بینی ۵ روز آینده", "5-day forecast", "توقعات 5 أيام"),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            items(state.info.days) { day ->
                ForecastRow(day = day, regionType = regionType, lang = lang)
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
private fun ForecastRow(day: DayForecast, regionType: CalendarType, lang: String) {
    val dateText = formatWith(day.dateMillis, "EEEE d MMM", regionType.locale, deviceTimeZone())
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(dateText, style = MaterialTheme.typography.titleSmall)
                Text(conditionText(day.code, lang), style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = "▼ ${day.min.roundToInt()}°  ▲ ${day.max.roundToInt()}°",
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// پنجره اول اجرا: GPS یا ورود دستی
@Composable
fun LocationSetupDialog(onDone: () -> Unit) {
    var showManual by remember { mutableStateOf(false) }
    val gps = rememberGpsAction(
        label = tr("موقعیت من", "My location", "موقعي"),
        onDone = onDone,
        onDenied = { showManual = true }
    )

    if (showManual) {
        ManualCityDialog(onDismiss = onDone, onSaved = onDone)
    } else {
        AlertDialog(
            onDismissRequest = onDone,
            title = {
                Text(tr("موقعیت برای آب‌وهوا", "Location for weather", "الموقع للطقس"))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        tr(
                            "برای نمایش دما و وضعیت هوا، موقعیت شما لازم است. می‌توانید اجازه GPS بدهید یا نام شهر را دستی وارد کنید.",
                            "Your location is needed to show the temperature. Allow GPS or type your city name.",
                            "موقعك مطلوب لعرض درجة الحرارة. يمكنك السماح بـ GPS أو كتابة اسم المدينة."
                        )
                    )
                    TextButton(onClick = { showManual = true }) {
                        Text(tr("ورود دستی نام شهر", "Enter city manually", "إدخال المدينة يدوياً"))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = gps) {
                    Text(tr("اجازه GPS", "Allow GPS", "السماح بـ GPS"))
                }
            },
            dismissButton = {
                TextButton(onClick = onDone) {
                    Text(tr("بعداً", "Later", "لاحقاً"))
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
        results = searchCities(q, lang)
        searching = false
        searched = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(tr("نام شهر را بنویسید", "Type a city name", "اكتب اسم المدينة"))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("نام شهر", "City name", "اسم المدينة")) }
                )

                if (searching) {
                    Text(
                        tr("در حال جست‌وجو...", "Searching...", "جارٍ البحث..."),
                        style = MaterialTheme.typography.bodySmall
                    )
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
                    Text(
                        tr(
                            "شهری پیدا نشد یا اتصال اینترنت برقرار نیست.",
                            "No city found, or no internet connection.",
                            "لم يُعثر على مدينة، أو لا يوجد اتصال بالإنترنت."
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("بستن", "Close", "إغلاق"))
            }
        }
    )
}
