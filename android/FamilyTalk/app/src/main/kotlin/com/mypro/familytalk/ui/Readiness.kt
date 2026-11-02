package com.mypro.familytalk.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

private data class Step(val title: String, val why: String, val button: String, val skippable: Boolean = false, val action: () -> Unit)

/**
 * One clear step at a time: why FamilyTalk needs something, then the system prompt.
 * Shown on Home until calls can actually ring on this phone.
 */
@Composable
fun ReadinessBanner() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { tick++ } }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    var skippedCamera by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    // Reading `tick` re-checks every time the screen resumes (e.g. back from Settings).
    val step = (if (tick >= 0) nextStep(ctx, skippedCamera) { list -> perms.launch(list.toTypedArray()) } else null) ?: return
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(step.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(step.why, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(vertical = 6.dp))
            androidx.compose.foundation.layout.Row {
                Button(step.action) { Text(step.button) }
                if (step.skippable) androidx.compose.material3.TextButton({ skippedCamera = true }) { Text("Not now") }
            }
        }
    }
}

private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

private fun nextStep(ctx: Context, skipCamera: Boolean, request: (List<String>) -> Unit): Step? {
    if (!granted(ctx, Manifest.permission.RECORD_AUDIO)) {
        return Step("Allow the microphone", "So your family can hear you on calls. FamilyTalk only uses it during a call.", "Allow microphone") {
            request(listOf(Manifest.permission.RECORD_AUDIO) + if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList())
        }
    }
    if (Build.VERSION.SDK_INT >= 33 && !granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) {
        return Step("Allow notifications", "Without this, calls can't ring and you won't see new messages.", "Allow notifications") {
            request(listOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }
    if (Build.VERSION.SDK_INT >= 34 && !ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
        return Step("Let calls show on the lock screen", "So an incoming family call fills the screen like a normal phone call.", "Open setting") {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + ctx.packageName)))
        }
    }
    if (!ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)) {
        return Step("Keep FamilyTalk awake", "Some phones stop apps in the background to save battery. Allow FamilyTalk so calls still ring when the screen is off.", "Allow") {
            runCatching {
                @Suppress("BatteryLife")
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)))
            }.onFailure { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }
    if (!skipCamera && !granted(ctx, Manifest.permission.CAMERA)) {
        return Step("Allow the camera (for video calls)", "Only needed for video calls and scanning codes.", "Allow camera", skippable = true) {
            request(listOf(Manifest.permission.CAMERA))
        }
    }
    return null
}
