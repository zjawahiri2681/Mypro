package com.mypro.familytalk.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mypro.familytalk.FamilyTalkApp
import com.mypro.familytalk.update.UpdateState

/** Card on Home when a verified update is waiting. */
@Composable
fun UpdateBanner(app: FamilyTalkApp) {
    val st by app.updater.state.collectAsStateWithLifecycle()
    val ready = st as? UpdateState.Ready ?: return
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Update ready", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("Version ${ready.info.version} (build ${ready.info.build}) is downloaded and checked.", color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Button({ app.updater.install() }) { Text("Install") }
        }
    }
}

@Composable
fun UpdateSettings(app: FamilyTalkApp) {
    val data by app.family.data.collectAsStateWithLifecycle()
    val st by app.updater.state.collectAsStateWithLifecycle()
    ListItem(
        leadingContent = { Icon(Icons.Filled.SystemUpdate, null) },
        headlineContent = { Text("Update automatically") },
        supportingContent = { Text("Checks this project's GitHub page twice a day and downloads new versions. Only the version number is sent to GitHub.") },
        trailingContent = { Switch(data.autoUpdate, { app.family.setAutoUpdate(it) }) },
    )
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text("Installed: version ${app.updater.currentVersion} (build ${app.updater.currentBuild})", color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (val s = st) {
            UpdateState.Idle -> {}
            UpdateState.Checking -> Text("Checking…")
            UpdateState.UpToDate -> Text("You have the latest version.", color = trustOk())
            is UpdateState.Available -> Text("Version ${s.info.version} (build ${s.info.build}) is available.")
            is UpdateState.Downloading -> {
                Text("Downloading build ${s.info.build}…")
                LinearProgressIndicator({ s.progress }, Modifier.fillMaxWidth().padding(vertical = 6.dp))
            }
            is UpdateState.Ready -> Text("Build ${s.info.build} is ready to install.", color = trustOk())
            UpdateState.Installing -> Text("Installing… FamilyTalk will restart.")
            is UpdateState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
        }
        Row(Modifier.padding(vertical = 8.dp)) {
            when (val s = st) {
                is UpdateState.Ready -> Button({ app.updater.install() }) { Text("Install now") }
                is UpdateState.Available -> Button({ app.updater.download(s.info) }) { Text("Download") }
                is UpdateState.Downloading, UpdateState.Checking, UpdateState.Installing -> {}
                else -> OutlinedButton({ app.updater.check(download = true) }) { Text("Check for updates") }
            }
        }
    }
}

/** Version line + "Check for updates" for screens before joining a family (no Settings yet). */
@Composable
fun CompactUpdate(app: FamilyTalkApp) {
    val st by app.updater.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text("FamilyTalk ${app.updater.currentVersion} (build ${app.updater.currentBuild})", color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (val s = st) {
            UpdateState.Checking -> Text("Checking for updates…")
            UpdateState.UpToDate -> Text("You have the latest version.", color = trustOk())
            is UpdateState.Available -> Text("Version ${s.info.version} (build ${s.info.build}) is available.")
            is UpdateState.Downloading -> LinearProgressIndicator({ s.progress }, Modifier.fillMaxWidth().padding(vertical = 6.dp))
            is UpdateState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
            else -> {}
        }
        when (val s = st) {
            is UpdateState.Ready -> Button({ app.updater.install() }) { Text("Install update") }
            is UpdateState.Available -> OutlinedButton({ app.updater.download(s.info) }) { Text("Download update") }
            is UpdateState.Downloading, UpdateState.Checking, UpdateState.Installing -> {}
            else -> androidx.compose.material3.TextButton({ app.updater.check(download = true) }) { Text("Check for updates") }
        }
    }
}
