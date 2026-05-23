package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.UserSighting
import com.example.ui.viewmodel.FungiViewModel
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: FungiViewModel
) {
    val context = LocalContext.current
    val measureUnits by viewModel.measureUnits.collectAsState()
    val mapTheme by viewModel.mapTheme.collectAsState()
    val userSightings by viewModel.userSightings.collectAsState()

    var showExportSuccessDialog by remember { mutableStateOf(false) }
    var exportedCsvFilePath by remember { mutableStateOf("") }
    var exportedCsvPreview by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
    ) {
        // Hero bar
        Surface(
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "FIELD RESEARCH TERMINAL SETTINGS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "System Preferences & Purge Ports",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Column(modifier = Modifier.padding(16.dp)) {

            // 1. Measure Units setting
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Straighten, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "METEOROLOGY MEASUREMENT UNITS",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        listOf("Metric", "Imperial").forEach { unit ->
                            val isSelected = measureUnits == unit
                            ElevatedCard(
                                onClick = { viewModel.measureUnits.value = unit },
                                colors = CardDefaults.elevatedCardColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("unit_option_$unit")
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = unit.uppercase(),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. Map Style config
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Map, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "TOPOGRAPHICAL MAP FIELD STYLE",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    listOf("Topo Field Plan", "Dark Ecological Radar", "Satellite Microhabitat").forEach { style ->
                        val isSelected = mapTheme == style
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { viewModel.mapTheme.value = style },
                                modifier = Modifier.testTag("map_theme_$style")
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = style,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            // 3. Cache management TTL flush
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "LOCAL ROOM DATA STORAGE (TTL)",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "iNaturalist observations are cached locally to support offline-first exploration. Our database uses a sliding 24-hour cache TTL policy before purging.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            viewModel.clearCache()
                            Toast.makeText(context, "iNaturalist database cache flushed completely!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("clear_cache_button"),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "FLUSH OBSERVATIONS CACHE",
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // 4. Export database listings to CSV
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.IosShare, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "EXPORT SIGHTINGS DATABASE",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Generate and export your local sightings data as a standardized CSV file for import into external GIS packages or QGIS.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            if (userSightings.isEmpty()) {
                                Toast.makeText(context, "No logged sightings available to export. Save a sighting first!", Toast.LENGTH_LONG).show()
                            } else {
                                val csv = buildCsvString(userSightings)
                                val filePath = saveCsvFile(context, csv)
                                if (filePath.isNotEmpty()) {
                                    exportedCsvPreview = csv
                                    exportedCsvFilePath = filePath
                                    showExportSuccessDialog = true
                                } else {
                                    Toast.makeText(context, "Failed to compile export file.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("export_csv_button"),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.FileDownload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "EXPORT TO STANDARDIZED CSV",
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }

    if (showExportSuccessDialog) {
        AlertDialog(
            onDismissRequest = { showExportSuccessDialog = false },
            title = {
                Text(
                    text = "CSV DATASET EXPORT PROGRESS",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            },
            text = {
                Column {
                    Text(
                        text = "Data successfully compiled to CSV!",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "File written to:\n$exportedCsvFilePath",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Dataset Preview:",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                    ) {
                        Text(
                            text = exportedCsvPreview,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .padding(8.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExportSuccessDialog = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showExportSuccessDialog = false
                        shareCsvText(context, exportedCsvPreview)
                    }
                ) {
                    Text("SHARE CSV DATA")
                }
            }
        )
    }
}

private fun buildCsvString(sightings: List<UserSighting>): String {
    val sb = StringBuilder()
    sb.append("id,species_id,latitude,longitude,timestamp,date_utc,notes,is_private\n")
    val defSdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    defSdf.timeZone = TimeZone.getTimeZone("UTC")

    for (s in sightings) {
        val dateString = defSdf.format(Date(s.timestamp))
        val escapedNotes = s.notes.replace("\"", "\"\"")
        sb.append("${s.id},\"${s.speciesId}\",${s.lat},${s.lng},${s.timestamp},\"$dateString\",\"$escapedNotes\",${s.isPrivate}\n")
    }
    return sb.toString()
}

private fun saveCsvFile(context: Context, csvContent: String): String {
    return try {
        val csvDir = File(context.filesDir, "exports")
        if (!csvDir.exists()) csvDir.mkdirs()
        val file = File(csvDir, "mycelium_sightings_${System.currentTimeMillis()}.csv")
        val writer = FileWriter(file)
        writer.write(csvContent)
        writer.flush()
        writer.close()
        file.absolutePath
    } catch (e: Exception) {
        ""
    }
}

private fun shareCsvText(context: Context, text: String) {
    val sendIntent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_TEXT, text)
        type = "text/csv"
    }
    val shareIntent = Intent.createChooser(sendIntent, "Export Mycelium Sightings")
    context.startActivity(shareIntent)
}
