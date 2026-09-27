package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.*
import androidx.compose.foundation.text.input.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PlatformImeOptions
import androidx.compose.ui.unit.dp
import com.example.core.security.PrivateInputContract

@Composable
fun PrivateTextField(label: String, value: String, onChange: (String) -> Unit, secret: Boolean = false, limit: Int, enabled: Boolean = true) {
    var reveal by remember { mutableStateOf(false) }
    val options = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password,
        platformImeOptions = PlatformImeOptions(privateImeOptions = PrivateInputContract.MARKER))
    if (secret) {
        // Do not use rememberTextFieldState: its saver could serialize a password.
        val state = remember { TextFieldState(initialText = value) }
        LaunchedEffect(value) {
            if (state.text.toString() != value) state.edit { replace(0, length, value) }
        }
        LaunchedEffect(state) { snapshotFlow { state.text.toString() }.collect { onChange(it) } }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    BasicSecureTextField(state = state, enabled = enabled, modifier = Modifier.weight(1f),
                        inputTransformation = InputTransformation.maxLength(limit),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        keyboardOptions = options,
                        textObfuscationMode = if (reveal) TextObfuscationMode.Visible else TextObfuscationMode.Hidden)
                    TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Show") }
                }
            }
        }
        return
    }
    OutlinedTextField(value = value, enabled = enabled, onValueChange = { if (it.length <= limit) onChange(it) }, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), singleLine = true,
        // All vault fields are private, including labels/usernames/origins/cardholder names.
        // Password input type suppresses learning in compliant IMEs independently of visual masking.
        keyboardOptions = options)
}
