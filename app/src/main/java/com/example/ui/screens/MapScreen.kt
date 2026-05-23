package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.HotspotCell
import com.example.model.Observation
import com.example.model.Species
import com.example.ui.viewmodel.FungiViewModel
import com.example.ui.viewmodel.HotspotState
import java.util.*
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: FungiViewModel
) {
    val context = LocalContext.current
    val speciesList by viewModel.speciesList.collectAsState()
    val activeHotspotSpecies by viewModel.selectedSpeciesForHotspot.collectAsState()
    val mapCenter by viewModel.mapCenter.collectAsState()
    val searchRadiusKm by viewModel.searchRadiusKm.collectAsState()

    val hotspotState by viewModel.hotspotState.collectAsState()
    val pins by viewModel.observationPins.collectAsState()
    val weatherSummary by viewModel.weatherSummary.collectAsState()
    val isRunning by viewModel.isRecomputationsRunning.collectAsState()

    var showSpeciesDropdown by remember { mutableStateOf(false) }
    var selectedHotspotCell by remember { mutableStateOf<HotspotCell?>(null) }

    // Navigation and coordinates editing states
    var manualLatText by remember { mutableStateOf(String.format(Locale.US, "%.5f", mapCenter.first)) }
    var manualLngText by remember { mutableStateOf(String.format(Locale.US, "%.5f", mapCenter.second)) }

    // Auto-calculate hotspots whenever parameters are set or active species is chosen
    LaunchedEffect(activeHotspotSpecies, mapCenter, searchRadiusKm) {
        if (activeHotspotSpecies != null) {
            viewModel.computeHotspots()
        } else if (speciesList.isNotEmpty()) {
            viewModel.selectedSpeciesForHotspot.value = speciesList.first()
        }
    }

    // Reset coordinates helper text if mapCenter shifts
    LaunchedEffect(mapCenter) {
        manualLatText = String.format(Locale.US, "%.5f", mapCenter.first)
        manualLngText = String.format(Locale.US, "%.5f", mapCenter.second)
    }

    Scaffold { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            
            // 1. Interactive Canvas Topography Map View
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xFF0C100D)) // Dark moss forest green-black
                ) {
                    // Render Vector Topological Map Grid
                    InteractiveTopoCanvas(
                        centerX = mapCenter.first,
                        centerY = mapCenter.second,
                        radiusKm = searchRadiusKm,
                        hotspotCells = if (hotspotState is HotspotState.Success) (hotspotState as HotspotState.Success).cells else emptyList(),
                        observationPins = pins,
                        onCellSelected = { clickedCell ->
                            selectedHotspotCell = clickedCell
                        },
                        onPointSelected = { newLat, newLng ->
                            viewModel.mapCenter.value = Pair(newLat, newLng)
                            selectedHotspotCell = null
                        }
                    )

                    // Compass Indicator & Scale Overlay
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(16.dp)
                            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "N 🧭",
                            color = Color.Green,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "GRID: 500m",
                            color = Color.LightGray,
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Loading Spinner Overlay
                    if (isRunning) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "Querying ecological layers...",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. Control center bottom drawer (Parameters, Slider, Microclimate signal, Details)
                Surface(
                    tonalElevation = 4.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {

                        // Species choosing drop folder
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "TARGET TAXON:",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(end = 8.dp)
                            )

                            Box(modifier = Modifier.weight(1f)) {
                                Button(
                                    onClick = { showSpeciesDropdown = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("map_species_selector"),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = activeHotspotSpecies?.scientificName ?: "Select Target Specimen",
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f),
                                        textAlign = TextAlign.Start
                                    )
                                    Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null)
                                }

                                DropdownMenu(
                                    expanded = showSpeciesDropdown,
                                    onDismissRequest = { showSpeciesDropdown = false }
                                ) {
                                    speciesList.forEach { spec ->
                                        DropdownMenuItem(
                                            text = { Text("${spec.scientificName} (${spec.commonNames.firstOrNull() ?: ""})") },
                                            onClick = {
                                                viewModel.selectedSpeciesForHotspot.value = spec
                                                showSpeciesDropdown = false
                                                selectedHotspotCell = null
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Draw-Radius Slider Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SEARCH RADIUS:",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Slider(
                                value = searchRadiusKm.toFloat(),
                                onValueChange = { viewModel.searchRadiusKm.value = it.toDouble() },
                                valueRange = 1f..20f,
                                steps = 19,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("radius_slider")
                            )
                            Text(
                                text = "${searchRadiusKm.toInt()} km",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.width(54.dp),
                                textAlign = TextAlign.End
                            )
                        }

                        // Weather parameters microclimate summary notification block
                        weatherSummary?.let { (rainfall, maxTemp) ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.CloudQueue,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Microclimate Signal (Past 30d):",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Text(
                                        text = "⚡ Rainfall: ${String.format(Locale.getDefault(), "%.1f", rainfall)}mm  |  🔥 Avg Max Temp: ${String.format(Locale.getDefault(), "%.1f", maxTemp)}°C",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        // Quick coordinates override inputs for emulator mapping convenience
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "STATION LAT/LNG:",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            OutlinedTextField(
                                value = manualLatText,
                                onValueChange = { manualLatText = it },
                                textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                singleLine = true,
                                shape = RoundedCornerShape(4.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            OutlinedTextField(
                                value = manualLngText,
                                onValueChange = { manualLngText = it },
                                textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                singleLine = true,
                                shape = RoundedCornerShape(4.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            IconButton(
                                onClick = {
                                    val customLat = manualLatText.toDoubleOrNull()
                                    val customLng = manualLngText.toDoubleOrNull()
                                    if (customLat != null && customLng != null) {
                                        viewModel.mapCenter.value = Pair(customLat, customLng)
                                        viewModel.computeHotspots()
                                        Toast.makeText(context, "Coordinates modified to manual site", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Invalid input coordinates", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary)
                            ) {
                                Icon(imageVector = Icons.Default.Check, contentDescription = "Apply custom coordinates")
                            }
                        }
                    }
                }
            }

            // 3. Floating Bottom Sheet panel triggered by tapping a specific Hotspot Grid square cell!
            selectedHotspotCell?.let { cell ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(8.dp)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .border(1.5.dp, if (cell.tier == "High") Color.Red else if (cell.tier == "Medium") Color.Yellow else Color.Cyan, RoundedCornerShape(12.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            when (cell.tier) {
                                                "High" -> Color(0xFFFF5252)
                                                "Medium" -> Color(0xFFFFD740)
                                                else -> Color(0xFF69F0AE)
                                            }
                                        )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "FRUITING DENSITY TIER: ${cell.tier.uppercase()}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (cell.tier == "High") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(onClick = { selectedHotspotCell = null }) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = "Close description")
                            }
                        }
                        
                        Text(
                            text = "Model Probability Score: ${String.format(Locale.getDefault(), "%.1f%%", cell.score * 100.0)}",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )

                        Text(
                            text = "COMPUTING ENVIRONMENTAL CONSTITUENTS:",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )

                        cell.contributingFactors.forEach { factor ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = "• ",
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = factor,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 16.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(10.dp))
                        
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    viewModel.mapCenter.value = Pair(cell.lat, cell.lng)
                                    viewModel.computeHotspots()
                                    selectedHotspotCell = null
                                    Toast.makeText(context, "Search grid moved to targeted microcell site", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(imageVector = Icons.Default.FilterVintage, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("PROBE THIS GRIDPOINT", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Custom-drawn Vector Topography Maps with mouse gesture pan!
 */
@Composable
fun InteractiveTopoCanvas(
    centerX: Double,
    centerY: Double,
    radiusKm: Double,
    hotspotCells: List<HotspotCell>,
    observationPins: List<Observation>,
    onCellSelected: (HotspotCell) -> Unit,
    onPointSelected: (Double, Double) -> Unit
) {
    // Zoom & pan states
    var panOffset by remember { mutableStateOf(Offset(0f, 0f)) }
    
    // Scale mapping factor (Pixels per kilometer)
    val baseScale = 22f // constant scale multiplier

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    panOffset += dragAmount
                }
            }
            .pointerInput(centerX, centerY, radiusKm, hotspotCells) {
                detectTapGestures { tapOffset ->
                    // Calculate click position relative to map center in lat/lng coordinate degrees!
                    val canvasWidth = size.width
                    val canvasHeight = size.height
                    
                    val mapRelativeX = (tapOffset.x - (canvasWidth / 2f) - panOffset.x) / baseScale
                    val mapRelativeY = (tapOffset.y - (canvasHeight / 2f) - panOffset.y) / baseScale
                    
                    val deltaLat = -mapRelativeY / 111.0
                    val deltaLng = mapRelativeX / 87.7

                    val tappedLat = centerX + deltaLat
                    val tappedLng = centerY + deltaLng

                    val tappedCell = hotspotCells.minByOrNull { cell ->
                        val latDiff = cell.lat - tappedLat
                        val lngDiff = cell.lng - tappedLng
                        (latDiff * latDiff) + (lngDiff * lngDiff)
                    }

                    if (tappedCell != null && calculateDistanceBetweenPoints(tappedCell.lat, tappedCell.lng, tappedLat, tappedLng) <= 500.0) {
                        onCellSelected(tappedCell)
                    } else {
                        onPointSelected(tappedLat, tappedLng)
                    }
                }
            }
    ) {
        val width = size.width
        val height = size.height

        val canvasCenterX = width / 2f + panOffset.x
        val canvasCenterY = height / 2f + panOffset.y

        val linePaint = Color(0xFF222823)
        val densityHz = 80f

        // Verticals
        var xOffset = canvasCenterX % densityHz
        while (xOffset < width) {
            drawLine(
                color = linePaint,
                start = Offset(xOffset, 0f),
                end = Offset(xOffset, height),
                strokeWidth = 1f
            )
            xOffset += densityHz
        }

        // Horizontals
        var yOffset = canvasCenterY % densityHz
        while (yOffset < height) {
            drawLine(
                color = linePaint,
                start = Offset(0f, yOffset),
                end = Offset(width, yOffset),
                strokeWidth = 1f
            )
            yOffset += densityHz
        }

        val topoLineColor = Color(0xFF142016)
        
        val topoCenter1 = Offset(canvasCenterX + 120f, canvasCenterY - 180f)
        for (r in listOf(60f, 110f, 170f, 240f, 320f)) {
            drawCircle(
                color = topoLineColor,
                radius = r,
                center = topoCenter1,
                style = Stroke(width = 1.5f)
            )
        }

        val topoCenter2 = Offset(canvasCenterX - 280f, canvasCenterY + 220f)
        for (r in listOf(70f, 130f, 210f, 300f)) {
            drawCircle(
                color = topoLineColor,
                radius = r,
                center = topoCenter2,
                style = Stroke(width = 1.2f)
            )
        }

        val cellSidePixels = baseScale * 0.5f

        for (cell in hotspotCells) {
            val cellRelLat = cell.lat - centerX
            val cellRelLng = cell.lng - centerY

            val cellRelXKm = cellRelLng * 87.7
            val cellRelYKm = cellRelLat * 111.0

            val cellX = canvasCenterX + (cellRelXKm * baseScale).toFloat() - (cellSidePixels / 2f)
            val cellY = canvasCenterY - (cellRelYKm * baseScale).toFloat() - (cellSidePixels / 2f)

            val cellColor = when (cell.tier) {
                "High" -> Color(0xFFFF5252).copy(alpha = 0.45f)
                "Medium" -> Color(0xFFFFD740).copy(alpha = 0.35f)
                else -> Color(0xFF69F0AE).copy(alpha = 0.15f)
            }

            drawRect(
                color = cellColor,
                topLeft = Offset(cellX, cellY),
                size = Size(cellSidePixels, cellSidePixels)
            )

            drawRect(
                color = cellColor.copy(alpha = 0.6f),
                topLeft = Offset(cellX, cellY),
                size = Size(cellSidePixels, cellSidePixels),
                style = Stroke(width = 1f)
            )
        }

        val boundaryRadiusPixels = (radiusKm * baseScale).toFloat()
        drawCircle(
            color = Color(0xFF388E3C).copy(alpha = 0.8f),
            radius = boundaryRadiusPixels,
            center = Offset(canvasCenterX, canvasCenterY),
            style = Stroke(width = 2.5f)
        )

        for (pin in observationPins) {
            val pinRelLat = pin.lat - centerX
            val pinRelLng = pin.lng - centerY

            val pinRelXKm = pinRelLng * 87.7
            val pinRelYKm = pinRelLat * 111.0

            val pinX = canvasCenterX + (pinRelXKm * baseScale).toFloat()
            val pinY = canvasCenterY - (pinRelYKm * baseScale).toFloat()

            drawCircle(
                color = Color.Black,
                radius = 7f,
                center = Offset(pinX, pinY)
            )

            drawCircle(
                color = Color.White,
                radius = 6f,
                center = Offset(pinX, pinY)
            )

            drawCircle(
                color = Color(0xFFFF1744),
                radius = 4.5f,
                center = Offset(pinX, pinY)
            )
        }

        drawCircle(
            color = Color.Cyan.copy(alpha = 0.3f),
            radius = 16f,
            center = Offset(canvasCenterX, canvasCenterY),
            style = Stroke(width = 1.5f)
        )
        drawCircle(
            color = Color.Cyan,
            radius = 5f,
            center = Offset(canvasCenterX, canvasCenterY)
        )
        drawLine(
            color = Color.Cyan,
            start = Offset(canvasCenterX - 24f, canvasCenterY),
            end = Offset(canvasCenterX + 24f, canvasCenterY),
            strokeWidth = 1.5f
        )
        drawLine(
            color = Color.Cyan,
            start = Offset(canvasCenterX, canvasCenterY - 24f),
            end = Offset(canvasCenterX, canvasCenterY + 24f),
            strokeWidth = 1.5f
        )
    }
}

private fun calculateDistanceBetweenPoints(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371e3
    val phi1 = lat1 * Math.PI / 180.0
    val phi2 = lat2 * Math.PI / 180.0
    val deltaPhi = (lat2 - lat1) * Math.PI / 180.0
    val deltaLambda = (lon2 - lon1) * Math.PI / 180.0

    val a = sin(deltaPhi / 2) * sin(deltaPhi / 2) +
            cos(phi1) * cos(phi2) *
            sin(deltaLambda / 2) * sin(deltaLambda / 2)
    val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))

    return R * c
}
