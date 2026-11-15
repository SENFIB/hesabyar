package com.hesabyar.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.hesabyar.app.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume

data class DayForecast(val dateMillis: Long, val min: Double, val max: Double, val code: Int)

data class WeatherInfo(val current: Double, val code: Int, val days: List<DayForecast>)

sealed interface WeatherState {
    data object None : WeatherState
    data object Loading : WeatherState
    data object Error : WeatherState
    data class Ok(val place: String, val info: WeatherInfo) : WeatherState
}

// وضعیت آب‌وهوا در کل برنامه (هدر و تب آب‌وهوا از همین استفاده می‌کنند)
object WeatherStore {
    private const val PREFS = "hesabyar_weather"

    var state: WeatherState by mutableStateOf<WeatherState>(WeatherState.None)
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasLocation(ctx: Context): Boolean = prefs(ctx).contains("lat")

    fun savedPlace(ctx: Context): String? = prefs(ctx).getString("place", null)

    private fun save(ctx: Context, lat: Double, lon: Double, place: String) {
        prefs(ctx).edit()
            .putString("lat", lat.toString())
            .putString("lon", lon.toString())
            .putString("place", place)
            .apply()
    }

    suspend fun refresh(ctx: Context) {
        val p = prefs(ctx)
        val lat = p.getString("lat", null)?.toDoubleOrNull()
        val lon = p.getString("lon", null)?.toDoubleOrNull()
        val place = p.getString("place", null) ?: ""
        if (lat == null || lon == null) {
            state = WeatherState.None
            return
        }
        state = WeatherState.Loading
        state = try {
            val info = withContext(Dispatchers.IO) { fetchWeather(lat, lon) }
            WeatherState.Ok(place, info)
        } catch (e: Exception) {
            WeatherState.Error
        }
    }

    // نام شهر را وارد می‌کند؛ اگر پیدا شد ذخیره و آب‌وهوا گرفته می‌شود
    suspend fun setManual(ctx: Context, query: String, lang: String): Boolean {
        val found = withContext(Dispatchers.IO) { geocode(query, lang) } ?: return false
        save(ctx, found.lat, found.lon, found.name)
        refresh(ctx)
        return true
    }

    // موقعیت GPS را می‌گیرد؛ اگر نشد false برمی‌گرداند
    suspend fun useGps(ctx: Context, label: String): Boolean {
        val loc = LocationHelper.current(ctx) ?: return false
        save(ctx, loc.latitude, loc.longitude, label)
        refresh(ctx)
        return true
    }
}

private data class GeoPlace(val name: String, val lat: Double, val lon: Double)

private fun geocode(query: String, lang: String): GeoPlace? {
    return runCatching { geocodeOnce(query, lang) }.getOrNull()
        ?: runCatching { geocodeOnce(query, "en") }.getOrNull()
}

private fun geocodeOnce(query: String, lang: String): GeoPlace? {
    val encoded = URLEncoder.encode(query, "UTF-8")
    val url = URL(
        "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=$lang&format=json"
    )
    val json = JSONObject(readUrl(url))
    val results = json.optJSONArray("results") ?: return null
    if (results.length() == 0) return null
    val r = results.getJSONObject(0)
    return GeoPlace(r.getString("name"), r.getDouble("latitude"), r.getDouble("longitude"))
}

private fun readUrl(url: URL): String {
    val conn = url.openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        return conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}

private fun fetchWeather(lat: Double, lon: Double): WeatherInfo {
    val url = URL(
        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code" +
            "&daily=temperature_2m_max,temperature_2m_min,weather_code" +
            "&timezone=auto&forecast_days=5"
    )
    val json = JSONObject(readUrl(url))
    val cur = json.getJSONObject("current")
    val daily = json.getJSONObject("daily")
    val times = daily.getJSONArray("time")
    val maxs = daily.getJSONArray("temperature_2m_max")
    val mins = daily.getJSONArray("temperature_2m_min")
    val codes = daily.getJSONArray("weather_code")

    val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val days = (0 until times.length()).map { i ->
        DayForecast(
            dateMillis = parser.parse(times.getString(i))?.time ?: 0L,
            min = mins.getDouble(i),
            max = maxs.getDouble(i),
            code = codes.getInt(i)
        )
    }
    return WeatherInfo(cur.getDouble("temperature_2m"), cur.getInt("weather_code"), days)
}

// متن وضعیت هوا بر اساس کد WMO
fun conditionText(code: Int, lang: String): String {
    val (icon, fa, en, ar) = when (code) {
        0 -> listOf("☀", "آفتابی", "Clear", "صافٍ")
        1, 2 -> listOf("🌤", "نیمه‌ابری", "Partly cloudy", "غائم جزئياً")
        3 -> listOf("☁", "ابری", "Overcast", "غائم")
        45, 48 -> listOf("🌫", "مه", "Fog", "ضباب")
        51, 53, 55, 56, 57 -> listOf("🌦", "نم‌نم باران", "Drizzle", "رذاذ")
        61, 63, 65, 66, 67, 80, 81, 82 -> listOf("🌧", "بارانی", "Rain", "ممطر")
        71, 73, 75, 77, 85, 86 -> listOf("❄", "برفی", "Snow", "ثلجي")
        95, 96, 99 -> listOf("⛈", "طوفان رعدوبرقی", "Thunderstorm", "عاصفة رعدية")
        else -> listOf("•", "نامشخص", "Unknown", "غير معروف")
    }
    val label = when (lang) {
        "fa" -> fa
        "ar" -> ar
        else -> en
    }
    return "$icon $label"
}

// ---------- موقعیت GPS ----------

object LocationHelper {
    fun hasPermission(ctx: Context): Boolean {
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { lm.isProviderEnabled(it) } ?: return null

        val fresh = withTimeoutOrNull(30_000L) {
            suspendCancellableCoroutine<Location?> { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        lm.removeUpdates(this)
                        if (cont.isActive) cont.resume(location)
                    }

                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }
                lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                cont.invokeOnCancellation { lm.removeUpdates(listener) }
            }
        }
        return fresh ?: lm.getLastKnownLocation(provider)
    }
}

// درخواست اجازه موقعیت و گرفتن GPS؛ خروجی: تابعی که با کلیک صدا زده می‌شود
@Composable
fun rememberGpsAction(label: String, onDone: (Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) {
            scope.launch { onDone(WeatherStore.useGps(context, label)) }
        } else {
            onDone(false)
        }
    }

    return {
        if (LocationHelper.hasPermission(context)) {
            scope.launch { onDone(WeatherStore.useGps(context, label)) }
        } else {
            // نمایش پنجره اجازه باعث قفل شدن دوباره برنامه نشود
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
