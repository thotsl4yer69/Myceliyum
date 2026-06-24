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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.HotspotCell
import com.example.model.MapObservation
import com.example.model.Observation
import com.example.model.Species
import com.example.ui.theme.HeatHigh
import com.example.ui.theme.HeatLow
import com.example.ui.theme.MapPinExcellent
import com.example.ui.theme.MapPinPossible
import com.example.ui.theme.MapPinPromising
import com.example.ui.theme.MapPinUnlikely
import com.example.ui.theme.MapPinVeryGood
import com.example.ui.theme.TierExcellent
import com.example.ui.theme.TierPossible
import com.example.ui.theme.TierPromising
import com.example.ui.theme.TierUnlikely
import com.example.ui.theme.TierVeryGood
import com.example.ui.viewmodel.DeepSearchState
import com.example.ui.viewmodel.FungiViewModel
import com.example.ui.viewmodel.HotspotState
import com.example.util.MycoMath
import com.google.android.gms.location.LocationServices
import java.util.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.os.Handler
import android.os.Looper
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.ceil
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
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
    val deepSearchState by viewModel.deepSearchState.collectAsState()
    val pins by viewModel.observationPins.collectAsState()
    val allFungiPins by viewModel.allFungiPins.collectAsState()
    val showAllSightings by viewModel.showAllSightings.collectAsState()
    val weatherSummary by viewModel.weatherSummary.collectAsState()
    val isRunning by viewModel.isRecomputationsRunning.collectAsState()
    val computeRuns by viewModel.computeRuns.collectAsState()

    var showSpeciesDropdown by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }
    var selectedHotspotCell by remember { mutableStateOf<HotspotCell?>(null) }
    var isFullscreen by remember { mutableStateOf(false) }
    var currentBottomTab by remember { mutableStateOf(0) } // 0 = Parameters, 1 = Hotspots Registry
    // Where the map has been panned to but NOT yet searched. Panning only records
    // this pending centre (so the heavy grid is not recomputed on every drag);
    // tapping "Search this area" promotes it to the real search centre.
    var pendingCenter by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    val hotspotsList = remember(hotspotState) {
        if (hotspotState is HotspotState.Success) {
            val cells = (hotspotState as HotspotState.Success).cells
            val promising = cells.filter { it.tier != "Unlikely" }.sortedByDescending { it.score }
            if (promising.isNotEmpty()) {
                // Cap the displayed list to the strongest 20 so the "Hotspot list"
                // tab is a usable shortlist, not thousands of rows.
                promising.take(20)
            } else {
                // Nothing crossed the absolute "Possible" line (sparse evidence /
                // off-season) — still surface the strongest cells relative to this
                // grid so the list shows the best-available spots, honestly tiered,
                // rather than an empty "nothing here".
                // Always surface the strongest cells (no absolute floor) so a
                // populated grid never shows an empty "no hotspots" list — the
                // relative best is honestly tiered below.
                cells.sortedByDescending { it.score }.take(20)
            }
        } else {
            emptyList()
        }
    }

    // Cells drawn on the map: the broad overview grid, with any Deep-Search fine
    // sub-grid overlaid on top (each polygon is sized from its own cellSizeMeters).
    val overviewCells = if (hotspotState is HotspotState.Success)
        (hotspotState as HotspotState.Success).cells else emptyList()
    val deepCells = (deepSearchState as? DeepSearchState.Success)?.cells ?: emptyList()
    val displayedCells = remember(overviewCells, deepCells) { overviewCells + deepCells }

    // Top spots for the numbered map pins: from the active Deep-Search grid if
    // drilled in, else the overview. Best first. Prefer genuinely Promising+ spots
    // (>=0.40), but never go empty when the area is modest — fall back to the
    // strongest cells relative to this grid so foragers always get ranked "best
    // near you" pins instead of a bare map.
    val rankedPins = remember(overviewCells, deepCells) {
        val cells = deepCells.ifEmpty { overviewCells }
        val gridMax = cells.maxOfOrNull { it.score } ?: 0.0
        val pinFloor = minOf(0.34, maxOf(0.12, gridMax * 0.6))
        val above = cells.filter { it.score >= pinFloor }.sortedByDescending { it.score }
        // Never leave a populated grid pinless: if even the relative floor admits
        // nothing (a uniformly weak area), still surface the strongest few cells so
        // foragers always get ranked "best near you" markers, honestly tiered.
        val ranked = above.ifEmpty { cells.sortedByDescending { it.score }.take(3) }
        ranked.take(8)
    }
    // One-shot camera focus (Deep Search zoom / "centre here").
    var focusTarget by remember { mutableStateOf<MapFocus?>(null) }
    // When a Deep Search completes, zoom into that square so the fine grid is legible.
    LaunchedEffect(deepSearchState) {
        (deepSearchState as? DeepSearchState.Success)?.let { ds ->
            focusTarget = MapFocus(ds.parent.lat, ds.parent.lng, 16.5, System.nanoTime())
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
                        viewModel.setMapCenter(location.latitude, location.longitude)
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

    // Auto-calculate hotspots whenever parameters change. In all-species
    // (aggregate) mode there is no single target species, so compute on the mode
    // itself — otherwise the grid would never run and the map stays blank
    // ("grid: idle"). In single-species mode, pick a default species first.
    LaunchedEffect(activeHotspotSpecies, speciesList, allSpeciesMode) {
        if (!allSpeciesMode && activeHotspotSpecies == null && speciesList.isNotEmpty()) {
            viewModel.setSelectedSpeciesForHotspot(speciesList.first())
        }
    }

    // When the search centre changes via an explicit action (search bar / GPS /
    // preset / manual coords / "Search this area"), the pending pan is consumed —
    // clear it so the "Search this area" button doesn't linger.
    LaunchedEffect(mapCenter) { pendingCenter = null }

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
                        heatmapCells = displayedCells,
                        rankedPins = rankedPins,
                        focusTarget = focusTarget,
                        // In aggregate mode the cells already represent combined
                        // evidence; pinning one species' records on top is noisy
                        // and misleading, so skip them.
                        observationPins = if (allSpeciesMode) emptyList() else pins,
                        // Every nearby fungal sighting (any species) from
                        // iNaturalist, rendered as labelled pins independent of
                        // the selected species / mode.
                        allFungiPins = if (showAllSightings) allFungiPins else emptyList(),
                        selectedSpeciesName = if (allSpeciesMode) null else activeHotspotSpecies?.scientificName,
                        onCellSelected = { clickedCell ->
                            selectedHotspotCell = clickedCell
                        },
                        // The search centre follows the map: when the user stops
                        // panning/zooming, recentre on the new viewport centre
                        // (debounced in OSMMapView). Guard against tiny deltas so
                        // we don't recompute when the map barely moved.
                        onCameraIdle = { newLat, newLng ->
                            // While drilled into a Deep-Search square, panning/zoom must
                            // NOT relocate the search or recompute — that would wipe the
                            // drill-down. Exit via "Back to overview" first.
                            if (deepSearchState !is DeepSearchState.Success) {
                                val (curLat, curLng) = viewModel.mapCenter.value
                                if (abs(curLat - newLat) > 1e-4 || abs(curLng - newLng) > 1e-4) {
                                    // Panning alone must NOT recompute the heavy grid —
                                    // only record where the viewport moved to. The user
                                    // promotes it via the "Search this area" button, which
                                    // sets mapCenter and triggers the compute LaunchedEffect.
                                    pendingCenter = Pair(newLat, newLng)
                                }
                            }
                        }
                    )

                    // Fixed crosshair marking the search centre (the map viewport
                    // centre). Drawn in Compose so it never moves and is never
                    // confused with a sighting pin. Non-interactive — taps pass
                    // through to the map / markers below.
                    Icon(
                        imageVector = Icons.Default.GpsFixed,
                        contentDescription = "Search centre",
                        tint = Color(0xFF2196F3),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(30.dp)
                    )

                    // Deep Search status chip (top-centre): a spinner while the fine
                    // sub-grid computes, then a "Back to overview" affordance once it
                    // is overlaid so the broad grid is always one tap away.
                    when (val ds = deepSearchState) {
                        is DeepSearchState.Loading -> Surface(
                            color = MaterialTheme.colorScheme.surfaceColorAtElevation(12.dp),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Deep searching this square…", fontSize = 12.sp)
                            }
                        }
                        is DeepSearchState.Success -> Surface(
                            color = MaterialTheme.colorScheme.surfaceColorAtElevation(12.dp),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 12.dp)
                                .clickable {
                                    viewModel.clearDeepSearch()
                                    // Zoom back out to the overview.
                                    focusTarget = MapFocus(mapCenter.first, mapCenter.second, 13.0, System.nanoTime())
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Fine grid: ${ds.cells.count { it.tier != "Unlikely" }} cells • Back to overview",
                                    fontSize = 12.sp, fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        is DeepSearchState.Error -> Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 12.dp)
                                .clickable { viewModel.clearDeepSearch() }
                        ) {
                            Text(
                                "Deep search failed — tap to dismiss",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                        DeepSearchState.Idle -> {}
                    }

                    // "Search this area" — appears after the user pans the map a
                    // meaningful distance. Panning only records `pendingCenter`; this
                    // button promotes it to the real search centre, which triggers the
                    // compute LaunchedEffect. Anchored top-centre, below the search card.
                    val pc = pendingCenter
                    val showSearchHere = pc != null && calculateDistanceBetweenPoints(
                        viewModel.mapCenter.value.first, viewModel.mapCenter.value.second,
                        pc.first, pc.second
                    ) > 1000.0  // metres
                    if (showSearchHere && pc != null && !isFullscreen) {
                        Button(
                            onClick = {
                                viewModel.setMapCenter(pc.first, pc.second)
                                pendingCenter = null
                                selectedHotspotCell = null
                            },
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            elevation = ButtonDefaults.buttonElevation(
                                defaultElevation = 6.dp,
                                pressedElevation = 2.dp
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 150.dp)
                                .testTag("search_this_area_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Search this area",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

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

                    // Legend — heatmap ramp + numbered top-spot pins. Anchored
                    // bottom-start (clear of the top search card and the bottom-end
                    // FABs) and hidden while a hotspot card is open (the card itself
                    // shows the tier colour) so the two never overlap — no fragile
                    // hard-coded offsets.
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !isFullscreen && selectedHotspotCell == null,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.BottomStart)
                    ) {
                        // When nothing in view reaches the absolute "Promising" line,
                        // the warm shading is purely relative — say so, so a modest
                        // area's colours can't be mistaken for strong absolute odds.
                        val gridBest = displayedCells.maxOfOrNull { it.score } ?: 0.0
                        Column(
                            modifier = Modifier
                                .padding(start = 16.dp, bottom = 16.dp)
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
                            // Heatmap ramp — warm intensity (amber → red), matching the
                            // on-map surface so it stays legible over green/topo tiles.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(lerp(HeatLow, HeatHigh, 0f)))
                                Box(modifier = Modifier.size(10.dp).background(lerp(HeatLow, HeatHigh, 0.33f)))
                                Box(modifier = Modifier.size(10.dp).background(lerp(HeatLow, HeatHigh, 0.66f)))
                                Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(lerp(HeatLow, HeatHigh, 1f)))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Low → High", color = Color.White, fontSize = 9.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier.size(12.dp).clip(CircleShape).background(MapPinExcellent),
                                    contentAlignment = Alignment.Center
                                ) { Text("1", color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold) }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Numbered = best spots", color = Color.White, fontSize = 9.sp)
                            }
                            Text(
                                "Built-up / bare left unshaded",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 8.sp
                            )
                            if (gridBest < 0.34) {
                                Text(
                                    "Best nearby is modest — shading is relative",
                                    color = Color(0xFFE6B24C).copy(alpha = 0.9f),
                                    fontSize = 8.sp
                                )
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
                                        viewModel.setMapCenter(preset.lat, preset.lng)
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

                    // Error state — surface failures instead of a silent blank map
                    val errState = hotspotState as? HotspotState.Error
                    if (!isRunning && errState != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 12.dp, start = 12.dp, end = 12.dp)
                        ) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        // Surface the real reason so a failure in the field
                                        // is diagnosable, not a silent blank map.
                                        text = "Couldn't compute hotspots — ${errState.message}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    TextButton(onClick = { viewModel.computeHotspots() }) {
                                        Text("Retry")
                                    }
                                }
                            }
                        }
                    }

                    // Empty-grid state — the computation succeeded but produced no
                    // scored cells for this area (e.g. catalogue not ready, or every
                    // cell gated out). Without this, the map would just look blank.
                    if (!isRunning && hotspotState is HotspotState.Success && displayedCells.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 12.dp, start = 12.dp, end = 12.dp)
                        ) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(12.dp).copy(alpha = 0.95f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "No prediction grid for this spot — try a larger radius or move the map.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    TextButton(onClick = { viewModel.computeHotspots() }) {
                                        Text("Retry")
                                    }
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

                            // All-fungi sightings layer toggle
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Show iNaturalist sightings",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (showAllSightings)
                                            "${allFungiPins.size} raw records shown as muted dots — tap for details"
                                        else
                                            "Off — overlay raw iNaturalist records as muted dots",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = showAllSightings,
                                    onCheckedChange = { viewModel.setShowAllSightings(it) },
                                    modifier = Modifier.testTag("all_sightings_toggle")
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
                                                    viewModel.setSelectedSpeciesForHotspot(spec)
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
                                                viewModel.setMapCenter(customLat, customLng)
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
                                modifier = Modifier.padding(bottom = 4.dp)
                            )

                            // Honest state readout — distinguishes "still computing"
                            // / "failed" / "empty grid" / "weak grid" so the map can
                            // never silently blank without a reason.
                            val gridBest = overviewCells.maxOfOrNull { it.score } ?: 0.0
                            // Label off the actual state (Loading was previously
                            // mislabelled "idle"); runs= exposes re-trigger thrash.
                            val stateLabel = when (hotspotState) {
                                is HotspotState.Loading -> "computing…"
                                is HotspotState.Error -> "error"
                                is HotspotState.Success -> "ok"
                                else -> "idle"
                            }
                            Text(
                                text = "grid: $stateLabel · cells=${overviewCells.size} · best=${String.format(java.util.Locale.US, "%.2f", gridBest)} · runs=$computeRuns",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            if (hotspotsList.isEmpty()) {
                                val errMsg = (hotspotState as? HotspotState.Error)?.message
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 140.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = when {
                                            isRunning || hotspotState is HotspotState.Loading -> "Computing hotspots — combining records, weather & terrain…"
                                            errMsg != null -> "Couldn't compute hotspots:\n$errMsg"
                                            else -> "No grid produced for this spot.\n• Move the map onto woodland\n• Try a smaller radius\n• Tap Retry"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        textAlign = TextAlign.Center,
                                        lineHeight = 18.sp
                                    )
                                    if (!isRunning) {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Button(onClick = { viewModel.computeHotspots() }) { Text("Retry") }
                                    }
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
                                                        viewModel.setMapCenter(cell.lat, cell.lng)
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
                // Cap the card to ~60% of the screen and let its content scroll, so a
                // cell with a long "Why this score" breakdown can never overflow or
                // push the action buttons off-screen on a short device.
                val maxSheetHeight = LocalConfiguration.current.screenHeightDp.dp * 0.6f
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(12.dp)),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                        .heightIn(max = maxSheetHeight)
                        .border(1.5.dp, tierColor(cell.tier), RoundedCornerShape(12.dp))
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
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
                                val rank = rankedPins.indexOfFirst { it.lat == cell.lat && it.lng == cell.lng }
                                Text(
                                    text = if (rank >= 0) "Spot #${rank + 1} · ${tierLabel(cell.tier)}"
                                           else "${tierLabel(cell.tier)} spot",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = tierColor(cell.tier)
                                )
                            }
                            IconButton(onClick = { selectedHotspotCell = null }) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = "Close details")
                            }
                        }
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            // Score (how good) — kept colour-neutral so the only
                            // colour carrying "how good" is the tier mark/title above.
                            Text(
                                text = "Likelihood ${String.format(Locale.getDefault(), "%.0f%%", cell.score * 100.0)}",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            // Confidence (how much to TRUST the score) — a distinct
                            // dimension from the tier, shown as a neutral 3-pip meter
                            // so it never competes with the tier colour.
                            val pips = confidencePips(cell.confidence)
                            val meterColor = MaterialTheme.colorScheme.onSurfaceVariant
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                repeat(3) { i ->
                                    Box(
                                        modifier = Modifier
                                            .padding(end = 3.dp)
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (i < pips) meterColor
                                                else meterColor.copy(alpha = 0.25f)
                                            )
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${MycoMath.confidenceLabel(cell.confidence)} confidence",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = meterColor
                                )
                            }
                        }

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
                        
                        // Deep Search — refine this promising square into a fine
                        // (~15 m) sub-grid for pinpoint foraging. Single-species,
                        // VeryGood+ only (a finer grid on a weak cell isn't useful).
                        if (viewModel.canDeepSearch(cell)) {
                            Button(
                                onClick = {
                                    viewModel.deepSearch(cell)
                                    selectedHotspotCell = null
                                    Toast.makeText(context, "Deep searching this square — pinch to zoom in.", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = tierColor(cell.tier)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(imageVector = Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Deep Search this square (~15 m)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    viewModel.setMapCenter(cell.lat, cell.lng)
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
    heatmapCells: List<HotspotCell>,
    rankedPins: List<HotspotCell>,
    observationPins: List<Observation>,
    allFungiPins: List<MapObservation> = emptyList(),
    selectedSpeciesName: String? = null,
    focusTarget: MapFocus? = null,
    onCellSelected: (HotspotCell) -> Unit,
    // Fired (debounced) when the user finishes moving the map — the search
    // centre follows the viewport. Tapping is reserved for selecting pins/cells.
    onCameraIdle: (Double, Double) -> Unit
) {
    val context = LocalContext.current

    // Initialize standard user agent for OSM required by policy
    LaunchedEffect(Unit) {
        Configuration.getInstance().userAgentValue = context.packageName
    }

    // Remember the last externally-applied search centre and focus request so the
    // camera moves ONLY when those actually change — never fighting the user's pan
    // or a Deep-Search zoom.
    val lastCenter = remember { doubleArrayOf(centerX, centerY) }
    val lastFocusKey = remember { longArrayOf(0L) }

    AndroidView(
        factory = { ctx ->
            val mapView = MapView(ctx)
            mapView.setTileSource(tileSourceForTheme(mapTheme))
            mapView.setMultiTouchControls(true)
            mapView.controller.setZoom(13.0)
            mapView.controller.setCenter(GeoPoint(centerX, centerY))

            // Relocate the search centre whenever the user moves the map. The
            // MapListener fires continuously while panning/zooming, so debounce
            // and only commit the new centre once movement settles (~half a
            // second after the last gesture) to avoid recomputing on every frame.
            val idleHandler = Handler(Looper.getMainLooper())
            var idleRunnable: Runnable? = null
            fun scheduleIdle() {
                idleRunnable?.let { idleHandler.removeCallbacks(it) }
                val r = Runnable {
                    val c = mapView.mapCenter
                    onCameraIdle(c.latitude, c.longitude)
                }
                idleRunnable = r
                idleHandler.postDelayed(r, 550)
            }
            mapView.addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean { scheduleIdle(); return false }
                override fun onZoom(event: ZoomEvent?): Boolean { scheduleIdle(); return false }
            })
            mapView
        },
        update = { mapView ->
            // Apply the selected map style (and a real dark mode via colour inversion).
            mapView.setTileSource(tileSourceForTheme(mapTheme))
            mapView.overlayManager.tilesOverlay.setColorFilter(
                if (mapTheme == "Dark") TilesOverlay.INVERT_COLORS else null
            )
            // Move the camera only when the SEARCH centre prop actually changes
            // (search, GPS, presets, manual coords, list) — not when the user pans
            // or we Deep-Search zoom (handled by focusTarget below).
            if (centerX != lastCenter[0] || centerY != lastCenter[1]) {
                mapView.controller.animateTo(GeoPoint(centerX, centerY))
                lastCenter[0] = centerX; lastCenter[1] = centerY
            }
            // Deep-Search / "centre here" zoom: animate + zoom once per request.
            focusTarget?.let { ft ->
                if (ft.key != lastFocusKey[0]) {
                    mapView.controller.animateTo(GeoPoint(ft.lat, ft.lng))
                    mapView.controller.setZoom(ft.zoom)
                    lastFocusKey[0] = ft.key
                }
            }

            // Clear all overlays to redraw. No catch-all tap overlay any more:
            // taps now belong to the markers and cells (for info/selection),
            // while panning the map relocates the search centre.
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
                fillColor = android.graphics.Color.TRANSPARENT
                strokeColor = android.graphics.Color.argb(120, 76, 175, 80)
                strokeWidth = 2.5f
                infoWindow = null   // never show an (empty) info bubble
            }
            mapView.overlays.add(circlePolygon)

            // 2. PROBABILITY HEAT SURFACE — only genuinely-strong cells render, each
            // as a translucent warm CIRCLE blob slightly larger than its cell so
            // neighbours overlap and build up into soft blobs with hot cores, instead
            // of an opaque red checkerboard. The display floor is raised so ordinary
            // ground stays clear (scores are now calibrated and reach ~0.76).
            val cosLngFactor = Math.cos(centerX * Math.PI / 180.0)
            val gridMax = heatmapCells.maxOfOrNull { it.score } ?: 0.0
            val heatFloor = maxOf(0.40, gridMax * 0.55)
            val heatTop = maxOf(gridMax, heatFloor + 0.12)
            for (cell in heatmapCells) {
                if (cell.score < heatFloor) continue
                // Circle radius slightly larger than the cell so adjacent strong
                // blobs overlap and blend. Convert metres → degrees: latitude is
                // ~111 km/deg everywhere; longitude shrinks by cos(latitude).
                val r = cell.cellSizeMeters * 0.85
                val latRadius = r / 111_000.0
                val lngRadius = r / (111_000.0 * cosLngFactor)
                val circle = ArrayList<GeoPoint>(19)
                val segments = 18
                for (i in 0 until segments) {
                    val angle = 2.0 * Math.PI * i.toDouble() / segments
                    circle.add(
                        GeoPoint(
                            cell.lat + latRadius * Math.sin(angle),
                            cell.lng + lngRadius * Math.cos(angle)
                        )
                    )
                }
                circle.add(circle.first()) // close the ring
                val blob = Polygon(mapView).apply {
                    points = circle
                    fillColor = heatColor(cell.score, heatFloor, heatTop)
                    strokeColor = android.graphics.Color.TRANSPARENT
                    strokeWidth = 0f
                    infoWindow = null
                }
                mapView.overlays.add(blob)
            }

            // 3. RANKED SPOT PINS — the best spots as numbered discs (① = best).
            // Tap routes straight to the Compose detail card; no osmdroid bubble.
            rankedPins.forEachIndexed { i, cell ->
                val marker = Marker(mapView).apply {
                    position = GeoPoint(cell.lat, cell.lng)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = numberedPinDrawable(context, i + 1, mapPinColorInt(cell.tier))
                    infoWindow = null
                    setOnMarkerClickListener { _, _ -> onCellSelected(cell); true }
                }
                mapView.overlays.add(marker)
            }

            val dateFmt = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())

            // 4. Sighting markers — no info-window (so no empty bubbles); a tap
            // shows a quick toast with the species + date instead.
            for (pin in observationPins) {
                val label = selectedSpeciesName ?: "Sighting"
                val whenStr = if (pin.observedAt > 0) " · ${dateFmt.format(java.util.Date(pin.observedAt))}" else ""
                val marker = Marker(mapView).apply {
                    position = GeoPoint(pin.lat, pin.lng)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    infoWindow = null
                    setOnMarkerClickListener { _, _ ->
                        Toast.makeText(context, "$label$whenStr (${pin.source})", Toast.LENGTH_SHORT).show(); true
                    }
                }
                mapView.overlays.add(marker)
            }
            // Raw iNaturalist sightings (when the layer is on) — drawn as small,
            // muted hollow dots, deliberately UNLIKE the bold warm numbered
            // prediction discs, so raw records can't be mistaken for predictions.
            // One shared drawable for all of them (cheap, vs a bitmap per pin).
            val sightingDot = sightingDotDrawable(context)
            for (pin in allFungiPins.take(250)) {
                val name = pin.commonName?.takeIf { it.isNotBlank() } ?: pin.taxonName
                val whenStr = if (pin.observedAt > 0) " · ${dateFmt.format(java.util.Date(pin.observedAt))}" else ""
                val marker = Marker(mapView).apply {
                    position = GeoPoint(pin.lat, pin.lng)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = sightingDot
                    infoWindow = null
                    setOnMarkerClickListener { _, _ ->
                        Toast.makeText(context, "Sighting · $name$whenStr", Toast.LENGTH_SHORT).show(); true
                    }
                }
                mapView.overlays.add(marker)
            }

            // No search-centre marker: the centre is the map viewport centre and
            // is shown by a fixed crosshair drawn in Compose over the map. This
            // keeps the centre unambiguous and stops it being mistaken for a pin.

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

/** Maps a 5-tier name to its UI colour. Reads the canonical, monotonic
 *  green-anchored ramp from the theme so the heatmap, pins, chips and card can
 *  never drift apart (see [com.example.ui.theme.TierExcellent]). */
private fun tierColor(tier: String): Color = when (tier) {
    "Excellent" -> TierExcellent
    "VeryGood"  -> TierVeryGood
    "Promising" -> TierPromising
    "Possible"  -> TierPossible
    else        -> TierUnlikely
}

/** A one-shot camera move request (Deep Search / "centre here"); [key] makes each
 *  request unique so the map animates once rather than on every recomposition. */
data class MapFocus(val lat: Double, val lng: Double, val zoom: Double, val key: Long)

/**
 * Number of filled "pips" (out of 3) for a prediction-confidence value, keyed to
 * the same three bands as [MycoMath.confidenceLabel] so the meter and its word
 * label always agree. Confidence is rendered as a neutral dot-meter (not a
 * coloured chip) so it reads as a distinct dimension from the tier colour.
 */
private fun confidencePips(c: Double): Int = when {
    c >= 0.66 -> 3
    c >= 0.33 -> 2
    else -> 1
}

/**
 * Relative probability→colour ramp for the heatmap: a WARM intensity ramp (pale
 * amber → red, [HeatLow]/[HeatHigh]) with opacity rising with score, so weak
 * areas stay faint and strong ones pop. Warm — not green — because the surface
 * sits on a green/topographic basemap that camouflages a green ramp (the very
 * reason users reported "never see any shading"); amber→red reads clearly over
 * terrain and follows the universal "hotter = more likely" heatmap convention.
 * The ramp is relative to the grid's own [floor, top] range, so the best spots
 * in view always read hot even when absolute scores are modest (sparse evidence
 * / off-season): the map shows "best near you", never a blank surface. The
 * minimum opacity (~37%) is high enough that even floor-level cells are visible
 * over busy terrain tiles. Scores below the caller's floor are filtered out.
 */
private fun heatColor(score: Double, floor: Double, top: Double): Int {
    val span = (top - floor).coerceAtLeast(0.0001)
    val t = ((score - floor) / span).coerceIn(0.0, 1.0).toFloat()
    val base = lerp(HeatLow, HeatHigh, t)
    // Low base opacity so overlapping circle blobs accumulate softly instead of
    // forming an opaque wall; only hot cores approach full strength.
    val alpha = (35 + 120 * t).toInt().coerceIn(0, 255)
    return base.copy(alpha = alpha / 255f).toArgb()
}

/**
 * Warm ARGB colour for a ranked map-pin disc, by tier. Decoupled from the green
 * [tierColor] (which the dark-UI card uses) so the numbered "best spot" discs
 * stand out against the green/topo basemap rather than blending into it.
 */
private fun mapPinColorInt(tier: String): Int = when (tier) {
    "Excellent" -> MapPinExcellent
    "VeryGood"  -> MapPinVeryGood
    "Promising" -> MapPinPromising
    "Possible"  -> MapPinPossible
    else        -> MapPinUnlikely
}.toArgb()

/** Builds a numbered, coloured map-pin disc (① ② ③ …) for a ranked spot. */
private fun numberedPinDrawable(
    ctx: android.content.Context,
    number: Int,
    colorInt: Int
): android.graphics.drawable.Drawable {
    val density = ctx.resources.displayMetrics.density
    val size = (28f * density).toInt().coerceAtLeast(40)
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val r = size / 2f
    val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = colorInt; style = android.graphics.Paint.Style.FILL
    }
    canvas.drawCircle(r, r, r - density, fill)
    val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    canvas.drawCircle(r, r, r - density, ring)
    val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = size * 0.5f
        textAlign = android.graphics.Paint.Align.CENTER
        isFakeBoldText = true
    }
    val fm = text.fontMetrics
    canvas.drawText(number.toString(), r, r - (fm.ascent + fm.descent) / 2f, text)
    return android.graphics.drawable.BitmapDrawable(ctx.resources, bmp)
}

/**
 * A small, muted hollow dot for a raw iNaturalist sighting — intentionally low-key
 * and unlike the bold warm numbered prediction discs, so raw records read as
 * "observed here", not as a model prediction. Shared across all sighting markers.
 */
private fun sightingDotDrawable(ctx: android.content.Context): android.graphics.drawable.Drawable {
    val density = ctx.resources.displayMetrics.density
    val size = (12f * density).toInt().coerceAtLeast(16)
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val r = size / 2f
    val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(150, 230, 230, 230); style = android.graphics.Paint.Style.FILL
    }
    canvas.drawCircle(r, r, r - density, fill)
    val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(200, 60, 60, 60)
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    canvas.drawCircle(r, r, r - density, ring)
    return android.graphics.drawable.BitmapDrawable(ctx.resources, bmp)
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
