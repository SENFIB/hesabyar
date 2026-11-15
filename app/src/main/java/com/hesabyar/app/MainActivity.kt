package com.hesabyar.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hesabyar.app.ui.AppBackground
import com.hesabyar.app.ui.ClockFormat
import com.hesabyar.app.ui.FirstRunNotice
import com.hesabyar.app.ui.HesabyarScreen
import com.hesabyar.app.ui.HesabyarTheme
import com.hesabyar.app.ui.LanguageGate
import com.hesabyar.app.ui.LockScreen
import com.hesabyar.app.ui.ThemeStore
import com.hesabyar.app.ui.languageChosen

class MainActivity : AppCompatActivity() {

    companion object {
        val locked: MutableState<Boolean> = mutableStateOf(true)
        var skipAutoLock: Boolean = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeStore.load(this)
        ClockFormat.load(this)

        setContent {
            HesabyarTheme {
                AppBackground {
                    var langDone by remember { mutableStateOf(languageChosen(this@MainActivity)) }

                    when {
                        !langDone -> LanguageGate(onChosen = { langDone = true })
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
        if (!isChangingConfigurations && !skipAutoLock) {
            locked.value = true
        }
    }

    override fun onResume() {
        super.onResume()
        skipAutoLock = false
    }
}
