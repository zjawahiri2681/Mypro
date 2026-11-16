package com.mypro.rfidguard.security

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.Build
import android.provider.Settings
import java.io.File
import javax.crypto.Cipher

/** System authentication sheet: strong biometrics or the phone's PIN / pattern / password. */
object Auth {
    private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /**
     * Asks the owner to authenticate. With a [cipher] the Keystore releases that one operation only
     * after success. Without a screen lock there is nothing to ask, so [onSuccess] runs directly.
     */
    fun prompt(activity: Activity, title: String, subtitle: String, cipher: Cipher?, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val secure = activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
        if (!secure) {
            if (cipher == null) onSuccess() else onError("Set a screen lock on the phone first.")
            return
        }
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .setConfirmationRequired(true)
            .build()
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                val cancelled = errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED || errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                onError(if (cancelled) "" else (errString?.toString() ?: "Authentication failed"))
            }
        }
        try {
            if (cipher != null) prompt.authenticate(BiometricPrompt.CryptoObject(cipher), CancellationSignal(), activity.mainExecutor, callback)
            else prompt.authenticate(CancellationSignal(), activity.mainExecutor, callback)
        } catch (e: RuntimeException) {
            onError(e.message ?: "Authentication is not available")
        }
    }
}

/** Advisory checks on the phone itself. They inform the owner; they are not a defence on their own. */
data class DeviceStatus(
    val secureLock: Boolean,
    val rootSuspected: Boolean,
    val usbDebugging: Boolean,
    val debugBuild: Boolean,
) {
    companion object {
        fun probe(activity: Activity): DeviceStatus {
            val secure = activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
            val suPaths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/app/Superuser.apk", "/data/adb/magisk")
            val root = (Build.TAGS?.contains("test-keys") == true) || suPaths.any { runCatching { File(it).exists() }.getOrDefault(false) }
            val adb = runCatching { Settings.Global.getInt(activity.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 }.getOrDefault(false)
            val debug = activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
            return DeviceStatus(secure, root, adb, debug)
        }
    }
}
