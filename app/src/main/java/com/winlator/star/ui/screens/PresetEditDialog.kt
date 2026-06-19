package com.winlator.star.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Help
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.box64.Box64PresetManager
import com.winlator.star.core.ArrayUtils
import com.winlator.star.core.EnvVars
import com.winlator.star.core.FileUtils
import com.winlator.star.core.StringUtils
import com.winlator.star.fexcore.FEXCorePresetManager
import org.json.JSONArray
import java.util.Locale

data class EnvVarDef(
    val name: String,
    val values: List<String>,
    val defaultValue: String,
    val isToggle: Boolean,
    val isEditText: Boolean
)

@Composable
fun PresetEditDialog(
    prefix: String,
    presetId: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val context = LocalContext.current

    val isBox64 = prefix == "box64"
    val preset = remember {
        if (isBox64)
            if (presetId != null) Box64PresetManager.getPreset(prefix, context, presetId) else null
        else
            if (presetId != null) FEXCorePresetManager.getPreset(context, presetId) else null
    }
    val readonly = preset != null && !preset.isCustom()

    val envVars = remember {
        if (preset != null) {
            if (isBox64) Box64PresetManager.getEnvVars(prefix, context, preset.id)
            else FEXCorePresetManager.getEnvVars(context, preset.id)
        } else null
    }

    val envVarDefs = remember {
        try {
            val json = JSONArray(FileUtils.readString(context, "${prefix}_env_vars.json"))
            (0 until json.length()).map { i ->
                val item = json.getJSONObject(i)
                EnvVarDef(
                    name = item.getString("name"),
                    values = ArrayUtils.toStringArray(item.getJSONArray("values")).toList(),
                    defaultValue = item.getString("defaultValue"),
                    isToggle = item.optBoolean("toggleSwitch", false),
                    isEditText = item.optBoolean("editText", false)
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    val initialName = remember {
        if (preset != null) preset.name
        else {
            val nextId = if (isBox64) Box64PresetManager.getNextPresetId(context, prefix)
                         else FEXCorePresetManager.getNextPresetId(context)
            "${context.getString(com.winlator.star.R.string.preset)}-$nextId"
        }
    }

    var name by remember { mutableStateOf(initialName) }
    val values = remember {
        mutableStateMapOf<String, String>().apply {
            for (def in envVarDefs) {
                val saved = envVars?.get(def.name)
                this[def.name] = if (saved != null && saved.isNotEmpty()) saved else def.defaultValue
            }
        }
    }

    var openDropdown by remember { mutableStateOf<String?>(null) }

    fun saveAndDismiss() {
        val cleanName = name.trim().replace(Regex("[,\\|]+"), "")
        if (cleanName.isEmpty()) return
        val ev = EnvVars()
        for ((k, v) in values) ev.put(k, v)
        if (isBox64) Box64PresetManager.editPreset(prefix, context, preset?.id, cleanName, ev)
        else FEXCorePresetManager.editPreset(context, preset?.id, cleanName, ev)
        onConfirm()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.Black,
        shape = RoundedCornerShape(10.dp),
        title = {
            Text(
                StringUtils.getString(context, "${prefix}_preset") ?: prefix.uppercase(),
                color = Color(0xFF0055FF),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (!readonly) name = it },
                    singleLine = true,
                    enabled = !readonly,
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color(0xFF0055FF),
                        focusedContainerColor = Color(0xFF111111),
                        unfocusedContainerColor = Color(0xFF111111),
                        focusedBorderColor = Color(0xFF0055FF),
                        unfocusedBorderColor = Color(0xFF222B36)
                    )
                )

                Text(
                    "Environment Variables",
                    color = Color(0xFF0055FF),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black, RoundedCornerShape(10.dp))
                        .border(1.dp, Color(0xFF222B36), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (def in envVarDefs) {
                        EnvVarRow(
                            def = def,
                            value = values[def.name] ?: def.defaultValue,
                            readonly = readonly,
                            onValueChange = { values[def.name] = it },
                            openDropdown = openDropdown == def.name,
                            onDropdownToggle = { openDropdown = if (openDropdown == def.name) null else def.name },
                            context = context,
                            prefix = prefix
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { saveAndDismiss(); onDismiss() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
            ) { Text("OK", color = Color.White) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF1976D2))
            }
        }
    )
}

@Composable
private fun EnvVarRow(
    def: EnvVarDef,
    value: String,
    readonly: Boolean,
    onValueChange: (String) -> Unit,
    openDropdown: Boolean,
    onDropdownToggle: () -> Unit,
    context: Context,
    prefix: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Text(
                def.name,
                color = if (readonly) Color(0xFF888888) else Color(0xFFCCCCCC),
                fontSize = 13.sp,
                maxLines = 1
            )
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = {
                    val suffix = def.name
                        .replace("${prefix.uppercase(Locale.ENGLISH)}_", "")
                        .lowercase(Locale.ENGLISH)
                    val help = StringUtils.getString(context, "${prefix}_env_var_help__$suffix")
                    if (help != null) Toast.makeText(context, help, Toast.LENGTH_LONG).show()
                },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(Icons.Default.Help, "Help", tint = Color(0xFF888888), modifier = Modifier.size(16.dp))
            }
        }

        Spacer(Modifier.width(8.dp))

        if (def.isToggle) {
            Switch(
                checked = value == "1",
                onCheckedChange = { onValueChange(if (it) "1" else "0") },
                enabled = !readonly,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Color(0xFF1976D2),
                    checkedThumbColor = Color.White,
                    uncheckedTrackColor = Color(0xFF333333),
                    uncheckedThumbColor = Color(0xFF888888)
                )
            )
        } else if (def.isEditText) {
            OutlinedTextField(
                value = value,
                onValueChange = { if (!readonly) onValueChange(it) },
                singleLine = true,
                enabled = !readonly,
                modifier = Modifier.width(100.dp),
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF111111),
                    unfocusedContainerColor = Color(0xFF111111),
                    focusedBorderColor = Color(0xFF0055FF),
                    unfocusedBorderColor = Color(0xFF222B36),
                    cursorColor = Color(0xFF0055FF)
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        } else {
            Box {
                Button(
                    onClick = { if (!readonly) onDropdownToggle() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                    enabled = !readonly,
                    modifier = Modifier.width(100.dp)
                ) {
                    Text(value, color = Color.White, fontSize = 12.sp, maxLines = 1)
                }
                DropdownMenu(
                    expanded = openDropdown && !readonly,
                    onDismissRequest = { onDropdownToggle() }
                ) {
                    def.values.forEach { v ->
                        DropdownMenuItem(
                            text = { Text(v) },
                            onClick = { onValueChange(v); onDropdownToggle() }
                        )
                    }
                }
            }
        }
    }
}
