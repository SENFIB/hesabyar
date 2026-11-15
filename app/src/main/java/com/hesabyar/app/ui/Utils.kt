package com.hesabyar.app.ui

import android.content.res.Resources
import android.icu.text.SimpleDateFormat as IcuDateFormat
import android.icu.util.Calendar as IcuCalendar
import android.icu.util.TimeZone as IcuTimeZone
import android.icu.util.ULocale
import androidx.annotation.StringRes
import com.hesabyar.app.R
import java.util.Date
import java.util.Locale

// تقویم و منطقه‌ی زمانی هر زبان
enum class CalendarType(val tag: String, val timeZoneId: String, @StringRes val label: Int) {
    PERSIAN("fa-IR-u-ca-persian", "Asia/Tehran", R.string.cal_persian),
    HIJRI("ar-SA-u-ca-islamic-umalqura", "Asia/Riyadh", R.string.cal_hijri),
    GREGORIAN("en-US-u-ca-gregory", "Europe/London", R.string.cal_gregorian);

    val locale: ULocale get() = ULocale.forLanguageTag(tag)
    val timeZone: IcuTimeZone get() = IcuTimeZone.getTimeZone(timeZoneId)

    companion object {
        fun forLanguage(lang: String): CalendarType = when (lang) {
            "fa" -> PERSIAN
            "ar" -> HIJRI
            else -> GREGORIAN
        }
    }
}

enum class ReportPeriod(@StringRes val label: Int) {
    DAILY(R.string.period_daily),
    WEEKLY(R.string.period_weekly),
    MONTHLY(R.string.period_monthly),
    YEARLY(R.string.period_yearly)
}

data class PeriodRange(val start: Long, val end: Long, val type: CalendarType)

// زبان و منطقه‌ی زمانی خود گوشی
fun deviceLocale(): ULocale = ULocale.forLocale(Resources.getSystem().configuration.locales[0])

fun deviceTimeZone(): IcuTimeZone = IcuTimeZone.getDefault()

// فرمت تاریخ و ساعت با تقویم و منطقه‌ی دلخواه
fun formatWith(millis: Long, pattern: String, locale: ULocale, zone: IcuTimeZone): String {
    val formatter = IcuDateFormat(pattern, locale)
    formatter.setTimeZone(zone)
    return formatter.format(Date(millis))
}

fun newCalendar(type: CalendarType, millis: Long): IcuCalendar {
    val cal = IcuCalendar.getInstance(type.timeZone, type.locale)
    cal.timeInMillis = millis
    return cal
}

private fun IcuCalendar.zeroTime() {
    set(IcuCalendar.HOUR_OF_DAY, 0)
    set(IcuCalendar.MINUTE, 0)
    set(IcuCalendar.SECOND, 0)
    set(IcuCalendar.MILLISECOND, 0)
}

// محاسبه‌ی بازه‌ی زمانی گزارش
// روزانه/هفتگی/ماهانه با تقویم زبان، سالانه با تقویم سال مالی
fun periodRange(period: ReportPeriod, now: Long, langType: CalendarType, fiscal: CalendarType): PeriodRange {
    val c = newCalendar(langType, now)
    c.zeroTime()

    return when (period) {
        ReportPeriod.DAILY -> {
            val start = c.timeInMillis
            c.add(IcuCalendar.DAY_OF_MONTH, 1)
            PeriodRange(start, c.timeInMillis, langType)
        }
        ReportPeriod.WEEKLY -> {
            // ۷ روز اخیر (امروز و ۶ روز قبل)
            c.add(IcuCalendar.DAY_OF_MONTH, -6)
            val start = c.timeInMillis
            c.add(IcuCalendar.DAY_OF_MONTH, 7)
            PeriodRange(start, c.timeInMillis, langType)
        }
        ReportPeriod.MONTHLY -> {
            c.set(IcuCalendar.DAY_OF_MONTH, 1)
            val start = c.timeInMillis
            c.add(IcuCalendar.MONTH, 1)
            PeriodRange(start, c.timeInMillis, langType)
        }
        ReportPeriod.YEARLY -> {
            val f = newCalendar(fiscal, now)
            f.zeroTime()
            f.set(IcuCalendar.MONTH, 0)
            f.set(IcuCalendar.DAY_OF_MONTH, 1)
            val start = f.timeInMillis
            f.add(IcuCalendar.YEAR, 1)
            PeriodRange(start, f.timeInMillis, fiscal)
        }
    }
}

// ---------- توابع کمکی عددی ----------

fun formatMoney(value: Long): String = String.format(Locale.US, "%,d", value)

// تبدیل ارقام فارسی/عربی به انگلیسی تا محاسبه درست انجام شود
fun toLatinDigits(input: String): String = input.map { c ->
    when (c) {
        in '۰'..'۹' -> '0' + (c - '۰')
        in '٠'..'٩' -> '0' + (c - '٠')
        else -> c
    }
}.joinToString("")
