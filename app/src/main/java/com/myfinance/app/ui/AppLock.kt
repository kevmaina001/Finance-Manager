package com.myfinance.app.ui

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Fingerprint (or face) lock, with the phone's PIN/pattern as a fallback so you can't get locked out. */
object AppLock {
    /** Re-lock if the app was in the background longer than this. */
    const val GRACE_MS = 30_000L

    private val authenticators =
        if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** True if the phone has a fingerprint, face or screen lock set up that we can use. */
    fun available(a: FragmentActivity) =
        BiometricManager.from(a).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

    fun hasFingerprint(a: FragmentActivity) =
        BiometricManager.from(a).canAuthenticate(if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG else BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(a: FragmentActivity, title: String = "Unlock My Finance", onResult: (Boolean) -> Unit) {
        val p = BiometricPrompt(a, ContextCompat.getMainExecutor(a), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
            override fun onAuthenticationError(code: Int, msg: CharSequence) = onResult(false)
            // a single bad finger read just lets the user try again; the prompt stays open
        })
        p.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle("Use your fingerprint — or your phone PIN")
                .setAllowedAuthenticators(authenticators)
                .build(),
        )
    }
}

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    Box(Modifier.fillMaxSize().background(HeroBrush), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.size(84.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Lock, null, tint = Color.White, modifier = Modifier.size(40.dp))
            }
            Text("My Finance is locked", color = Color.White, style = MaterialTheme.typography.headlineMedium)
            Text("Touch the fingerprint sensor to open your finances.", color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
            Button(onClick = onUnlock, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = BrandDark)) {
                Text("Unlock")
            }
        }
    }
}
