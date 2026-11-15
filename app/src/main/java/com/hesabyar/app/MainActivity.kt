package com.hesabyar.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.*
import com.hesabyar.app.ui.AppBackground
import com.hesabyar.app.ui.ClockFormat
import com.hesabyar.app.ui.FirstRunNotice
import com.hesabyar.app.ui.HesabyarScreen
import com.hesabyar.app.ui.HesabyarTheme
import com.hesabyar.app.ui.LanguageGate
import com.hesabyar.app.ui.LockScreen
import com.hesabyar.app.ui.PasscodeOffer
import com.hesabyar.app.ui.PinStore
import com.hesabyar.app.ui.ThemeStore
import com.hesabyar.app.ui.languageChosen

class MainActivity : AppCompatActivity() {

    companion object {
        val locked: MutableState<Boolean> = mutableStateOf(true)
        var skipAutoLock: Boolean = false
        // زمان رفتن برنامه به پس‌زمینه (صفر یعنی الان برنمی‌گردیم)
        var backgroundAt: Long = 0L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeStore.load(this)
        ClockFormat.load(this)

        if (!PinStore.isEnabled(this)) {
            locked.value = false
        } else if (savedInstanceState == null) {
            locked.value = true
        }

        setContent {
            HesabyarTheme {
                AppBackground {
                    var langDone by remember { mutableStateOf(languageChosen(this@MainActivity)) }
                    var offerDone by remember { mutableStateOf(PinStore.wasAsked(this@MainActivity)) }

                    when {
                        !langDone -> LanguageGate(onChosen = { langDone = true })
                        !offerDone -> PasscodeOffer(onDone = { offerDone = true })
                        locked.value -> LockScreen(onUnlocked = { locked.value = false })
                        else -> {
                            HesabyarScreen()
                            FirstRunNotice()
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations || skipAutoLock) return
        if (PinStore.isEnabled(this)) {
            if (PinStore.delayMs(this) == 0L) {
                locked.value = true
            } else {
                backgroundAt = System.currentTimeMillis()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val skipped = skipAutoLock
        skipAutoLock = false
        if (!skipped && backgroundAt > 0L) {
            val elapsed = System.currentTimeMillis() - backgroundAt
            if (PinStore.isEnabled(this) && elapsed >= PinStore.delayMs(this)) {
                locked.value = true
            }
        }
        backgroundAt = 0L
    }
}
