package com.pocketbedrock.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.pocketbedrock.app.AppGraph
import kotlinx.coroutines.launch

/**
 * Direct text editing of server.properties. Order and unknown keys are preserved
 * verbatim; validation runs afterwards and reports problems without blocking saves.
 */
@Composable
fun RawEditorScreen(graph: AppGraph, navController: NavHostController) {
    val scope = rememberCoroutineScope()
    val file = graph.propsManager.file()
    var text by rememberSaveable(file.absolutePath) {
        mutableStateOf(if (file.exists()) file.readText() else "# server.properties not created yet")
    }
    var message by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Raw server.properties", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    file.parentFile?.mkdirs()
                    file.writeText(text)
                    val parsed = graph.propsManager.read()
                    val errors = graph.propsManager.validate(parsed)
                    val bad = errors.filterValues { it != null }
                    message = if (bad.isEmpty()) {
                        "Saved ${parsed.size} settings."
                    } else {
                        "Saved, but ${bad.size} value(s) look wrong: " +
                            bad.entries.joinToString { "${it.key}: ${it.value}" }
                    }
                }
            }) { Text("Save") }
            OutlinedButton(onClick = { navController.popBackStack() }) { Text("Back") }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        Text(
            "Keys unknown to PocketBedrock are preserved exactly as written. The server reads " +
                "this file at startup.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
