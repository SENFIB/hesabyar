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
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.hesabyar.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume

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

// ================= ذخیره و وضعیت آب‌وهوا =================

object WeatherStore {
    private const val PREFS = "hesabyar_weather"
    private const val K_LAT = "geo_lat"
    private const val K_LON = "geo_lon"
    private const val K_PLACE = "geo_place"
    private const val K_CACHE = "geo_cache"
    private const val K_CACHE_KEY = "geo_cache_key"

    var state: WeatherState by mutableStateOf(WeatherState.None)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasLocation(ctx: Context): Boolean = prefs(ctx).getString(K_LAT, null) != null

    fun savedPlace(ctx: Context): String = prefs(ctx).getString(K_PLACE, "") ?: ""

    fun saveCity(ctx: Context, lat: Double, lon: Double, place: String) {
        prefs(ctx).edit()
            .putString(K_LAT, lat.toString())
            .putString(K_LON, lon.toString())
            .putString(K_PLACE, place)
            .apply()
        refresh(ctx)
    }

    fun refresh(ctx: Context) {
        val p = prefs(ctx)
        val lat = p.getString(K_LAT, null)?.toDoubleOrNull()
        val lon = p.getString(K_LON, null)?.toDoubleOrNull()
        val place = p.getString(K_PLACE, "") ?: ""
        if (lat == null || lon == null) {
            state = WeatherState.None
            return
        }
        val key = "$lat,$lon"
        state = WeatherState.Loading
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

    val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
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

// جست‌وجوی شهر در سراسر جهان (OpenStreetMap)
private fun geocodeList(q: String, lang: String): List<CityResult> {
    val raw = httpGet(
        "https://nominatim.openstreetmap.org/search?q=$q&format=jsonv2&limit=8" +
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

// جست‌وجوی شهر: ابتدا با زبان برنامه، اگر نتیجه نبود با انگلیسی
suspend fun searchCities(query: String, lang: String): List<CityResult> =
    withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val first = runCatching { geocodeList(q, lang) }.getOrDefault(emptyList())
        if (first.isNotEmpty() || lang == "en") {
            first
        } else {
            runCatching { geocodeList(q, "en") }.getOrDefault(emptyList())
        }
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

fun conditionText(code: Int, lang: String): String {
    val c = condFor(code)
    val word = when (lang) {
        "fa" -> c.fa
        "ar" -> c.ar
        else -> c.en
    }
    return "${c.icon} $word"
}

fun conditionIcon(code: Int): String = condFor(code).icon

// ================= موقعیت GPS =================

object LocationHelper {
    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun lastKnown(lm: LocationManager): Location? =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .firstOrNull()

    // حداکثر ۳۰ ثانیه منتظر موقعیت می‌ماند، بعد آخرین موقعیت ثبت‌شده را برمی‌گرداند
    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context): Location? = suspendCancellableCoroutine { cont ->
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        if (provider == null) {
            cont.resume(lastKnown(lm))
            return@suspendCancellableCoroutine
        }

        val handler = Handler(Looper.getMainLooper())
        var listener: LocationListener? = null
        val timeout = Runnable {
            listener?.let { lm.removeUpdates(it) }
            if (cont.isActive) cont.resume(lastKnown(lm))
        }

        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                handler.removeCallbacks(timeout)
                lm.removeUpdates(this)
                if (cont.isActive) cont.resume(location)
            }
        }
        listener = l

        cont.invokeOnCancellation {
            handler.removeCallbacks(timeout)
            lm.removeUpdates(l)
        }

        handler.postDelayed(timeout, 30_000L)
        lm.requestLocationUpdates(provider, 0L, 0f, l, Looper.getMainLooper())
    }
}

// نام شهر از روی مختصات (اگر دستگاه پشتیبانی کند)
@Suppress("DEPRECATION")
fun placeNameFor(ctx: Context, lat: Double, lon: Double, fallback: String): String = try {
    val list: List<Address>? = Geocoder(ctx, Locale.getDefault()).getFromLocation(lat, lon, 1)
    list?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea } ?: fallback
} catch (e: Exception) {
    fallback
}

// دکمه‌ای که اجازه موقعیت می‌گیرد و موقعیت را ذخیره می‌کند.
// اگر اجازه داده نشود، onDenied صدا زده می‌شود تا کاربر شهر را دستی وارد کند.
@Composable
fun rememberGpsAction(
    label: String,
    onDone: () -> Unit,
    onDenied: () -> Unit = {}
): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val doneNow by rememberUpdatedState(onDone)
    val deniedNow by rememberUpdatedState(onDenied)

    fun fetch() {
        scope.launch {
            val loc = LocationHelper.current(ctx)
            if (loc == null) {
                deniedNow()
                return@launch
            }
            val name = withContext(Dispatchers.IO) {
                placeNameFor(ctx, loc.latitude, loc.longitude, label)
            }
            WeatherStore.saveCity(ctx, loc.latitude, loc.longitude, name)
            doneNow()
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) fetch() else deniedNow()
    }

    return {
        if (LocationHelper.hasPermission(ctx)) {
            fetch()
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
