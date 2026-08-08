package com.example.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ime.ConfigurableIME
import com.example.ime.clipboard.ClipboardItem
import kotlinx.coroutines.launch

@Composable
fun ClipboardPanel(
    onClose: () -> Unit,
    onPaste: (String) -> Unit,
    onEdit: (ClipboardItem) -> Unit
) {
    val context = LocalContext.current
    val ime = context as? ConfigurableIME
    val repo = ime?.clipboardRepository
    
    val items by repo?.allItems?.collectAsState(initial = emptyList()) ?: remember { mutableStateOf(emptyList()) }
    val scope = rememberCoroutineScope()
    
    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF2B2C30)).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Schowek", color = Color.White, modifier = Modifier.padding(start = 8.dp))
            Row {
                IconButton(onClick = { 
                    scope.launch { repo?.deleteAll() }
                }) {
                    Icon(Icons.Default.Delete, contentDescription = "Wyczyść wszystko", tint = Color.Gray)
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Zamknij", tint = Color.White)
                }
            }
        }
        
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(items) { item ->
                var expanded by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        onPaste(item.content)
                    }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(40.dp).background(Color.DarkGray)) {
                        if (item.type == "URI") {
                            Text("IMG", color = Color.White, modifier = Modifier.align(Alignment.Center), fontSize = 10.sp)
                        } else {
                            Text("TXT", color = Color.White, modifier = Modifier.align(Alignment.Center), fontSize = 10.sp)
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = item.content, 
                        color = Color.White, 
                        maxLines = 1, 
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Box {
                        IconButton(onClick = { expanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Opcje", tint = Color.Gray)
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Edytuj") },
                                onClick = { 
                                    expanded = false
                                    onEdit(item)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Usuń") },
                                onClick = { 
                                    expanded = false
                                    scope.launch { repo?.deleteById(item.id) }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EditClipboardWindow(
    item: ClipboardItem,
    onClose: () -> Unit
) {
    var offsetX by remember { mutableStateOf(50f) }
    var offsetY by remember { mutableStateOf(50f) }
    var content by remember { mutableStateOf(item.content) }
    
    val context = LocalContext.current
    val ime = context as? ConfigurableIME
    val repo = ime?.clipboardRepository
    val scope = rememberCoroutineScope()
    
    Box(
        modifier = Modifier
            .offset(offsetX.dp, offsetY.dp)
            .size(300.dp, 200.dp)
            .background(Color(0xFF2B2C30), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .border(1.dp, Color.Gray, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offsetX += dragAmount.x / density
                    offsetY += dragAmount.y / density
                }
            }
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Edycja schowka", color = Color.White, fontSize = 14.sp)
                IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Zamknij", tint = Color.White)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = {
                scope.launch { 
                    repo?.updateContent(item.id, content) 
                    onClose()
                }
            }, modifier = Modifier.align(Alignment.End)) {
                Text("Zapisz")
            }
        }
    }
}
