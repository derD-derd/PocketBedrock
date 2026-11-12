package com.pocketbedrock.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pocketbedrock.app.core.ServerState

private fun stateColor(state: ServerState): Color = when (state) {
    ServerState.OFFLINE -> Color(0xFF9AA2AE)
    ServerState.STARTING -> Color(0xFFF5C356)
    ServerState.ONLINE -> Color(0xFF57C793)
    ServerState.STOPPING -> Color(0xFFF5C356)
    ServerState.CRASHED -> Color(0xFFFF6B6B)
}

@Composable
fun StatePill(state: ServerState, modifier: Modifier = Modifier) {
    val color = stateColor(state)
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.16f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .padding(0.dp),
            ) {
                Canvas(Modifier.size(10.dp)) {
                    drawCircle(color)
                }
            }
            Text(
                text = state.name.lowercase().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelLarge,
                color = color,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * Lightweight arc gauge drawn with Canvas - no chart library, no animations.
 */
@Composable
fun Gauge(
    title: String,
    value: Double?,
    maxValue: Double,
    unit: String,
    modifier: Modifier = Modifier,
    decimals: Int = 0,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val fraction = ((value ?: 0.0) / maxValue).coerceIn(0.0, 1.0).toFloat()
    val displayText = value?.let {
        String.format("%." + decimals + "f", it)
    } ?: "-"
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(96.dp)) {
            Canvas(Modifier.size(96.dp)) {
                val stroke = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                val inset = stroke.width / 2 + 2.dp.toPx()
                val arcSize = size.minDimension - inset * 2
                drawArc(
                    color = track,
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(arcSize, arcSize),
                    style = stroke,
                )
                drawArc(
                    color = accent,
                    startAngle = 135f,
                    sweepAngle = 270f * fraction,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(arcSize, arcSize),
                    style = stroke,
                )
            }
            Text(
                text = displayText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = unit,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

enum class BannerSeverity { INFO, WARNING, CRITICAL }

@Composable
fun WarningBanner(
    text: String,
    severity: BannerSeverity,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    val container = when (severity) {
        BannerSeverity.INFO -> MaterialTheme.colorScheme.primaryContainer
        BannerSeverity.WARNING -> Color(0xFF4A3A16)
        BannerSeverity.CRITICAL -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (severity) {
        BannerSeverity.INFO -> MaterialTheme.colorScheme.onPrimaryContainer
        BannerSeverity.WARNING -> Color(0xFFFFD98A)
        BannerSeverity.CRITICAL -> MaterialTheme.colorScheme.onErrorContainer
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = content,
                modifier = Modifier.weight(1f),
            )
            if (onDismiss != null) {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun KeyValueRow(label: String, value: String, monospaceValue: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            value,
            style = if (monospaceValue) {
                MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            modifier = Modifier.weight(0.58f),
        )
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(
                    confirmLabel,
                    color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}

fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val d = totalSec / 86400
    val h = (totalSec % 86400) / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        d > 0 -> String.format("%dd %02dh %02dm", d, h, m)
        h > 0 -> String.format("%dh %02dm %02ds", h, m, s)
        else -> String.format("%02dm %02ds", m, s)
    }
}

fun formatPlaytime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
