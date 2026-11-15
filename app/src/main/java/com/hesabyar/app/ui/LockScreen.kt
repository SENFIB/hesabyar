package com.hesabyar.app.ui

import android.content.Context
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.security.MessageDigest

object PinStore {
    private const val PREFS = "hesabyar_lock"
    private const val KEY = "pin_hash"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun hash(pin: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(("hesabyar::$pin").toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hasPin(ctx: Context): Boolean = prefs(ctx).getString(KEY, null) != null

    fun setPin(ctx: Context, pin: String) {
        prefs(ctx).edit().putString(KEY, hash(pin)).apply()
    }

    fun check(ctx: Context, pin: String): Boolean =
        prefs(ctx).getString(KEY, null) == hash(pin)
}

fun fingerprintAvailable(ctx: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)

// نمایش پنجره اثر انگشت. هر خطا یا لغو، onFail را صدا می‌زند تا ورود با رمز نشان داده شود.
@Suppress("NewApi")
private fun showFingerprint(
    ctx: Context,
    title: String,
    cancelText: String,
    onSuccess: () -> Unit,
    onFail: () -> Unit
) {
    val executor = ContextCompat.getMainExecutor(ctx)
    val prompt = BiometricPrompt.Builder(ctx)
        .setTitle(title)
        .setNegativeButton(cancelText, executor, DialogInterface.OnClickListener { _, _ -> onFail() })
        .build()
    prompt.authenticate(
        CancellationSignal(),
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                onFail()
            }
        }
    )
}

// قفل برنامه: اگر رمز ساخته نشده، صفحه ساخت رمز نشان داده می‌شود.
// اگر رمز هست و گوشی اثر انگشت دارد، اول اثر انگشت خواسته می‌شود و دکمه رمز عبور هم هست.
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val ctx = LocalContext.current
    if (!PinStore.hasPin(ctx)) {
        PinSetup(onUnlocked = onUnlocked)
        return
    }

    val canFp = remember { fingerprintAvailable(ctx) }
    var usePin by rememberSaveable { mutableStateOf(!canFp) }
    var autoTried by rememberSaveable { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }

    val fpTitle = tr("ورود با اثر انگشت", "Sign in with fingerprint", "تسجيل الدخول بالبصمة")
    val fpCancel = tr("ورود با رمز عبور", "Use password", "استخدام كلمة المرور")
    val wrongText = tr("رمز عبور اشتباه است", "Wrong password", "كلمة المرور غير صحيحة")

    fun startFp() {
        showFingerprint(ctx, fpTitle, fpCancel, onUnlocked) { usePin = true }
    }

    LaunchedEffect(Unit) {
        if (canFp && !autoTried) {
            autoTried = true
            startFp()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            tr("برنامه قفل است", "App is locked", "التطبيق مقفل"),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(20.dp))

        if (usePin) {
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = digitsOnly(it).take(8) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                label = { Text(tr("رمز عبور", "Password", "كلمة المرور")) }
            )
            pinError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = {
                    if (PinStore.check(ctx, pin)) {
                        onUnlocked()
                    } else {
                        pinError = wrongText
                        pin = ""
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(tr("ورود", "Enter", "دخول"))
            }
            if (canFp) {
                TextButton(onClick = { usePin = false; startFp() }) {
                    Text(fpTitle)
                }
            }
        } else {
            Button(onClick = { startFp() }, modifier = Modifier.fillMaxWidth()) {
                Text(fpTitle)
            }
            TextButton(onClick = { usePin = true }) {
                Text(fpCancel)
            }
        }
    }
}

// ساخت رمز: دو کادر، یکی برای رمز و یکی برای تکرار آن
@Composable
private fun PinSetup(onUnlocked: () -> Unit) {
    val ctx = LocalContext.current
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            tr("رمز عبور بسازید", "Create a password", "أنشئ كلمة مرور"),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(20.dp))

        OutlinedTextField(
            value = p1,
            onValueChange = { p1 = digitsOnly(it).take(8) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            label = { Text(tr("رمز عبور (حداقل ۴ رقم)", "Password (at least 4 digits)", "كلمة المرور (4 أرقام على الأقل)")) }
        )
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(
            value = p2,
            onValueChange = { p2 = digitsOnly(it).take(8) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            label = { Text(tr("تکرار رمز عبور", "Repeat password", "تكرار كلمة المرور")) }
        )

        err?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                val a = p1
                val b = p2
                when {
                    a.length < 4 -> err = tr("حداقل ۴ رقم وارد کنید", "Enter at least 4 digits", "أدخل 4 أرقام على الأقل")
                    a != b -> err = tr("دو رمز یکسان نیستند", "The passwords do not match", "كلمتا المرور غير متطابقتين")
                    else -> {
                        PinStore.setPin(ctx, a)
                        onUnlocked()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(tr("ذخیره و ورود", "Save and enter", "حفظ ودخول"))
        }
    }
}
