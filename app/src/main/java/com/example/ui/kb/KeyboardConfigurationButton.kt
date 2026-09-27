package com.example.ui.kb

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.config.Settings
import com.example.core.config.SettingsStore
import com.example.ui.settings.SettingsNavigation

/** Configuration stays reachable while suggestions occupy the strip. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeyboardConfigurationButton(settings: Settings, theme: KeyboardTheme) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    fun open(route: String, query: String = "") {
        expanded = false
        SettingsNavigation.open(context, route, query)
    }
    Box {
        Box(Modifier.fillMaxHeight()
            .semantics { contentDescription = "Customize keyboard; hold for expert settings" }
            .combinedClickable(onClick = { expanded = true }, onLongClick = { open("all") })
            .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            PanelText("⚙", theme.stripText, 17.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("What would you like to change?") }, onClick = { open("request") })
            DropdownMenuItem(text = { Text("Every expert setting & profiles") }, onClick = { open("all") })
            DropdownMenuItem(text = { Text(if (settings.keyboardKeepVisible) "Release keyboard after focus changes" else "Keep keyboard active for shortcuts") },
                onClick = {
                    SettingsStore.update { it.copy(keyboardKeepVisible = !it.keyboardKeepVisible) }
                    expanded = false
                })
            DropdownMenuItem(text = { Text("Lock options") }, onClick = { open("all", "pocket") })
            DropdownMenuItem(text = { Text("Models & providers") }, onClick = { open("setup") })
            DropdownMenuItem(text = { Text("Files & cloud media") }, onClick = { open("media") })
            DropdownMenuItem(text = { Text("Passwords & cards") }, onClick = { open("vault") })
            DropdownMenuItem(text = { Text("Phone capabilities") }, onClick = { open("capabilities") })
            DropdownMenuItem(text = { Text("All settings screens") }, onClick = { open("home") })
        }
    }
}
