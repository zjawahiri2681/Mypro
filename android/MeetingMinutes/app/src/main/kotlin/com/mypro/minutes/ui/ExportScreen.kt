package com.mypro.minutes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mypro.minutes.core.AiPrompt
import com.mypro.minutes.core.EntryType

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExportScreen(vm: AppViewModel) {
    val m = vm.meeting ?: return
    val context = LocalContext.current
    val md = remember(m, vm.includeChronological) { vm.markdown() }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri: Uri? ->
        if (uri != null) toast(context, if (vm.saveTo(context, uri)) "Saved" else "Could not save the file")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transcript") },
                navigationIcon = { IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { IconButton(onClick = vm::editDetails) { Icon(Icons.Filled.Edit, contentDescription = "Meeting details") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(m.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${m.entries.size} entries · ${m.entries.count { it.type == EntryType.DECISION }} decisions · " +
                        "${m.entries.count { it.type == EntryType.ACTION }} actions · ${vm.fileName()}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Also add a chronological transcript", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = vm.includeChronological, onCheckedChange = { vm.includeChronological = it })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val uri = vm.exportUri(context)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, vm.fileName())
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, "Share transcript (.md)"))
                    }) { Text("Share .md") }
                    OutlinedButton(onClick = { saveLauncher.launch(vm.fileName()) }) { Text("Save to…") }
                    OutlinedButton(onClick = {
                        copy(context, "AI prompt", AiPrompt.minutes(m))
                        toast(context, "Prompt copied. Paste it into your AI chat with the .md file attached.")
                    }) { Text("Copy AI prompt") }
                    val audio = remember(m.audioFiles) { vm.audioUris(context) }
                    if (audio.isNotEmpty()) {
                        OutlinedButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                type = "audio/aac"
                                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(audio))
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, "Share audio"))
                        }) { Text("Share audio") }
                    }
                    OutlinedButton(onClick = vm::reopen) { Text(if (m.endedAt != null) "Reopen meeting" else "Back to meeting") }
                }
            }
            HorizontalDivider()
            SelectionContainer(Modifier.weight(1f)) {
                Text(
                    md,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                )
            }
        }
    }
}

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

private fun copy(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
