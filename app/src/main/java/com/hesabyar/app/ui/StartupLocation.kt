package com.hesabyar.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.hesabyar.app.MainActivity

private const val WEATHER_PREFS = "hesabyar_weather"
private const val LOC_ASKED_KEY = "loc_asked"

private fun myLocationLabel(lang: String): String = when (lang) {
    "fa" -> "موقعیت من"
    "ar" -> "موقعي"
    else -> "My location"
}

/**
 * Put this once inside the main screen body (HesabyarScreen).
 * Asks for location permission on first entry, then gets the city automatically.
 */
@Composable
fun StartupLocationRequest() {
    val ctx = LocalContext.current
    val lang = currentLang()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        MainActivity.skipAutoLock = false
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            WeatherStore.locateFromGps(ctx, myLocationLabel(lang), lang) { _ -> }
        }
    }

    LaunchedEffect(Unit) {
        val prefs = ctx.getSharedPreferences(WEATHER_PREFS, Context.MODE_PRIVATE)
        val hasFine = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val granted = hasFine || hasCoarse
        val asked = prefs.getBoolean(LOC_ASKED_KEY, false)

        if (!granted && !asked) {
            prefs.edit().putBoolean(LOC_ASKED_KEY, true).apply()
            MainActivity.skipAutoLock = true
            launcher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else if (granted && !prefs.contains("geo_lat")) {
            // Permission already given but no city saved yet: locate now.
            WeatherStore.locateFromGps(ctx, myLocationLabel(lang), lang) { _ -> }
        }
    }
}
