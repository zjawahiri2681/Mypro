package com.mypro.iptvbox.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The one focus style used everywhere: a 3dp primary ring and a slight zoom, so the
 * focused element is obvious from across the room. Works for any focusable child
 * (Button, Card, clickable Row) because it observes focus on the whole subtree.
 */
fun Modifier.tvFocus(shape: Shape = RoundedCornerShape(50), scale: Float = 1.05f): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (focused) scale else 1f, label = "focusScale")
    val ring = MaterialTheme.colorScheme.primary
    this
        .onFocusChanged { focused = it.hasFocus }
        .graphicsLayer { scaleX = s; scaleY = s }
        .border(if (focused) 3.dp else 0.dp, if (focused) ring else Color.Transparent, shape)
}

/**
 * A list row that works with a D-pad remote and with touch.
 * Focused: primary ring, primary tint, slight zoom. Selected: tinted fill and a leading accent bar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusableRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onFocused: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.small
    val primary = MaterialTheme.colorScheme.primary
    val scale by animateFloatAsState(if (focused) 1.02f else 1f, label = "rowScale")
    val bg = when {
        focused -> primary.copy(alpha = 0.30f)
        selected -> primary.copy(alpha = 0.14f)
        else -> Color.Transparent
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused?.invoke() }
            .clip(shape)
            .background(bg, shape)
            .border(if (focused) 3.dp else 0.dp, if (focused) primary else Color.Transparent, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Selected marker: a short accent bar on the leading edge.
        Box(Modifier.width(4.dp).height(28.dp).background(if (selected) primary else Color.Transparent, RoundedCornerShape(2.dp)))
        Row(Modifier.weight(1f).padding(padding), verticalAlignment = Alignment.CenterVertically) {
            CompositionLocalProvider(LocalContentColor provides if (selected && !focused) primary else MaterialTheme.colorScheme.onSurface) {
                content()
            }
        }
    }
}

/** Poster/tile card with the shared focus treatment. */
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.medium
    val primary = MaterialTheme.colorScheme.primary
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "cardScale")
    Column(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused?.invoke() }
            .clip(shape)
            .background(if (focused) primary.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f), shape)
            .border(if (focused) 3.dp else 0.dp, if (focused) primary else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(8.dp),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action?.invoke()
        }
    }
}

@Composable
fun LoadingState(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(Modifier.size(48.dp), color = MaterialTheme.colorScheme.primary)
            Text(message.ifBlank { "Loading…" }, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Search input. On phones a normal text field; on TV a focusable button that opens a
 * dialog, so D-pad navigation never gets stuck inside a text field or pops the keyboard.
 */
@Composable
fun SearchField(query: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    if (!LocalIsTv.current) {
        OutlinedTextField(
            value = query, onValueChange = onChange, singleLine = true, placeholder = { Text(hint) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Close, "Clear search") } },
            modifier = modifier,
        )
        return
    }
    var open by remember { mutableStateOf(false) }
    FocusableRow(onClick = { open = true }, modifier = modifier, padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Icon(Icons.Default.Search, null)
        Spacer(Modifier.width(10.dp))
        Text(query.ifBlank { hint }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (query.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        if (query.isNotBlank()) Text("Clear: long-press", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) {
        var draft by remember { mutableStateOf(query) }
        val fr = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Search") },
            text = { OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true, placeholder = { Text(hint) }, modifier = Modifier.fillMaxWidth().focusRequester(fr)) },
            confirmButton = { Button(onClick = { onChange(draft.trim()); open = false }, modifier = Modifier.tvFocus()) { Text("Search") } },
            dismissButton = { TextButton(onClick = { onChange(""); open = false }, modifier = Modifier.tvFocus()) { Text("Clear") } },
        )
    }
}

/** Screen-edge padding: TV keeps content inside the 5% overscan-safe area. */
@Composable
fun safeAreaPadding(): PaddingValues = if (LocalIsTv.current) PaddingValues(horizontal = 40.dp, vertical = 24.dp) else PaddingValues(0.dp)
