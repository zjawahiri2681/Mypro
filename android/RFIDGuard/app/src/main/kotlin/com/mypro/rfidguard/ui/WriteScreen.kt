package com.mypro.rfidguard.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mypro.rfidguard.AppModel
import com.mypro.rfidguard.DraftRecord
import com.mypro.rfidguard.Host
import com.mypro.rfidguard.core.ndef.NdefCodec
import com.mypro.rfidguard.core.ndef.NdefContents
import com.mypro.rfidguard.core.ndef.UriRisk
import com.mypro.rfidguard.nfc.WriteOp

private enum class Kind(val label: String) {
    TEXT("Text"), LINK("Link"), PHONE("Phone"), EMAIL("E-mail"), WIFI("Wi-Fi"), CONTACT("Contact"), LOCATION("Location"), APP("App")
}

/** Seal record: header 3 + type 19 + payload 21 bytes. */
private const val SEAL_BYTES = 43

@Composable
fun WriteScreen(model: AppModel, host: Host) {
    val vault = model.data ?: return
    var kind by rememberSaveable { mutableStateOf(Kind.TEXT) }
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    var c by remember { mutableStateOf("") }
    var d by remember { mutableStateOf("") }
    var sign by remember { mutableStateOf(vault.settings.signWrites) }
    var confirmWrite by remember { mutableStateOf(false) }
    fun clear() { a = ""; b = ""; c = ""; d = "" }

    // Builds the record for the current form, or explains what is missing.
    val built: Pair<DraftRecord?, String?> = when (kind) {
        Kind.TEXT -> if (a.isBlank()) null to "Enter some text" else DraftRecord("Text", a, NdefContents.text(a)) to null
        Kind.LINK -> {
            val uri = a.trim()
            when {
                uri.isEmpty() -> null to "Enter a link"
                !uri.contains(':') -> null to "Start with https://"
                uri.any { it.isWhitespace() } -> null to "A link cannot contain spaces"
                else -> DraftRecord("Link", uri, NdefContents.uri(uri)) to null
            }
        }
        Kind.PHONE -> {
            val n = a.filter { it.isDigit() || it == '+' }
            if (n.length < 3) null to "Enter a phone number" else DraftRecord("Phone", n, NdefContents.uri("tel:$n")) to null
        }
        Kind.EMAIL -> {
            val m = a.trim()
            if (!m.contains('@') || m.any { it.isWhitespace() }) null to "Enter an e-mail address" else DraftRecord("E-mail", m, NdefContents.uri("mailto:$m")) to null
        }
        Kind.WIFI -> when {
            a.isEmpty() -> null to "Enter the network name"
            a.toByteArray().size > 32 -> null to "Network name is too long"
            b.isNotEmpty() && b.length < 8 -> null to "A Wi-Fi password has at least 8 characters"
            b.length > 63 -> null to "Password is too long"
            else -> DraftRecord("Wi-Fi", a + if (b.isEmpty()) " (open)" else " (password hidden)", NdefContents.wifi(a, b)) to null
        }
        Kind.CONTACT -> if (a.isBlank()) null to "Enter a name" else DraftRecord("Contact", a.trim(), NdefContents.vcard(a.trim(), b.trim(), c.trim(), d.trim())) to null
        Kind.LOCATION -> {
            val lat = a.trim().toDoubleOrNull()
            val lon = b.trim().toDoubleOrNull()
            if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) null to "Enter latitude and longitude in decimal degrees"
            else DraftRecord("Location", "$lat, $lon", NdefContents.uri("geo:$lat,$lon")) to null
        }
        Kind.APP -> {
            val pkg = a.trim()
            if (!Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$").matches(pkg)) null to "Enter an app package name such as com.example.app"
            else DraftRecord("App", pkg, NdefContents.androidApp(pkg)) to null
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        SectionCard("Compose a record") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (k in Kind.entries) FilterChip(selected = kind == k, onClick = { kind = k; clear() }, label = { Text(k.label) })
            }
            when (kind) {
                Kind.TEXT -> Field(a, { a = it.take(800) }, "Text", singleLine = false)
                Kind.LINK -> {
                    Field(a, { a = it.take(800) }, "Link (https://…)", keyboard = KeyboardType.Uri)
                    val risks = if (a.contains(':')) UriRisk.assess(a.trim()) else emptyList()
                    for (f in risks) FindingRow(f)
                }
                Kind.PHONE -> Field(a, { a = it.take(20) }, "Phone number", keyboard = KeyboardType.Phone)
                Kind.EMAIL -> Field(a, { a = it.take(120) }, "E-mail address", keyboard = KeyboardType.Email)
                Kind.WIFI -> {
                    Field(a, { a = it }, "Network name (SSID)")
                    Field(b, { b = it }, "Password (leave empty for an open network)", secret = true)
                    Hint("The password is stored on the tag unencrypted - anyone who can touch the tag can read it. Use this for a guest network only.")
                }
                Kind.CONTACT -> {
                    Field(a, { a = it.take(80) }, "Name")
                    Field(b, { b = it.take(30) }, "Phone", keyboard = KeyboardType.Phone)
                    Field(c, { c = it.take(120) }, "E-mail", keyboard = KeyboardType.Email)
                    Field(d, { d = it.take(80) }, "Organisation")
                }
                Kind.LOCATION -> {
                    Field(a, { a = it.take(12) }, "Latitude", keyboard = KeyboardType.Decimal)
                    Field(b, { b = it.take(12) }, "Longitude", keyboard = KeyboardType.Decimal)
                }
                Kind.APP -> Field(a, { a = it.take(120) }, "Package name")
            }
            val (record, problem) = built
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { if (record != null) { model.draft.add(record); clear() } }, enabled = record != null) { Text("Add to tag content") }
                if (problem != null && (a.isNotEmpty() || b.isNotEmpty())) Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        SectionCard("Tag content") {
            if (model.draft.isEmpty()) Hint("Nothing added yet. Compose a record above; several records can go on one tag.")
            model.draft.forEachIndexed { i, r ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.kind, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(r.summary, maxLines = 3)
                    }
                    TextButton(onClick = { if (i < model.draft.size) model.draft.removeAt(i) }) { Text("Remove") }
                }
            }
            if (model.draft.isNotEmpty()) {
                val size = NdefCodec.encode(model.draft.map { it.record }).size + if (sign) SEAL_BYTES else 0
                Text("Size: $size bytes", fontWeight = FontWeight.SemiBold)
                Hint("Usable space: NTAG213 about 137 bytes, NTAG215 about 496, NTAG216 about 872.")
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Add authenticity seal")
                    Hint("Lets this vault detect later whether the content was changed or copied to another tag. Adds $SEAL_BYTES bytes.")
                }
                Switch(checked = sign, onCheckedChange = { sign = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { confirmWrite = true }, enabled = model.draft.isNotEmpty()) { Text("Write to tag") }
                OutlinedButton(onClick = { model.draft.clear() }, enabled = model.draft.isNotEmpty()) { Text("Clear") }
            }
            Hint("Writing replaces whatever is on the tag. The result is read back and compared before success is reported. To erase, lock or password-protect a tag, scan it first and use its actions.")
        }
        Spacer(Modifier.height(12.dp))
    }

    if (confirmWrite) ConfirmDialog(
        title = "Write to the next tag?",
        text = "The next tag you present is overwritten with ${model.draft.size} record(s). The request expires after 45 seconds.",
        confirmLabel = "Write",
        onConfirm = {
            confirmWrite = false
            model.arm(WriteOp.WriteNdef(model.draft.map { it.record }, sign))
        },
        onDismiss = { confirmWrite = false },
    )
}
