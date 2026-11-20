package com.hesabyar.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hesabyar.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.roundToInt

// ================= کلیدهای ذخیره =================
private const val WEATHER_PREFS = "hesabyar_weather"
private const val K_LAT = "geo_lat"
private const val K_LON = "geo_lon"
private const val K_PLACE = "geo_place"
private const val K_CACHE = "geo_cache"
private const val K_CACHE_KEY = "geo_cache_key"
private const val K_CACHE_TIME = "geo_cache_time"
private const val K_UNIT_F = "unit_f"
private const val K_AUTO_HOURS = "auto_hours"
private const val AUTO_WORK_NAME = "hesabyar_weather_auto"

// اگر اطلاعات بیشتر از این مدت قدیمی باشد، هنگام باز شدن تب دوباره گرفته می‌شود
private const val STALE_MS = 30L * 60L * 1000L

// ================= مدل‌ها =================
data class DayForecast(val dateMillis: Long, val min: Double, val max: Double, val code: Int)
data class HourForecast(val time: String, val temp: Double, val code: Int)
data class WeatherInfo(
    val current: Double,
    val code: Int,
    val days: List<DayForecast>,
    val hours: List<HourForecast>
)

sealed class WeatherState {
    object None : WeatherState()
    object Loading : WeatherState()
    object Error : WeatherState()
    class Ok(val place: String, val info: WeatherInfo) : WeatherState()
}

data class CityResult(
    val name: String,
    val lat: Double,
    val lon: Double,
    val subtitle: String
)

// دلیل ناموفق بودن گرفتن موقعیت
enum class GpsFail { DENIED, SERVICES_OFF, NOT_FOUND }

sealed class LocResult {
    class Ok(val lat: Double, val lon: Double) : LocResult()
    object Denied : LocResult()
    object ServicesOff : LocResult()
    object NotFound : LocResult()
}

// ================= ذخیره و وضعیت آب‌وهوا =================
object WeatherStore {
    var state: WeatherState by mutableStateOf(WeatherState.None)
        private set

    // false = سلسیوس، true = فارنهایت
    var fahrenheit: Boolean by mutableStateOf(false)
        private set

    // با هر تغییر موقعیت این عدد زیاد می‌شود؛ صفحه‌هایی که hasLocation را می‌خوانند دوباره ساخته می‌شوند
    private var locVersion by mutableIntStateOf(0)

    // وضعیت گرفتن موقعیت GPS (برای نمایش در صفحه)
    var gpsBusy: Boolean by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(WEATHER_PREFS, Context.MODE_PRIVATE)

    fun hasLocation(ctx: Context): Boolean {
        val version = locVersion // خواندن این مقدار باعث بازسازی صفحه بعد از تغییر موقعیت می‌شود
        return version >= 0 && prefs(ctx).getString(K_LAT, null) != null
    }

    fun savedPlace(ctx: Context): String {
        val version = locVersion
        return if (version >= 0) prefs(ctx).getString(K_PLACE, "") ?: "" else ""
    }

    // زمان آخرین دریافت موفق اطلاعات (میلی‌ثانیه). صفر یعنی هنوز چیزی ذخیره نشده
    fun lastUpdatedMillis(ctx: Context): Long = prefs(ctx).getLong(K_CACHE_TIME, 0L)

    fun loadUnit(ctx: Context) {
        fahrenheit = prefs(ctx).getBoolean(K_UNIT_F, false)
    }

    fun setFahrenheit(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(K_UNIT_F, value).apply()
        fahrenheit = value
    }

    fun saveCity(ctx: Context, lat: Double, lon: Double, place: String) {
        prefs(ctx).edit()
            .putString(K_LAT, lat.toString())
            .putString(K_LON, lon.toString())
            .putString(K_PLACE, place)
            .apply()
        locVersion++
        state = WeatherState.Loading
        refresh(ctx)
        WeatherAuto.ensure(ctx)
    }

    // گرفتن موقعیت GPS در حوزه‌ی جدا از صفحه، تا با بسته شدن پنجره یا دیالوگ هم کار تمام شود.
    // onResult بعد از پایان کار صدا زده می‌شود: null یعنی موفق، وگرنه دلیل شکست.
    fun locateFromGps(ctx: Context, label: String, lang: String, onResult: (GpsFail?) -> Unit) {
        if (gpsBusy) return
        val appCtx = ctx.applicationContext
        gpsBusy = true
        scope.launch {
            val result = runCatching { LocationHelper.current(appCtx) }.getOrDefault(LocResult.NotFound)
            val fail: GpsFail? = when (result) {
                is LocResult.Ok -> {
                    val name = withContext(Dispatchers.IO) {
                        reversePlace(appCtx, result.lat, result.lon, lang, label)
                    }
                    saveCity(appCtx, result.lat, result.lon, name)
                    null
                }
                LocResult.Denied -> GpsFail.DENIED
                LocResult.ServicesOff -> GpsFail.SERVICES_OFF
                LocResult.NotFound -> GpsFail.NOT_FOUND
            }
            gpsBusy = false
            onResult(fail)
        }
    }

    // وقتی تب آب‌وهوا باز می‌شود: اگر کش همین شهر تازه باشد همان نمایش داده می‌شود،
    // وگرنه (یا اگر قدیمی باشد) از اینترنت گرفته می‌شود. بدون اینترنت، کش قبلی نشان داده می‌شود.
    fun onScreenOpened(ctx: Context) {
        loadUnit(ctx)
        val p = prefs(ctx)
        val lat = p.getString(K_LAT, null)?.toDoubleOrNull()
        val lon = p.getString(K_LON, null)?.toDoubleOrNull()
        val place = p.getString(K_PLACE, "") ?: ""
        if (lat == null || lon == null) {
            state = WeatherState.None
            return
        }
        val key = "$lat,$lon"
        val cachedRaw = if (p.getString(K_CACHE_KEY, null) == key) p.getString(K_CACHE, null) else null
        val info = cachedRaw?.let { parseWeather(it) }
        val age = System.currentTimeMillis() - p.getLong(K_CACHE_TIME, 0L)
        if (info != null) {
            state = WeatherState.Ok(place, info)
            if (age > STALE_MS) refresh(ctx)
        } else {
            refresh(ctx)
        }
    }

    fun refresh(ctx: Context) {
        loadUnit(ctx)
        val p = prefs(ctx)
        val lat = p.getString(K_LAT, null)?.toDoubleOrNull()
        val lon = p.getString(K_LON, null)?.toDoubleOrNull()
        val place = p.getString(K_PLACE, "") ?: ""
        if (lat == null || lon == null) {
            state = WeatherState.None
            return
        }
        val key = "$lat,$lon"
        // اگر اطلاعات قبلی روی صفحه است، تا رسیدن جواب جدید همان را نگه می‌داریم
        if (state !is WeatherState.Ok) state = WeatherState.Loading
        val appCtx = ctx.applicationContext
        scope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching { httpGet(forecastUrl(lat, lon)) }.getOrNull()
            }
            if (raw != null) {
                val info = parseWeather(raw)
                if (info != null) {
                    prefs(appCtx).edit()
                        .putString(K_CACHE, raw)
                        .putString(K_CACHE_KEY, key)
                        .putLong(K_CACHE_TIME, System.currentTimeMillis())
                        .apply()
                    state = WeatherState.Ok(place, info)
                } else {
                    state = WeatherState.Error
                }
            } else {
                // قطع اینترنت: آخرین اطلاعات همین شهر نمایش داده می‌شود
                val cp = prefs(appCtx)
                val cached = if (cp.getString(K_CACHE_KEY, null) == key) cp.getString(K_CACHE, null) else null
                val info = cached?.let { parseWeather(it) }
                state = if (info != null) WeatherState.Ok(place, info) else WeatherState.Error
            }
        }
    }
}

// ================= به‌روزرسانی خودکار (WorkManager) =================
// فاصله‌های ممکن (ساعت). صفر یعنی خاموش
object WeatherAuto {
    const val DEFAULT_HOURS = 2

    fun hours(ctx: Context): Int =
        ctx.getSharedPreferences(WEATHER_PREFS, Context.MODE_PRIVATE).getInt(K_AUTO_HOURS, DEFAULT_HOURS)

    fun setHours(ctx: Context, value: Int) {
        ctx.getSharedPreferences(WEATHER_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(K_AUTO_HOURS, value)
            .apply()
        schedule(ctx, ExistingPeriodicWorkPolicy.UPDATE)
    }

    // فقط اگر کاری ثبت نشده باشد، ثبت می‌کند (بار دوم کاری انجام نمی‌دهد)
    fun ensure(ctx: Context) = schedule(ctx, ExistingPeriodicWorkPolicy.KEEP)

    private fun schedule(ctx: Context, policy: ExistingPeriodicWorkPolicy) {
        val wm = WorkManager.getInstance(ctx.applicationContext)
        val h = hours(ctx)
        if (h <= 0 || !WeatherStore.hasLocation(ctx)) {
            wm.cancelUniqueWork(AUTO_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<WeatherRefreshWorker>(h.toLong(), TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        wm.enqueueUniquePeriodicWork(AUTO_WORK_NAME, policy, request)
    }
}

// این کلاس توسط WorkManager اجرا می‌شود؛ فقط وقتی اینترنت وصل است و فاصله انتخابی گذشته باشد
class WeatherRefreshWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {
    override suspend fun doWork(): ListenableWorker.Result {
        withContext(Dispatchers.IO) { fetchAndCache(applicationContext) }
        return ListenableWorker.Result.success()
    }
}

// گرفتن اطلاعات شهر ذخیره‌شده و ذخیره در کش (بدون به‌روزرسانی صفحه)
private fun fetchAndCache(ctx: Context) {
    val p = ctx.getSharedPreferences(WEATHER_PREFS, Context.MODE_PRIVATE)
    val lat = p.getString(K_LAT, null)?.toDoubleOrNull() ?: return
    val lon = p.getString(K_LON, null)?.toDoubleOrNull() ?: return
    val raw = runCatching { httpGet(forecastUrl(lat, lon)) }.getOrNull() ?: return
    if (parseWeather(raw) == null) return
    p.edit()
        .putString(K_CACHE, raw)
        .putString(K_CACHE_KEY, "$lat,$lon")
        .putLong(K_CACHE_TIME, System.currentTimeMillis())
        .apply()
}

// ================= واحد دما و منطقه زمانی پیش‌بینی =================
// دما را طبق واحد انتخابی به عدد صحیح تبدیل می‌کند
fun tempValue(c: Double, fahrenheit: Boolean): Int =
    if (fahrenheit) (c * 9.0 / 5.0 + 32.0).roundToInt() else c.roundToInt()

fun tempText(c: Double, fahrenheit: Boolean): String = tempValue(c, fahrenheit).toString() + "°"

// تاریخ پیش‌بینی‌ها بر اساس UTC است تا روز جابه‌جا نشود
fun forecastZone(): TimeZone = TimeZone.getTimeZone("UTC")

// ================= شبکه =================
private fun coord(v: Double): String = String.format(Locale.US, "%.4f", v)

private fun forecastUrl(lat: Double, lon: Double): String =
    "https://api.open-meteo.com/v1/forecast?latitude=${coord(lat)}&longitude=${coord(lon)}" +
        "&current=temperature_2m,weather_code" +
        "&hourly=temperature_2m,weather_code" +
        "&daily=temperature_2m_max,temperature_2m_min,weather_code" +
        "&timezone=auto&forecast_days=5"

private fun httpGet(url: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "Hesabyar/0.6 (Android)")
        conn.setRequestProperty("Accept", "application/json")
        return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        conn.disconnect()
    }
}

private fun parseWeather(raw: String): WeatherInfo? = try {
    val root = JSONObject(raw)
    val cur = root.getJSONObject("current")
    val daily = root.getJSONObject("daily")
    val hourly = root.getJSONObject("hourly")

    val f = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = forecastZone() }
    val times = daily.getJSONArray("time")
    val maxs = daily.getJSONArray("temperature_2m_max")
    val mins = daily.getJSONArray("temperature_2m_min")
    val codes = daily.getJSONArray("weather_code")
    val days = (0 until times.length()).map { i ->
        DayForecast(
            dateMillis = f.parse(times.getString(i))?.time ?: 0L,
            min = mins.getDouble(i),
            max = maxs.getDouble(i),
            code = codes.getInt(i)
        )
    }

    // ساعت‌های ۲۴ ساعت آینده، از ساعت جاری مکان انتخابی
    val hTimes = hourly.getJSONArray("time")
    val hTemps = hourly.getJSONArray("temperature_2m")
    val hCodes = hourly.getJSONArray("weather_code")
    val nowKey = cur.getString("time").take(13)
    val start = (0 until hTimes.length()).firstOrNull { hTimes.getString(it).take(13) == nowKey } ?: 0
    val end = minOf(start + 24, hTimes.length())
    val hours = (start until end).map { i ->
        HourForecast(
            time = hTimes.getString(i).drop(11).take(5),
            temp = hTemps.getDouble(i),
            code = hCodes.getInt(i)
        )
    }

    WeatherInfo(
        current = cur.getDouble("temperature_2m"),
        code = cur.getInt("weather_code"),
        days = days,
        hours = hours
    )
} catch (e: Exception) {
    null
}

// ================= جست‌وجوی شهر: ۵ سرویس همزمان =================
// سرویس ۱ و ۵: Nominatim (OpenStreetMap) با زبان برنامه و با انگلیسی
private fun geocodeList(q: String, lang: String): List<CityResult> {
    val raw = httpGet(
        "https://nominatim.openstreetmap.org/search?q=$q&format=jsonv2&limit=10" +
            "&addressdetails=1&accept-language=$lang"
    )
    val arr = JSONArray(raw)
    val out = ArrayList<CityResult>()
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        val cls = o.optString("class")
        if (cls != "place" && cls != "boundary") continue
        val display = o.optString("display_name")
        val name = o.optString("name").ifBlank { display.substringBefore(',') }
        out.add(
            CityResult(
                name = name,
                lat = o.getString("lat").toDouble(),
                lon = o.getString("lon").toDouble(),
                subtitle = display.substringAfter(",", "").trim()
            )
        )
    }
    return out
}

// سرویس ۲: Photon (بر پایه OpenStreetMap، با جست‌وجوی سریع)
private val PHOTON_TYPES = setOf(
    "city", "town", "village", "hamlet", "locality", "district", "county", "state", "country"
)

private fun photonSearch(q: String, lang: String): List<CityResult> {
    val pl = if (lang == "en" || lang == "de" || lang == "fr" || lang == "it") lang else "default"
    val raw = httpGet("https://photon.komoot.io/api/?q=$q&limit=10&lang=$pl")
    val feats = JSONObject(raw).optJSONArray("features") ?: return emptyList()
    val out = ArrayList<CityResult>()
    for (i in 0 until feats.length()) {
        val f = feats.getJSONObject(i)
        val p = f.getJSONObject("properties")
        if (p.optString("type") !in PHOTON_TYPES) continue
        val name = p.optString("name")
        if (name.isBlank()) continue
        val c = f.getJSONObject("geometry").getJSONArray("coordinates")
        val sub = listOf(p.optString("state"), p.optString("country"))
            .filter { it.isNotBlank() }
            .joinToString(", ")
        // در Photon ترتیب مختصات: طول جغرافیایی، عرض جغرافیایی
        out.add(CityResult(name, c.getDouble(1), c.getDouble(0), sub))
    }
    return out
}

// سرویس ۳: جست‌وجوی خود اندروید (Geocoder)
@Suppress("DEPRECATION")
private fun androidGeocoderSearch(ctx: Context, q: String): List<CityResult> {
    if (!Geocoder.isPresent()) return emptyList()
    val list: List<Address> = Geocoder(ctx, Locale.getDefault()).getFromLocationName(q, 10) ?: return emptyList()
    return list.mapNotNull { a ->
        val name = a.locality ?: a.subAdminArea ?: a.adminArea ?: return@mapNotNull null
        CityResult(
            name = name,
            lat = a.latitude,
            lon = a.longitude,
            subtitle = listOfNotNull(a.adminArea, a.countryName).distinct().joinToString(", ")
        )
    }
}

// سرویس ۴: Open-Meteo Geocoding (شهرهای بزرگ و پرجمعیت جهان)
private fun openMeteoSearch(q: String): List<CityResult> {
    val raw = httpGet("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=10&language=en&format=json")
    val arr = JSONObject(raw).optJSONArray("results") ?: return emptyList()
    return (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val sub = listOf(o.optString("admin1"), o.optString("country"))
            .filter { it.isNotBlank() }
            .joinToString(", ")
        CityResult(
            name = o.getString("name"),
            lat = o.getDouble("latitude"),
            lon = o.getDouble("longitude"),
            subtitle = sub
        )
    }
}

// جست‌وجو از همه سرویس‌ها همزمان. نتیجه‌ها ادغام، تکراری‌ها حذف و حداکثر ۱۰ شهر نمایش داده می‌شود.
// اگر یک سرویس خراب باشد یا دیر جواب دهد، بقیه نتیجه‌ها نشان داده می‌شوند.
suspend fun searchCities(ctx: Context, query: String, lang: String): List<CityResult> = coroutineScope {
    val text = query.trim()
    val q = URLEncoder.encode(text, "UTF-8")
    val sources: List<() -> List<CityResult>> = listOf(
        { geocodeList(q, lang) },
        { photonSearch(q, lang) },
        { androidGeocoderSearch(ctx, text) },
        { openMeteoSearch(q) },
        { geocodeList(q, "en") }
    )
    val jobs = sources.map { source ->
        async(Dispatchers.IO) {
            withTimeoutOrNull(8000L) { runCatching { source() }.getOrNull() } ?: emptyList()
        }
    }
    val merged = jobs.awaitAll().flatten()
    // حذف تکراری: شهرهایی که مختصات تقریباً یکسان دارند (حدود ۱۰ کیلومتر)
    val seen = HashSet<String>()
    merged
        .filter { c -> seen.add("${(c.lat * 10).roundToInt()}:${(c.lon * 10).roundToInt()}") }
        .take(10)
}

// ================= وضعیت هوا به زبان‌های مختلف =================
private data class Cond(val icon: String, val fa: String, val ar: String, val en: String)

private fun condFor(code: Int): Cond = when (code) {
    0 -> Cond("☀", "آفتابی", "مشمس", "Sunny")
    1, 2 -> Cond("🌤", "نیمه‌ابری", "غائم جزئياً", "Partly cloudy")
    3 -> Cond("☁", "ابری", "غائم", "Cloudy")
    45, 48 -> Cond("🌫", "مه", "ضباب", "Fog")
    in 51..57 -> Cond("🌦", "نم‌نم باران", "رذاذ", "Drizzle")
    in 71..77, in 85..86 -> Cond("❄", "برفی", "ثلجي", "Snow")
    in 61..82 -> Cond("🌧", "بارانی", "ممطر", "Rain")
    in 95..99 -> Cond("⛈", "طوفان رعدوبرقی", "عاصفة رعدية", "Thunderstorm")
    else -> Cond("•", "نامشخص", "غير معروف", "Unknown")
}

// فقط متن وضعیت (بدون آیکون)
fun conditionWord(code: Int, lang: String): String {
    val c = condFor(code)
    return when (lang) {
        "fa" -> c.fa
        "ar" -> c.ar
        else -> c.en
    }
}

fun conditionText(code: Int, lang: String): String =
    "${condFor(code).icon} ${conditionWord(code, lang)}"

fun conditionIcon(code: Int): String = condFor(code).icon

// ================= موقعیت GPS =================
object LocationHelper {
    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasFine(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun providerOn(lm: LocationManager, p: String): Boolean =
        runCatching { lm.isProviderEnabled(p) }.getOrDefault(false)

    // آخرین موقعیت ثبت‌شده در گوشی (اگر وجود داشته باشد)
    @SuppressLint("MissingPermission")
    private fun lastKnown(lm: LocationManager, fine: Boolean): Location? {
        val list = if (fine) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        }
        return list
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    // یک تلاش برای گرفتن موقعیت از یک سرویس. null یعنی موقعیتی نیامد.
    @SuppressLint("MissingPermission")
    private suspend fun requestFix(ctx: Context, lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine<Location?> { cont ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    lm.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(ctx)) { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }
                } else {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            lm.removeUpdates(this)
                            if (cont.isActive) cont.resume(location)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    }
                    cont.invokeOnCancellation { lm.removeUpdates(listener) }
                    lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }

    // ترتیب: اگر موقعیت خیلی تازه (کمتر از ۲ دقیقه) در گوشی باشد همان؛ وگرنه GPS، بعد شبکه.
    // هر کدام حداکثر ۱۲ ثانیه وقت دارد.
    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context): LocResult {
        if (!hasPermission(ctx)) return LocResult.Denied
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val fine = hasFine(ctx)
        val anyOn = providerOn(lm, LocationManager.GPS_PROVIDER) || providerOn(lm, LocationManager.NETWORK_PROVIDER)
        if (!anyOn) return LocResult.ServicesOff

        val now = System.currentTimeMillis()
        val fresh = lastKnown(lm, fine)?.takeIf { now - it.time < 2L * 60L * 1000L }
        if (fresh != null) return LocResult.Ok(fresh.latitude, fresh.longitude)

        val order = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { p -> providerOn(lm, p) && (p != LocationManager.GPS_PROVIDER || fine) }
        for (p in order) {
            val loc = withTimeoutOrNull(12_000L) { requestFix(ctx, lm, p) }
            if (loc != null) return LocResult.Ok(loc.latitude, loc.longitude)
        }
        // اگر تازه نبود، آخرین موقعیت تا ۶ ساعت قبل قابل قبول است
        val last = lastKnown(lm, fine)?.takeIf { now - it.time < 6L * 60L * 60L * 1000L }
        return if (last != null) LocResult.Ok(last.latitude, last.longitude) else LocResult.NotFound
    }
}

// نام شهر از روی مختصات با سرویس OpenStreetMap (بهتر از Geocoder گوشی برای نام شهر)
// باید خارج از رابط کاربری (IO) صدا زده شود
suspend fun reversePlace(ctx: Context, lat: Double, lon: Double, lang: String, fallback: String): String {
    val fromNominatim = runCatching {
        val raw = httpGet(
            "https://nominatim.openstreetmap.org/reverse?lat=${coord(lat)}&lon=${coord(lon)}" +
                "&format=jsonv2&zoom=10&accept-language=$lang"
        )
        val addr = JSONObject(raw).optJSONObject("address")
        listOf("city", "town", "village", "municipality", "county", "state")
            .firstNotNullOfOrNull { k -> addr?.optString(k)?.takeIf { it.isNotBlank() } }
    }.getOrNull()
    if (!fromNominatim.isNullOrBlank()) return fromNominatim
    return placeNameFor(ctx, lat, lon, fallback)
}

// نام شهر از Geocoder خود گوشی (پشتیبان)
@Suppress("DEPRECATION")
fun placeNameFor(ctx: Context, lat: Double, lon: Double, fallback: String): String = try {
    val list: List<Address>? = Geocoder(ctx, Locale.getDefault()).getFromLocation(lat, lon, 1)
    list?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea } ?: fallback
} catch (e: Exception) {
    fallback
}

// دکمه‌ای که اجازه موقعیت می‌گیرد و موقعیت را ذخیره می‌کند.
// onDone: وقتی موقعیت با موفقیت ذخیره شد (دیالوگ باید بسته شود)
// onDenied: وقتی اجازه داده نشده یا موقعیت پیدا نشد (برای باز کردن جست‌وجوی دستی)
// onReason: دلیل دقیق شکست را می‌دهد (برای پیام به کاربر)
// onStart: وقتی گرفتن موقعیت شروع می‌شود
@Composable
fun rememberGpsAction(
    label: String,
    onDone: () -> Unit,
    onDenied: () -> Unit = {},
    onReason: (GpsFail) -> Unit = {},
    onStart: () -> Unit = {}
): () -> Unit {
    val ctx = LocalContext.current
    val lang = currentLang()
    val doneNow by rememberUpdatedState(onDone)
    val deniedNow by rememberUpdatedState(onDenied)
    val reasonNow by rememberUpdatedState(onReason)
    val startNow by rememberUpdatedState(onStart)

    // گرفتن موقعیت در حوزه‌ی برنامه انجام می‌شود، نه وابسته به این صفحه
    fun locate() {
        WeatherStore.locateFromGps(ctx, label, lang) { fail ->
            if (fail == null) {
                doneNow()
            } else {
                reasonNow(fail)
                if (fail == GpsFail.DENIED || fail == GpsFail.NOT_FOUND) deniedNow()
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) {
            locate()
        } else {
            reasonNow(GpsFail.DENIED)
            deniedNow()
        }
    }

    return {
        startNow()
        if (LocationHelper.hasPermission(ctx)) {
            locate()
        } else {
            // جلوگیری از قفل شدن برنامه هنگام نمایش پنجره اجازه
            MainActivity.skipAutoLock = true
            launcher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }
}
