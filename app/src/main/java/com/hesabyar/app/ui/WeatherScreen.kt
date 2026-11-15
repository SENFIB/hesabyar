package com.hesabyar.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun WeatherScreen(vm: MainViewModel, regionType: CalendarType) {
    val context = LocalContext.current
    val lang = currentLang()
    val scope = rememberCoroutineScope()
    val state = WeatherStore.state

    var showManual by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val gpsLabel = tr("موقعیت من", "My location", "موقعي")
    val gpsFail = tr(
        "GPS فعال نیست یا اجازه داده نشد. شهر را دستی وارد کنید.",
        "GPS is off or permission was denied. Enter the city manually.",
        "GPS غير مفعّل أو لم يُسمح به. أدخل المدينة يدوياً."
    )
    val noLocation = tr(
        "موقعیت تنظیم نشده است.",
        "Location is not set.",
        "لم يتم تحديد الموقع."
    )
    val loadingText = tr("در حال دریافت...", "Loading...", "جارٍ التحميل...")
    val errorText = tr(
        "دریافت آب و هوا انجام نشد. اینترنت را بررسی کنید و دوباره تلاش کنید.",
        "Could not load weather. Check your internet and try again.",
        "تعذّر جلب الطقس. تحقق من الإنترنت وحاول مجدداً."
    )

    val gps = rememberGpsAction(gpsLabel) { ok ->
        message = if (ok) null else gpsFail
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (val s = state) {
            WeatherState.None -> Text(noLocation)
            WeatherState.Loading -> Text(loadingText)
            WeatherState.Error -> Text(errorText, color = MaterialTheme.colorScheme.error)
            is WeatherState.Ok -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(s.place, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            tr("دمای فعلی", "Current temperature", "درجة الحرارة الآن"),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "${s.info.current.roundToInt()}°",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = conditionText(s.info.code, lang),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            tr("پیش‌بینی ۵ روز", "5-day forecast", "توقعات 5 أيام"),
                            style = MaterialTheme.typography.titleMedium
                        )
                        s.info.days.forEach { day ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = formatWith(
                                            day.dateMillis,
                                            "EEEE, yyyy/MM/dd",
                                            regionType.locale,
                                            regionType.timeZone
                                        ),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = conditionText(day.code, lang),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Text(
                                    text = "${day.min.roundToInt()}° / ${day.max.roundToInt()}°",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        Button(onClick = gps, modifier = Modifier.fillMaxWidth()) {
            Text(tr("استفاده از موقعیت من (GPS)", "Use my location (GPS)", "استخدام موقعي (GPS)"))
        }
        OutlinedButton(onClick = { showManual = true }, modifier = Modifier.fillMaxWidth()) {
            Text(tr("ورود دستی نام شهر", "Enter city name", "إدخال اسم المدينة"))
        }
        OutlinedButton(
            onClick = { scope.launch { WeatherStore.refresh(context) } },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(tr("به‌روزرسانی", "Refresh", "تحديث"))
        }

        message?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Text(
            tr("منبع داده: Open-Meteo", "Source: Open-Meteo", "المصدر: Open-Meteo"),
            style = MaterialTheme.typography.bodySmall
        )
    }

    if (showManual) {
        ManualCityDialog(
            onDismiss = { showManual = false },
            onSaved = {
                showManual = false
                message = null
            }
        )
    }
}

// پنجره اول اجرا: درخواست موقعیت، یا ورود دستی شهر
@Composable
fun LocationSetupDialog(onDone: () -> Unit) {
    val gpsLabel = tr("موقعیت من", "My location", "موقعي")
    var showManual by remember { mutableStateOf(false) }

    val gps = rememberGpsAction(gpsLabel) { ok ->
        if (ok) onDone() else showManual = true
    }

    if (showManual) {
        ManualCityDialog(onDismiss = onDone, onSaved = onDone)
    } else {
        AlertDialog(
            onDismissRequest = onDone,
            title = {
                Text(tr("موقعیت برای آب‌وهوا", "Location for weather", "الموقع للطقس"))
            },
            text = {
                Text(
                    tr(
                        "برای نمایش دمای فعلی در بالای برنامه، موقعیت شما لازم است. با اجازه GPS خودکار پیدا می‌شود، یا می‌توانید نام شهرتان را دستی وارد کنید.",
                        "To show the current temperature at the top, your location is needed. Allow GPS to detect it automatically, or enter your city name manually.",
                        "لعرض درجة الحرارة الحالية أعلى الشاشة نحتاج موقعك. اسمح بـGPS للكشف التلقائي، أو أدخل اسم مدينتك يدوياً."
                    )
                )
            },
            confirmButton = {
                Button(onClick = { gps() }) {
                    Text(tr("اجازه GPS", "Allow GPS", "السماح بـGPS"))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showManual = true }) {
                        Text(tr("ورود دستی", "Enter manually", "إدخال يدوي"))
                    }
                    TextButton(onClick = onDone) {
                        Text(tr("بعداً", "Later", "لاحقاً"))
                    }
                }
            }
        )
    }
}

@Composable
fun ManualCityDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val lang = currentLang()
    val scope = rememberCoroutineScope()

    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val titleText = tr("نام شهر یا منطقه", "City or area name", "اسم المدينة أو المنطقة")
    val labelText = tr("مثلاً: تهران، Dubai، الرياض", "e.g. Tehran, Dubai, Riyadh", "مثال: طهران، دبي، الرياض")
    val notFound = tr(
        "شهر پیدا نشد. نام را دوباره بنویسید یا اینترنت را بررسی کنید.",
        "City not found. Check the name or your internet.",
        "لم يتم العثور على المدينة. تحقق من الاسم أو الإنترنت."
    )
    val loadingText = tr("در حال جستجو...", "Searching...", "جارٍ البحث...")
    val saveText = tr("ثبت", "Save", "حفظ")
    val cancelText = tr("انصراف", "Cancel", "إلغاء")

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(titleText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(labelText) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (busy) Text(loadingText)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = text.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        val ok = WeatherStore.setManual(context, text.trim(), lang)
                        busy = false
                        if (ok) onSaved() else error = notFound
                    }
                }
            ) {
                Text(saveText)
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!busy) onDismiss() }) {
                Text(cancelText)
            }
        }
    )
}
