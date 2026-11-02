package com.mypro.familytalk.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

@Composable
fun Avatar(name: String, color: Int, size: Dp = 48.dp) {
    val initials = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
    Box(
        Modifier.size(size).clip(CircleShape).background(Brand.Avatars[Math.floorMod(color, Brand.Avatars.size)]),
        contentAlignment = Alignment.Center,
    ) { Text(initials, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.38f).sp) }
}

/** Extension number chip, e.g. "102". Monospace so lists line up. */
@Composable
fun ExtChip(ext: Int) {
    Text(
        "Ext \u2066$ext\u2069",
        Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 8.dp, vertical = 2.dp),
        color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge, fontSize = 13.sp,
    )
}

/** Shield + word. Colour is never the only signal: the icon and text change too. */
@Composable
fun TrustBadge(verified: Boolean, compact: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val tint = if (verified) trustOk() else trustWarn()
        Icon(
            if (verified) Icons.Filled.VerifiedUser else Icons.Filled.GppMaybe,
            // When the word is shown, the icon is decorative (TalkBack reads the word once).
            contentDescription = if (compact) (if (verified) "Verified" else "Not verified yet") else null,
            tint = tint, modifier = Modifier.size(18.dp),
        )
        if (!compact) {
            Spacer(Modifier.width(4.dp))
            Text(if (verified) "Verified" else "Not verified", style = MaterialTheme.typography.bodyMedium, fontSize = 14.sp, color = tint)
        }
    }
}

@Composable
fun RoundAction(icon: ImageVector, label: String, color: Color, onClick: () -> Unit, size: Dp = 64.dp, contentColor: Color = Color.White, modifier: Modifier = Modifier) {
    FilledIconButton(
        onClick = onClick, modifier = modifier.size(size),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = contentColor),
    ) { Icon(icon, contentDescription = label, modifier = Modifier.size(size * 0.45f)) }
}

@Composable
fun QrImage(text: String, sizeDp: Dp = 240.dp) {
    val bmp = androidx.compose.runtime.remember(text) { renderQr(text) }
    if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = "QR code", modifier = Modifier.size(sizeDp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp))
}

fun renderQr(text: String, sizePx: Int = 640): Bitmap? = runCatching {
    val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val px = IntArray(sizePx * sizePx) { i -> if (m[i % sizePx, i / sizePx]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    Bitmap.createBitmap(px, sizePx, sizePx, Bitmap.Config.RGB_565)
}.getOrNull()

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp))
}

fun formatDuration(sec: Long): String = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)

val Spacing = Arrangement.spacedBy(12.dp)
