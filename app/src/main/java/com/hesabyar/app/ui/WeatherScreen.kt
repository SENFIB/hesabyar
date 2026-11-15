package com.hesabyar.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hesabyar.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

data class City(
    val fa: String,
    val en: String,
    val ar: String,
    val lat: Double,
    val lon: Double
) {
    fun nameFor(lang: String): String = when (lang) {
        "fa" -> fa
        "ar" -> ar
        else -> en
    }
}

val CITIES = listOf(
    City("تهران", "Tehran", "طهران", 35.6892, 51.3890),
    City("مشهد", "Mashhad", "مشهد", 36.2605, 59.6168),
    City("اصفهان", "Isfahan", "أصفهان", 32.6539, 51.6660),
    City("شیراز", "Shiraz", "شيراز", 29.5918, 52.5836),
    City("تبریز", "Tabriz", "تبريز", 38.0962, 46.2738),
    City("اهواز", "Ahvaz", "الأهواز", 31.3183, 48.6706),
    City("ریاض", "Riyadh", "الرياض", 24.7136, 46.6753),
    City("جده", "Jeddah", "جدة", 21.4858, 39.1925),
    City("دبی", "Dubai", "دبي", 25.2048, 55.2708),
    City("لندن", "London", "لندن", 51.5072, -0.1276)
)

data class DayForecast(val dateMillis: Long, val min: Double, val max: Double)

data class WeatherInfo(val current: Double, val days: List<DayForecast>)

sealed interface WeatherState {
    data object Loading : WeatherState
    data object Error : WeatherState
    data class Ok(val info: WeatherInfo) : WeatherState
}

private suspend fun fetchWeather(city: City): WeatherInfo = withContext(Dispatchers.IO) {
    val url = URL(
        "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${city.lat}&longitude=${city.lon}" +
            "&current=temperature_2m&daily=temperature_2m_max,temperature_2m_min" +
            "&timezone=auto&forecast_days=5"
    )
    val conn = url.openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(body)

        val current = json.getJSONObject("current").getDouble("temperature_2m")
        val daily = json.getJSONObject("daily")
        val times = daily.getJSONArray("time")
        val maxs = daily.getJSONArray("temperature_2m_max")
        val mins = daily.getJSONArray("temperature_2m_min")

        val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val days = (0 until times.length()).map { i ->
            DayForecast(
                dateMillis = parser.parse(times.getString(i))?.time ?: 0L,
                min = mins.getDouble(i),
                max = maxs.getDouble(i)
            )
        }
        WeatherInfo(current, days)
    } finally {
        conn.disconnect()
    }
}

@Composable
fun WeatherScreen(vm: MainViewModel, regionType: CalendarType) {
    val cityIndexSaved by vm.weatherCity.collectAsState()
    val lang = LocalConfiguration.current.locales[0].language
    val cityIndex = cityIndexSaved.coerceIn(0, CITIES.lastIndex)
    val city = CITIES[cityIndex]

    var reload by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<WeatherState>(WeatherState.Loading) }

    LaunchedEffect(city, reload) {
        state = WeatherState.Loading
        state = try {
            WeatherState.Ok(fetchWeather(city))
        } catch (e: Exception) {
            WeatherState.Error
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CityDropdown(
            selectedIndex = cityIndex,
            lang = lang,
            onSelect = { vm.setWeatherCity(it) }
        )

        when (val s = state) {
            WeatherState.Loading -> Text(stringResource(R.string.v4_weather_loading))
            WeatherState.Error -> Text(
                stringResource(R.string.v4_weather_error),
                color = MaterialTheme.colorScheme.error
            )
            is WeatherState.Ok -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(city.nameFor(lang), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.v4_temp_now), style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = "${s.info.current.roundToInt()}°",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(stringResource(R.string.v4_forecast), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.v4_min_max), style = MaterialTheme.typography.bodySmall)
                        s.info.days.forEach { day ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = formatWith(
                                        day.dateMillis,
                                        "EEEE, yyyy/MM/dd",
                                        regionType.locale,
                                        regionType.timeZone
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
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

        Button(
            onClick = { reload = reload + 1 },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.v4_refresh))
        }

        Text(stringResource(R.string.v4_weather_source), style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CityDropdown(selectedIndex: Int, lang: String, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = CITIES[selectedIndex].nameFor(lang),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.v4_city)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            CITIES.forEachIndexed { i, c ->
                DropdownMenuItem(
                    text = { Text(c.nameFor(lang)) },
                    onClick = {
                        onSelect(i)
                        expanded = false
                    }
                )
            }
        }
    }
}
