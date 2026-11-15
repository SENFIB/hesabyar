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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.security.MessageDigest

// کد خطاهای BiometricPrompt (به صورت عدد تا به نسخه‌های مختلف Android وابسته نباشد)
private const val ERR_NEGATIVE = 13
private const val ERR_LOCKOUT = 7
private const val ERR_LOCKOUT_PERMANENT = 9

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

// نمایش پنجره اثر انگشت
// onSuccess: اثر انگشت درست بود
// onNegative: کاربر دکمه «ورود با رمز عبور» را زد
// onError: خطای دیگر (مثل اثر انگشت نامعتبر، لغو سیستمی یا قفل شدن موقت)
@Suppress("NewApi")
private fun showFingerprint(
    ctx: Context,
    title: String,
    cancelText: String,
    onSuccess: () -> Unit,
    onNegative: () -> Unit,
    onError: (Int) -> Unit
) {
    val executor = ContextCompat.getMainExecutor(ctx)
    val prompt = BiometricPrompt.Builder(ctx)
        .setTitle(title)
        .setNegativeButton(cancelText, executor, DialogInterface.OnClickListener { _, _ -> onNegative() })
        .build()
    prompt.authenticate(
        CancellationSignal(),
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                // خطای دکمه منفی قبلاً با onNegative مدیریت شده
                if (errorCode != ERR_NEGATIVE) onError(errorCode)
            }
        }
    )
}

// قفل برنامه: اگر رمز ساخته نشده، صفحه ساخت رمز نشان داده می‌شود.
// اگر رمز هست: همیشه اول اثر انگشت خواسته می‌شود. رمز عبور فقط با انتخاب کاربر باز می‌شود.
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val ctx = LocalContext.current
    if (!PinStore.hasPin(ctx)) {
        PinSetup(onUnlocked = onUnlocked)
        return
    }

    val canFp = remember { fingerprintAvailable(ctx) }
    // این حالت هر بار که قفل فعال می‌شود از نو ساخته می‌شود، پس همیشه از اثر انگشت شروع می‌کند
    var usePin by remember { mutableStateOf(!canFp) }
    var attempt by remember { mutableStateOf(0) }
    var fpMessage by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }

    // متن‌ها باید داخل بدنه Compose ساخته شوند
    val fpTitle = tr("ورود با اثر انگشت", "Sign in with fingerprint", "تسجيل الدخول بالبصمة")
    val fpCancel = tr("ورود با رمز عبور", "Use password", "استخدام كلمة المرور")
    val fpHint = tr("اثر انگشت خود را روی حسگر بگذارید", "Place your finger on the sensor", "ضع إصبعك على الحساس")
    val fpFailText = tr("اثر انگشت شناسایی نشد. دوباره امتحان کنید.", "Fingerprint not recognised. Try again.", "لم يتم التعرف على البصمة. حاول مرة أخرى.")
    val lockoutText = tr("اثر انگشت موقتاً قفل شده است. با رمز عبور وارد شوید.", "Fingerprint is temporarily locked. Use your password.", "البصمة مقفلة مؤقتاً. استخدم كلمة المرور.")
    val fpRetry = tr("تلاش دوباره با اثر انگشت", "Try fingerprint again", "حاول بالبصمة مرة أخرى")
    val pinLabel = tr("رمز عبور", "Password", "كلمة المرور")
    val enterText = tr("ورود", "Enter", "دخول")
    val wrongText = tr("رمز عبور اشتباه است", "Wrong password", "كلمة المرور غير صحيحة")
    val appLockedText = tr("برنامه قفل است", "App is locked", "التطبيق مقفل")
    val backToFpText = tr("ورود با اثر انگشت", "Sign in with fingerprint", "تسجيل الدخول بالبصمة")

    // هر بار که attempt عوض شود (بار اول، دکمه تلاش دوباره، یا برگشت از رمز به اثر انگشت) پنجره اثر انگشت باز می‌شود
    LaunchedEffect(attempt) {
        if (canFp && !usePin) {
            // کمی صبر تا برنامه کاملاً فعال شود، وگرنه پنجره اثر انگشت خطا می‌دهد
            delay(350)
            showFingerprint(
                ctx = ctx,
                title = fpTitle,
                cancelText = fpCancel,
                onSuccess = onUnlocked,
                onNegative = {
                    usePin = true
                    fpMessage = null
                },
                onError = { code ->
                    fpMessage = if (code == ERR_LOCKOUT || code == ERR_LOCKOUT_PERMANENT) lockoutText else fpFailText
                }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(appLockedText, style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(20.dp))

        if (usePin) {
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = digitsOnly(it).take(8) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                label = { Text(pinLabel) }
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
                Text(enterText)
            }
            if (canFp) {
                TextButton(onClick = {
                    pinError = null
                    fpMessage = null
                    usePin = false
                    attempt++
                }) {
                    Text(backToFpText)
                }
            }
        } else {
            Text(fpHint, style = MaterialTheme.typography.bodyLarge)
            fpMessage?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    fpMessage = null
                    attempt++
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(fpRetry)
            }
            TextButton(onClick = {
                pinError = null
                usePin = true
            }) {
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

    // متن خطاها باید اینجا (داخل بدنه Compose) ساخته شوند، نه داخل onClick
    val shortErr = tr("حداقل ۴ رقم وارد کنید", "Enter at least 4 digits", "أدخل 4 أرقام على الأقل")
    val mismatchErr = tr("دو رمز یکسان نیستند", "The passwords do not match", "كلمتا المرور غير متطابقتين")

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
                    a.length < 4 -> err = shortErr
                    a != b -> err = mismatchErr
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
