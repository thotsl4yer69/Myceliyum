package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.MyceliumApplication
import com.example.data.local.SightingBackup
import kotlinx.coroutines.launch

@Composable
fun BackupControls() {
    val context = LocalContext.current
    val backup = remember { SightingBackup(context, (context.applicationContext as MyceliumApplication).database.fungiDao()) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try { message = "Backup saved: ${backup.write(uri)} sightings, including private records and photos." }
            catch (e: Exception) { message = "Backup failed: ${e.message}. Do not rely on the incomplete file." }
            finally { busy = false }
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { restoreUri = it }
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Personal backup & restore", style = MaterialTheme.typography.titleSmall)
            Text("Includes every sighting, private coordinates, notes and photos. API keys are excluded. Keep this ZIP somewhere private. Back up before changing installations.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { save.launch("myceliyums-sightings.zip") }) { Text("Save backup") }
                OutlinedButton(enabled = !busy, onClick = { restore.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Restore") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
    restoreUri?.let { uri ->
        AlertDialog(onDismissRequest = { restoreUri = null }, title = { Text("Restore personal backup?") },
            text = { Text("Adds missing sightings and photos. Existing records stay intact; matching records are skipped.") },
            confirmButton = { TextButton(onClick = {
                restoreUri = null
                scope.launch {
                    busy = true
                    try { message = "Restored ${backup.restore(uri)} sightings." }
                    catch (e: Exception) { message = "Restore failed: ${e.message}. Existing records were preserved." }
                    finally { busy = false }
                }
            }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("Cancel") } })
    }
}
