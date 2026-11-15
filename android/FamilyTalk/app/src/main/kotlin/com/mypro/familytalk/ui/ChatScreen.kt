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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LooksOne
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.activity.result.PickVisualMediaRequest
import android.graphics.Bitmap
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Keyboard
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
    val palette = chatPalette()
    var text by rememberSaveable { mutableStateOf("") }
    var emojiOpen by rememberSaveable { mutableStateOf(false) }
    var attachOpen by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<ChatMsg?>(null) }
    var viewing by remember { mutableStateOf<Pair<ChatMsg, Bitmap>?>(null) }
    var saveAfterPick by remember { mutableStateOf<ChatMsg?>(null) }
    val list = rememberLazyListState()

    fun send(uri: Uri?, viewOnce: Boolean) {
        uri ?: return
        val cr = ctx.contentResolver
        val (name, size) = cr.query(uri, null, null, null, null)?.use { c ->
            c.moveToFirst(); c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) to c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE))
        } ?: ("file" to 0L)
        if (size > FileTransfer.INLINE_LIMIT) { Toast.makeText(ctx, "Files up to 25 MB for now", Toast.LENGTH_LONG).show(); return }
        val mime = cr.getType(uri) ?: "application/octet-stream"
        if (viewOnce && !mime.startsWith("image/")) { Toast.makeText(ctx, "View once works with photos", Toast.LENGTH_LONG).show(); return }
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return
        app.chats.sendFile(conv, name, mime, bytes, viewOnce)
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { send(it, false) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { send(it, false) }
    val pickViewOnce = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { send(it, true) }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val m = saveAfterPick ?: return@rememberLauncherForActivityResult
        saveAfterPick = null
        if (uri != null) Toast.makeText(ctx, if (app.chats.saveTo(m, uri)) "Saved" else "Couldn't save", Toast.LENGTH_SHORT).show()
    }
    fun save(m: ChatMsg) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val ok = app.chats.saveToPhone(m) != null
            val where = if (m.file?.mime?.let { it.startsWith("image/") || it.startsWith("video/") } == true) "Gallery › FamilyTalk" else "Downloads › FamilyTalk"
            Toast.makeText(ctx, if (ok) "Saved to $where" else "Couldn't save", Toast.LENGTH_SHORT).show()
        } else { saveAfterPick = m; saveAs.launch(m.file?.name ?: "file") }
    }

    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size)
        app.chats.markRead(conv)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = appBarColors(),
                navigationIcon = { IconButton(nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(title, member?.color ?: 0, 36.dp); Spacer(Modifier.size(10.dp))
                        Column {
                            Text(title, maxLines = 1)
                            if (member != null) Text("Ext ${member.ext}", fontSize = 12.sp, color = onAppBar().copy(alpha = 0.85f))
                        }
                    }
                },
                actions = {
                    val targets = if (conv == FAMILY_CONV) roster?.members?.filter { it.id != app.family.myId }.orEmpty() else listOfNotNull(member)
                    if (targets.isNotEmpty()) {
                        IconButton({ app.calls.call(targets, true) }) { Icon(Icons.Filled.Videocam, "Video call") }
                        IconButton({ app.calls.call(targets, false) }) { Icon(Icons.Filled.Call, "Voice call") }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().background(palette.wallpaper).imePadding()) {
            LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
                            .background(palette.system, RoundedCornerShape(8.dp)).padding(8.dp),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Lock, null, Modifier.size(14.dp), tint = palette.systemText)
                        Spacer(Modifier.size(6.dp))
                        Text("Messages and files are end-to-end encrypted. Only your family's phones can read them.", fontSize = 13.sp,
                            color = palette.systemText, textAlign = TextAlign.Center)
                    }
                }
                items(msgs, key = { it.from + it.id }) { m ->
                    if (m.system) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                            Text(m.text ?: "", Modifier.background(palette.system, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
                                textAlign = TextAlign.Center, fontSize = 13.sp, color = palette.systemText)
                        }
                    } else Bubble(
                        app, m, mine = m.from == app.family.myId,
                        sender = if (conv == FAMILY_CONV) roster?.byId(m.from)?.let { it.name to it.color } else null,
                        onLongPress = { menuFor = m },
                        onTap = tap@{
                            val f = m.file ?: return@tap
                            val mine = m.from == app.family.myId
                            when {
                                m.deleted -> Unit
                                f.viewOnce && mine -> Toast.makeText(ctx, if (f.opened) "Opened" else "View-once photo: you can't open it again", Toast.LENGTH_SHORT).show()
                                f.viewOnce && f.opened -> Toast.makeText(ctx, "This view-once photo was already opened", Toast.LENGTH_SHORT).show()
                                f.localPath == null -> { app.chats.requestDownload(m); Toast.makeText(ctx, "Downloading…", Toast.LENGTH_SHORT).show() }
                                f.viewOnce -> app.chats.openViewOnce(m)?.let { viewing = m to it }
                                    ?: Toast.makeText(ctx, "Can't show this photo", Toast.LENGTH_SHORT).show()
                                else -> app.chats.exportForViewing(f.localPath ?: return@tap, f.name)?.let { openFile(ctx, it) }
                            }
                        },
                    )
                }
            }
            if (emojiOpen) EmojiPanel { e -> text += e }
            Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(26.dp)).padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton({ emojiOpen = !emojiOpen }) {
                        Icon(if (emojiOpen) Icons.Filled.Keyboard else Icons.Filled.EmojiEmotions, if (emojiOpen) "Hide emojis" else "Emojis",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextField(
                        text, { text = it }, Modifier.weight(1f), placeholder = { Text("Message") }, maxLines = 5,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                    Box {
                        IconButton({ attachOpen = true }) { Icon(Icons.Filled.AttachFile, "Attach", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        DropdownMenu(attachOpen, { attachOpen = false }) {
                            DropdownMenuItem({ Text("Photo or video") }, leadingIcon = { Icon(Icons.Filled.Image, null) }, onClick = {
                                attachOpen = false; pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                            })
                            DropdownMenuItem({ Text("View-once photo") }, leadingIcon = { Icon(Icons.Filled.LooksOne, null) }, onClick = {
                                attachOpen = false; pickViewOnce.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            })
                            DropdownMenuItem({ Text("Document") }, leadingIcon = { Icon(Icons.Filled.Description, null) }, onClick = {
                                attachOpen = false; pickFile.launch(arrayOf("*/*"))
                            })
                        }
                    }
                }
                Spacer(Modifier.size(6.dp))
                FilledIconButton(
                    { app.chats.sendText(conv, text); text = "" }, Modifier.size(50.dp), enabled = text.isNotBlank(),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Brand.TealLight, contentColor = Color.White,
                        disabledContainerColor = Brand.TealLight.copy(alpha = 0.5f), disabledContentColor = Color.White),
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            }
        }
    }

    menuFor?.let { m -> MessageMenu(app, m, onDismiss = { menuFor = null }, onSave = { save(m) }) }
    viewing?.let { (m, bmp) ->
        ViewOnceViewer(bmp) { viewing = null; app.chats.finishViewOnce(m) }
    }
}

@Composable
private fun MessageMenu(app: FamilyTalkApp, m: ChatMsg, onDismiss: () -> Unit, onSave: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var confirm by remember { mutableStateOf(false) }
    val f = m.file
    val canSave = f != null && !f.viewOnce && f.localPath != null && !m.deleted
    val everyone = app.chats.canDeleteForEveryone(m)
    if (!confirm) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (f != null) f.name else "Message") },
            text = {
                Column {
                    if (m.text != null) MenuRow(Icons.Filled.ContentCopy, "Copy text") {
                        clipboard.setText(AnnotatedString(m.text)); Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show(); onDismiss()
                    }
                    if (canSave) MenuRow(Icons.Filled.Download, "Save to phone") { onSave(); onDismiss() }
                    if (f != null && f.localPath == null && !m.deleted && m.from != app.family.myId && !f.opened)
                        MenuRow(Icons.Filled.Refresh, "Download again") { app.chats.requestDownload(m); onDismiss() }
                    MenuRow(Icons.Filled.Delete, "Delete…") { confirm = true }
                }
            },
            confirmButton = { TextButton(onDismiss) { Text("Close") } },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Delete message?") },
            text = { Text(if (everyone) "\"Delete for everyone\" removes it from every family phone that received it." else "It will be removed from this phone only.") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (everyone) TextButton({ app.chats.deleteForEveryone(listOf(m)); onDismiss() }) { Text("Delete for everyone") }
                    TextButton({ app.chats.deleteForMe(listOf(m)); onDismiss() }) { Text("Delete for me") }
                    TextButton(onDismiss) { Text("Cancel") }
                }
            },
        )
    }
}

@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null); Spacer(Modifier.size(16.dp)); Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Full-screen, screenshot-blocked viewer for a view-once photo. Closing it deletes the photo. */
@Composable
private fun ViewOnceViewer(bmp: Bitmap, onClose: () -> Unit) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onClose)) {
            androidx.compose.foundation.Image(bmp.asImageBitmap(), "View-once photo", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            Row(Modifier.align(Alignment.TopCenter).padding(top = 40.dp).background(Color(0x99000000), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LooksOne, null, tint = Color.White); Spacer(Modifier.size(6.dp))
                Text("View once · tap to close (the photo is then deleted)", color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Bubble(app: FamilyTalkApp, m: ChatMsg, mine: Boolean, sender: Pair<String, Int>?, onLongPress: () -> Unit, onTap: () -> Unit) {
    val p = chatPalette()
    val bg = if (mine) p.outBubble else p.inBubble
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 300.dp)
                .shadow(0.5.dp, RoundedCornerShape(10.dp))
                .background(bg, RoundedCornerShape(topStart = if (mine) 10.dp else 0.dp, topEnd = if (mine) 0.dp else 10.dp, bottomStart = 10.dp, bottomEnd = 10.dp))
                .combinedClickable(onClick = onTap, onLongClick = onLongPress, onLongClickLabel = "Message options")
                .padding(horizontal = 9.dp, vertical = 6.dp),
        ) {
            if (sender != null && !mine) Text(sender.first, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Brand.Avatars[Math.floorMod(sender.second, Brand.Avatars.size)].let {
                if (LocalDarkTheme.current) it.copy(red = minOf(1f, it.red + 0.35f), green = minOf(1f, it.green + 0.35f), blue = minOf(1f, it.blue + 0.35f)) else it
            })
            if (m.deleted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Block, null, Modifier.size(16.dp), tint = p.meta); Spacer(Modifier.size(6.dp))
                    Text(if (mine) "You deleted this message" else "This message was deleted", color = p.meta, fontStyle = FontStyle.Italic)
                }
            }
            m.text?.let { Text(it, color = p.text, style = MaterialTheme.typography.bodyLarge) }
            m.file?.let { f -> FileContent(app, m, mine, p) }
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.at)), fontSize = 11.sp, color = p.meta)
                if (mine && !m.deleted) {
                    Spacer(Modifier.size(4.dp))
                    when (m.status) {
                        MsgStatus.READ -> Icon(Icons.Filled.DoneAll, "Read", Modifier.size(16.dp), tint = Brand.ReadBlue)
                        MsgStatus.DELIVERED -> Icon(Icons.Filled.DoneAll, "Delivered", Modifier.size(16.dp), tint = p.meta)
                        MsgStatus.FAILED -> Icon(Icons.Filled.ErrorOutline, "Failed", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                        else -> Icon(Icons.Filled.Done, "Sent", Modifier.size(15.dp), tint = p.meta)
                    }
                }
            }
        }
    }
}

@Composable
private fun FileContent(app: FamilyTalkApp, m: ChatMsg, mine: Boolean, p: ChatPalette) {
    val f = m.file ?: return
    val have = f.localPath != null
    if (f.viewOnce) {
        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LooksOne, null, tint = Brand.TealLight, modifier = Modifier.size(28.dp)); Spacer(Modifier.size(8.dp))
            Column {
                Text("Photo", color = p.text, fontWeight = FontWeight.Medium)
                Text(
                    when {
                        mine && f.opened -> "Opened"
                        mine -> "View once · not opened yet"
                        f.opened -> "Opened"
                        have -> "Tap to view once"
                        else -> "Downloading… tap to retry"
                    }, color = p.meta, fontSize = 12.sp,
                )
            }
        }
        if (!mine && !have && !f.opened) LinearProgressIndicator({ f.progress }, Modifier.width(180.dp).padding(top = 4.dp))
        return
    }
    if (f.mime.startsWith("image/") && have) {
        val bmp by produceState<Bitmap?>(null, f.localPath) { value = withContext(Dispatchers.IO) { app.chats.loadBitmap(f.localPath!!, 720) } }
        bmp?.let {
            androidx.compose.foundation.Image(it.asImageBitmap(), f.name, Modifier.widthIn(max = 280.dp).heightIn(max = 340.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
            return
        }
    }
    Row(
        Modifier.background(p.text.copy(alpha = 0.06f), RoundedCornerShape(8.dp)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (f.mime.startsWith("image/")) Icons.Filled.Image else Icons.Filled.Description, null, tint = p.meta, modifier = Modifier.size(30.dp))
        Spacer(Modifier.size(8.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(f.name, color = p.text, maxLines = 2)
            val state = when {
                have -> ""
                m.status == MsgStatus.FAILED -> " · damaged, tap to download again"
                mine -> ""
                app.chats.isDownloading(m) -> " · downloading ${(f.progress * 100).toInt()}%"
                else -> " · tap to download"
            }
            Text(humanSize(f.size) + state, color = if (m.status == MsgStatus.FAILED) MaterialTheme.colorScheme.error else p.meta, fontSize = 12.sp)
        }
        if (!have && !mine) {
            Spacer(Modifier.size(8.dp))
            Icon(Icons.Filled.Download, "Download", tint = Brand.TealLight)
        }
    }
    if (!have && !mine && m.status != MsgStatus.FAILED) LinearProgressIndicator({ f.progress }, Modifier.fillMaxWidth().padding(top = 4.dp))
}

private fun humanSize(b: Long) = when {
    b >= 1 shl 20 -> "%.1f MB".format(b / 1048576.0)
    b >= 1024 -> "%d KB".format(b / 1024)
    else -> "$b B"
}

/** Types that would install software or run code are never handed to another app. */
private val blockedExtensions = setOf("apk", "apks", "xapk", "aab", "dex", "jar", "sh", "bat", "exe", "js", "html", "htm", "svg")

private fun openFile(ctx: android.content.Context, f: File) {
    // Trailing dots/spaces are ignored by Android, so "x.apk." still counts as .apk.
    val ext = f.name.trimEnd('.', ' ').substringAfterLast('.', "").lowercase()
    if (ext in blockedExtensions) {
        Toast.makeText(ctx, "For safety, FamilyTalk won't open .$ext files.", Toast.LENGTH_LONG).show(); return
    }
    runCatching {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val mime = ctx.contentResolver.getType(uri) ?: "*/*"
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open with"))
    }.onFailure { Toast.makeText(ctx, "No app can open this file", Toast.LENGTH_SHORT).show() }
}

private val EmojiGroups = listOf(
    "Smileys" to "😀 😂 🤣 😊 😍 🥰 😘 😎 🤗 🤔 😴 😢 😭 😡 😱 🥳 😇 🙃 😅 🤩",
    "Hands" to "👍 👎 👏 🙏 🤲 👋 🤝 💪 ✌️ 👌 ☝️ 🫶",
    "Hearts" to "❤️ 🧡 💛 💚 💙 💜 🤍 💔 💕 💯 ✨ 🔥",
    "Family" to "👨‍👩‍👧‍👦 👶 👧 👦 👩 👨 👵 👴 🏠 🕌 🌙 ⭐",
    "Everyday" to "☕ 🍽️ 🍰 🎂 🎁 🎉 🚗 ✈️ 📞 📷 ⏰ ✅",
)

/** Simple, always-available emoji picker (works the same on every phone and keyboard). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EmojiPanel(onPick: (String) -> Unit) {
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(8.dp)) {
            EmojiGroups.forEach { (title, list) ->
                Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
                androidx.compose.foundation.layout.FlowRow {
                    list.split(" ").filter { it.isNotBlank() }.forEach { e ->
                        Box(
                            Modifier.size(48.dp).clickable(onClickLabel = "Insert $e") { onPick(e) },
                            contentAlignment = Alignment.Center,
                        ) { Text(e, fontSize = 26.sp) }
                    }
                }
            }
        }
    }
}
