package com.mypro.familytalk.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mypro.familytalk.FamilyTalkApp
import com.mypro.familytalk.core.files.FileTransfer
import com.mypro.familytalk.data.ChatMsg
import com.mypro.familytalk.data.FAMILY_CONV
import com.mypro.familytalk.data.MsgStatus
import java.io.File
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(app: FamilyTalkApp, nav: Nav, conv: String) {
    val ctx = LocalContext.current
    val history by app.chats.history.collectAsStateWithLifecycle()
    val roster by app.family.roster.collectAsStateWithLifecycle()
    val msgs = history.chats.filter { it.conv == conv }
    val member = roster?.byId(conv)
    val title = if (conv == FAMILY_CONV) "Family group" else member?.name ?: "Former member"
    var text by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val cr = ctx.contentResolver
        val (name, size) = cr.query(uri, null, null, null, null)?.use { c ->
            c.moveToFirst(); c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) to c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE))
        } ?: ("file" to 0L)
        if (size > FileTransfer.INLINE_LIMIT) { Toast.makeText(ctx, "Files up to 25 MB for now", Toast.LENGTH_LONG).show(); return@rememberLauncherForActivityResult }
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return@rememberLauncherForActivityResult
        app.chats.sendFile(conv, name, cr.getType(uri) ?: "application/octet-stream", bytes)
    }
    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size)
        app.chats.markRead(conv)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(title, member?.color ?: 0, 36.dp); Spacer(Modifier.size(10.dp))
                        Column {
                            Text(title, maxLines = 1)
                            if (member != null) Text("Ext ${member.ext}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    val targets = if (conv == FAMILY_CONV) roster?.members?.filter { it.id != app.family.myId }.orEmpty() else listOfNotNull(member)
                    if (targets.isNotEmpty()) {
                        IconButton({ app.calls.call(targets, false) }) { Icon(Icons.Filled.Call, "Voice call") }
                        IconButton({ app.calls.call(targets, true) }) { Icon(Icons.Filled.Videocam, "Video call") }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.size(6.dp))
                        Text("Messages and files are end-to-end encrypted. Only your family's phones can read them.", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
                items(msgs, key = { it.from + it.id }) { m ->
                    Bubble(m, mine = m.from == app.family.myId, sender = if (conv == FAMILY_CONV) roster?.byId(m.from)?.name else null) { f -> openFile(ctx, f) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ pick.launch(arrayOf("*/*")) }) { Icon(Icons.Filled.AttachFile, "Send a file") }
                OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Message") }, maxLines = 5, shape = RoundedCornerShape(24.dp))
                IconButton({ app.chats.sendText(conv, text); text = "" }, enabled = text.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMsg, mine: Boolean, sender: String?, open: (File) -> Unit) {
    val bg = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 300.dp)
                .background(bg, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (sender != null && !mine) Text(sender, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            m.text?.let { Text(it, color = fg, style = MaterialTheme.typography.bodyLarge) }
            m.file?.let { f ->
                Row(
                    Modifier.clickable(enabled = f.localPath != null) { f.localPath?.let { open(File(it)) } }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Description, null, tint = fg)
                    Spacer(Modifier.size(8.dp))
                    Column {
                        Text(f.name, color = fg, maxLines = 2)
                        Text(humanSize(f.size) + if (f.localPath == null && m.status != MsgStatus.FAILED) " · receiving…" else "", color = fg, fontSize = 12.sp)
                    }
                }
                if (f.localPath == null && m.status != MsgStatus.FAILED) LinearProgressIndicator({ f.progress }, Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.at)), fontSize = 11.sp, color = fg.copy(alpha = 0.8f))
                if (mine) {
                    Spacer(Modifier.size(4.dp))
                    when (m.status) {
                        MsgStatus.READ -> Icon(Icons.Filled.DoneAll, "Read", Modifier.size(14.dp), tint = Brand.SandLight)
                        MsgStatus.DELIVERED -> Icon(Icons.Filled.DoneAll, "Delivered", Modifier.size(14.dp), tint = fg)
                        MsgStatus.FAILED -> Icon(Icons.Filled.ErrorOutline, "Failed", Modifier.size(14.dp), tint = fg)
                        else -> Icon(Icons.Filled.Done, "Sent", Modifier.size(14.dp), tint = fg)
                    }
                }
                if (!mine && m.status == MsgStatus.FAILED) Text(" · damaged in transit, ask to resend", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun humanSize(b: Long) = when {
    b >= 1 shl 20 -> "%.1f MB".format(b / 1048576.0)
    b >= 1024 -> "%d KB".format(b / 1024)
    else -> "$b B"
}

private fun openFile(ctx: android.content.Context, f: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val mime = ctx.contentResolver.getType(uri) ?: "*/*"
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open with"))
    }.onFailure { Toast.makeText(ctx, "No app can open this file", Toast.LENGTH_SHORT).show() }
}
