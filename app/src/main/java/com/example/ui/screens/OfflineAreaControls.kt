package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ui.viewmodel.FungiViewModel
import com.example.ui.viewmodel.HotspotState

@Composable
fun OfflineAreaControls(viewModel: FungiViewModel) {
    val status by viewModel.offlineAreaStatus.collectAsState()
    val busy by viewModel.isPreparingArea.collectAsState()
    val grid by viewModel.hotspotState.collectAsState()
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Prepare an offline area", style = MaterialTheme.typography.titleSmall)
            Text("Saves the current suitability snapshot, public observations and up to two reusable photos for each of eight catalogue species (or the selected species). Saved conditions are dated. Basemap tiles are not included.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && grid is HotspotState.Success, onClick = viewModel::prepareArea) { Text("Prepare area") }
                OutlinedButton(enabled = !busy, onClick = viewModel::openPreparedArea) { Text("Load saved") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}
