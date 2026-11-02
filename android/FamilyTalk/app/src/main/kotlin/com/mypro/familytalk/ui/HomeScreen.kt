package com.mypro.familytalk.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mypro.familytalk.FamilyTalkApp
import com.mypro.familytalk.core.dial.DialPlan
import com.mypro.familytalk.core.dial.DialTarget
import com.mypro.familytalk.core.family.Member
import com.mypro.familytalk.data.FAMILY_CONV
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(app: FamilyTalkApp, nav: Nav) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val roster by app.family.roster.collectAsStateWithLifecycle()
    val online by app.family.relayConnected.collectAsStateWithLifecycle()
    val history by app.chats.history.collectAsStateWithLifecycle()
    val unread = history.unread.values.sum()
    val me = roster?.byId(app.family.myId)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(roster?.familyName ?: "FamilyTalk", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            (if (online) "● Connected" else "○ Connecting…") + (me?.let { " · You are ${it.ext}" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium, fontSize = 13.sp,
                            color = if (online) Brand.Verified else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.Groups, null) }, label = { Text("Family") })
                NavigationBarItem(tab == 1, { tab = 1 }, {
                    BadgedBox(badge = { if (unread > 0) Badge { Text("$unread") } }) { Icon(Icons.AutoMirrored.Filled.Chat, null) }
                }, label = { Text("Chats") })
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Filled.Dialpad, null) }, label = { Text("Keypad") })
                NavigationBarItem(tab == 3, { tab = 3 }, { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") })
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> FamilyTab(app, nav)
                1 -> ChatsTab(app, nav)
                2 -> KeypadTab(app)
                3 -> SettingsTab(app, nav)
            }
        }
    }
}

@Composable
private fun FamilyTab(app: FamilyTalkApp, nav: Nav) {
    val roster by app.family.roster.collectAsStateWithLifecycle()
    val data by app.family.data.collectAsStateWithLifecycle()
    val others = roster?.members?.filter { it.id != app.family.myId }.orEmpty().sortedBy { it.ext }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Groups, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Whole family", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Dial 0 to ring everyone", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    FilledTonalIconButton({ app.calls.call(others, false) }, enabled = others.isNotEmpty()) { Icon(Icons.Filled.Call, "Call everyone") }
                    FilledTonalIconButton({ nav.go(Screen.Chat(FAMILY_CONV)) }) { Icon(Icons.AutoMirrored.Filled.Chat, "Family group chat") }
                }
            }
        }
        if (others.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("No one else here yet", style = MaterialTheme.typography.titleMedium)
                Text(if (app.family.isAdmin) "Open Settings › Invite a family member and let them scan the QR with their phone." else "Ask the family admin to invite more people.",
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (app.family.isAdmin) TextButton({ nav.go(Screen.Invite) }) { Text("Invite someone now") }
            }
        }
        items(others, key = { it.id }) { m -> MemberCard(app, nav, m, m.id in data.verified) }
    }
}

@Composable
private fun MemberCard(app: FamilyTalkApp, nav: Nav, m: Member, verified: Boolean) {
    Card(Modifier.fillMaxWidth().clickable { nav.go(Screen.Chat(m.id)) }) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(m.name, m.color)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(m.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ExtChip(m.ext); Spacer(Modifier.width(8.dp))
                    Box(Modifier.clickable { nav.go(Screen.Verify(m.id)) }) { TrustBadge(verified) }
                }
            }
            FilledTonalIconButton({ app.calls.call(listOf(m), false) }) { Icon(Icons.Filled.Call, "Voice call ${m.name}") }
            FilledTonalIconButton({ app.calls.call(listOf(m), true) }) { Icon(Icons.Filled.Videocam, "Video call ${m.name}") }
        }
    }
}

@Composable
private fun ChatsTab(app: FamilyTalkApp, nav: Nav) {
    val history by app.chats.history.collectAsStateWithLifecycle()
    val roster by app.family.roster.collectAsStateWithLifecycle()
    val convs = history.chats.groupBy { it.conv }.map { (c, msgs) -> c to msgs.maxBy { it.at } }.sortedByDescending { it.second.at }
    val fmt = DateFormat.getTimeInstance(DateFormat.SHORT)
    LazyColumn {
        if (convs.isEmpty()) item {
            Text("No messages yet. Tap someone in Family to start a chat.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(convs, key = { it.first }) { (conv, last) ->
            val m = roster?.byId(conv)
            val name = if (conv == FAMILY_CONV) "Family group" else m?.name ?: "Former member"
            val n = history.unread[conv] ?: 0
            ListItem(
                modifier = Modifier.clickable { nav.go(Screen.Chat(conv)) },
                leadingContent = { Avatar(name, m?.color ?: 0) },
                headlineContent = { Text(name, maxLines = 1) },
                supportingContent = { Text(last.text ?: last.file?.let { "📎 ${it.name}" } ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(fmt.format(Date(last.at)), fontSize = 12.sp)
                        if (n > 0) Badge { Text("$n") }
                    }
                },
            )
        }
        if (history.calls.isNotEmpty()) {
            item { SectionTitle("Recent calls") }
            items(history.calls.takeLast(30).reversed(), key = { "call-" + it.id + it.at }) { c ->
                val names = c.with.joinToString { roster?.byId(it)?.name ?: "?" }
                val missed = !c.outgoing && c.durationSec == 0L
                ListItem(
                    leadingContent = {
                        Icon(
                            when { missed -> Icons.Filled.CallMissed; c.outgoing -> Icons.Filled.CallMade; else -> Icons.Filled.CallReceived },
                            contentDescription = if (missed) "Missed" else if (c.outgoing) "Outgoing" else "Incoming",
                            tint = if (missed) Brand.Decline else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    headlineContent = { Text(names, maxLines = 1) },
                    supportingContent = { Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(c.at)) + if (c.durationSec > 0) " · " + formatDuration(c.durationSec) else " · ${c.result}") },
                    trailingContent = {
                        val members = c.with.mapNotNull { roster?.byId(it) }
                        if (members.isNotEmpty()) FilledTonalIconButton({ app.calls.call(members, c.video) }) { Icon(if (c.video) Icons.Filled.Videocam else Icons.Filled.Call, "Call back") }
                    },
                )
            }
        }
    }
}

@Composable
private fun KeypadTab(app: FamilyTalkApp) {
    var number by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    val roster by app.family.roster.collectAsStateWithLifecycle()
    val data by app.family.data.collectAsStateWithLifecycle()
    val sipUi by app.sip.ui.collectAsStateWithLifecycle()
    val me = roster?.byId(app.family.myId)
    val preview = if (number.isEmpty()) null else DialPlan.resolve(number, roster, data.sip, me?.ext)

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(number.ifEmpty { " " }, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace, fontSize = 34.sp, maxLines = 1)
        Text(
            when (preview) {
                null -> "101–199 family · 0 everyone · 9 or + outside · *43 echo test"
                is DialTarget.FamilyMember -> "${preview.member.name} · end-to-end encrypted"
                DialTarget.Everyone -> "Ring the whole family"
                DialTarget.EchoTest -> "Test your microphone and connection"
                is DialTarget.External -> "Outside call via SIP · ${if (sipUi.registered) "ready" else sipUi.registration} · not end-to-end"
                is DialTarget.Invalid -> preview.reason
            },
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, minLines = 2,
            color = if (preview is DialTarget.Invalid && number.length >= 3) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        val keys = listOf("1" to "", "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "*" to "", "0" to "+", "#" to "")
        keys.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { (d, sub) ->
                    Surface(
                        Modifier.size(76.dp).clip(CircleShape).clickable { if (number.length < 24) number += d },
                        color = MaterialTheme.colorScheme.surfaceVariant, shape = CircleShape,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text(d, fontSize = 28.sp)
                            if (sub.isNotEmpty()) Text(sub, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            TextButton({ if (number.isEmpty() || number.last() != '+') number = if (number == "0") "+" else number + "+" }) { Text("+", fontSize = 22.sp) }
            RoundAction(Icons.Filled.Call, "Call", Brand.Accept, onClick = {
                message = null
                val others = roster?.members?.filter { it.id != app.family.myId }.orEmpty()
                when (val t = DialPlan.resolve(number, roster, data.sip, me?.ext)) {
                    is DialTarget.FamilyMember -> app.calls.call(listOf(t.member), false)
                    DialTarget.Everyone -> app.calls.call(others, false)
                    DialTarget.EchoTest -> app.calls.echoTest()
                    is DialTarget.External -> if (!app.sip.call(t.uri)) message = "SIP account is not ready (${sipUi.registration})" else
                        app.startActivity(android.content.Intent(app, CallActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    is DialTarget.Invalid -> message = t.reason
                }
            }, size = 72.dp)
            androidx.compose.material3.IconButton({ number = number.dropLast(1) }, enabled = number.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Filled.Backspace, "Delete")
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
    }
}
