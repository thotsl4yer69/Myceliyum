package com.example.ui.screens

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.lazy.*
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
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
import com.google.android.gms.location.LocationServices
import java.util.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.ceil
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory

private data class PresetLoc(val name: String, val lat: Double, val lng: Double)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: FungiViewModel
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val speciesList by viewModel.speciesList.collectAsState()
    val activeHotspotSpecies by viewModel.selectedSpeciesForHotspot.collectAsState()
    val mapCenter by viewModel.mapCenter.collectAsState()
    val searchRadiusKm by viewModel.searchRadiusKm.collectAsState()
    val mapTheme by viewModel.mapTheme.collectAsState()
    val measureUnits by viewModel.measureUnits.collectAsState()
    val isImperial = measureUnits == "Imperial"
    val allSpeciesMode by viewModel.isAllSpeciesMode.collectAsState()

    val hotspotState by viewModel.hotspotState.collectAsState()
    val pins by viewModel.observationPins.collectAsState()
    val weatherSummary by viewModel.weatherSummary.collectAsState()
    val isRunning by viewModel.isRecomputationsRunning.collectAsState()

    var showSpeciesDropdown by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }
    var selectedHotspotCell by remember { mutableStateOf<HotspotCell?>(null) }
    var isFullscreen by remember { mutableStateOf(false) }
    var currentBottomTab by remember { mutableStateOf(0) } // 0 = Parameters, 1 = Hotspots Registry

    val hotspotsList = remember(hotspotState) {
        if (hotspotState is HotspotState.Success) {
            (hotspotState as HotspotState.Success).cells
                .filter { it.tier != "Unlikely" }
                .sortedByDescending { it.score }
        } else {
            emptyList()
        }
    }

    // Navigation and coordinates editing states
    var manualLatText by remember { mutableStateOf(String.format(Locale.US, "%.5f", mapCenter.first)) }
    var manualLngText by remember { mutableStateOf(String.format(Locale.US, "%.5f", mapCenter.second)) }

    // Resolve current locality description in background thread via Geocoder
    var currentLocalName by remember { mutableStateOf("Resolving local site...") }

    // Geocoder lookup whenever map center shifts
    LaunchedEffect(mapCenter) {
        manualLatText = String.format(Locale.US, "%.5f", mapCenter.first)
        manualLngText = String.format(Locale.US, "%.5f", mapCenter.second)
        
        currentLocalName = "Reading coordinates..."
        // Reverse-geocode via the repository (Google when keyed, else device).
        viewModel.reverseGeocode(mapCenter.first, mapCenter.second) { name ->
            currentLocalName = name
        }
    }

    // Geocode Search execution — geocoding (Google when a key is configured,
    // else the device geocoder) is handled in the ViewModel/repository; here
    // we just surface the result to the user.
    fun searchLocation(query: String) {
        if (query.isBlank()) return
        viewModel.searchLocation(query) { label ->
            if (label != null) {
                Toast.makeText(context, "Centered on: $label", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Location not found. Try coordinates directly.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Location Permission request launcher
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            try {
                val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    if (location != null) {
                        viewModel.mapCenter.value = Pair(location.latitude, location.longitude)
                        viewModel.computeHotspots()
                        Toast.makeText(context, "GPS synchronized: Centered on your actual local area!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Could not acquire GPS position. Ensure Location is enabled on device.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (se: SecurityException) {
                Toast.makeText(context, "Security exception fetching GPS location.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Location permission is required to center on your physical area.", Toast.LENGTH_LONG).show()
        }
    }

    // Auto-calculate hotspots whenever parameters change
    LaunchedEffect(activeHotspotSpecies, mapCenter, searchRadiusKm, speciesList) {
        if (activeHotspotSpecies != null) {
            viewModel.computeHotspots()
        } else if (speciesList.isNotEmpty()) {
            viewModel.selectedSpeciesForHotspot.value = speciesList.first()
        }
    }

    Scaffold { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            
            // 1. Full-Height Interactive Canvas Topography Map View
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xFF0B0D0B)) // Ultra dark forest obsidian green
                ) {
                    OSMMapView(
                        centerX = mapCenter.first,
                        centerY = mapCenter.second,
                        radiusKm = searchRadiusKm,
                        mapTheme = mapTheme,
                        hotspotCells = if (hotspotState is HotspotState.Success) (hotspotState as HotspotState.Success).cells else emptyList(),
                        // In aggregate mode the cells already represent combined
                        // evidence; pinning one species' records on top is noisy
                        // and misleading, so skip them.
                        observationPins = if (allSpeciesMode) emptyList() else pins,
                        onCellSelected = { clickedCell ->
                            selectedHotspotCell = clickedCell
                        },
                        onPointSelected = { newLat, newLng ->
                            viewModel.mapCenter.value = Pair(newLat, newLng)
                            selectedHotspotCell = null
                        }
                    )

                    // Compass Indicator & Scale Overlay (hidden in fullscreen)
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !isFullscreen,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.TopStart)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(top = 130.dp, start = 16.dp)
                                .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "N 🧭",
                                color = Color(0xFF66BB6A),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "500 m grid",
                                color = Color.LightGray,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // Color Legend Overlay — 5-tier system (hidden in fullscreen)
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !isFullscreen,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(top = 130.dp, end = 16.dp)
                                .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            horizontalAlignment = Alignment.Start,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Likelihood",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFFFF6B6B)))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Excellent >80%", color = Color.White, fontSize = 9.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF54E0A0)))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Very Good 60-80%", color = Color.White, fontSize = 9.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFFE6B24C)))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Promising 40-60%", color = Color.White, fontSize = 9.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF8B9D93)))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Possible 20-40%", color = Color.White, fontSize = 9.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF5B6353).copy(alpha = 0.3f)))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Unlikely <20%", color = Color.White, fontSize = 9.sp)
                            }
                        }
                    }

                    // Fullscreen & Layer toggle buttons
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 16.dp, end = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Fullscreen toggle
                        FloatingActionButton(
                            onClick = { isFullscreen = !isFullscreen },
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            contentColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = "Toggle fullscreen",
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Satellite view toggle
                        FloatingActionButton(
                            onClick = {
                                val currentTheme = mapTheme
                                if (currentTheme == "Satellite") {
                                    viewModel.setMapTheme("Topographic")
                                } else {
                                    viewModel.setMapTheme("Satellite")
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            contentColor = if (mapTheme == "Satellite") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Satellite,
                                contentDescription = "Toggle satellite view",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Dynamic Floating Geolocation Search & presets Column (hidden in fullscreen)
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !isFullscreen,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                        modifier = Modifier.align(Alignment.TopCenter)
                    ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // A. Search Bar Input Card
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Place,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Area:",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = currentLocalName,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                var searchQueryText by remember { mutableStateOf("") }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = searchQueryText,
                                        onValueChange = { searchQueryText = it },
                                        placeholder = { Text("Search city, forest, national park...", fontSize = 12.sp) },
                                        textStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Color.White),
                                        singleLine = true,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("location_search_input"),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                            focusedPlaceholderColor = Color.Gray,
                                            unfocusedPlaceholderColor = Color.Gray
                                        ),
                                        trailingIcon = {
                                            if (searchQueryText.isNotEmpty()) {
                                                IconButton(onClick = { searchQueryText = "" }) {
                                                    Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        }
                                    )

                                    // Run geocoder button
                                    IconButton(
                                        onClick = {
                                            if (searchQueryText.isNotBlank()) {
                                                searchLocation(searchQueryText)
                                            } else {
                                                Toast.makeText(context, "Please write a locality name!", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                                            .testTag("search_submit_btn")
                                    ) {
                                        Icon(imageVector = Icons.Default.Search, contentDescription = "Search location", tint = MaterialTheme.colorScheme.onPrimary)
                                    }

                                    // Locate Current GPS GPS Button
                                    IconButton(
                                        onClick = {
                                            locationPermissionLauncher.launch(
                                                arrayOf(
                                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                                )
                                            )
                                        },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(8.dp))
                                            .testTag("locate_me_btn")
                                    ) {
                                        Icon(imageVector = Icons.Default.MyLocation, contentDescription = "Use My Location", tint = MaterialTheme.colorScheme.onSecondary)
                                    }
                                }
                            }
                        }

                        // B. Quick presets — collapsed by default to keep the
                        // card compact so it doesn't block the map.
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (showPresets) "▴ Hide quick spots" else "▾ Quick spots (Victoria)",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { showPresets = !showPresets }
                                .padding(vertical = 2.dp)
                                .testTag("presets_toggle")
                        )
                        androidx.compose.animation.AnimatedVisibility(visible = showPresets) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            val presetSites = listOf(
                                PresetLoc("🍂 Dandenong Ranges", -37.8386, 145.3524),
                                PresetLoc("🌲 Sherbrooke Forest", -37.8896, 145.3580),
                                PresetLoc("🌿 Otway Ranges", -38.6500, 143.5500),
                                PresetLoc("🌲 Toolangi Forest", -37.5300, 145.4700),
                                PresetLoc("🏞️ Kinglake NP", -37.5200, 145.3500),
                                PresetLoc("🏔️ Wombat Forest", -37.4500, 144.3000),
                                PresetLoc("🍄 Wilsons Prom", -39.0300, 146.3200)
                            )
                            items(presetSites) { preset ->
                                AssistChip(
                                    onClick = {
                                        viewModel.mapCenter.value = Pair(preset.lat, preset.lng)
                                        viewModel.computeHotspots()
                                        Toast.makeText(context, "Map centred on ${preset.name}", Toast.LENGTH_SHORT).show()
                                    },
                                    label = { Text(preset.name, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(12.dp).copy(alpha = 0.95f),
                                        labelColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                                    modifier = Modifier.testTag("preset_${preset.name.replace(" ", "_")}")
                                )
                            }
                        }
                        }
                    }
                    } // end AnimatedVisibility for search bar

                    // Loading Spinner Overlay
                    if (isRunning) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.3f)),
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
                                        text = "Combining observations and weather…",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. Control center bottom drawer (Parameters, Slider, Microclimate signal, Details)
                AnimatedVisibility(
                    visible = !isFullscreen,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                Surface(
                    tonalElevation = 6.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        // Cap the panel so it never dominates the map; scroll if
                        // the Parameters content is taller than this.
                        .heightIn(max = 300.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        // Section Switcher Tab Row
                        TabRow(
                            selectedTabIndex = currentBottomTab,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            containerColor = Color.Transparent,
                            divider = {}
                        ) {
                            Tab(
                                selected = currentBottomTab == 0,
                                onClick = { currentBottomTab = 0 },
                                text = { Text("Parameters", fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                                icon = { Icon(imageVector = Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            )
                            Tab(
                                selected = currentBottomTab == 1,
                                onClick = { currentBottomTab = 1 },
                                text = { 
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Hotspot list", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        val count = hotspotsList.size
                                        if (count > 0) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Badge(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                            ) {
                                                Text("$count", style = TextStyle(fontSize = 10.sp))
                                            }
                                        }
                                    }
                                },
                                icon = { Icon(imageVector = Icons.Default.Radar, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            )
                        }

                        if (currentBottomTab == 0) {
                            // Mode toggle: combine all species vs focus one
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Combine all species",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (allSpeciesMode)
                                            "Aggregate evidence across ${speciesList.size} catalogued species"
                                        else
                                            "Focus on a single species below",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = allSpeciesMode,
                                    onCheckedChange = { viewModel.setAllSpeciesMode(it) },
                                    modifier = Modifier.testTag("all_species_toggle")
                                )
                            }

                            // Species selector (only meaningful when not in aggregate mode)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Target taxon",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (allSpeciesMode)
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    else
                                        MaterialTheme.colorScheme.primary,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(end = 8.dp)
                                )

                                Box(modifier = Modifier.weight(1f)) {
                                    Button(
                                        onClick = { if (!allSpeciesMode) showSpeciesDropdown = true },
                                        enabled = !allSpeciesMode,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                            contentColor = MaterialTheme.colorScheme.onSurface,
                                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("map_species_selector"),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = when {
                                                allSpeciesMode -> "All species (combined)"
                                                else -> activeHotspotSpecies?.scientificName ?: "Select target specimen"
                                            },
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(1f),
                                            textAlign = TextAlign.Start
                                        )
                                        Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null)
                                    }

                                    DropdownMenu(
                                        expanded = showSpeciesDropdown && !allSpeciesMode,
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
                                    text = "Radius",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Slider(
                                    value = searchRadiusKm.toFloat(),
                                    onValueChange = { viewModel.searchRadiusKm.value = it.toDouble() },
                                    valueRange = 1f..30f,
                                    steps = 29,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("radius_slider")
                                )
                                Text(
                                    text = if (isImperial)
                                        "${String.format(Locale.US, "%.1f", searchRadiusKm * 0.621371)} mi"
                                    else
                                        "${searchRadiusKm.toInt()} km",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(64.dp),
                                    textAlign = TextAlign.End
                                )
                            }

                            // Weather parameters microclimate summary notification block
                            weatherSummary?.let { (rainfall, maxTemp) ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CloudQueue,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Microclimate (past 30 days)",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary,
                                                letterSpacing = 0.5.sp
                                            )
                                        }
                                        val rainfallText = if (isImperial)
                                            "${String.format(Locale.US, "%.2f", rainfall / 25.4)} in"
                                        else
                                            "${String.format(Locale.US, "%.1f", rainfall)}mm"
                                        val tempText = if (isImperial)
                                            "${String.format(Locale.US, "%.1f", maxTemp * 9.0 / 5.0 + 32.0)}°F"
                                        else
                                            "${String.format(Locale.US, "%.1f", maxTemp)}°C"
                                        Text(
                                            text = "⚡ Rainfall (Past 30d): $rainfallText\n🔥 Avg Max Temperature: $tempText",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            lineHeight = 16.sp,
                                            modifier = Modifier.padding(start = 22.dp)
                                        )
                                    }
                                }
                            }

                            // Quick coordinates override inputs for manual debugging
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(
                                    text = "Manual coordinates",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = manualLatText,
                                        onValueChange = { manualLatText = it },
                                        label = { Text("Latitude", fontSize = 12.sp) },
                                        textStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Color.White),
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    OutlinedTextField(
                                        value = manualLngText,
                                        onValueChange = { manualLngText = it },
                                        label = { Text("Longitude", fontSize = 12.sp) },
                                        textStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Color.White),
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    Button(
                                        onClick = {
                                            val customLat = manualLatText.toDoubleOrNull()
                                            val customLng = manualLngText.toDoubleOrNull()
                                            if (customLat != null && customLng != null) {
                                                viewModel.mapCenter.value = Pair(customLat, customLng)
                                                viewModel.computeHotspots()
                                                Toast.makeText(context, "Map centred on those coordinates.", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "Invalid coordinates formatting!", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier
                                            .height(52.dp)
                                            .width(52.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(0.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Check, 
                                            contentDescription = "Apply custom coordinates",
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            // Hotspots list tab
                            Text(
                                text = "Promising and possible hotspots nearby",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            if (hotspotsList.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(180.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No high probability hotspots in selection area.\n• Try increasing search radius.\n• Center near rivers or woodlands using Presets above!\n• Switch to alternative target species.",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        textAlign = TextAlign.Center,
                                        lineHeight = 18.sp
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 240.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    contentPadding = PaddingValues(bottom = 8.dp)
                                ) {
                                    items(hotspotsList) { cell ->
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .border(
                                                    width = 1.dp,
                                                    color = tierColor(cell.tier).copy(alpha = 0.5f),
                                                    shape = RoundedCornerShape(8.dp)
                                                ),
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp))
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(10.dp)
                                                                .clip(RoundedCornerShape(2.dp))
                                                                .background(tierColor(cell.tier))
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = tierLabel(cell.tier),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontWeight = FontWeight.Bold,
                                                            color = tierColor(cell.tier)
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = "Score: ${String.format(Locale.getDefault(), "%.1f%%", cell.score * 100.0)}",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        fontFamily = FontFamily.Monospace
                                                    )
                                                    Text(
                                                        text = "Coords: ${String.format(Locale.getDefault(), "%.4f", cell.lat)}, ${String.format(Locale.getDefault(), "%.4f", cell.lng)}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        fontFamily = FontFamily.Monospace
                                                    )
                                                }

                                                Button(
                                                    onClick = {
                                                        viewModel.mapCenter.value = Pair(cell.lat, cell.lng)
                                                        viewModel.computeHotspots()
                                                        selectedHotspotCell = cell
                                                        Toast.makeText(context, "Centred on this hotspot.", Toast.LENGTH_SHORT).show()
                                                    },
                                                    shape = RoundedCornerShape(6.dp),
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                    modifier = Modifier.height(36.dp)
                                                ) {
                                                    Icon(imageVector = Icons.Default.Explore, contentDescription = null, modifier = Modifier.size(14.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("CENTER", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                } // end AnimatedVisibility
            }

            // 3. Floating Bottom Details overlay panel (hidden in fullscreen)
            AnimatedVisibility(
                visible = !isFullscreen && selectedHotspotCell != null,
                enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
            selectedHotspotCell?.let { cell ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(12.dp)),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .border(1.5.dp, tierColor(cell.tier), RoundedCornerShape(12.dp))
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
                                        .background(tierColor(cell.tier))
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${tierLabel(cell.tier)} spot",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = tierColor(cell.tier)
                                )
                            }
                            IconButton(onClick = { selectedHotspotCell = null }) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = "Close details")
                            }
                        }
                        
                        Text(
                            text = "Likelihood score: ${String.format(Locale.getDefault(), "%.0f%%", cell.score * 100.0)}",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )

                        Text(
                            text = "Why this score",
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
                                    Toast.makeText(context, "Centred on this spot.", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(imageVector = Icons.Default.FilterVintage, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Centre on this spot", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
            } // end AnimatedVisibility for hotspot details
        }
    }
}

@Composable
fun OSMMapView(
    centerX: Double,
    centerY: Double,
    radiusKm: Double,
    mapTheme: String,
    hotspotCells: List<HotspotCell>,
    observationPins: List<Observation>,
    onCellSelected: (HotspotCell) -> Unit,
    onPointSelected: (Double, Double) -> Unit
) {
    val context = LocalContext.current
    
    // Initialize standard user agent for OSM required by policy
    LaunchedEffect(Unit) {
        Configuration.getInstance().userAgentValue = context.packageName
    }
    
    AndroidView(
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(tileSourceForTheme(mapTheme))
                setMultiTouchControls(true)
                controller.setZoom(13.0)
                controller.setCenter(GeoPoint(centerX, centerY))
            }
        },
        update = { mapView ->
            // Apply the selected map style (and a real dark mode via colour inversion).
            mapView.setTileSource(tileSourceForTheme(mapTheme))
            mapView.overlayManager.tilesOverlay.setColorFilter(
                if (mapTheme == "Dark") TilesOverlay.INVERT_COLORS else null
            )
            mapView.controller.animateTo(GeoPoint(centerX, centerY))

            // Clear all overlays to redraw
            mapView.overlays.clear()
            
            // 1. Draw boundary circle (Radius)
            val circlePolygon = Polygon(mapView).apply {
                val geoPoints = mutableListOf<GeoPoint>()
                val steps = 60
                val radiusMeters = radiusKm * 1000.0
                val R = 6378137.0 // Earth radius
                for (i in 0 until steps) {
                    val bearing = 2.0 * Math.PI * i.toDouble() / steps
                    val lat1 = Math.toRadians(centerX)
                    val lon1 = Math.toRadians(centerY)
                    val lat2 = Math.asin(Math.sin(lat1) * Math.cos(radiusMeters / R) + Math.cos(lat1) * Math.sin(radiusMeters / R) * Math.cos(bearing))
                    val lon2 = lon1 + Math.atan2(Math.sin(bearing) * Math.sin(radiusMeters / R) * Math.cos(lat1), Math.cos(radiusMeters / R) - Math.sin(lat1) * Math.sin(lat2))
                    geoPoints.add(GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2)))
                }
                geoPoints.add(geoPoints.first()) // close Path
                points = geoPoints
                fillColor = android.graphics.Color.argb(30, 76, 175, 80) // 4CAF50 alpha
                strokeColor = android.graphics.Color.parseColor("#4CAF50")
                strokeWidth = 3f
            }
            mapView.overlays.add(circlePolygon)

            // 2. Add Hotspot Cells
            val rad = centerX * Math.PI / 180.0
            val cosLngFactor = Math.cos(rad)
            val cellWidth = 0.0057
            val cellHeight = 0.0045
            
            for (cell in hotspotCells) {
                if (cell.tier == "Unlikely") continue // Skip lowest tier for visual clarity

                val halfW = cellWidth / 2.0
                val halfH = cellHeight / 2.0
                val pts = listOf(
                    GeoPoint(cell.lat + halfH, cell.lng - halfW),
                    GeoPoint(cell.lat + halfH, cell.lng + halfW),
                    GeoPoint(cell.lat - halfH, cell.lng + halfW),
                    GeoPoint(cell.lat - halfH, cell.lng - halfW)
                )

                val cellPoly = Polygon(mapView).apply {
                    points = pts
                    // Translucent fills so the basemap stays navigable — only
                    // the top tiers are emphasised; Promising/Possible are faint.
                    fillColor = when (cell.tier) {
                        "Excellent" -> android.graphics.Color.argb(115, 255, 107, 107)  // warm red — hotspot
                        "VeryGood"  -> android.graphics.Color.argb(85, 84, 224, 160)    // mint green
                        "Promising" -> android.graphics.Color.argb(45, 230, 178, 76)   // chanterelle gold
                        "Possible"  -> android.graphics.Color.argb(20, 139, 157, 147)   // muted sage
                        else -> android.graphics.Color.argb(14, 91, 99, 83)          // dim sage
                    }
                    strokeColor = when (cell.tier) {
                        "Excellent" -> android.graphics.Color.parseColor("#FF6B6B")
                        "VeryGood"  -> android.graphics.Color.parseColor("#54E0A0")
                        "Promising" -> android.graphics.Color.parseColor("#E6B24C")
                        "Possible"  -> android.graphics.Color.parseColor("#8B9D93")
                        else -> android.graphics.Color.parseColor("#5B6353")
                    }
                    strokeWidth = when (cell.tier) {
                        "Excellent" -> 3f
                        "VeryGood" -> 2.5f
                        else -> 1.5f
                    }
                    setOnClickListener { _, _, _ ->
                        onCellSelected(cell)
                        true
                    }
                }
                mapView.overlays.add(cellPoly)
            }
            
            // 3. Add Observation Markers
            for (pin in observationPins) {
                val marker = Marker(mapView).apply {
                    position = GeoPoint(pin.lat, pin.lng)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "Observation"
                }
                mapView.overlays.add(marker)
            }

            // 4. Center Crosshair / Probe center
            val centerMarker = Marker(mapView).apply {
                position = GeoPoint(centerX, centerY)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = "Center"
            }
            mapView.overlays.add(centerMarker)
            
            // Handle general map taps
            val tapOverlay = object : org.osmdroid.views.overlay.Overlay() {
                override fun onSingleTapConfirmed(e: android.view.MotionEvent, mapView: MapView): Boolean {
                    val proj = mapView.projection
                    val loc = proj.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint
                    onPointSelected(loc.latitude, loc.longitude)
                    return true
                }
            }
            mapView.overlays.add(tapOverlay)
            
            mapView.invalidate()
        },
        modifier = Modifier.fillMaxSize()
    )
}

/**
 * Maps a user-selected map style to a concrete OSM tile source.
 *
 * DEFAULT is OpenTopoMap (terrain/topography) — the best basemap for
 * identifying woodland, river, and elevation features relevant to
 * mushroom habitat. "Dark" uses standard tiles with colour inversion.
 */
private fun tileSourceForTheme(theme: String): ITileSource = when (theme) {
    "Standard Street" -> TileSourceFactory.MAPNIK
    "Dark" -> TileSourceFactory.MAPNIK // uses color inversion filter
    "Satellite" -> object : org.osmdroid.tileprovider.tilesource.XYTileSource(
        "EsriWorldImagery", 0, 19, 256, "",
        arrayOf("https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
    ) {
        // Esri/ArcGIS tiles are addressed z/y/x (row before column) — the
        // default XYTileSource emits z/x/y, which fetches the wrong tiles.
        // Global imagery; ideal for spotting actual tree canopy when foraging.
        override fun getTileURLString(pMapTileIndex: Long): String =
            baseUrl + org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex) + "/" +
                org.osmdroid.util.MapTileIndex.getY(pMapTileIndex) + "/" +
                org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
    }
    "Topographic" -> org.osmdroid.tileprovider.tilesource.XYTileSource(
        "OpenTopoMap", 0, 17, 256, ".png",
        arrayOf("https://a.tile.opentopomap.org/", "https://b.tile.opentopomap.org/", "https://c.tile.opentopomap.org/")
    ) // OpenTopoMap shows terrain, not roads (can be rate-limited)
    else -> TileSourceFactory.MAPNIK // reliable default basemap
}

/** Maps a 5-tier name to its display label. */
private fun tierLabel(tier: String): String = when (tier) {
    "Excellent" -> "Excellent"
    "VeryGood"  -> "Very Good"
    "Promising" -> "Promising"
    "Possible"  -> "Possible"
    else        -> "Unlikely"
}

/** Maps a 5-tier name to its UI colour. */
private fun tierColor(tier: String): Color = when (tier) {
    "Excellent" -> Color(0xFFFF6B6B)  // warm red
    "VeryGood"  -> Color(0xFF54E0A0)  // mint green
    "Promising" -> Color(0xFFE6B24C)  // chanterelle gold
    "Possible"  -> Color(0xFF8B9D93)  // muted sage
    else        -> Color(0xFF5B6353)  // dim forest
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
