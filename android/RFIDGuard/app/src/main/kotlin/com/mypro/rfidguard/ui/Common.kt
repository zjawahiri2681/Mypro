package com.mypro.rfidguard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mypro.rfidguard.core.security.Finding
import com.mypro.rfidguard.core.security.Severity

private val Colors = darkColorScheme(
    primary = Color(0xFF2DD4BF),
    onPrimary = Color(0xFF04201C),
    secondary = Color(0xFF7DD3FC),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE5EDF7),
    surface = Color(0xFF0B1220),
    onSurface = Color(0xFFE5EDF7),
    surfaceVariant = Color(0xFF162236),
    onSurfaceVariant = Color(0xFFA9B8CC),
    surfaceContainer = Color(0xFF111B2D),
    surfaceContainerHigh = Color(0xFF162236),
    surfaceContainerHighest = Color(0xFF1B2A42),
    error = Color(0xFFF87171),
    onError = Color(0xFF2A0A0A),
    outline = Color(0xFF3B4C66),
)

@Composable
fun RfidTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}

fun severityColor(severity: Severity): Color = when (severity) {
    Severity.GOOD -> Color(0xFF34D399)
    Severity.INFO -> Color(0xFF7DD3FC)
    Severity.LOW -> Color(0xFFFDE047)
    Severity.MEDIUM -> Color(0xFFFB923C)
    Severity.HIGH -> Color(0xFFF87171)
}

fun severityLabel(severity: Severity): String = when (severity) {
    Severity.GOOD -> "Good"
    Severity.INFO -> "Note"
    Severity.LOW -> "Low risk"
    Severity.MEDIUM -> "Medium risk"
    Severity.HIGH -> "High risk"
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
fun FindingRow(finding: Finding) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape).background(severityColor(finding.severity)))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(finding.title, fontWeight = FontWeight.SemiBold)
            Text(severityLabel(finding.severity), color = severityColor(finding.severity), style = MaterialTheme.typography.labelSmall)
            Text(finding.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun LabelValue(label: String, value: String, mono: Boolean = false) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
    }
}

@Composable
fun Mono(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
}

@Composable
fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (danger) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    singleLine: Boolean = true,
    keyboard: KeyboardType = KeyboardType.Text,
    error: String? = null,
) {
    val supporting: (@Composable () -> Unit)? = if (error != null) {
        { Text(error) }
    } else null
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        isError = error != null,
        supportingText = supporting,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = false),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Dialog with a single text input. */
@Composable
fun InputDialog(
    title: String,
    label: String,
    confirmLabel: String,
    initial: String = "",
    hint: String? = null,
    validate: (String) -> String? = { null },
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    val problem = validate(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hint != null) Hint(hint)
                Field(value, { value = it }, label, error = if (value.isEmpty()) null else problem)
            }
        },
        confirmButton = { Button(onClick = { onConfirm(value) }, enabled = problem == null) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
