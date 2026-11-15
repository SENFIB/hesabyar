package com.hesabyar.app.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val CLOCK_PREFS = "hesabyar_prefs"
private const val CLOCK_KEY = "clock_24h"

// تبدیل ارقام فارسی و عربی به انگلیسی
fun asciiDigits(s: String): String {
    val sb = StringBuilder(s.length)
    for (ch in s) {
        val c = when (ch) {
            in '۰'..'۹' -> '0' + (ch - '۰')
            in '٠'..'٩' -> '0' + (ch - '٠')
            else -> ch
        }
        sb.append(c)
    }
    return sb.toString()
}

// فقط رقم‌ها (انگلیسی) را برمی‌گرداند
fun digitsOnly(s: String): String = asciiDigits(s).filter { it in '0'..'9' }

// نمایش مبلغ با جداکننده سه‌رقمی هنگام تایپ، مثل 1,234,567
class ThousandsVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val len = raw.length
        val out = StringBuilder()
        val o2t = IntArray(len + 1)
        var commas = 0
        for (p in 0..len) {
            if (p in 1 until len && (len - p) % 3 == 0) {
                commas++
                out.append(',')
            }
            o2t[p] = p + commas
            if (p < len) out.append(raw[p])
        }
        val outStr = out.toString()

        val t2o = IntArray(outStr.length + 1)
        var o = 0
        for (t in 0..outStr.length) {
            t2o[t] = o
            if (t < outStr.length && outStr[t] != ',') o++
        }

        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = o2t[offset.coerceIn(0, len)]
            override fun transformedToOriginal(offset: Int): Int = t2o[offset.coerceIn(0, outStr.length)]
        }
        return TransformedText(AnnotatedString(outStr), mapping)
    }
}

// ================= ساعت ۱۲ / ۲۴ ساعته =================

object ClockFormat {
    private val state = MutableStateFlow(true)
    val is24h: StateFlow<Boolean> get() = state

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(CLOCK_PREFS, Context.MODE_PRIVATE)

    // باید یک بار در MainActivity صدا زده شود
    fun load(ctx: Context) {
        state.value = prefs(ctx).getBoolean(CLOCK_KEY, true)
    }

    fun set(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(CLOCK_KEY, value).apply()
        state.value = value
    }

    fun timePattern(is24: Boolean): String = if (is24) "HH:mm:ss" else "hh:mm:ss a"
}

// دکمه‌های انتخاب ساعت ۱۲ یا ۲۴ ساعته (برای صفحه تنظیمات)
@Composable
fun ClockFormatRow(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val is24 by ClockFormat.is24h.collectAsState()
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = is24,
            onClick = { ClockFormat.set(ctx, true) },
            label = { Text(tr("۲۴ ساعته", "24-hour", "٢٤ ساعة")) }
        )
        FilterChip(
            selected = !is24,
            onClick = { ClockFormat.set(ctx, false) },
            label = { Text(tr("۱۲ ساعته", "12-hour", "١٢ ساعة")) }
        )
    }
}
