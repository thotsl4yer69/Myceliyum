package com.example.ui.screens

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.MyceliumApplication
import com.example.data.local.SightingBackup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Keeps transfers running across rotation and navigation using the application context. */
class BackupSession(application: Application) : AndroidViewModel(application) {
    private val backup = SightingBackup(
        application,
        (application as MyceliumApplication).database.fungiDao()
    )
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set
    var restoreUri by mutableStateOf<Uri?>(null)

    fun save(uri: Uri) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                message = "Backup saved: ${backup.write(uri)} sightings, including private records and photos."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message = "Backup failed: ${error.message}. Do not rely on the incomplete file."
            } finally {
                busy = false
            }
        }
    }

    fun restore(uri: Uri) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                message = "Restored ${backup.restore(uri)} sightings."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message = "Restore failed: ${error.message}. Existing records were preserved."
            } finally {
                busy = false
            }
        }
    }
}

@Composable
fun BackupControls(session: BackupSession = viewModel()) {
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) session.save(uri)
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        session.restoreUri = uri
    }
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Personal backup & restore", style = MaterialTheme.typography.titleSmall)
            Text("Includes every sighting, private coordinates, notes and photos. API keys are excluded. Keep this ZIP somewhere private. Back up before changing installations.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !session.busy, onClick = { save.launch("myceliyums-sightings.zip") }) { Text("Save backup") }
                OutlinedButton(enabled = !session.busy, onClick = { restore.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Restore") }
            }
            if (session.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (session.message.isNotEmpty()) Text(session.message, style = MaterialTheme.typography.bodySmall)
        }
    }
    session.restoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { session.restoreUri = null },
            title = { Text("Restore personal backup?") },
            text = { Text("Adds missing sightings and photos. Existing records stay intact; matching records are skipped.") },
            confirmButton = {
                TextButton(enabled = !session.busy, onClick = {
                    session.restoreUri = null
                    session.restore(uri)
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { session.restoreUri = null }) { Text("Cancel") } }
        )
    }
}
