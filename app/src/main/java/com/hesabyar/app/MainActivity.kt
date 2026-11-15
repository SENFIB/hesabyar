package com.hesabyar.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import com.hesabyar.app.ui.AppBackground
import com.hesabyar.app.ui.HesabyarScreen
import com.hesabyar.app.ui.HesabyarTheme
import com.hesabyar.app.ui.LockScreen
import com.hesabyar.app.ui.ThemeStore

class MainActivity : AppCompatActivity() {

    companion object {
        // در companion نگه داشته می‌شود تا با تغییر زبان (ساخت دوباره اکتیویتی) رمز دوباره پرسیده نشود
        val locked = mutableStateOf(true)

        // وقتی برنامه برای گرفتن اجازه (مثلاً موقعیت) از خودش بیرون می‌رود، قفل نشود
        var skipAutoLock = false
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeStore.load(this)
        ClockFormat.load(this)
        setContent {
            HesabyarTheme {
                AppBackground {
                    if (locked.value) {
                        LockScreen(onUnlocked = { locked.value = false })
                    } else {
                        HesabyarScreen()
                        FirstRunNotice()
                    }
                }
            }
        }
    }
}
