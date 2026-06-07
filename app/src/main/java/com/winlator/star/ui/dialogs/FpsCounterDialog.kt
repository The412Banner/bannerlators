package com.winlator.star.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.winlator.star.ui.XServerDialogState

@Composable
fun FpsCounterDialog(state: XServerDialogState) {
    val initialConfig by state.fpsConfig.collectAsState()

    fun parseConfig(s: String): Map<String, String> {
        if (s.isEmpty()) return emptyMap()
        val map = mutableMapOf<String, String>()
        s.split(",").forEach { part ->
            val eq = part.indexOf('=')
            if (eq >= 0) map[part.substring(0, eq)] = part.substring(eq + 1)
        }
        return map
    }

    val cfg = remember(initialConfig) { parseConfig(initialConfig) }

    var showFPS by remember { mutableStateOf(cfg.getOrDefault("showFPS", "1") == "1") }
    var showCPULoad by remember { mutableStateOf(cfg.getOrDefault("showCPULoad", "0") == "1") }
    var showGPULoad by remember { mutableStateOf(cfg.getOrDefault("showGPULoad", "0") == "1") }
    var showRAM by remember { mutableStateOf(cfg.getOrDefault("showRAM", "0") == "1") }
    var showRenderer by remember { mutableStateOf(cfg.getOrDefault("showRenderer", "0") == "1") }
    var showBatteryTemp by remember { mutableStateOf(cfg.getOrDefault("showBatteryTemp", "0") == "1") }
    var hudScale by remember { mutableStateOf(cfg.getOrDefault("hudScale", "100").toIntOrNull() ?: 100) }
    var hudTransparency by remember { mutableStateOf(cfg.getOrDefault("hudTransparency", "0").toIntOrNull() ?: 0) }

    // Preserve hudMode from current config (shouldn't be changed here)
    val hudMode = remember { cfg.getOrDefault("hudMode", "horizontal") }

    fun buildConfig(): String = listOf(
        "hudMode=$hudMode",
        "showFPS=${if (showFPS) "1" else "0"}",
        "showCPULoad=${if (showCPULoad) "1" else "0"}",
        "showGPULoad=${if (showGPULoad) "1" else "0"}",
        "showRAM=${if (showRAM) "1" else "0"}",
        "showRenderer=${if (showRenderer) "1" else "0"}",
        "showBatteryTemp=${if (showBatteryTemp) "1" else "0"}",
        "hudScale=$hudScale",
        "hudTransparency=$hudTransparency"
    ).joinToString(",")

    AlertDialog(
        onDismissRequest = { state.dismiss() },
        title = { Text("FPS Counter") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                CheckRow("Show FPS", showFPS) { showFPS = it }
                CheckRow("Show CPU Temp", showCPULoad) { showCPULoad = it }
                CheckRow("Show GPU Load", showGPULoad) { showGPULoad = it }
                CheckRow("Show RAM", showRAM) { showRAM = it }
                CheckRow("Show Renderer", showRenderer) { showRenderer = it }
                CheckRow("Show Battery Temp", showBatteryTemp) { showBatteryTemp = it }

                Spacer(Modifier.height(12.dp))
                Text("HUD Scale: $hudScale%")
                Slider(
                    value = hudScale.toFloat(),
                    onValueChange = { hudScale = it.toInt().coerceAtLeast(50) },
                    valueRange = 50f..150f,
                    steps = 99
                )
                Spacer(Modifier.height(4.dp))
                Text("HUD Transparency: $hudTransparency")
                Slider(
                    value = hudTransparency.toFloat(),
                    onValueChange = { hudTransparency = it.toInt() },
                    valueRange = 0f..50f,
                    steps = 49
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                state.onFpsConfigApply?.invoke(buildConfig())
                state.dismiss()
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = { state.dismiss() }) { Text("Cancel") }
        }
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(4.dp))
        Text(label)
    }
}
