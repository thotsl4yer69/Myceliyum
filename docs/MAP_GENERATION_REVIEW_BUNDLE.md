# Myceliyum — Map Generation: Annotated Review Bundle

- **Repository:** thotsl4yer69/Myceliyum
- **Version:** `65d2d289 (main, post-#59)`
- **Generated:** 2026-06-22
- **Scope:** every file involved in producing the prediction map (UI render → orchestration → scoring engine → data sources → backend), with role annotations and key line anchors. Source is reproduced verbatim with line numbers.

## Pipeline overview

```
MapScreen (UI, osmdroid)  --pan/params-->  FungiViewModel.computeHotspots()
        ^                                          |
        | HotspotState.Success(cells)              v
        +--------------  FungiRepository.generateHotspots / generateMultiSpeciesHotspots
                                                   |  builds a grid; scores each cell via:
                                                   +- MycoMath   (factor math + weights + gates + tiers)
                                                   +- remote/*   (iNaturalist, Open-Meteo, Overpass, EnvLayers)
                                                   +- backend/main.py  (Earth Engine land/canopy/NDVI/soil)
```

**Score composition (per cell):** `finalScore = weightedFactorScore(15 factors) x penaltyMultiplier(moisture) x habitatGate(land cover)` → `classifyTier()`. See `runSpeciesGrid` and `MycoMath.FACTOR_WEIGHTS`.

**Reviewer note:** the scoring engine (`runSpeciesGrid` + `MycoMath`) predates PR #59 and was unchanged by it; PR #59 changed only rendering, palette, and compute orchestration/state. For prediction-validity review, focus on `FungiRepository.kt:1205-1570` and `MycoMath.kt`.

## Contents

1. [`app/src/main/java/com/example/ui/screens/MapScreen.kt`](#appsrcmainjavacomexampleuiscreensmapscreenkt)
2. [`app/src/main/java/com/example/ui/theme/Color.kt`](#appsrcmainjavacomexampleuithemecolorkt)
3. [`app/src/main/java/com/example/ui/viewmodel/FungiViewModel.kt`](#appsrcmainjavacomexampleuiviewmodelfungiviewmodelkt)
4. [`app/src/main/java/com/example/data/repository/FungiRepository.kt`](#appsrcmainjavacomexampledatarepositoryfungirepositorykt)
5. [`app/src/main/java/com/example/util/MycoMath.kt`](#appsrcmainjavacomexampleutilmycomathkt)
6. [`app/src/main/java/com/example/model/FungiModels.kt`](#appsrcmainjavacomexamplemodelfungimodelskt)
7. [`app/src/main/java/com/example/data/remote/INaturalistApi.kt`](#appsrcmainjavacomexampledataremoteinaturalistapikt)
8. [`app/src/main/java/com/example/data/remote/OpenMeteoApi.kt`](#appsrcmainjavacomexampledataremoteopenmeteoapikt)
9. [`app/src/main/java/com/example/data/remote/OverpassApi.kt`](#appsrcmainjavacomexampledataremoteoverpassapikt)
10. [`app/src/main/java/com/example/data/remote/EnvLayersApi.kt`](#appsrcmainjavacomexampledataremoteenvlayersapikt)
11. [`app/src/main/java/com/example/data/remote/GeocodingApi.kt`](#appsrcmainjavacomexampledataremotegeocodingapikt)
12. [`app/src/main/java/com/example/data/remote/BiodiversityApis.kt`](#appsrcmainjavacomexampledataremotebiodiversityapiskt)
13. [`backend/main.py`](#backendmainpy)
14. [`app/src/test/java/com/example/MycoMathTest.kt`](#appsrctestjavacomexamplemycomathtestkt)


---

<a id="appsrcmainjavacomexampleuiscreensmapscreenkt"></a>
## `app/src/main/java/com/example/ui/screens/MapScreen.kt`

**Role:** UI / RENDERING — draws everything on the osmdroid map.  
**Lines:** 1779

**Key anchors:**
- OSMMapView composable ~:1410 (AndroidView wrapping osmdroid MapView)
- Heat-tile draw loop ~:1469 (adaptive floor/top, per-cell polygon)
- Ranked numbered pins ~:1551 (mapPinColorInt + numberedPinDrawable)
- Raw sighting dots ~:1581 (sightingDotDrawable)
- heatColor ~:1676  | mapPinColorInt ~:1700  | numberedPinDrawable ~:1705  | sightingDotDrawable ~:1744
- Legend ~:429 | loading/error/empty banners ~:674 / ~:708 / ~:748
- Compute triggers: LaunchedEffect ~:234, debounced pan onCameraIdle ~:282

```kotlin
   1  package com.example.ui.screens
   2  
   3  import android.Manifest
   4  import android.widget.Toast
   5  import androidx.activity.compose.rememberLauncherForActivityResult
   6  import androidx.activity.result.contract.ActivityResultContracts
   7  import androidx.compose.animation.AnimatedVisibility
   8  import androidx.compose.animation.expandVertically
   9  import androidx.compose.animation.fadeIn
  10  import androidx.compose.animation.fadeOut
  11  import androidx.compose.animation.shrinkVertically
  12  import androidx.compose.foundation.*
  13  import androidx.compose.foundation.lazy.*
  14  import androidx.compose.foundation.gestures.detectDragGestures
  15  import androidx.compose.foundation.gestures.detectTapGestures
  16  import androidx.compose.foundation.layout.*
  17  import androidx.compose.foundation.shape.CircleShape
  18  import androidx.compose.foundation.shape.RoundedCornerShape
  19  import androidx.compose.material.icons.Icons
  20  import androidx.compose.material.icons.filled.*
  21  import androidx.compose.material3.*
  22  import androidx.compose.runtime.*
  23  import androidx.compose.ui.Alignment
  24  import androidx.compose.ui.Modifier
  25  import androidx.compose.ui.draw.clip
  26  import androidx.compose.ui.geometry.Offset
  27  import androidx.compose.ui.geometry.Size
  28  import androidx.compose.ui.graphics.Color
  29  import androidx.compose.ui.graphics.Path
  30  import androidx.compose.ui.graphics.PathEffect
  31  import androidx.compose.ui.graphics.drawscope.Stroke
  32  import androidx.compose.ui.graphics.lerp
  33  import androidx.compose.ui.graphics.nativeCanvas
  34  import androidx.compose.ui.graphics.toArgb
  35  import androidx.compose.ui.input.pointer.pointerInput
  36  import androidx.compose.ui.platform.LocalConfiguration
  37  import androidx.compose.ui.platform.LocalContext
  38  import androidx.compose.ui.platform.testTag
  39  import androidx.compose.ui.text.TextStyle
  40  import androidx.compose.ui.text.font.FontFamily
  41  import androidx.compose.ui.text.font.FontWeight
  42  import androidx.compose.ui.text.style.TextAlign
  43  import androidx.compose.ui.unit.dp
  44  import androidx.compose.ui.unit.sp
  45  import com.example.model.HotspotCell
  46  import com.example.model.MapObservation
  47  import com.example.model.Observation
  48  import com.example.model.Species
  49  import com.example.ui.theme.HeatHigh
  50  import com.example.ui.theme.HeatLow
  51  import com.example.ui.theme.MapPinExcellent
  52  import com.example.ui.theme.MapPinPossible
  53  import com.example.ui.theme.MapPinPromising
  54  import com.example.ui.theme.MapPinUnlikely
  55  import com.example.ui.theme.MapPinVeryGood
  56  import com.example.ui.theme.TierExcellent
  57  import com.example.ui.theme.TierPossible
  58  import com.example.ui.theme.TierPromising
  59  import com.example.ui.theme.TierUnlikely
  60  import com.example.ui.theme.TierVeryGood
  61  import com.example.ui.viewmodel.DeepSearchState
  62  import com.example.ui.viewmodel.FungiViewModel
  63  import com.example.ui.viewmodel.HotspotState
  64  import com.example.util.MycoMath
  65  import com.google.android.gms.location.LocationServices
  66  import java.util.*
  67  import kotlinx.coroutines.launch
  68  import kotlinx.coroutines.withContext
  69  import kotlinx.coroutines.Dispatchers
  70  import android.os.Handler
  71  import android.os.Looper
  72  import kotlin.math.abs
  73  import kotlin.math.cos
  74  import kotlin.math.sin
  75  import kotlin.math.ceil
  76  import org.osmdroid.config.Configuration
  77  import org.osmdroid.events.MapListener
  78  import org.osmdroid.events.ScrollEvent
  79  import org.osmdroid.events.ZoomEvent
  80  import org.osmdroid.util.GeoPoint
  81  import org.osmdroid.views.MapView
  82  import org.osmdroid.views.overlay.Polygon
  83  import org.osmdroid.views.overlay.Marker
  84  import org.osmdroid.views.overlay.TilesOverlay
  85  import androidx.compose.ui.viewinterop.AndroidView
  86  import org.osmdroid.tileprovider.tilesource.ITileSource
  87  import org.osmdroid.tileprovider.tilesource.TileSourceFactory
  88  
  89  private data class PresetLoc(val name: String, val lat: Double, val lng: Double)
  90  
  91  @OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
  92  @Composable
  93  fun MapScreen(
  94      viewModel: FungiViewModel
  95  ) {
  96      val context = LocalContext.current
  97      val coroutineScope = rememberCoroutineScope()
  98  
  99      val speciesList by viewModel.speciesList.collectAsState()
 100      val activeHotspotSpecies by viewModel.selectedSpeciesForHotspot.collectAsState()
 101      val mapCenter by viewModel.mapCenter.collectAsState()
 102      val searchRadiusKm by viewModel.searchRadiusKm.collectAsState()
 103      val mapTheme by viewModel.mapTheme.collectAsState()
 104      val measureUnits by viewModel.measureUnits.collectAsState()
 105      val isImperial = measureUnits == "Imperial"
 106      val allSpeciesMode by viewModel.isAllSpeciesMode.collectAsState()
 107  
 108      val hotspotState by viewModel.hotspotState.collectAsState()
 109      val deepSearchState by viewModel.deepSearchState.collectAsState()
 110      val pins by viewModel.observationPins.collectAsState()
 111      val allFungiPins by viewModel.allFungiPins.collectAsState()
 112      val showAllSightings by viewModel.showAllSightings.collectAsState()
 113      val weatherSummary by viewModel.weatherSummary.collectAsState()
 114      val isRunning by viewModel.isRecomputationsRunning.collectAsState()
 115  
 116      var showSpeciesDropdown by remember { mutableStateOf(false) }
 117      var showPresets by remember { mutableStateOf(false) }
 118      var selectedHotspotCell by remember { mutableStateOf<HotspotCell?>(null) }
 119      var isFullscreen by remember { mutableStateOf(false) }
 120      var currentBottomTab by remember { mutableStateOf(0) } // 0 = Parameters, 1 = Hotspots Registry
 121  
 122      val hotspotsList = remember(hotspotState) {
 123          if (hotspotState is HotspotState.Success) {
 124              val cells = (hotspotState as HotspotState.Success).cells
 125              val promising = cells.filter { it.tier != "Unlikely" }.sortedByDescending { it.score }
 126              if (promising.isNotEmpty()) {
 127                  promising
 128              } else {
 129                  // Nothing crossed the absolute "Possible" line (sparse evidence /
 130                  // off-season) — still surface the strongest cells relative to this
 131                  // grid so the list shows the best-available spots, honestly tiered,
 132                  // rather than an empty "nothing here".
 133                  val gridMax = cells.maxOfOrNull { it.score } ?: 0.0
 134                  val floor = maxOf(0.10, gridMax * 0.6)
 135                  cells.filter { it.score >= floor }.sortedByDescending { it.score }.take(12)
 136              }
 137          } else {
 138              emptyList()
 139          }
 140      }
 141  
 142      // Cells drawn on the map: the broad overview grid, with any Deep-Search fine
 143      // sub-grid overlaid on top (each polygon is sized from its own cellSizeMeters).
 144      val overviewCells = if (hotspotState is HotspotState.Success)
 145          (hotspotState as HotspotState.Success).cells else emptyList()
 146      val deepCells = (deepSearchState as? DeepSearchState.Success)?.cells ?: emptyList()
 147      val displayedCells = remember(overviewCells, deepCells) { overviewCells + deepCells }
 148  
 149      // Top spots for the numbered map pins: from the active Deep-Search grid if
 150      // drilled in, else the overview. Best first. Prefer genuinely Promising+ spots
 151      // (>=0.40), but never go empty when the area is modest — fall back to the
 152      // strongest cells relative to this grid so foragers always get ranked "best
 153      // near you" pins instead of a bare map.
 154      val rankedPins = remember(overviewCells, deepCells) {
 155          val cells = deepCells.ifEmpty { overviewCells }
 156          val gridMax = cells.maxOfOrNull { it.score } ?: 0.0
 157          val pinFloor = minOf(0.40, maxOf(0.12, gridMax * 0.6))
 158          val above = cells.filter { it.score >= pinFloor }.sortedByDescending { it.score }
 159          // Never leave a populated grid pinless: if even the relative floor admits
 160          // nothing (a uniformly weak area), still surface the strongest few cells so
 161          // foragers always get ranked "best near you" markers, honestly tiered.
 162          val ranked = above.ifEmpty { cells.sortedByDescending { it.score }.take(3) }
 163          ranked.take(8)
 164      }
 165      // One-shot camera focus (Deep Search zoom / "centre here").
 166      var focusTarget by remember { mutableStateOf<MapFocus?>(null) }
 167      // When a Deep Search completes, zoom into that square so the fine grid is legible.
 168      LaunchedEffect(deepSearchState) {
 169          (deepSearchState as? DeepSearchState.Success)?.let { ds ->
 170              focusTarget = MapFocus(ds.parent.lat, ds.parent.lng, 16.5, System.nanoTime())
 171          }
 172      }
 173  
 174      // Navigation and coordinates editing states
 175      var manualLatText by remember { mutableStateOf(String.format(Locale.US, "%.5f", mapCenter.first)) }
 176      var manualLngText by remember { mutableStateOf(String.format(Locale.US, "%.5f", mapCenter.second)) }
 177  
 178      // Resolve current locality description in background thread via Geocoder
 179      var currentLocalName by remember { mutableStateOf("Resolving local site...") }
 180  
 181      // Geocoder lookup whenever map center shifts
 182      LaunchedEffect(mapCenter) {
 183          manualLatText = String.format(Locale.US, "%.5f", mapCenter.first)
 184          manualLngText = String.format(Locale.US, "%.5f", mapCenter.second)
 185          
 186          currentLocalName = "Reading coordinates..."
 187          // Reverse-geocode via the repository (Google when keyed, else device).
 188          viewModel.reverseGeocode(mapCenter.first, mapCenter.second) { name ->
 189              currentLocalName = name
 190          }
 191      }
 192  
 193      // Geocode Search execution — geocoding (Google when a key is configured,
 194      // else the device geocoder) is handled in the ViewModel/repository; here
 195      // we just surface the result to the user.
 196      fun searchLocation(query: String) {
 197          if (query.isBlank()) return
 198          viewModel.searchLocation(query) { label ->
 199              if (label != null) {
 200                  Toast.makeText(context, "Centered on: $label", Toast.LENGTH_SHORT).show()
 201              } else {
 202                  Toast.makeText(context, "Location not found. Try coordinates directly.", Toast.LENGTH_SHORT).show()
 203              }
 204          }
 205      }
 206  
 207      // Location Permission request launcher
 208      val locationPermissionLauncher = rememberLauncherForActivityResult(
 209          contract = ActivityResultContracts.RequestMultiplePermissions()
 210      ) { permissions ->
 211          val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
 212          val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
 213          if (fineGranted || coarseGranted) {
 214              try {
 215                  val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
 216                  fusedLocationClient.lastLocation.addOnSuccessListener { location ->
 217                      if (location != null) {
 218                          viewModel.mapCenter.value = Pair(location.latitude, location.longitude)
 219                          viewModel.computeHotspots()
 220                          Toast.makeText(context, "GPS synchronized: Centered on your actual local area!", Toast.LENGTH_SHORT).show()
 221                      } else {
 222                          Toast.makeText(context, "Could not acquire GPS position. Ensure Location is enabled on device.", Toast.LENGTH_LONG).show()
 223                      }
 224                  }
 225              } catch (se: SecurityException) {
 226                  Toast.makeText(context, "Security exception fetching GPS location.", Toast.LENGTH_SHORT).show()
 227              }
 228          } else {
 229              Toast.makeText(context, "Location permission is required to center on your physical area.", Toast.LENGTH_LONG).show()
 230          }
 231      }
 232  
 233      // Auto-calculate hotspots whenever parameters change
 234      LaunchedEffect(activeHotspotSpecies, mapCenter, searchRadiusKm, speciesList) {
 235          if (activeHotspotSpecies != null) {
 236              viewModel.computeHotspots()
 237          } else if (speciesList.isNotEmpty()) {
 238              viewModel.selectedSpeciesForHotspot.value = speciesList.first()
 239          }
 240      }
 241  
 242      Scaffold { paddingValues ->
 243          Box(
 244              modifier = Modifier
 245                  .fillMaxSize()
 246                  .padding(paddingValues)
 247                  .background(MaterialTheme.colorScheme.background)
 248          ) {
 249              
 250              // 1. Full-Height Interactive Canvas Topography Map View
 251              Column(modifier = Modifier.fillMaxSize()) {
 252                  Box(
 253                      modifier = Modifier
 254                          .fillMaxWidth()
 255                          .weight(1f)
 256                          .background(Color(0xFF0B0D0B)) // Ultra dark forest obsidian green
 257                  ) {
 258                      OSMMapView(
 259                          centerX = mapCenter.first,
 260                          centerY = mapCenter.second,
 261                          radiusKm = searchRadiusKm,
 262                          mapTheme = mapTheme,
 263                          heatmapCells = displayedCells,
 264                          rankedPins = rankedPins,
 265                          focusTarget = focusTarget,
 266                          // In aggregate mode the cells already represent combined
 267                          // evidence; pinning one species' records on top is noisy
 268                          // and misleading, so skip them.
 269                          observationPins = if (allSpeciesMode) emptyList() else pins,
 270                          // Every nearby fungal sighting (any species) from
 271                          // iNaturalist, rendered as labelled pins independent of
 272                          // the selected species / mode.
 273                          allFungiPins = if (showAllSightings) allFungiPins else emptyList(),
 274                          selectedSpeciesName = if (allSpeciesMode) null else activeHotspotSpecies?.scientificName,
 275                          onCellSelected = { clickedCell ->
 276                              selectedHotspotCell = clickedCell
 277                          },
 278                          // The search centre follows the map: when the user stops
 279                          // panning/zooming, recentre on the new viewport centre
 280                          // (debounced in OSMMapView). Guard against tiny deltas so
 281                          // we don't recompute when the map barely moved.
 282                          onCameraIdle = { newLat, newLng ->
 283                              // While drilled into a Deep-Search square, panning/zoom must
 284                              // NOT relocate the search or recompute — that would wipe the
 285                              // drill-down. Exit via "Back to overview" first.
 286                              if (deepSearchState !is DeepSearchState.Success) {
 287                                  val (curLat, curLng) = viewModel.mapCenter.value
 288                                  if (abs(curLat - newLat) > 1e-4 || abs(curLng - newLng) > 1e-4) {
 289                                      viewModel.mapCenter.value = Pair(newLat, newLng)
 290                                      selectedHotspotCell = null
 291                                  }
 292                              }
 293                          }
 294                      )
 295  
 296                      // Fixed crosshair marking the search centre (the map viewport
 297                      // centre). Drawn in Compose so it never moves and is never
 298                      // confused with a sighting pin. Non-interactive — taps pass
 299                      // through to the map / markers below.
 300                      Icon(
 301                          imageVector = Icons.Default.GpsFixed,
 302                          contentDescription = "Search centre",
 303                          tint = Color(0xFF2196F3),
 304                          modifier = Modifier
 305                              .align(Alignment.Center)
 306                              .size(30.dp)
 307                      )
 308  
 309                      // Deep Search status chip (top-centre): a spinner while the fine
 310                      // sub-grid computes, then a "Back to overview" affordance once it
 311                      // is overlaid so the broad grid is always one tap away.
 312                      when (val ds = deepSearchState) {
 313                          is DeepSearchState.Loading -> Surface(
 314                              color = MaterialTheme.colorScheme.surfaceColorAtElevation(12.dp),
 315                              shape = RoundedCornerShape(20.dp),
 316                              modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
 317                          ) {
 318                              Row(
 319                                  verticalAlignment = Alignment.CenterVertically,
 320                                  modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
 321                              ) {
 322                                  CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
 323                                  Spacer(Modifier.width(8.dp))
 324                                  Text("Deep searching this square…", fontSize = 12.sp)
 325                              }
 326                          }
 327                          is DeepSearchState.Success -> Surface(
 328                              color = MaterialTheme.colorScheme.surfaceColorAtElevation(12.dp),
 329                              shape = RoundedCornerShape(20.dp),
 330                              modifier = Modifier
 331                                  .align(Alignment.TopCenter)
 332                                  .padding(top = 12.dp)
 333                                  .clickable {
 334                                      viewModel.clearDeepSearch()
 335                                      // Zoom back out to the overview.
 336                                      focusTarget = MapFocus(mapCenter.first, mapCenter.second, 13.0, System.nanoTime())
 337                                  }
 338                          ) {
 339                              Row(
 340                                  verticalAlignment = Alignment.CenterVertically,
 341                                  modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
 342                              ) {
 343                                  Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
 344                                  Spacer(Modifier.width(6.dp))
 345                                  Text(
 346                                      "Fine grid: ${ds.cells.count { it.tier != "Unlikely" }} cells • Back to overview",
 347                                      fontSize = 12.sp, fontWeight = FontWeight.Bold
 348                                  )
 349                              }
 350                          }
 351                          is DeepSearchState.Error -> Surface(
 352                              color = MaterialTheme.colorScheme.errorContainer,
 353                              shape = RoundedCornerShape(20.dp),
 354                              modifier = Modifier
 355                                  .align(Alignment.TopCenter)
 356                                  .padding(top = 12.dp)
 357                                  .clickable { viewModel.clearDeepSearch() }
 358                          ) {
 359                              Text(
 360                                  "Deep search failed — tap to dismiss",
 361                                  fontSize = 12.sp,
 362                                  color = MaterialTheme.colorScheme.onErrorContainer,
 363                                  modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
 364                              )
 365                          }
 366                          DeepSearchState.Idle -> {}
 367                      }
 368  
 369                      // Compass Indicator & Scale Overlay (hidden in fullscreen)
 370                      androidx.compose.animation.AnimatedVisibility(
 371                          visible = !isFullscreen,
 372                          enter = fadeIn(),
 373                          exit = fadeOut(),
 374                          modifier = Modifier.align(Alignment.TopStart)
 375                      ) {
 376                          Column(
 377                              modifier = Modifier
 378                                  .padding(top = 130.dp, start = 16.dp)
 379                                  .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
 380                                  .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
 381                                  .padding(10.dp),
 382                              horizontalAlignment = Alignment.CenterHorizontally
 383                          ) {
 384                              Text(
 385                                  text = "N 🧭",
 386                                  color = Color(0xFF66BB6A),
 387                                  fontWeight = FontWeight.Bold,
 388                                  fontSize = 13.sp,
 389                                  fontFamily = FontFamily.Monospace
 390                              )
 391                              Spacer(modifier = Modifier.height(4.dp))
 392                              Text(
 393                                  text = "500 m grid",
 394                                  color = Color.LightGray,
 395                                  fontSize = 9.sp,
 396                                  fontFamily = FontFamily.Monospace
 397                              )
 398                          }
 399                      }
 400  
 401                      // Legend — heatmap ramp + numbered top-spot pins. Anchored
 402                      // bottom-start (clear of the top search card and the bottom-end
 403                      // FABs) and hidden while a hotspot card is open (the card itself
 404                      // shows the tier colour) so the two never overlap — no fragile
 405                      // hard-coded offsets.
 406                      androidx.compose.animation.AnimatedVisibility(
 407                          visible = !isFullscreen && selectedHotspotCell == null,
 408                          enter = fadeIn(),
 409                          exit = fadeOut(),
 410                          modifier = Modifier.align(Alignment.BottomStart)
 411                      ) {
 412                          // When nothing in view reaches the absolute "Promising" line,
 413                          // the warm shading is purely relative — say so, so a modest
 414                          // area's colours can't be mistaken for strong absolute odds.
 415                          val gridBest = displayedCells.maxOfOrNull { it.score } ?: 0.0
 416                          Column(
 417                              modifier = Modifier
 418                                  .padding(start = 16.dp, bottom = 16.dp)
 419                                  .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
 420                                  .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
 421                                  .padding(10.dp),
 422                              horizontalAlignment = Alignment.Start,
 423                              verticalArrangement = Arrangement.spacedBy(4.dp)
 424                          ) {
 425                              Text(
 426                                  text = "Likelihood",
 427                                  color = Color.White,
 428                                  fontWeight = FontWeight.Bold,
 429                                  fontSize = 10.sp,
 430                                  modifier = Modifier.padding(bottom = 2.dp)
 431                              )
 432                              // Heatmap ramp — warm intensity (amber → red), matching the
 433                              // on-map surface so it stays legible over green/topo tiles.
 434                              Row(verticalAlignment = Alignment.CenterVertically) {
 435                                  Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(lerp(HeatLow, HeatHigh, 0f)))
 436                                  Box(modifier = Modifier.size(10.dp).background(lerp(HeatLow, HeatHigh, 0.33f)))
 437                                  Box(modifier = Modifier.size(10.dp).background(lerp(HeatLow, HeatHigh, 0.66f)))
 438                                  Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(lerp(HeatLow, HeatHigh, 1f)))
 439                                  Spacer(modifier = Modifier.width(6.dp))
 440                                  Text("Low → High", color = Color.White, fontSize = 9.sp)
 441                              }
 442                              Row(verticalAlignment = Alignment.CenterVertically) {
 443                                  Box(
 444                                      modifier = Modifier.size(12.dp).clip(CircleShape).background(MapPinExcellent),
 445                                      contentAlignment = Alignment.Center
 446                                  ) { Text("1", color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold) }
 447                                  Spacer(modifier = Modifier.width(6.dp))
 448                                  Text("Numbered = best spots", color = Color.White, fontSize = 9.sp)
 449                              }
 450                              Text(
 451                                  "Built-up / bare left unshaded",
 452                                  color = Color.White.copy(alpha = 0.6f),
 453                                  fontSize = 8.sp
 454                              )
 455                              if (gridBest < 0.40) {
 456                                  Text(
 457                                      "Best nearby is modest — shading is relative",
 458                                      color = Color(0xFFE6B24C).copy(alpha = 0.9f),
 459                                      fontSize = 8.sp
 460                                  )
 461                              }
 462                          }
 463                      }
 464  
 465                      // Fullscreen & Layer toggle buttons
 466                      Column(
 467                          modifier = Modifier
 468                              .align(Alignment.BottomEnd)
 469                              .padding(bottom = 16.dp, end = 16.dp),
 470                          verticalArrangement = Arrangement.spacedBy(8.dp)
 471                      ) {
 472                          // Fullscreen toggle
 473                          FloatingActionButton(
 474                              onClick = { isFullscreen = !isFullscreen },
 475                              containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
 476                              contentColor = MaterialTheme.colorScheme.primary,
 477                              modifier = Modifier.size(40.dp)
 478                          ) {
 479                              Icon(
 480                                  imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
 481                                  contentDescription = "Toggle fullscreen",
 482                                  modifier = Modifier.size(20.dp)
 483                              )
 484                          }
 485  
 486                          // Satellite view toggle
 487                          FloatingActionButton(
 488                              onClick = {
 489                                  val currentTheme = mapTheme
 490                                  if (currentTheme == "Satellite") {
 491                                      viewModel.setMapTheme("Topographic")
 492                                  } else {
 493                                      viewModel.setMapTheme("Satellite")
 494                                  }
 495                              },
 496                              containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
 497                              contentColor = if (mapTheme == "Satellite") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
 498                              modifier = Modifier.size(40.dp)
 499                          ) {
 500                              Icon(
 501                                  imageVector = Icons.Default.Satellite,
 502                                  contentDescription = "Toggle satellite view",
 503                                  modifier = Modifier.size(20.dp)
 504                              )
 505                          }
 506                      }
 507  
 508                      // Dynamic Floating Geolocation Search & presets Column (hidden in fullscreen)
 509                      androidx.compose.animation.AnimatedVisibility(
 510                          visible = !isFullscreen,
 511                          enter = expandVertically() + fadeIn(),
 512                          exit = shrinkVertically() + fadeOut(),
 513                          modifier = Modifier.align(Alignment.TopCenter)
 514                      ) {
 515                      Column(
 516                          modifier = Modifier
 517                              .fillMaxWidth()
 518                              .padding(12.dp),
 519                          verticalArrangement = Arrangement.spacedBy(8.dp)
 520                      ) {
 521                          // A. Search Bar Input Card
 522                          Card(
 523                              shape = RoundedCornerShape(12.dp),
 524                              colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
 525                              elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
 526                              modifier = Modifier.fillMaxWidth()
 527                          ) {
 528                              Column(modifier = Modifier.padding(12.dp)) {
 529                                  Row(
 530                                      verticalAlignment = Alignment.CenterVertically,
 531                                      modifier = Modifier.fillMaxWidth()
 532                                  ) {
 533                                      Icon(
 534                                          imageVector = Icons.Default.Place,
 535                                          contentDescription = null,
 536                                          tint = MaterialTheme.colorScheme.primary,
 537                                          modifier = Modifier.size(16.dp)
 538                                      )
 539                                      Spacer(modifier = Modifier.width(6.dp))
 540                                      Text(
 541                                          text = "Area:",
 542                                          style = MaterialTheme.typography.labelSmall,
 543                                          fontFamily = FontFamily.Monospace,
 544                                          fontWeight = FontWeight.Bold,
 545                                          color = MaterialTheme.colorScheme.primary
 546                                      )
 547                                      Spacer(modifier = Modifier.width(6.dp))
 548                                      Text(
 549                                          text = currentLocalName,
 550                                          style = MaterialTheme.typography.labelSmall,
 551                                          fontFamily = FontFamily.Monospace,
 552                                          fontWeight = FontWeight.ExtraBold,
 553                                          color = Color.White,
 554                                          maxLines = 1,
 555                                          modifier = Modifier.weight(1f)
 556                                      )
 557                                  }
 558  
 559                                  Spacer(modifier = Modifier.height(8.dp))
 560  
 561                                  var searchQueryText by remember { mutableStateOf("") }
 562                                  Row(
 563                                      modifier = Modifier.fillMaxWidth(),
 564                                      verticalAlignment = Alignment.CenterVertically,
 565                                      horizontalArrangement = Arrangement.spacedBy(8.dp)
 566                                  ) {
 567                                      OutlinedTextField(
 568                                          value = searchQueryText,
 569                                          onValueChange = { searchQueryText = it },
 570                                          placeholder = { Text("Search city, forest, national park...", fontSize = 12.sp) },
 571                                          textStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Color.White),
 572                                          singleLine = true,
 573                                          modifier = Modifier
 574                                              .weight(1f)
 575                                              .height(48.dp)
 576                                              .testTag("location_search_input"),
 577                                          shape = RoundedCornerShape(8.dp),
 578                                          colors = OutlinedTextFieldDefaults.colors(
 579                                              focusedBorderColor = MaterialTheme.colorScheme.primary,
 580                                              unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
 581                                              focusedPlaceholderColor = Color.Gray,
 582                                              unfocusedPlaceholderColor = Color.Gray
 583                                          ),
 584                                          trailingIcon = {
 585                                              if (searchQueryText.isNotEmpty()) {
 586                                                  IconButton(onClick = { searchQueryText = "" }) {
 587                                                      Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
 588                                                  }
 589                                              }
 590                                          }
 591                                      )
 592  
 593                                      // Run geocoder button
 594                                      IconButton(
 595                                          onClick = {
 596                                              if (searchQueryText.isNotBlank()) {
 597                                                  searchLocation(searchQueryText)
 598                                              } else {
 599                                                  Toast.makeText(context, "Please write a locality name!", Toast.LENGTH_SHORT).show()
 600                                              }
 601                                          },
 602                                          modifier = Modifier
 603                                              .size(48.dp)
 604                                              .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
 605                                              .testTag("search_submit_btn")
 606                                      ) {
 607                                          Icon(imageVector = Icons.Default.Search, contentDescription = "Search location", tint = MaterialTheme.colorScheme.onPrimary)
 608                                      }
 609  
 610                                      // Locate Current GPS GPS Button
 611                                      IconButton(
 612                                          onClick = {
 613                                              locationPermissionLauncher.launch(
 614                                                  arrayOf(
 615                                                      Manifest.permission.ACCESS_FINE_LOCATION,
 616                                                      Manifest.permission.ACCESS_COARSE_LOCATION
 617                                                  )
 618                                              )
 619                                          },
 620                                          modifier = Modifier
 621                                              .size(48.dp)
 622                                              .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(8.dp))
 623                                              .testTag("locate_me_btn")
 624                                      ) {
 625                                          Icon(imageVector = Icons.Default.MyLocation, contentDescription = "Use My Location", tint = MaterialTheme.colorScheme.onSecondary)
 626                                      }
 627                                  }
 628                              }
 629                          }
 630  
 631                          // B. Quick presets — collapsed by default to keep the
 632                          // card compact so it doesn't block the map.
 633                          Spacer(modifier = Modifier.height(6.dp))
 634                          Text(
 635                              text = if (showPresets) "▴ Hide quick spots" else "▾ Quick spots (Victoria)",
 636                              style = MaterialTheme.typography.labelSmall,
 637                              fontFamily = FontFamily.Monospace,
 638                              color = MaterialTheme.colorScheme.primary,
 639                              modifier = Modifier
 640                                  .clickable { showPresets = !showPresets }
 641                                  .padding(vertical = 2.dp)
 642                                  .testTag("presets_toggle")
 643                          )
 644                          androidx.compose.animation.AnimatedVisibility(visible = showPresets) {
 645                          LazyRow(
 646                              modifier = Modifier.fillMaxWidth(),
 647                              horizontalArrangement = Arrangement.spacedBy(6.dp),
 648                              contentPadding = PaddingValues(horizontal = 4.dp)
 649                          ) {
 650                              val presetSites = listOf(
 651                                  PresetLoc("🍂 Dandenong Ranges", -37.8386, 145.3524),
 652                                  PresetLoc("🌲 Sherbrooke Forest", -37.8896, 145.3580),
 653                                  PresetLoc("🌿 Otway Ranges", -38.6500, 143.5500),
 654                                  PresetLoc("🌲 Toolangi Forest", -37.5300, 145.4700),
 655                                  PresetLoc("🏞️ Kinglake NP", -37.5200, 145.3500),
 656                                  PresetLoc("🏔️ Wombat Forest", -37.4500, 144.3000),
 657                                  PresetLoc("🍄 Wilsons Prom", -39.0300, 146.3200)
 658                              )
 659                              items(presetSites) { preset ->
 660                                  AssistChip(
 661                                      onClick = {
 662                                          viewModel.mapCenter.value = Pair(preset.lat, preset.lng)
 663                                          viewModel.computeHotspots()
 664                                          Toast.makeText(context, "Map centred on ${preset.name}", Toast.LENGTH_SHORT).show()
 665                                      },
 666                                      label = { Text(preset.name, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
 667                                      colors = AssistChipDefaults.assistChipColors(
 668                                          containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(12.dp).copy(alpha = 0.95f),
 669                                          labelColor = Color.White
 670                                      ),
 671                                      shape = RoundedCornerShape(16.dp),
 672                                      border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
 673                                      modifier = Modifier.testTag("preset_${preset.name.replace(" ", "_")}")
 674                                  )
 675                              }
 676                          }
 677                          }
 678                      }
 679                      } // end AnimatedVisibility for search bar
 680  
 681                      // Loading Spinner Overlay
 682                      if (isRunning) {
 683                          Box(
 684                              modifier = Modifier
 685                                  .fillMaxSize()
 686                                  .background(Color.Black.copy(alpha = 0.3f)),
 687                              contentAlignment = Alignment.Center
 688                          ) {
 689                              Card(
 690                                  colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
 691                              ) {
 692                                  Row(
 693                                      modifier = Modifier.padding(16.dp),
 694                                      verticalAlignment = Alignment.CenterVertically
 695                                  ) {
 696                                      CircularProgressIndicator(modifier = Modifier.size(24.dp))
 697                                      Spacer(modifier = Modifier.width(12.dp))
 698                                      Text(
 699                                          text = "Combining observations and weather…",
 700                                          style = MaterialTheme.typography.bodySmall,
 701                                          fontFamily = FontFamily.Monospace
 702                                      )
 703                                  }
 704                              }
 705                          }
 706                      }
 707  
 708                      // Error state — surface failures instead of a silent blank map
 709                      val errState = hotspotState as? HotspotState.Error
 710                      if (!isRunning && errState != null) {
 711                          Box(
 712                              modifier = Modifier
 713                                  .align(Alignment.TopCenter)
 714                                  .padding(top = 12.dp, start = 12.dp, end = 12.dp)
 715                          ) {
 716                              Card(
 717                                  colors = CardDefaults.cardColors(
 718                                      containerColor = MaterialTheme.colorScheme.errorContainer
 719                                  )
 720                              ) {
 721                                  Row(
 722                                      modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
 723                                      verticalAlignment = Alignment.CenterVertically
 724                                  ) {
 725                                      Icon(
 726                                          imageVector = Icons.Default.Warning,
 727                                          contentDescription = null,
 728                                          tint = MaterialTheme.colorScheme.onErrorContainer,
 729                                          modifier = Modifier.size(18.dp)
 730                                      )
 731                                      Spacer(modifier = Modifier.width(10.dp))
 732                                      Text(
 733                                          // Surface the real reason so a failure in the field
 734                                          // is diagnosable, not a silent blank map.
 735                                          text = "Couldn't compute hotspots — ${errState.message}",
 736                                          style = MaterialTheme.typography.bodySmall,
 737                                          color = MaterialTheme.colorScheme.onErrorContainer,
 738                                          modifier = Modifier.weight(1f)
 739                                      )
 740                                      Spacer(modifier = Modifier.width(8.dp))
 741                                      TextButton(onClick = { viewModel.computeHotspots() }) {
 742                                          Text("Retry")
 743                                      }
 744                                  }
 745                              }
 746                          }
 747                      }
 748  
 749                      // Empty-grid state — the computation succeeded but produced no
 750                      // scored cells for this area (e.g. catalogue not ready, or every
 751                      // cell gated out). Without this, the map would just look blank.
 752                      if (!isRunning && hotspotState is HotspotState.Success && displayedCells.isEmpty()) {
 753                          Box(
 754                              modifier = Modifier
 755                                  .align(Alignment.TopCenter)
 756                                  .padding(top = 12.dp, start = 12.dp, end = 12.dp)
 757                          ) {
 758                              Card(
 759                                  colors = CardDefaults.cardColors(
 760                                      containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(12.dp).copy(alpha = 0.95f)
 761                                  )
 762                              ) {
 763                                  Row(
 764                                      modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
 765                                      verticalAlignment = Alignment.CenterVertically
 766                                  ) {
 767                                      Icon(
 768                                          imageVector = Icons.Default.Info,
 769                                          contentDescription = null,
 770                                          tint = MaterialTheme.colorScheme.primary,
 771                                          modifier = Modifier.size(18.dp)
 772                                      )
 773                                      Spacer(modifier = Modifier.width(10.dp))
 774                                      Text(
 775                                          text = "No prediction grid for this spot — try a larger radius or move the map.",
 776                                          style = MaterialTheme.typography.bodySmall,
 777                                          color = Color.White,
 778                                          modifier = Modifier.weight(1f)
 779                                      )
 780                                      Spacer(modifier = Modifier.width(8.dp))
 781                                      TextButton(onClick = { viewModel.computeHotspots() }) {
 782                                          Text("Retry")
 783                                      }
 784                                  }
 785                              }
 786                          }
 787                      }
 788                  }
 789  
 790                  // 2. Control center bottom drawer (Parameters, Slider, Microclimate signal, Details)
 791                  AnimatedVisibility(
 792                      visible = !isFullscreen,
 793                      enter = expandVertically() + fadeIn(),
 794                      exit = shrinkVertically() + fadeOut()
 795                  ) {
 796                  Surface(
 797                      tonalElevation = 6.dp,
 798                      modifier = Modifier
 799                          .fillMaxWidth()
 800                          // Cap the panel so it never dominates the map; scroll if
 801                          // the Parameters content is taller than this.
 802                          .heightIn(max = 300.dp)
 803                  ) {
 804                      Column(
 805                          modifier = Modifier
 806                              .padding(16.dp)
 807                              .verticalScroll(rememberScrollState())
 808                      ) {
 809                          // Section Switcher Tab Row
 810                          TabRow(
 811                              selectedTabIndex = currentBottomTab,
 812                              modifier = Modifier
 813                                  .fillMaxWidth()
 814                                  .padding(bottom = 12.dp),
 815                              containerColor = Color.Transparent,
 816                              divider = {}
 817                          ) {
 818                              Tab(
 819                                  selected = currentBottomTab == 0,
 820                                  onClick = { currentBottomTab = 0 },
 821                                  text = { Text("Parameters", fontWeight = FontWeight.Bold, fontSize = 12.sp) },
 822                                  icon = { Icon(imageVector = Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp)) }
 823                              )
 824                              Tab(
 825                                  selected = currentBottomTab == 1,
 826                                  onClick = { currentBottomTab = 1 },
 827                                  text = { 
 828                                      Row(verticalAlignment = Alignment.CenterVertically) {
 829                                          Text("Hotspot list", fontWeight = FontWeight.Bold, fontSize = 13.sp)
 830                                          val count = hotspotsList.size
 831                                          if (count > 0) {
 832                                              Spacer(modifier = Modifier.width(4.dp))
 833                                              Badge(
 834                                                  containerColor = MaterialTheme.colorScheme.primary,
 835                                                  contentColor = MaterialTheme.colorScheme.onPrimary
 836                                              ) {
 837                                                  Text("$count", style = TextStyle(fontSize = 10.sp))
 838                                              }
 839                                          }
 840                                      }
 841                                  },
 842                                  icon = { Icon(imageVector = Icons.Default.Radar, contentDescription = null, modifier = Modifier.size(16.dp)) }
 843                              )
 844                          }
 845  
 846                          if (currentBottomTab == 0) {
 847                              // Mode toggle: combine all species vs focus one
 848                              Row(
 849                                  modifier = Modifier
 850                                      .fillMaxWidth()
 851                                      .padding(bottom = 6.dp),
 852                                  verticalAlignment = Alignment.CenterVertically
 853                              ) {
 854                                  Column(modifier = Modifier.weight(1f)) {
 855                                      Text(
 856                                          text = "Combine all species",
 857                                          style = MaterialTheme.typography.bodyMedium,
 858                                          fontWeight = FontWeight.Bold,
 859                                          color = MaterialTheme.colorScheme.onSurface
 860                                      )
 861                                      Text(
 862                                          text = if (allSpeciesMode)
 863                                              "Aggregate evidence across ${speciesList.size} catalogued species"
 864                                          else
 865                                              "Focus on a single species below",
 866                                          style = MaterialTheme.typography.bodySmall,
 867                                          color = MaterialTheme.colorScheme.onSurfaceVariant
 868                                      )
 869                                  }
 870                                  Switch(
 871                                      checked = allSpeciesMode,
 872                                      onCheckedChange = { viewModel.setAllSpeciesMode(it) },
 873                                      modifier = Modifier.testTag("all_species_toggle")
 874                                  )
 875                              }
 876  
 877                              // All-fungi sightings layer toggle
 878                              Row(
 879                                  modifier = Modifier
 880                                      .fillMaxWidth()
 881                                      .padding(bottom = 6.dp),
 882                                  verticalAlignment = Alignment.CenterVertically
 883                              ) {
 884                                  Column(modifier = Modifier.weight(1f)) {
 885                                      Text(
 886                                          text = "Show iNaturalist sightings",
 887                                          style = MaterialTheme.typography.bodyMedium,
 888                                          fontWeight = FontWeight.Bold,
 889                                          color = MaterialTheme.colorScheme.onSurface
 890                                      )
 891                                      Text(
 892                                          text = if (showAllSightings)
 893                                              "${allFungiPins.size} raw records shown as muted dots — tap for details"
 894                                          else
 895                                              "Off — overlay raw iNaturalist records as muted dots",
 896                                          style = MaterialTheme.typography.bodySmall,
 897                                          color = MaterialTheme.colorScheme.onSurfaceVariant
 898                                      )
 899                                  }
 900                                  Switch(
 901                                      checked = showAllSightings,
 902                                      onCheckedChange = { viewModel.setShowAllSightings(it) },
 903                                      modifier = Modifier.testTag("all_sightings_toggle")
 904                                  )
 905                              }
 906  
 907                              // Species selector (only meaningful when not in aggregate mode)
 908                              Row(
 909                                  modifier = Modifier.fillMaxWidth(),
 910                                  verticalAlignment = Alignment.CenterVertically
 911                              ) {
 912                                  Text(
 913                                      text = "Target taxon",
 914                                      style = MaterialTheme.typography.labelSmall,
 915                                      fontWeight = FontWeight.Bold,
 916                                      color = if (allSpeciesMode)
 917                                          MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
 918                                      else
 919                                          MaterialTheme.colorScheme.primary,
 920                                      fontFamily = FontFamily.Monospace,
 921                                      modifier = Modifier.padding(end = 8.dp)
 922                                  )
 923  
 924                                  Box(modifier = Modifier.weight(1f)) {
 925                                      Button(
 926                                          onClick = { if (!allSpeciesMode) showSpeciesDropdown = true },
 927                                          enabled = !allSpeciesMode,
 928                                          colors = ButtonDefaults.buttonColors(
 929                                              containerColor = MaterialTheme.colorScheme.surfaceVariant,
 930                                              contentColor = MaterialTheme.colorScheme.onSurface,
 931                                              disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
 932                                              disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
 933                                          ),
 934                                          modifier = Modifier
 935                                              .fillMaxWidth()
 936                                              .testTag("map_species_selector"),
 937                                          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
 938                                      ) {
 939                                          Text(
 940                                              text = when {
 941                                                  allSpeciesMode -> "All species (combined)"
 942                                                  else -> activeHotspotSpecies?.scientificName ?: "Select target specimen"
 943                                              },
 944                                              fontWeight = FontWeight.Bold,
 945                                              modifier = Modifier.weight(1f),
 946                                              textAlign = TextAlign.Start
 947                                          )
 948                                          Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null)
 949                                      }
 950  
 951                                      DropdownMenu(
 952                                          expanded = showSpeciesDropdown && !allSpeciesMode,
 953                                          onDismissRequest = { showSpeciesDropdown = false }
 954                                      ) {
 955                                          speciesList.forEach { spec ->
 956                                              DropdownMenuItem(
 957                                                  text = { Text("${spec.scientificName} (${spec.commonNames.firstOrNull() ?: ""})") },
 958                                                  onClick = {
 959                                                      viewModel.selectedSpeciesForHotspot.value = spec
 960                                                      showSpeciesDropdown = false
 961                                                      selectedHotspotCell = null
 962                                                  }
 963                                              )
 964                                          }
 965                                      }
 966                                  }
 967                              }
 968  
 969                              Spacer(modifier = Modifier.height(12.dp))
 970  
 971                              // Draw-Radius Slider Row
 972                              Row(
 973                                  modifier = Modifier.fillMaxWidth(),
 974                                  verticalAlignment = Alignment.CenterVertically
 975                              ) {
 976                                  Text(
 977                                      text = "Radius",
 978                                      style = MaterialTheme.typography.labelSmall,
 979                                      fontWeight = FontWeight.Bold,
 980                                      color = MaterialTheme.colorScheme.primary,
 981                                      fontFamily = FontFamily.Monospace,
 982                                      modifier = Modifier.padding(end = 8.dp)
 983                                  )
 984                                  Slider(
 985                                      value = searchRadiusKm.toFloat(),
 986                                      onValueChange = { viewModel.searchRadiusKm.value = it.toDouble() },
 987                                      valueRange = 1f..30f,
 988                                      steps = 29,
 989                                      modifier = Modifier
 990                                          .weight(1f)
 991                                          .testTag("radius_slider")
 992                                  )
 993                                  Text(
 994                                      text = if (isImperial)
 995                                          "${String.format(Locale.US, "%.1f", searchRadiusKm * 0.621371)} mi"
 996                                      else
 997                                          "${searchRadiusKm.toInt()} km",
 998                                      style = MaterialTheme.typography.titleSmall,
 999                                      fontWeight = FontWeight.Bold,
1000                                      fontFamily = FontFamily.Monospace,
1001                                      modifier = Modifier.width(64.dp),
1002                                      textAlign = TextAlign.End
1003                                  )
1004                              }
1005  
1006                              // Weather parameters microclimate summary notification block
1007                              weatherSummary?.let { (rainfall, maxTemp) ->
1008                                  Surface(
1009                                      shape = RoundedCornerShape(12.dp),
1010                                      color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f),
1011                                      border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
1012                                      modifier = Modifier
1013                                          .fillMaxWidth()
1014                                          .padding(vertical = 8.dp)
1015                                  ) {
1016                                      Column(
1017                                          modifier = Modifier.padding(12.dp),
1018                                          verticalArrangement = Arrangement.spacedBy(6.dp)
1019                                      ) {
1020                                          Row(
1021                                              verticalAlignment = Alignment.CenterVertically
1022                                          ) {
1023                                              Icon(
1024                                                  imageVector = Icons.Default.CloudQueue,
1025                                                  contentDescription = null,
1026                                                  tint = MaterialTheme.colorScheme.primary,
1027                                                  modifier = Modifier.size(16.dp)
1028                                              )
1029                                              Spacer(modifier = Modifier.width(6.dp))
1030                                              Text(
1031                                                  text = "Microclimate (past 30 days)",
1032                                                  style = MaterialTheme.typography.labelSmall,
1033                                                  fontFamily = FontFamily.Monospace,
1034                                                  fontWeight = FontWeight.Bold,
1035                                                  color = MaterialTheme.colorScheme.primary,
1036                                                  letterSpacing = 0.5.sp
1037                                              )
1038                                          }
1039                                          val rainfallText = if (isImperial)
1040                                              "${String.format(Locale.US, "%.2f", rainfall / 25.4)} in"
1041                                          else
1042                                              "${String.format(Locale.US, "%.1f", rainfall)}mm"
1043                                          val tempText = if (isImperial)
1044                                              "${String.format(Locale.US, "%.1f", maxTemp * 9.0 / 5.0 + 32.0)}°F"
1045                                          else
1046                                              "${String.format(Locale.US, "%.1f", maxTemp)}°C"
1047                                          Text(
1048                                              text = "⚡ Rainfall (Past 30d): $rainfallText\n🔥 Avg Max Temperature: $tempText",
1049                                              style = MaterialTheme.typography.bodySmall,
1050                                              fontFamily = FontFamily.Monospace,
1051                                              fontWeight = FontWeight.Bold,
1052                                              lineHeight = 16.sp,
1053                                              modifier = Modifier.padding(start = 22.dp)
1054                                          )
1055                                      }
1056                                  }
1057                              }
1058  
1059                              // Quick coordinates override inputs for manual debugging
1060                              Column(
1061                                  modifier = Modifier
1062                                      .fillMaxWidth()
1063                                      .padding(vertical = 8.dp)
1064                              ) {
1065                                  Text(
1066                                      text = "Manual coordinates",
1067                                      style = MaterialTheme.typography.labelSmall,
1068                                      fontWeight = FontWeight.Bold,
1069                                      color = MaterialTheme.colorScheme.primary,
1070                                      fontFamily = FontFamily.Monospace,
1071                                      modifier = Modifier.padding(bottom = 6.dp)
1072                                  )
1073                                  Row(
1074                                      modifier = Modifier.fillMaxWidth(),
1075                                      verticalAlignment = Alignment.CenterVertically,
1076                                      horizontalArrangement = Arrangement.spacedBy(8.dp)
1077                                  ) {
1078                                      OutlinedTextField(
1079                                          value = manualLatText,
1080                                          onValueChange = { manualLatText = it },
1081                                          label = { Text("Latitude", fontSize = 12.sp) },
1082                                          textStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Color.White),
1083                                          modifier = Modifier.weight(1f),
1084                                          singleLine = true,
1085                                          shape = RoundedCornerShape(8.dp)
1086                                      )
1087                                      OutlinedTextField(
1088                                          value = manualLngText,
1089                                          onValueChange = { manualLngText = it },
1090                                          label = { Text("Longitude", fontSize = 12.sp) },
1091                                          textStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Color.White),
1092                                          modifier = Modifier.weight(1f),
1093                                          singleLine = true,
1094                                          shape = RoundedCornerShape(8.dp)
1095                                      )
1096                                      Button(
1097                                          onClick = {
1098                                              val customLat = manualLatText.toDoubleOrNull()
1099                                              val customLng = manualLngText.toDoubleOrNull()
1100                                              if (customLat != null && customLng != null) {
1101                                                  viewModel.mapCenter.value = Pair(customLat, customLng)
1102                                                  viewModel.computeHotspots()
1103                                                  Toast.makeText(context, "Map centred on those coordinates.", Toast.LENGTH_SHORT).show()
1104                                              } else {
1105                                                  Toast.makeText(context, "Invalid coordinates formatting!", Toast.LENGTH_SHORT).show()
1106                                              }
1107                                          },
1108                                          modifier = Modifier
1109                                              .height(52.dp)
1110                                              .width(52.dp),
1111                                          shape = RoundedCornerShape(8.dp),
1112                                          contentPadding = PaddingValues(0.dp),
1113                                          colors = ButtonDefaults.buttonColors(
1114                                              containerColor = MaterialTheme.colorScheme.primary,
1115                                              contentColor = MaterialTheme.colorScheme.onPrimary
1116                                          )
1117                                      ) {
1118                                          Icon(
1119                                              imageVector = Icons.Default.Check, 
1120                                              contentDescription = "Apply custom coordinates",
1121                                              modifier = Modifier.size(20.dp)
1122                                          )
1123                                      }
1124                                  }
1125                              }
1126                          } else {
1127                              // Hotspots list tab
1128                              Text(
1129                                  text = "Promising and possible hotspots nearby",
1130                                  style = MaterialTheme.typography.labelSmall,
1131                                  fontWeight = FontWeight.Bold,
1132                                  color = MaterialTheme.colorScheme.primary,
1133                                  fontFamily = FontFamily.Monospace,
1134                                  modifier = Modifier.padding(bottom = 8.dp)
1135                              )
1136  
1137                              if (hotspotsList.isEmpty()) {
1138                                  Box(
1139                                      modifier = Modifier
1140                                          .fillMaxWidth()
1141                                          .height(180.dp),
1142                                      contentAlignment = Alignment.Center
1143                                  ) {
1144                                      Text(
1145                                          text = "No high probability hotspots in selection area.\n• Try increasing search radius.\n• Center near rivers or woodlands using Presets above!\n• Switch to alternative target species.",
1146                                          style = MaterialTheme.typography.bodySmall,
1147                                          fontFamily = FontFamily.Monospace,
1148                                          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
1149                                          textAlign = TextAlign.Center,
1150                                          lineHeight = 18.sp
1151                                      )
1152                                  }
1153                              } else {
1154                                  LazyColumn(
1155                                      modifier = Modifier
1156                                          .fillMaxWidth()
1157                                          .heightIn(max = 240.dp),
1158                                      verticalArrangement = Arrangement.spacedBy(8.dp),
1159                                      contentPadding = PaddingValues(bottom = 8.dp)
1160                                  ) {
1161                                      items(hotspotsList) { cell ->
1162                                          Card(
1163                                              modifier = Modifier
1164                                                  .fillMaxWidth()
1165                                                  .border(
1166                                                      width = 1.dp,
1167                                                      color = tierColor(cell.tier).copy(alpha = 0.5f),
1168                                                      shape = RoundedCornerShape(8.dp)
1169                                                  ),
1170                                              colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp))
1171                                          ) {
1172                                              Row(
1173                                                  modifier = Modifier.padding(12.dp),
1174                                                  verticalAlignment = Alignment.CenterVertically,
1175                                                  horizontalArrangement = Arrangement.SpaceBetween
1176                                              ) {
1177                                                  Column(modifier = Modifier.weight(1f)) {
1178                                                      Row(verticalAlignment = Alignment.CenterVertically) {
1179                                                          Box(
1180                                                              modifier = Modifier
1181                                                                  .size(10.dp)
1182                                                                  .clip(RoundedCornerShape(2.dp))
1183                                                                  .background(tierColor(cell.tier))
1184                                                          )
1185                                                          Spacer(modifier = Modifier.width(6.dp))
1186                                                          Text(
1187                                                              text = tierLabel(cell.tier),
1188                                                              style = MaterialTheme.typography.labelSmall,
1189                                                              fontWeight = FontWeight.Bold,
1190                                                              color = tierColor(cell.tier)
1191                                                          )
1192                                                      }
1193                                                      Spacer(modifier = Modifier.height(4.dp))
1194                                                      Text(
1195                                                          text = "Score: ${String.format(Locale.getDefault(), "%.1f%%", cell.score * 100.0)}",
1196                                                          style = MaterialTheme.typography.bodyMedium,
1197                                                          fontWeight = FontWeight.Bold,
1198                                                          fontFamily = FontFamily.Monospace
1199                                                      )
1200                                                      Text(
1201                                                          text = "Coords: ${String.format(Locale.getDefault(), "%.4f", cell.lat)}, ${String.format(Locale.getDefault(), "%.4f", cell.lng)}",
1202                                                          style = MaterialTheme.typography.bodySmall,
1203                                                          color = MaterialTheme.colorScheme.onSurfaceVariant,
1204                                                          fontFamily = FontFamily.Monospace
1205                                                      )
1206                                                  }
1207  
1208                                                  Button(
1209                                                      onClick = {
1210                                                          viewModel.mapCenter.value = Pair(cell.lat, cell.lng)
1211                                                          viewModel.computeHotspots()
1212                                                          selectedHotspotCell = cell
1213                                                          Toast.makeText(context, "Centred on this hotspot.", Toast.LENGTH_SHORT).show()
1214                                                      },
1215                                                      shape = RoundedCornerShape(6.dp),
1216                                                      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
1217                                                      modifier = Modifier.height(36.dp)
1218                                                  ) {
1219                                                      Icon(imageVector = Icons.Default.Explore, contentDescription = null, modifier = Modifier.size(14.dp))
1220                                                      Spacer(modifier = Modifier.width(4.dp))
1221                                                      Text("CENTER", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
1222                                                  }
1223                                              }
1224                                          }
1225                                      }
1226                                  }
1227                              }
1228                          }
1229                      }
1230                  }
1231                  } // end AnimatedVisibility
1232              }
1233  
1234              // 3. Floating Bottom Details overlay panel (hidden in fullscreen)
1235              AnimatedVisibility(
1236                  visible = !isFullscreen && selectedHotspotCell != null,
1237                  enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
1238                  exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
1239                  modifier = Modifier.align(Alignment.BottomCenter)
1240              ) {
1241              selectedHotspotCell?.let { cell ->
1242                  // Cap the card to ~60% of the screen and let its content scroll, so a
1243                  // cell with a long "Why this score" breakdown can never overflow or
1244                  // push the action buttons off-screen on a short device.
1245                  val maxSheetHeight = LocalConfiguration.current.screenHeightDp.dp * 0.6f
1246                  Card(
1247                      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(12.dp)),
1248                      modifier = Modifier
1249                          .padding(16.dp)
1250                          .fillMaxWidth()
1251                          .heightIn(max = maxSheetHeight)
1252                          .border(1.5.dp, tierColor(cell.tier), RoundedCornerShape(12.dp))
1253                  ) {
1254                      Column(
1255                          modifier = Modifier
1256                              .padding(16.dp)
1257                              .verticalScroll(rememberScrollState())
1258                      ) {
1259                          Row(
1260                              horizontalArrangement = Arrangement.SpaceBetween,
1261                              verticalAlignment = Alignment.CenterVertically,
1262                              modifier = Modifier.fillMaxWidth()
1263                          ) {
1264                              Row(verticalAlignment = Alignment.CenterVertically) {
1265                                  Box(
1266                                      modifier = Modifier
1267                                          .size(16.dp)
1268                                          .clip(RoundedCornerShape(4.dp))
1269                                          .background(tierColor(cell.tier))
1270                                  )
1271                                  Spacer(modifier = Modifier.width(8.dp))
1272                                  val rank = rankedPins.indexOfFirst { it.lat == cell.lat && it.lng == cell.lng }
1273                                  Text(
1274                                      text = if (rank >= 0) "Spot #${rank + 1} · ${tierLabel(cell.tier)}"
1275                                             else "${tierLabel(cell.tier)} spot",
1276                                      style = MaterialTheme.typography.titleSmall,
1277                                      fontWeight = FontWeight.Bold,
1278                                      color = tierColor(cell.tier)
1279                                  )
1280                              }
1281                              IconButton(onClick = { selectedHotspotCell = null }) {
1282                                  Icon(imageVector = Icons.Default.Close, contentDescription = "Close details")
1283                              }
1284                          }
1285                          
1286                          Row(
1287                              verticalAlignment = Alignment.CenterVertically,
1288                              modifier = Modifier
1289                                  .fillMaxWidth()
1290                                  .padding(vertical = 4.dp)
1291                          ) {
1292                              // Score (how good) — kept colour-neutral so the only
1293                              // colour carrying "how good" is the tier mark/title above.
1294                              Text(
1295                                  text = "Likelihood ${String.format(Locale.getDefault(), "%.0f%%", cell.score * 100.0)}",
1296                                  fontFamily = FontFamily.Monospace,
1297                                  fontWeight = FontWeight.Bold,
1298                                  style = MaterialTheme.typography.bodyMedium,
1299                                  color = MaterialTheme.colorScheme.onSurface
1300                              )
1301                              Spacer(modifier = Modifier.weight(1f))
1302                              // Confidence (how much to TRUST the score) — a distinct
1303                              // dimension from the tier, shown as a neutral 3-pip meter
1304                              // so it never competes with the tier colour.
1305                              val pips = confidencePips(cell.confidence)
1306                              val meterColor = MaterialTheme.colorScheme.onSurfaceVariant
1307                              Row(verticalAlignment = Alignment.CenterVertically) {
1308                                  repeat(3) { i ->
1309                                      Box(
1310                                          modifier = Modifier
1311                                              .padding(end = 3.dp)
1312                                              .size(6.dp)
1313                                              .clip(CircleShape)
1314                                              .background(
1315                                                  if (i < pips) meterColor
1316                                                  else meterColor.copy(alpha = 0.25f)
1317                                              )
1318                                      )
1319                                  }
1320                                  Spacer(modifier = Modifier.width(4.dp))
1321                                  Text(
1322                                      text = "${MycoMath.confidenceLabel(cell.confidence)} confidence",
1323                                      fontSize = 10.sp,
1324                                      fontWeight = FontWeight.Medium,
1325                                      color = meterColor
1326                                  )
1327                              }
1328                          }
1329  
1330                          Text(
1331                              text = "Why this score",
1332                              style = MaterialTheme.typography.labelSmall,
1333                              fontWeight = FontWeight.Bold,
1334                              fontSize = 9.sp,
1335                              fontFamily = FontFamily.Monospace,
1336                              color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
1337                              modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
1338                          )
1339  
1340                          cell.contributingFactors.forEach { factor ->
1341                              Row(
1342                                  modifier = Modifier
1343                                      .fillMaxWidth()
1344                                      .padding(vertical = 2.dp),
1345                                  verticalAlignment = Alignment.Top
1346                              ) {
1347                                  Text(
1348                                      text = "• ",
1349                                      fontFamily = FontFamily.Monospace,
1350                                      fontWeight = FontWeight.Bold,
1351                                      style = MaterialTheme.typography.bodySmall
1352                                  )
1353                                  Text(
1354                                      text = factor,
1355                                      style = MaterialTheme.typography.bodySmall,
1356                                      color = MaterialTheme.colorScheme.onSurfaceVariant,
1357                                      lineHeight = 16.sp,
1358                                      modifier = Modifier.weight(1f)
1359                                  )
1360                              }
1361                          }
1362                          
1363                          Spacer(modifier = Modifier.height(10.dp))
1364                          
1365                          // Deep Search — refine this promising square into a fine
1366                          // (~15 m) sub-grid for pinpoint foraging. Single-species,
1367                          // VeryGood+ only (a finer grid on a weak cell isn't useful).
1368                          if (viewModel.canDeepSearch(cell)) {
1369                              Button(
1370                                  onClick = {
1371                                      viewModel.deepSearch(cell)
1372                                      selectedHotspotCell = null
1373                                      Toast.makeText(context, "Deep searching this square — pinch to zoom in.", Toast.LENGTH_SHORT).show()
1374                                  },
1375                                  shape = RoundedCornerShape(8.dp),
1376                                  colors = ButtonDefaults.buttonColors(containerColor = tierColor(cell.tier)),
1377                                  modifier = Modifier.fillMaxWidth()
1378                              ) {
1379                                  Icon(imageVector = Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
1380                                  Spacer(modifier = Modifier.width(6.dp))
1381                                  Text("Deep Search this square (~15 m)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
1382                              }
1383                              Spacer(modifier = Modifier.height(8.dp))
1384                          }
1385  
1386                          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
1387                              Button(
1388                                  onClick = {
1389                                      viewModel.mapCenter.value = Pair(cell.lat, cell.lng)
1390                                      viewModel.computeHotspots()
1391                                      selectedHotspotCell = null
1392                                      Toast.makeText(context, "Centred on this spot.", Toast.LENGTH_SHORT).show()
1393                                  },
1394                                  shape = RoundedCornerShape(8.dp),
1395                                  modifier = Modifier.weight(1f)
1396                              ) {
1397                                  Icon(imageVector = Icons.Default.FilterVintage, contentDescription = null, modifier = Modifier.size(16.dp))
1398                                  Spacer(modifier = Modifier.width(6.dp))
1399                                  Text("Centre on this spot", fontSize = 12.sp)
1400                              }
1401                          }
1402                      }
1403                  }
1404              }
1405              } // end AnimatedVisibility for hotspot details
1406          }
1407      }
1408  }
1409  
1410  @Composable
1411  fun OSMMapView(
1412      centerX: Double,
1413      centerY: Double,
1414      radiusKm: Double,
1415      mapTheme: String,
1416      heatmapCells: List<HotspotCell>,
1417      rankedPins: List<HotspotCell>,
1418      observationPins: List<Observation>,
1419      allFungiPins: List<MapObservation> = emptyList(),
1420      selectedSpeciesName: String? = null,
1421      focusTarget: MapFocus? = null,
1422      onCellSelected: (HotspotCell) -> Unit,
1423      // Fired (debounced) when the user finishes moving the map — the search
1424      // centre follows the viewport. Tapping is reserved for selecting pins/cells.
1425      onCameraIdle: (Double, Double) -> Unit
1426  ) {
1427      val context = LocalContext.current
1428  
1429      // Initialize standard user agent for OSM required by policy
1430      LaunchedEffect(Unit) {
1431          Configuration.getInstance().userAgentValue = context.packageName
1432      }
1433  
1434      // Remember the last externally-applied search centre and focus request so the
1435      // camera moves ONLY when those actually change — never fighting the user's pan
1436      // or a Deep-Search zoom.
1437      val lastCenter = remember { doubleArrayOf(centerX, centerY) }
1438      val lastFocusKey = remember { longArrayOf(0L) }
1439  
1440      AndroidView(
1441          factory = { ctx ->
1442              val mapView = MapView(ctx)
1443              mapView.setTileSource(tileSourceForTheme(mapTheme))
1444              mapView.setMultiTouchControls(true)
1445              mapView.controller.setZoom(13.0)
1446              mapView.controller.setCenter(GeoPoint(centerX, centerY))
1447  
1448              // Relocate the search centre whenever the user moves the map. The
1449              // MapListener fires continuously while panning/zooming, so debounce
1450              // and only commit the new centre once movement settles (~half a
1451              // second after the last gesture) to avoid recomputing on every frame.
1452              val idleHandler = Handler(Looper.getMainLooper())
1453              var idleRunnable: Runnable? = null
1454              fun scheduleIdle() {
1455                  idleRunnable?.let { idleHandler.removeCallbacks(it) }
1456                  val r = Runnable {
1457                      val c = mapView.mapCenter
1458                      onCameraIdle(c.latitude, c.longitude)
1459                  }
1460                  idleRunnable = r
1461                  idleHandler.postDelayed(r, 550)
1462              }
1463              mapView.addMapListener(object : MapListener {
1464                  override fun onScroll(event: ScrollEvent?): Boolean { scheduleIdle(); return false }
1465                  override fun onZoom(event: ZoomEvent?): Boolean { scheduleIdle(); return false }
1466              })
1467              mapView
1468          },
1469          update = { mapView ->
1470              // Apply the selected map style (and a real dark mode via colour inversion).
1471              mapView.setTileSource(tileSourceForTheme(mapTheme))
1472              mapView.overlayManager.tilesOverlay.setColorFilter(
1473                  if (mapTheme == "Dark") TilesOverlay.INVERT_COLORS else null
1474              )
1475              // Move the camera only when the SEARCH centre prop actually changes
1476              // (search, GPS, presets, manual coords, list) — not when the user pans
1477              // or we Deep-Search zoom (handled by focusTarget below).
1478              if (centerX != lastCenter[0] || centerY != lastCenter[1]) {
1479                  mapView.controller.animateTo(GeoPoint(centerX, centerY))
1480                  lastCenter[0] = centerX; lastCenter[1] = centerY
1481              }
1482              // Deep-Search / "centre here" zoom: animate + zoom once per request.
1483              focusTarget?.let { ft ->
1484                  if (ft.key != lastFocusKey[0]) {
1485                      mapView.controller.animateTo(GeoPoint(ft.lat, ft.lng))
1486                      mapView.controller.setZoom(ft.zoom)
1487                      lastFocusKey[0] = ft.key
1488                  }
1489              }
1490  
1491              // Clear all overlays to redraw. No catch-all tap overlay any more:
1492              // taps now belong to the markers and cells (for info/selection),
1493              // while panning the map relocates the search centre.
1494              mapView.overlays.clear()
1495  
1496              // 1. Draw boundary circle (Radius)
1497              val circlePolygon = Polygon(mapView).apply {
1498                  val geoPoints = mutableListOf<GeoPoint>()
1499                  val steps = 60
1500                  val radiusMeters = radiusKm * 1000.0
1501                  val R = 6378137.0 // Earth radius
1502                  for (i in 0 until steps) {
1503                      val bearing = 2.0 * Math.PI * i.toDouble() / steps
1504                      val lat1 = Math.toRadians(centerX)
1505                      val lon1 = Math.toRadians(centerY)
1506                      val lat2 = Math.asin(Math.sin(lat1) * Math.cos(radiusMeters / R) + Math.cos(lat1) * Math.sin(radiusMeters / R) * Math.cos(bearing))
1507                      val lon2 = lon1 + Math.atan2(Math.sin(bearing) * Math.sin(radiusMeters / R) * Math.cos(lat1), Math.cos(radiusMeters / R) - Math.sin(lat1) * Math.sin(lat2))
1508                      geoPoints.add(GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2)))
1509                  }
1510                  geoPoints.add(geoPoints.first()) // close Path
1511                  points = geoPoints
1512                  fillColor = android.graphics.Color.TRANSPARENT
1513                  strokeColor = android.graphics.Color.argb(120, 76, 175, 80)
1514                  strokeWidth = 2.5f
1515                  infoWindow = null   // never show an (empty) info bubble
1516              }
1517              mapView.overlays.add(circlePolygon)
1518  
1519              // 2. PROBABILITY HEATMAP — every scored cell drawn as an edge-to-edge,
1520              // stroke-less tile on a continuous score→colour ramp (cool = low, warm
1521              // = high) with opacity rising with score. The ramp is ADAPTIVE: it
1522              // scales to the grid's own best score, so the strongest spots always
1523              // read warm — even where evidence is sparse or the species is off
1524              // season and absolute scores are modest. Gated cities/water still drop
1525              // out (low floor), but the map never goes blank when a grid exists.
1526              val cosLngFactor = Math.cos(centerX * Math.PI / 180.0)
1527              val gridMax = heatmapCells.maxOfOrNull { it.score } ?: 0.0
1528              // Normally hide weak/gated cells around ~0.18; when the whole area is
1529              // modest, drop the floor toward the grid so the relative surface paints.
1530              val heatFloor = minOf(0.18, gridMax * 0.45).coerceIn(0.05, 0.18)
1531              val heatTop = maxOf(gridMax, heatFloor + 0.05)
1532              for (cell in heatmapCells) {
1533                  if (cell.score < heatFloor) continue
1534                  val halfH = (cell.cellSizeMeters / 2.0) / 111_000.0
1535                  val halfW = (cell.cellSizeMeters / 2.0) / (111_000.0 * cosLngFactor)
1536                  val tile = Polygon(mapView).apply {
1537                      points = listOf(
1538                          GeoPoint(cell.lat + halfH, cell.lng - halfW),
1539                          GeoPoint(cell.lat + halfH, cell.lng + halfW),
1540                          GeoPoint(cell.lat - halfH, cell.lng + halfW),
1541                          GeoPoint(cell.lat - halfH, cell.lng - halfW)
1542                      )
1543                      fillColor = heatColor(cell.score, heatFloor, heatTop)
1544                      strokeColor = android.graphics.Color.TRANSPARENT
1545                      strokeWidth = 0f
1546                      infoWindow = null
1547                  }
1548                  mapView.overlays.add(tile)
1549              }
1550  
1551              // 3. RANKED SPOT PINS — the best spots as numbered discs (① = best).
1552              // Tap routes straight to the Compose detail card; no osmdroid bubble.
1553              rankedPins.forEachIndexed { i, cell ->
1554                  val marker = Marker(mapView).apply {
1555                      position = GeoPoint(cell.lat, cell.lng)
1556                      setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
1557                      icon = numberedPinDrawable(context, i + 1, mapPinColorInt(cell.tier))
1558                      infoWindow = null
1559                      setOnMarkerClickListener { _, _ -> onCellSelected(cell); true }
1560                  }
1561                  mapView.overlays.add(marker)
1562              }
1563  
1564              val dateFmt = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
1565  
1566              // 4. Sighting markers — no info-window (so no empty bubbles); a tap
1567              // shows a quick toast with the species + date instead.
1568              for (pin in observationPins) {
1569                  val label = selectedSpeciesName ?: "Sighting"
1570                  val whenStr = if (pin.observedAt > 0) " · ${dateFmt.format(java.util.Date(pin.observedAt))}" else ""
1571                  val marker = Marker(mapView).apply {
1572                      position = GeoPoint(pin.lat, pin.lng)
1573                      setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
1574                      infoWindow = null
1575                      setOnMarkerClickListener { _, _ ->
1576                          Toast.makeText(context, "$label$whenStr (${pin.source})", Toast.LENGTH_SHORT).show(); true
1577                      }
1578                  }
1579                  mapView.overlays.add(marker)
1580              }
1581              // Raw iNaturalist sightings (when the layer is on) — drawn as small,
1582              // muted hollow dots, deliberately UNLIKE the bold warm numbered
1583              // prediction discs, so raw records can't be mistaken for predictions.
1584              // One shared drawable for all of them (cheap, vs a bitmap per pin).
1585              val sightingDot = sightingDotDrawable(context)
1586              for (pin in allFungiPins.take(250)) {
1587                  val name = pin.commonName?.takeIf { it.isNotBlank() } ?: pin.taxonName
1588                  val whenStr = if (pin.observedAt > 0) " · ${dateFmt.format(java.util.Date(pin.observedAt))}" else ""
1589                  val marker = Marker(mapView).apply {
1590                      position = GeoPoint(pin.lat, pin.lng)
1591                      setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
1592                      icon = sightingDot
1593                      infoWindow = null
1594                      setOnMarkerClickListener { _, _ ->
1595                          Toast.makeText(context, "Sighting · $name$whenStr", Toast.LENGTH_SHORT).show(); true
1596                      }
1597                  }
1598                  mapView.overlays.add(marker)
1599              }
1600  
1601              // No search-centre marker: the centre is the map viewport centre and
1602              // is shown by a fixed crosshair drawn in Compose over the map. This
1603              // keeps the centre unambiguous and stops it being mistaken for a pin.
1604  
1605              mapView.invalidate()
1606          },
1607          modifier = Modifier.fillMaxSize()
1608      )
1609  }
1610  
1611  /**
1612   * Maps a user-selected map style to a concrete OSM tile source.
1613   *
1614   * DEFAULT is OpenTopoMap (terrain/topography) — the best basemap for
1615   * identifying woodland, river, and elevation features relevant to
1616   * mushroom habitat. "Dark" uses standard tiles with colour inversion.
1617   */
1618  private fun tileSourceForTheme(theme: String): ITileSource = when (theme) {
1619      "Standard Street" -> TileSourceFactory.MAPNIK
1620      "Dark" -> TileSourceFactory.MAPNIK // uses color inversion filter
1621      "Satellite" -> object : org.osmdroid.tileprovider.tilesource.XYTileSource(
1622          "EsriWorldImagery", 0, 19, 256, "",
1623          arrayOf("https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
1624      ) {
1625          // Esri/ArcGIS tiles are addressed z/y/x (row before column) — the
1626          // default XYTileSource emits z/x/y, which fetches the wrong tiles.
1627          // Global imagery; ideal for spotting actual tree canopy when foraging.
1628          override fun getTileURLString(pMapTileIndex: Long): String =
1629              baseUrl + org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex) + "/" +
1630                  org.osmdroid.util.MapTileIndex.getY(pMapTileIndex) + "/" +
1631                  org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
1632      }
1633      "Topographic" -> org.osmdroid.tileprovider.tilesource.XYTileSource(
1634          "OpenTopoMap", 0, 17, 256, ".png",
1635          arrayOf("https://a.tile.opentopomap.org/", "https://b.tile.opentopomap.org/", "https://c.tile.opentopomap.org/")
1636      ) // OpenTopoMap shows terrain, not roads (can be rate-limited)
1637      else -> TileSourceFactory.MAPNIK // reliable default basemap
1638  }
1639  
1640  /** Maps a 5-tier name to its display label. */
1641  private fun tierLabel(tier: String): String = when (tier) {
1642      "Excellent" -> "Excellent"
1643      "VeryGood"  -> "Very Good"
1644      "Promising" -> "Promising"
1645      "Possible"  -> "Possible"
1646      else        -> "Unlikely"
1647  }
1648  
1649  /** Maps a 5-tier name to its UI colour. Reads the canonical, monotonic
1650   *  green-anchored ramp from the theme so the heatmap, pins, chips and card can
1651   *  never drift apart (see [com.example.ui.theme.TierExcellent]). */
1652  private fun tierColor(tier: String): Color = when (tier) {
1653      "Excellent" -> TierExcellent
1654      "VeryGood"  -> TierVeryGood
1655      "Promising" -> TierPromising
1656      "Possible"  -> TierPossible
1657      else        -> TierUnlikely
1658  }
1659  
1660  /** A one-shot camera move request (Deep Search / "centre here"); [key] makes each
1661   *  request unique so the map animates once rather than on every recomposition. */
1662  data class MapFocus(val lat: Double, val lng: Double, val zoom: Double, val key: Long)
1663  
1664  /**
1665   * Number of filled "pips" (out of 3) for a prediction-confidence value, keyed to
1666   * the same three bands as [MycoMath.confidenceLabel] so the meter and its word
1667   * label always agree. Confidence is rendered as a neutral dot-meter (not a
1668   * coloured chip) so it reads as a distinct dimension from the tier colour.
1669   */
1670  private fun confidencePips(c: Double): Int = when {
1671      c >= 0.66 -> 3
1672      c >= 0.33 -> 2
1673      else -> 1
1674  }
1675  
1676  /**
1677   * Relative probability→colour ramp for the heatmap: a WARM intensity ramp (pale
1678   * amber → red, [HeatLow]/[HeatHigh]) with opacity rising with score, so weak
1679   * areas stay faint and strong ones pop. Warm — not green — because the surface
1680   * sits on a green/topographic basemap that camouflages a green ramp (the very
1681   * reason users reported "never see any shading"); amber→red reads clearly over
1682   * terrain and follows the universal "hotter = more likely" heatmap convention.
1683   * The ramp is relative to the grid's own [floor, top] range, so the best spots
1684   * in view always read hot even when absolute scores are modest (sparse evidence
1685   * / off-season): the map shows "best near you", never a blank surface. The
1686   * minimum opacity (~37%) is high enough that even floor-level cells are visible
1687   * over busy terrain tiles. Scores below the caller's floor are filtered out.
1688   */
1689  private fun heatColor(score: Double, floor: Double, top: Double): Int {
1690      val span = (top - floor).coerceAtLeast(0.0001)
1691      val t = ((score - floor) / span).coerceIn(0.0, 1.0).toFloat()
1692      val base = lerp(HeatLow, HeatHigh, t)
1693      val alpha = (95 + 150 * t).toInt().coerceIn(0, 255)
1694      return base.copy(alpha = alpha / 255f).toArgb()
1695  }
1696  
1697  /**
1698   * Warm ARGB colour for a ranked map-pin disc, by tier. Decoupled from the green
1699   * [tierColor] (which the dark-UI card uses) so the numbered "best spot" discs
1700   * stand out against the green/topo basemap rather than blending into it.
1701   */
1702  private fun mapPinColorInt(tier: String): Int = when (tier) {
1703      "Excellent" -> MapPinExcellent
1704      "VeryGood"  -> MapPinVeryGood
1705      "Promising" -> MapPinPromising
1706      "Possible"  -> MapPinPossible
1707      else        -> MapPinUnlikely
1708  }.toArgb()
1709  
1710  /** Builds a numbered, coloured map-pin disc (① ② ③ …) for a ranked spot. */
1711  private fun numberedPinDrawable(
1712      ctx: android.content.Context,
1713      number: Int,
1714      colorInt: Int
1715  ): android.graphics.drawable.Drawable {
1716      val density = ctx.resources.displayMetrics.density
1717      val size = (28f * density).toInt().coerceAtLeast(40)
1718      val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
1719      val canvas = android.graphics.Canvas(bmp)
1720      val r = size / 2f
1721      val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
1722          color = colorInt; style = android.graphics.Paint.Style.FILL
1723      }
1724      canvas.drawCircle(r, r, r - density, fill)
1725      val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
1726          color = android.graphics.Color.WHITE
1727          style = android.graphics.Paint.Style.STROKE
1728          strokeWidth = 2f * density
1729      }
1730      canvas.drawCircle(r, r, r - density, ring)
1731      val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
1732          color = android.graphics.Color.WHITE
1733          textSize = size * 0.5f
1734          textAlign = android.graphics.Paint.Align.CENTER
1735          isFakeBoldText = true
1736      }
1737      val fm = text.fontMetrics
1738      canvas.drawText(number.toString(), r, r - (fm.ascent + fm.descent) / 2f, text)
1739      return android.graphics.drawable.BitmapDrawable(ctx.resources, bmp)
1740  }
1741  
1742  /**
1743   * A small, muted hollow dot for a raw iNaturalist sighting — intentionally low-key
1744   * and unlike the bold warm numbered prediction discs, so raw records read as
1745   * "observed here", not as a model prediction. Shared across all sighting markers.
1746   */
1747  private fun sightingDotDrawable(ctx: android.content.Context): android.graphics.drawable.Drawable {
1748      val density = ctx.resources.displayMetrics.density
1749      val size = (12f * density).toInt().coerceAtLeast(16)
1750      val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
1751      val canvas = android.graphics.Canvas(bmp)
1752      val r = size / 2f
1753      val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
1754          color = android.graphics.Color.argb(150, 230, 230, 230); style = android.graphics.Paint.Style.FILL
1755      }
1756      canvas.drawCircle(r, r, r - density, fill)
1757      val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
1758          color = android.graphics.Color.argb(200, 60, 60, 60)
1759          style = android.graphics.Paint.Style.STROKE
1760          strokeWidth = 1.2f * density
1761      }
1762      canvas.drawCircle(r, r, r - density, ring)
1763      return android.graphics.drawable.BitmapDrawable(ctx.resources, bmp)
1764  }
1765  
1766  private fun calculateDistanceBetweenPoints(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
1767      val R = 6371e3
1768      val phi1 = lat1 * Math.PI / 180.0
1769      val phi2 = lat2 * Math.PI / 180.0
1770      val deltaPhi = (lat2 - lat1) * Math.PI / 180.0
1771      val deltaLambda = (lon2 - lon1) * Math.PI / 180.0
1772  
1773      val a = sin(deltaPhi / 2) * sin(deltaPhi / 2) +
1774              cos(phi1) * cos(phi2) *
1775              sin(deltaLambda / 2) * sin(deltaLambda / 2)
1776      val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
1777  
1778      return R * c
1779  }
```

---

<a id="appsrcmainjavacomexampleuithemecolorkt"></a>
## `app/src/main/java/com/example/ui/theme/Color.kt`

**Role:** PALETTE — warm map overlay ramp (surface + pins) vs the green dark-UI card tier ramp.  
**Lines:** 65

**Key anchors:**
- HeatLow/HeatHigh + MapPin* :43-61 (warm)
- Tier* :37-41 (green, card only)

```kotlin
 1  package com.example.ui.theme
 2  
 3  import androidx.compose.ui.graphics.Color
 4  
 5  // === Myceliyums brand v3 — "Field Signal Terminal" (dark) ===
 6  // Tokens mirror the site/brand plate: bg/chassis/rule neutrals, signal-green
 7  // primary, amber secondary, ink text ramp.
 8  val NeonMint = Color(0xFF54E0A0)            // primary accent (sig — signal green)
 9  val MossSpruce = Color(0xFF8E9583)          // ink-dim (muted olive; tertiary)
10  val ChanterelleGold = Color(0xFFE6B24C)     // secondary accent (amber)
11  val DimSage = Color(0xFF5B6353)             // ink-faint
12  
13  val DeepForestVoid = Color(0xFF0B0D0B)      // bg
14  val CharcoalSpruce = Color(0xFF14160F)      // chassis (surface)
15  val MediumSpruce = Color(0xFF1C2017)        // raised surface (chassis→rule midpoint)
16  val SageOutline = Color(0xFF2B3022)         // rule (outline)
17  
18  // === Quiet modernist (light) — accents aligned to brand deep tones ===
19  val DeepForestGreen = Color(0xFF1F9E6C)     // primary accent (sig-deep)
20  val WarmHoney = Color(0xFF8A6A26)           // secondary accent (amber-deep)
21  val SoftMoss = Color(0xFF4C7D63)            // tertiary accent
22  
23  val WarmOffWhite = Color(0xFFFAFAF7)
24  val PaperWhite = Color(0xFFFFFFFF)
25  val SoftStone = Color(0xFFE9E6DE)
26  val WarmStoneOutline = Color(0xFFCFCCC2)
27  
28  // === Forageability tier ramp (canonical, 5-tier) ==========================
29  // THE single source of truth for tier colour — read by the heatmap legend,
30  // the ranked map pins, the tier chips and the hotspot card, so the surface,
31  // pins and labels can never tell different colour stories (they used to:
32  // MapScreen hard-coded its own divergent set). The ramp is MONOTONIC and
33  // green-anchored: signal-green = best ("good fungal habitat", the brand
34  // signal), stepping down through lime and gold to desaturated sage/forest for
35  // the weakest tiers. Deliberately NO alarming red — red reads as
36  // "danger / avoid", the opposite of a spot you'd want to forage.
37  val TierExcellent = Color(0xFF54E0A0)       // signal green — best
38  val TierVeryGood = Color(0xFF9BD96B)        // lime — strong
39  val TierPromising = Color(0xFFE6B24C)       // chanterelle gold — promising
40  val TierPossible = Color(0xFF8B9D93)        // muted sage — possible
41  val TierUnlikely = Color(0xFF5B6353)        // dim forest — unlikely
42  
43  // === Map overlay palette — WARM, for legibility over terrain basemaps ========
44  // The on-map heat surface and the ranked pins use a WARM ramp (amber → red),
45  // NOT the green tier ramp. Reason: the default basemap is OpenTopoMap (green
46  // woodland / tan terrain), where a green surface and green pins are camouflaged
47  // — users reported "never see any shading". Warm hues pop against green/topo and
48  // satellite tiles. "Hotter = more likely" is the universal heatmap convention.
49  // (The dark-UI hotspot CARD keeps the green tier ramp above — on the near-black
50  // card, green reads clearly as "good habitat" and needs no warm treatment.)
51  //
52  // Heatmap intensity ramp anchors (low → high). The surface is RELATIVE (scaled
53  // to the grid's own best score): pale amber for the relatively-weak end, red for
54  // the relatively-strong end, with opacity also rising with score. Matches the
55  // site's `--heat-hi` (#FF4D4D).
56  val HeatLow = Color(0xFFFFD27A)             // pale amber (relatively low)
57  val HeatHigh = Color(0xFFFF4D4D)            // red (relatively high)
58  
59  // Ranked map-pin discs (① = best) — a warm, high-contrast 5-tier ramp mirroring
60  // the heat surface so the numbered "best spots" stand out on green/topo tiles.
61  val MapPinExcellent = Color(0xFFFF4D4D)     // red — hottest
62  val MapPinVeryGood = Color(0xFFFF8A3D)      // orange
63  val MapPinPromising = Color(0xFFFFC24D)     // amber
64  val MapPinPossible = Color(0xFFE0B070)      // tan
65  val MapPinUnlikely = Color(0xFFB0A99A)      // muted stone
```

---

<a id="appsrcmainjavacomexampleuiviewmodelfungiviewmodelkt"></a>
## `app/src/main/java/com/example/ui/viewmodel/FungiViewModel.kt`

**Role:** ORCHESTRATION — launches the grid computation and owns map state.  
**Lines:** 464

**Key anchors:**
- computeHotspots() :243 (45s timeout, Success-early, best-effort extras)
- HotspotState sealed interface :22
- deepSearch() :303
- GRID_TIMEOUT_MS :453

```kotlin
  1  package com.example.ui.viewmodel
  2  
  3  import android.app.Application
  4  import android.util.Log
  5  import androidx.lifecycle.AndroidViewModel
  6  import androidx.lifecycle.ViewModel
  7  import androidx.lifecycle.ViewModelProvider
  8  import androidx.lifecycle.viewModelScope
  9  import com.example.MyceliumApplication
 10  import com.example.data.local.SettingsStore
 11  import com.example.data.repository.FungiRepository
 12  import com.example.model.HotspotCell
 13  import com.example.model.MapObservation
 14  import com.example.model.Observation
 15  import com.example.model.Species
 16  import com.example.model.UserSighting
 17  import kotlinx.coroutines.ExperimentalCoroutinesApi
 18  import kotlinx.coroutines.FlowPreview
 19  import kotlinx.coroutines.TimeoutCancellationException
 20  import kotlinx.coroutines.flow.*
 21  import kotlinx.coroutines.launch
 22  import kotlinx.coroutines.withTimeout
 23  
 24  sealed interface HotspotState {
 25      object Idle : HotspotState
 26      object Loading : HotspotState
 27      data class Success(val cells: List<HotspotCell>) : HotspotState
 28      data class Error(val message: String) : HotspotState
 29  }
 30  
 31  /** Two-tier "Deep Search": a fine sub-grid drilled into one overview cell. */
 32  sealed interface DeepSearchState {
 33      object Idle : DeepSearchState
 34      data class Loading(val parent: HotspotCell) : DeepSearchState
 35      data class Success(val parent: HotspotCell, val cells: List<HotspotCell>) : DeepSearchState
 36      data class Error(val message: String) : DeepSearchState
 37  }
 38  
 39  class FungiViewModel(
 40      application: Application,
 41      private val repository: FungiRepository,
 42      private val settingsStore: SettingsStore
 43  ) : AndroidViewModel(application) {
 44  
 45      // Seed state (ensure database is initialized on start)
 46      init {
 47          viewModelScope.launch {
 48              repository.seedDatabase()
 49              speciesList.first { it.isNotEmpty() }.let { list ->
 50                  if (selectedSpeciesForHotspot.value == null) {
 51                      selectedSpeciesForHotspot.value = list.first()
 52                      computeHotspots()
 53                  }
 54              }
 55          }
 56      }
 57  
 58      // 1. Core Species Flows
 59      val speciesList: StateFlow<List<Species>> = repository.allSpeciesFlow
 60          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
 61  
 62      /**
 63       * A gallery of reference photos for a species, pulled from iNaturalist
 64       * taxon photos (cached). Used by the detail screen to show multiple images
 65       * for every species without bundling them in the APK.
 66       */
 67      suspend fun fetchSpeciesImages(scientificName: String): List<String> =
 68          repository.fetchSpeciesImages(scientificName)
 69  
 70      /** Reference photos with CC attribution for the detail gallery. */
 71      suspend fun fetchSpeciesPhotos(scientificName: String): List<com.example.model.SpeciesPhoto> =
 72          repository.fetchSpeciesPhotos(scientificName)
 73  
 74      /** Total worldwide GBIF record count for a species (detail screen). */
 75      suspend fun fetchGlobalRecordCount(scientificName: String): Int? =
 76          repository.getGlobalRecordCount(scientificName)
 77  
 78      // 2. User Sightings Flows
 79      val userSightings: StateFlow<List<UserSighting>> = repository.allUserSightingsFlow
 80          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
 81  
 82      // 3. Search Screen Filtering State
 83      val searchQuery = MutableStateFlow("")
 84      val selectedHabitatFilter = MutableStateFlow<String?>(null)
 85      val selectedSeasonFilter = MutableStateFlow<Int?>(null) // Month 1-12
 86      val selectedSporeFilter = MutableStateFlow<String?>(null)
 87  
 88      val filteredSpecies: StateFlow<List<Species>> = combine(
 89          speciesList,
 90          searchQuery,
 91          selectedHabitatFilter,
 92          selectedSeasonFilter,
 93          selectedSporeFilter
 94      ) { list, query, habitat, season, spore ->
 95          // Apply the attribute filters first, then rank what survives by how well
 96          // its name matches the query (best suggestions first). Ranking is lenient
 97          // on purpose — a close or fuzzy match still surfaces rather than showing
 98          // an empty screen.
 99          val byAttributes = list.filter { spec ->
100              val matchesHabitat = habitat == null || spec.habitatTypes.any { it.equals(habitat, ignoreCase = true) }
101              val matchesSeason = season == null || isMonthInSeason(season, spec.seasonStart, spec.seasonEnd)
102              val matchesSpore = spore == null || spec.sporeColor.equals(spore, ignoreCase = true)
103              matchesHabitat && matchesSeason && matchesSpore
104          }
105          com.example.util.SpeciesSearch.rank(byAttributes, query)
106      }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
107  
108      // Whether a global (worldwide) taxonomy search is in flight.
109      private val _isGlobalSearching = MutableStateFlow(false)
110      val isGlobalSearching: StateFlow<Boolean> = _isGlobalSearching.asStateFlow()
111  
112      /**
113       * Worldwide fungal results from the GBIF taxonomy backbone, driven by the
114       * same search box (debounced). Excludes anything already in the curated
115       * local catalogue so the two lists don't duplicate. Makes the catalogue a
116       * searchable front-end over every described fungus on Earth.
117       */
118      @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
119      val globalResults: StateFlow<List<Species>> = searchQuery
120          .debounce(350)
121          .combine(speciesList) { query, local -> query.trim() to local }
122          .mapLatest { (query, local) ->
123              if (query.length < 3) {
124                  _isGlobalSearching.value = false
125                  emptyList()
126              } else {
127                  _isGlobalSearching.value = true
128                  try {
129                      val localNames = local.map { it.scientificName.lowercase() }.toSet()
130                      repository.searchGlobalFungi(query)
131                          .filter { it.scientificName.lowercase() !in localNames }
132                  } finally {
133                      _isGlobalSearching.value = false
134                  }
135              }
136          }
137          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
138  
139      // 4. Map & Hotspots Calculation View State
140      val mapCenter = MutableStateFlow(Pair(-37.8136, 144.9631)) // Default to Melbourne, Victoria
141      val searchRadiusKm = MutableStateFlow(10.0) // 1.0 to 30.0 km
142      val selectedSpeciesForHotspot = MutableStateFlow<Species?>(null)
143      // Default to aggregate ("any fungi") mode so the map populates with rich
144      // evidence as soon as the user opens it during peak season. They can switch
145      // to a specific species via the bottom drawer.
146      val isAllSpeciesMode = MutableStateFlow(true)
147  
148      private val _hotspotState = MutableStateFlow<HotspotState>(HotspotState.Idle)
149      val hotspotState: StateFlow<HotspotState> = _hotspotState.asStateFlow()
150  
151      // Deep Search (drill-down) — independent of the overview hotspot state so the
152      // broad grid stays put underneath while a fine sub-grid is computed/overlaid.
153      private val _deepSearchState = MutableStateFlow<DeepSearchState>(DeepSearchState.Idle)
154      val deepSearchState: StateFlow<DeepSearchState> = _deepSearchState.asStateFlow()
155      private var deepJob: kotlinx.coroutines.Job? = null
156  
157      private val _observationPins = MutableStateFlow<List<Observation>>(emptyList())
158      val observationPins: StateFlow<List<Observation>> = _observationPins.asStateFlow()
159  
160      // "All fungi" map layer — every nearby fungal sighting from iNaturalist,
161      // labelled by taxon. Shown on the map independent of the selected species.
162      private val _allFungiPins = MutableStateFlow<List<MapObservation>>(emptyList())
163      val allFungiPins: StateFlow<List<MapObservation>> = _allFungiPins.asStateFlow()
164  
165      // User toggle for the raw all-sightings layer. Default OFF so the map opens on
166      // the prediction surface (the point of the screen) rather than a clutter of raw
167      // iNaturalist records that read like predictions. The user can switch it on, and
168      // when on the markers are drawn in a distinct muted style (see MapScreen).
169      val showAllSightings = MutableStateFlow(false)
170  
171      private val _weatherSummary = MutableStateFlow<Pair<Double, Double>?>(null) // Rainfall, MaxTemp
172      val weatherSummary: StateFlow<Pair<Double, Double>?> = _weatherSummary.asStateFlow()
173  
174      private val _isRecomputationsRunning = MutableStateFlow(false)
175      val isRecomputationsRunning: StateFlow<Boolean> = _isRecomputationsRunning.asStateFlow()
176  
177      // 5. Settings Configuration State (persisted via DataStore)
178      val measureUnits: StateFlow<String> = settingsStore.measureUnits
179          .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_UNITS)
180      val mapTheme: StateFlow<String> = settingsStore.mapTheme
181          .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_MAP_THEME)
182      val appTheme: StateFlow<String> = settingsStore.appTheme
183          .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_APP_THEME)
184      // User-supplied Anthropic API key for AI identification (stored on-device only).
185      val anthropicApiKey: StateFlow<String> = settingsStore.anthropicApiKey
186          .stateIn(viewModelScope, SharingStarted.Eagerly, "")
187      // Initial value true so the returning-user case doesn't flash the disclaimer
188      // before DataStore has loaded; a brand-new user sees it once it emits false.
189      val splashNoticeAccepted: StateFlow<Boolean> = settingsStore.splashAccepted
190          .stateIn(viewModelScope, SharingStarted.Eagerly, true)
191  
192      fun setMeasureUnits(value: String) {
193          viewModelScope.launch { settingsStore.setMeasureUnits(value) }
194      }
195  
196      fun setMapTheme(value: String) {
197          viewModelScope.launch { settingsStore.setMapTheme(value) }
198      }
199  
200      fun setAppTheme(value: String) {
201          viewModelScope.launch { settingsStore.setAppTheme(value) }
202      }
203  
204      fun setAnthropicApiKey(value: String) {
205          viewModelScope.launch { settingsStore.setAnthropicApiKey(value) }
206      }
207  
208      fun acceptSplashNotice() {
209          viewModelScope.launch { settingsStore.setSplashAccepted(true) }
210      }
211  
212      private var computeJob: kotlinx.coroutines.Job? = null
213  
214      /**
215       * Recomputes hotspot overlay + pins for the current map parameters.
216       * Branches between single-species and aggregate "all species" mode.
217       */
218      /**
219       * Geocodes [query] (Google Geocoding when a key is set, else the device
220       * geocoder), recentres the map, recomputes hotspots, and reports a
221       * human-readable label via [onResult] (null when nothing was found).
222       */
223      fun searchLocation(query: String, onResult: (String?) -> Unit) {
224          if (query.isBlank()) {
225              onResult(null)
226              return
227          }
228          viewModelScope.launch {
229              val place = repository.geocodePlace(query)
230              if (place != null) {
231                  mapCenter.value = Pair(place.lat, place.lng)
232                  computeHotspots()
233                  onResult(place.label)
234              } else {
235                  onResult(null)
236              }
237          }
238      }
239  
240      /** Reverse-geocodes [lat]/[lng] to a place label for the map header. */
241      fun reverseGeocode(lat: Double, lng: Double, onResult: (String) -> Unit) {
242          viewModelScope.launch {
243              val name = repository.reverseGeocode(lat, lng)
244              onResult(name ?: String.format(java.util.Locale.US, "GPS: %.3f, %.3f", lat, lng))
245          }
246      }
247  
248      fun computeHotspots() {
249          val (lat, lng) = mapCenter.value
250          val radius = searchRadiusKm.value
251          val multiSpecies = isAllSpeciesMode.value
252          val species = selectedSpeciesForHotspot.value
253          if (!multiSpecies && species == null) return
254  
255          computeJob?.cancel()
256          clearDeepSearch()  // a new overview invalidates any open drill-down
257          computeJob = viewModelScope.launch {
258              _hotspotState.value = HotspotState.Loading
259              _isRecomputationsRunning.value = true
260  
261              // Refresh the all-fungi sightings layer in parallel — independent of
262              // the hotspot computation, so the map populates pins quickly and a
263              // hotspot failure never blocks the sightings (and vice versa).
264              launch {
265                  _allFungiPins.value =
266                      if (showAllSightings.value) repository.getAllFungiObservations(lat, lng, radius)
267                      else emptyList()
268              }
269  
270              try {
271                  // Bound the grid computation so a stuck/slow upstream (Overpass,
272                  // Open-Meteo, Earth Engine over mobile data) can't leave the map
273                  // spinning forever — it surfaces a retryable error instead.
274                  val cells = withTimeout(GRID_TIMEOUT_MS) {
275                      if (multiSpecies) {
276                          repository.generateMultiSpeciesHotspots(lat, lng, radius)
277                      } else {
278                          repository.generateHotspots(species!!, lat, lng, radius)
279                      }
280                  }
281  
282                  // Publish the grid as soon as it's ready. The weather summary and
283                  // species pins below are non-essential extras for other tabs — they
284                  // must NEVER discard a perfectly good grid if they fail, so they run
285                  // best-effort AFTER Success is set.
286                  _hotspotState.value = HotspotState.Success(cells)
287  
288                  try {
289                      _weatherSummary.value = repository.getWeatherLast30Days(lat, lng)
290                      // Recent iNaturalist records for the selected species (Home tab).
291                      _observationPins.value = species?.let {
292                          repository.getObservations(it, lat, lng, radius)
293                      } ?: emptyList()
294                  } catch (e: Exception) {
295                      if (e is kotlinx.coroutines.CancellationException) throw e
296                      Log.w("FungiViewModel", "Hotspot extras (weather/pins) failed: ${e.message}")
297                  }
298              } catch (e: TimeoutCancellationException) {
299                  // A timeout IS a CancellationException, so it must be caught before
300                  // the generic cancellation check below or it would be swallowed and
301                  // leave the map stuck on the spinner.
302                  _hotspotState.value =
303                      HotspotState.Error("timed out — slow or blocked connection. Tap Retry.")
304              } catch (e: Exception) {
305                  if (e !is kotlinx.coroutines.CancellationException) {
306                      _hotspotState.value = HotspotState.Error(e.message ?: "Failed to compute hotspots.")
307                  }
308              } finally {
309                  _isRecomputationsRunning.value = false
310              }
311          }
312      }
313  
314      /** Whether a cell can be drilled into: single-species mode, VeryGood+ tier. */
315      fun canDeepSearch(cell: HotspotCell): Boolean =
316          !isAllSpeciesMode.value &&
317              selectedSpeciesForHotspot.value != null &&
318              (cell.tier == "Excellent" || cell.tier == "VeryGood")
319  
320      /**
321       * Drill into one promising overview cell, computing a fine sub-grid via the
322       * repository (single-species only in v1). Runs independently of the overview.
323       */
324      fun deepSearch(parentCell: HotspotCell) {
325          val species = selectedSpeciesForHotspot.value ?: return
326          if (isAllSpeciesMode.value) return
327          val radius = searchRadiusKm.value
328          deepJob?.cancel()
329          deepJob = viewModelScope.launch {
330              _deepSearchState.value = DeepSearchState.Loading(parentCell)
331              try {
332                  val sub = repository.deepSearchCell(species, parentCell, radius)
333                  _deepSearchState.value = DeepSearchState.Success(parentCell, sub)
334              } catch (e: Exception) {
335                  if (e !is kotlinx.coroutines.CancellationException) {
336                      _deepSearchState.value = DeepSearchState.Error(e.message ?: "Deep search failed.")
337                  }
338              }
339          }
340      }
341  
342      /** Dismiss the drill-down and return to the broad overview grid. */
343      fun clearDeepSearch() {
344          deepJob?.cancel()
345          _deepSearchState.value = DeepSearchState.Idle
346      }
347  
348      /** Toggle between single-species and aggregate "all species" hotspot mode. */
349      fun setAllSpeciesMode(enabled: Boolean) {
350          isAllSpeciesMode.value = enabled
351          computeHotspots()
352      }
353  
354      /** Toggle the "all fungi sightings" map layer. */
355      fun setShowAllSightings(enabled: Boolean) {
356          showAllSightings.value = enabled
357          if (!enabled) {
358              _allFungiPins.value = emptyList()
359          } else {
360              val (lat, lng) = mapCenter.value
361              viewModelScope.launch {
362                  _allFungiPins.value = repository.getAllFungiObservations(lat, lng, searchRadiusKm.value)
363              }
364          }
365      }
366  
367      /**
368       * Resets search screen filters to null
369       */
370      fun resetFilters() {
371          selectedHabitatFilter.value = null
372          selectedSeasonFilter.value = null
373          selectedSporeFilter.value = null
374          searchQuery.value = ""
375      }
376  
377      /**
378       * Adds a new sighting to local Room DB
379       */
380      fun addUserSighting(
381          speciesId: String,
382          latitude: Double,
383          longitude: Double,
384          notes: String,
385          photoPath: String?,
386          isPrivate: Boolean
387      ) {
388          viewModelScope.launch {
389              val sighting = UserSighting(
390                  speciesId = speciesId,
391                  lat = latitude,
392                  lng = longitude,
393                  timestamp = System.currentTimeMillis(),
394                  photoLocalPath = photoPath,
395                  notes = notes,
396                  isPrivate = isPrivate
397              )
398              repository.insertUserSighting(sighting)
399          }
400      }
401  
402      /**
403       * Deletes user observation sighting
404       */
405      fun deleteUserSighting(sighting: UserSighting) {
406          viewModelScope.launch {
407              repository.deleteUserSighting(sighting)
408          }
409      }
410  
411      /**
412       * Exports sightings in Darwin Core format for contribution to
413       * Fungimap/ALA/GBIF biodiversity databases.
414       */
415      private val _darwinCoreExport = MutableStateFlow<String?>(null)
416      val darwinCoreExport: StateFlow<String?> = _darwinCoreExport.asStateFlow()
417  
418      fun exportDarwinCore() {
419          viewModelScope.launch {
420              val csv = repository.exportDarwinCore()
421              _darwinCoreExport.value = csv
422          }
423      }
424  
425      fun clearExport() {
426          _darwinCoreExport.value = null
427      }
428  
429      /**
430       * Clear local observation query cache (TTL management)
431       */
432      fun clearCache() {
433          viewModelScope.launch {
434              repository.clearCaches()
435              _observationPins.value = emptyList()
436              if (selectedSpeciesForHotspot.value != null) {
437                  computeHotspots()
438              }
439          }
440      }
441  
442      private fun isMonthInSeason(month: Int, start: Int, end: Int): Boolean {
443          return if (start <= end) {
444              month in start..end
445          } else {
446              month >= start || month <= end
447          }
448      }
449  
450      // Factory Class
451      companion object {
452          /** Upper bound on a hotspot-grid computation before it fails retryably. */
453          private const val GRID_TIMEOUT_MS = 45_000L
454  
455          fun provideFactory(application: Application): ViewModelProvider.Factory =
456              object : ViewModelProvider.Factory {
457                  @Suppress("UNCHECKED_CAST")
458                  override fun <T : ViewModel> create(modelClass: Class<T>): T {
459                      val app = application as MyceliumApplication
460                      return FungiViewModel(application, app.repository, app.settingsStore) as T
461                  }
462              }
463          }
464  }
```

---

<a id="appsrcmainjavacomexampledatarepositoryfungirepositorykt"></a>
## `app/src/main/java/com/example/data/repository/FungiRepository.kt`

**Role:** ENGINE — builds the cell grid, fetches per-cell layers, assembles each score. *** REVIEW CORE ***  
**Lines:** 2035

**Key anchors:**
- generateHotspots :1168
- runSpeciesGrid :1205  <-- single-species grid + scoring
- per-cell factor map :1457
- weightedSum :1479, penaltyMultiplier :1485, habitatGate :1495-1508
- finalScore = weightedSum x penalty x gate :1508
- classifyTier call :1511
- generateMultiSpeciesHotspots :1636
- deepSearchCell :1592
- fetchEnvLayers :960, fetchElevations :711, fetchCanopyFeatures :754, fetchMulchFeatures :791
- classifyLandCell :929, gridKey(cache) :633
- getObservations :355, getAllFungiObservations :150, getWeatherLast30Days :1113, getDetailedWeather :582

```kotlin
   1  package com.example.data.repository
   2  
   3  import android.content.Context
   4  import android.util.Log
   5  import com.example.data.local.FungiDao
   6  import com.example.data.remote.ALAApi
   7  import com.example.data.remote.GBIFApi
   8  import com.example.data.remote.INatObservation
   9  import com.example.data.remote.EnvGridRequest
  10  import com.example.data.remote.EnvLayersApi
  11  import com.example.data.remote.GeocodingApi
  12  import com.example.data.remote.INaturalistApi
  13  import com.example.data.remote.OpenMeteoApi
  14  import com.example.data.remote.OverpassApi
  15  import com.example.model.HotspotCell
  16  import com.example.model.MapObservation
  17  import com.example.model.Observation
  18  import com.example.model.Species
  19  import com.example.model.UserSighting
  20  import com.example.util.MycoMath
  21  import com.example.util.SpeciesSearch
  22  import com.squareup.moshi.Moshi
  23  import com.squareup.moshi.Types
  24  import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
  25  import kotlinx.coroutines.Dispatchers
  26  import kotlinx.coroutines.async
  27  import kotlinx.coroutines.awaitAll
  28  import kotlinx.coroutines.coroutineScope
  29  import kotlinx.coroutines.delay
  30  import kotlinx.coroutines.flow.Flow
  31  import kotlinx.coroutines.withContext
  32  import kotlinx.coroutines.ensureActive
  33  import java.io.InputStreamReader
  34  import java.text.SimpleDateFormat
  35  import java.util.Calendar
  36  import java.util.Date
  37  import java.util.Locale
  38  import kotlin.math.*
  39  
  40  class FungiRepository(
  41      private val context: Context,
  42      private val dao: FungiDao,
  43      private val iNatApi: INaturalistApi,
  44      private val openMeteoApi: OpenMeteoApi,
  45      private val alaApi: ALAApi,
  46      private val gbifApi: GBIFApi,
  47      private val overpassApi: OverpassApi,
  48      private val envLayersApi: EnvLayersApi? = null,
  49      private val backendToken: String = "",
  50      private val geocodingApi: GeocodingApi? = null,
  51      private val googleApiKey: String = ""
  52  ) {
  53      private val moshi = Moshi.Builder()
  54          .add(KotlinJsonAdapterFactory())
  55          .build()
  56      private val TAG = "FungiRepository"
  57  
  58      // TTL for iNaturalist observations cache (24 hours)
  59      private val CACHE_TTL_MS = 24 * 60 * 60 * 1000L
  60  
  61      val allSpeciesFlow: Flow<List<Species>> = dao.getAllSpeciesFlow()
  62      val allUserSightingsFlow: Flow<List<UserSighting>> = dao.getAllUserSightingsFlow()
  63      /**
  64       * Seeds the local database with species from the bundled assets species.json.
  65       */
  66      suspend fun seedDatabase() = withContext(Dispatchers.IO) {
  67          try {
  68              context.assets.open("species.json").use { inputStream ->
  69                  val reader = InputStreamReader(inputStream)
  70                  val jsonString = reader.readText()
  71                  val listType = Types.newParameterizedType(List::class.java, Species::class.java)
  72                  val adapter = moshi.adapter<List<Species>>(listType)
  73                  val speciesList = adapter.fromJson(jsonString)
  74                  if (speciesList != null) {
  75                      // Upsert when the bundled catalogue and the DB differ in size
  76                      // (fresh install, or an app update that adds species). The
  77                      // species table is independent of user sightings, and inserts
  78                      // REPLACE on conflict, so this never touches user data.
  79                      val existingCount = dao.getAllSpecies().size
  80                      if (existingCount != speciesList.size) {
  81                          dao.insertSpecies(speciesList)
  82                          Log.d(TAG, "Seeded/updated species catalogue: $existingCount -> ${speciesList.size}.")
  83                      }
  84                  }
  85              }
  86          } catch (e: Exception) {
  87              Log.e(TAG, "Failed to seed species database from assets or check existing record schema", e)
  88          }
  89      }
  90  
  91      suspend fun getSpeciesById(id: String): Species? = withContext(Dispatchers.IO) {
  92          dao.getSpeciesById(id)
  93      }
  94  
  95      suspend fun getAllSpecies(): List<Species> = withContext(Dispatchers.IO) {
  96          dao.getAllSpecies()
  97      }
  98  
  99      suspend fun insertUserSighting(sighting: UserSighting) = withContext(Dispatchers.IO) {
 100          dao.insertUserSighting(sighting)
 101      }
 102  
 103      suspend fun deleteUserSighting(sighting: UserSighting) = withContext(Dispatchers.IO) {
 104          dao.deleteUserSighting(sighting)
 105      }
 106  
 107      suspend fun getAllUserSightings(): List<UserSighting> = withContext(Dispatchers.IO) {
 108          dao.getAllUserSightings()
 109      }
 110  
 111      /**
 112       * Retry a suspending IO block with exponential backoff. Network calls to
 113       * iNat/ALA/GBIF/Open-Meteo are flaky on mobile; one transient failure
 114       * shouldn't drop a whole data source. Re-throws only after the last try.
 115       */
 116      private suspend fun <T> retryIO(
 117          times: Int = 3,
 118          initialDelayMs: Long = 400L,
 119          block: suspend () -> T
 120      ): T {
 121          var delayMs = initialDelayMs
 122          var lastError: Exception? = null
 123          repeat(times) { attempt ->
 124              try {
 125                  return block()
 126              } catch (e: Exception) {
 127                  if (e is kotlinx.coroutines.CancellationException) throw e
 128                  lastError = e
 129                  if (attempt < times - 1) {
 130                      Log.w(TAG, "retry ${attempt + 1}/$times after: ${e.message}")
 131                      delay(delayMs)
 132                      delayMs *= 2
 133                  }
 134              }
 135          }
 136          throw lastError ?: IllegalStateException("retryIO failed")
 137      }
 138  
 139      // All-fungi map layer: every fungal observation in an area, keyed by a
 140      // coarse grid + radius and cached briefly so panning is cheap.
 141      private val areaObsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<MapObservation>>>()
 142      private val AREA_OBS_TTL_MS = 30 * 60 * 1000L
 143  
 144      /**
 145       * Every fungal sighting in the map area, fetched in ONE kingdom-wide
 146       * iNaturalist request (taxon_name=Fungi) rather than per species. Powers
 147       * the "all sightings" map layer and a generic fungal-activity signal in the
 148       * aggregate prediction. Fails soft to an empty list (never breaks the map).
 149       */
 150      suspend fun getAllFungiObservations(
 151          lat: Double,
 152          lng: Double,
 153          radiusKm: Double,
 154          forceRefresh: Boolean = false
 155      ): List<MapObservation> = withContext(Dispatchers.IO) {
 156          val key = "${"%.2f".format(lat)}_${"%.2f".format(lng)}_${radiusKm.toInt()}"
 157          val now = System.currentTimeMillis()
 158          if (!forceRefresh) {
 159              areaObsCache[key]?.let { (ts, value) ->
 160                  if (now - ts < AREA_OBS_TTL_MS) return@withContext value
 161              }
 162          }
 163          val cal = Calendar.getInstance().apply { add(Calendar.YEAR, -5) }
 164          val since = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
 165          // Bounding box for GBIF (it ranges by min,max lat/lng, not radius).
 166          val latDelta = radiusKm / 111.0
 167          val lngDelta = radiusKm / (111.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.05))
 168          val latRange = "${"%.4f".format(lat - latDelta)},${"%.4f".format(lat + latDelta)}"
 169          val lngRange = "${"%.4f".format(lng - lngDelta)},${"%.4f".format(lng + lngDelta)}"
 170  
 171          val result = coroutineScope {
 172              // iNaturalist citizen-science sightings (one kingdom-wide call)
 173              val iNatDeferred = async {
 174                  try {
 175                      val resp = retryIO {
 176                          iNatApi.getAreaObservations(lat = lat, lng = lng, radiusKm = radiusKm, sinceDate = since)
 177                      }
 178                      resp.results.mapNotNull { o ->
 179                          val geoLng = o.geojson?.coordinates?.getOrNull(0)
 180                          val geoLat = o.geojson?.coordinates?.getOrNull(1)
 181                          val pLat = o.latitude ?: o.location?.split(",")?.getOrNull(0)?.trim()?.toDoubleOrNull() ?: geoLat
 182                          val pLng = o.longitude ?: o.location?.split(",")?.getOrNull(1)?.trim()?.toDoubleOrNull() ?: geoLng
 183                          if (pLat == null || pLng == null) return@mapNotNull null
 184                          MapObservation(
 185                              id = o.id, lat = pLat, lng = pLng,
 186                              taxonName = o.taxon?.name ?: "Unidentified fungus",
 187                              commonName = o.taxon?.commonName,
 188                              source = "iNaturalist",
 189                              observedAt = parseObsDate(o.observedOn),
 190                              qualityGrade = o.qualityGrade ?: "needs_id",
 191                              photoUrl = o.photos?.firstOrNull()?.url,
 192                              placeGuess = o.placeGuess
 193                          )
 194                      }
 195                  } catch (e: Exception) {
 196                      Log.w(TAG, "all-fungi iNat fetch failed: ${e.message}"); emptyList()
 197                  }
 198              }
 199              // GBIF museum/herbarium + research occurrences in the same box
 200              val gbifDeferred = async {
 201                  try {
 202                      val resp = retryIO { gbifApi.searchAreaOccurrences(lat = latRange, lon = lngRange) }
 203                      (resp.results ?: emptyList()).mapNotNull { o ->
 204                          val pLat = o.decimalLatitude ?: return@mapNotNull null
 205                          val pLng = o.decimalLongitude ?: return@mapNotNull null
 206                          val whenMs = parseObsDate(o.eventDate) // null-safe; falls back to ~30d ago
 207                          MapObservation(
 208                              id = (o.key ?: (pLat * 1e6 + pLng).toLong()),
 209                              lat = pLat, lng = pLng,
 210                              taxonName = o.scientificName ?: "Fungus (GBIF record)",
 211                              commonName = null,
 212                              source = "GBIF",
 213                              observedAt = whenMs,
 214                              qualityGrade = o.basisOfRecord ?: "GBIF record",
 215                              photoUrl = null,
 216                              placeGuess = o.institutionCode ?: o.datasetName
 217                          )
 218                      }
 219                  } catch (e: Exception) {
 220                      Log.w(TAG, "all-fungi GBIF fetch failed: ${e.message}"); emptyList()
 221                  }
 222              }
 223              (iNatDeferred.await() + gbifDeferred.await())
 224          }
 225          areaObsCache[key] = now to result
 226          result
 227      }
 228  
 229      // Per-species global GBIF record count, cached.
 230      private val recordCountCache = java.util.concurrent.ConcurrentHashMap<String, Int>()
 231  
 232      /**
 233       * Total worldwide GBIF occurrence records for a species (museum, herbarium
 234       * and observation records). Surfaced on the detail screen as a measure of
 235       * how widely the species has been recorded. Fails soft to null.
 236       */
 237      suspend fun getGlobalRecordCount(scientificName: String): Int? = withContext(Dispatchers.IO) {
 238          recordCountCache[scientificName]?.let { return@withContext it }
 239          val count = try {
 240              retryIO { gbifApi.countOccurrences(scientificName) }.count
 241          } catch (e: Exception) {
 242              Log.w(TAG, "GBIF record count failed for $scientificName: ${e.message}"); null
 243          }
 244          if (count != null) recordCountCache[scientificName] = count
 245          count
 246      }
 247  
 248      // Global fungal taxonomy search results, cached per query.
 249      private val globalSearchCache = java.util.concurrent.ConcurrentHashMap<String, List<Species>>()
 250  
 251      /**
 252       * Search the ENTIRE described fungal kingdom via the GBIF species backbone
 253       * (~150k species worldwide), returning lightweight Species records that
 254       * reuse the normal detail screen (photos load live from iNaturalist by
 255       * scientific name). This makes the catalogue a front-end over every
 256       * described fungus on Earth, not just the bundled field guide. Fails soft.
 257       */
 258      suspend fun searchGlobalFungi(query: String): List<Species> = withContext(Dispatchers.IO) {
 259          val q = query.trim()
 260          if (q.length < 3) return@withContext emptyList()
 261          globalSearchCache[q.lowercase()]?.let { return@withContext it }
 262          val result = try {
 263              val resp = retryIO { gbifApi.searchSpecies(query = q) }
 264              (resp.results ?: emptyList()).mapNotNull { g ->
 265                  val sci = (g.canonicalName ?: g.scientificName)?.takeIf { it.isNotBlank() }
 266                      ?: return@mapNotNull null
 267                  val key = g.key ?: sci.hashCode().toLong()
 268                  val common = g.vernacularNames
 269                      ?.firstOrNull { it.language.equals("eng", true) || it.language.equals("en", true) }
 270                      ?.vernacularName
 271                      ?: g.vernacularNames?.firstOrNull()?.vernacularName
 272                  Species(
 273                      id = "gbif_$key",
 274                      scientificName = sci,
 275                      commonNames = listOfNotNull(common?.takeIf { it.isNotBlank() }),
 276                      genus = g.genus?.takeIf { it.isNotBlank() } ?: sci.substringBefore(" "),
 277                      family = g.family?.takeIf { it.isNotBlank() } ?: "Unknown family",
 278                      habitatTypes = emptyList(),
 279                      substrates = emptyList(),
 280                      seasonStart = 1,
 281                      seasonEnd = 12,
 282                      capDescription = "No curated field description yet — this is a global " +
 283                          "taxonomy record. Swipe the reference photos below (live from " +
 284                          "iNaturalist) and always cross-check with an expert before relying " +
 285                          "on any identification.",
 286                      gillDescription = "Not catalogued.",
 287                      stemDescription = "Not catalogued.",
 288                      sporeColor = "—",
 289                      bruisingReaction = "—",
 290                      lookAlikes = emptyList(),
 291                      notes = "Global catalogue entry from the GBIF fungal taxonomy" +
 292                          (g.family?.let { " · $it" } ?: "") +
 293                          (g.order?.let { " · $it" } ?: "") + ". Photos and observations are " +
 294                          "pulled live from iNaturalist.",
 295                      imageUrls = emptyList()
 296                  )
 297              }.distinctBy { it.scientificName.lowercase() }
 298                  // GBIF matched these server-side (now including synonyms/genera);
 299                  // re-rank by our own relevance so the closest names lead.
 300                  .let { SpeciesSearch.sortByRelevance(it, q) }
 301          } catch (e: Exception) {
 302              Log.w(TAG, "global fungi search failed for '$q': ${e.message}")
 303              emptyList()
 304          }
 305          globalSearchCache[q.lowercase()] = result
 306          result
 307      }
 308  
 309      // Reference-photo gallery per species (scientific name → photos with
 310      // attribution), pulled from iNaturalist taxon photos. Cached for the
 311      // process lifetime so revisiting a species detail is instant and free.
 312      private val speciesPhotoCache = java.util.concurrent.ConcurrentHashMap<String, List<com.example.model.SpeciesPhoto>>()
 313  
 314      /** Tidy iNaturalist's attribution string for compact on-image display. */
 315      private fun cleanAttribution(raw: String?): String? =
 316          raw?.replace("(c)", "©")?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotBlank() }
 317  
 318      /**
 319       * A gallery of reference photos (with CC attribution) for a species,
 320       * sourced from iNaturalist's curated taxon photos. URLs load directly via
 321       * Coil — no images bundled in the APK. Fails soft to an empty list.
 322       */
 323      suspend fun fetchSpeciesPhotos(scientificName: String): List<com.example.model.SpeciesPhoto> =
 324          withContext(Dispatchers.IO) {
 325              speciesPhotoCache[scientificName]?.let { return@withContext it }
 326              val photos = try {
 327                  val taxon = iNatApi.getTaxa(scientificName).results?.firstOrNull()
 328                  val gallery = taxon?.taxonPhotos.orEmpty().mapNotNull { wrap ->
 329                      val p = wrap.photo ?: return@mapNotNull null
 330                      val url = p.mediumUrl ?: p.url ?: return@mapNotNull null
 331                      com.example.model.SpeciesPhoto(url, cleanAttribution(p.attribution))
 332                  }
 333                  val withDefault = if (gallery.isEmpty()) {
 334                      val dp = taxon?.defaultPhoto
 335                      val url = dp?.mediumUrl ?: dp?.url
 336                      if (url != null) listOf(com.example.model.SpeciesPhoto(url, cleanAttribution(dp?.attribution)))
 337                      else emptyList()
 338                  } else gallery
 339                  withDefault.distinctBy { it.url }.take(12)
 340              } catch (e: Exception) {
 341                  Log.w(TAG, "species photos fetch failed for $scientificName: ${e.message}")
 342                  emptyList()
 343              }
 344              speciesPhotoCache[scientificName] = photos
 345              photos
 346          }
 347  
 348      /** Image URLs only — used for list thumbnails and the global-search filter. */
 349      suspend fun fetchSpeciesImages(scientificName: String): List<String> =
 350          fetchSpeciesPhotos(scientificName).map { it.url }
 351  
 352      /**
 353       * Fetch iNaturalist observations with offline-first Room cache with TTL.
 354       */
 355      suspend fun getObservations(
 356          species: Species,
 357          lat: Double,
 358          lng: Double,
 359          radiusKm: Double,
 360          forceRefresh: Boolean = false
 361      ): List<Observation> = withContext(Dispatchers.IO) {
 362          // 1. Check cached observations in Room
 363          val cached = dao.getCachedObservations(species.id)
 364          val now = System.currentTimeMillis()
 365  
 366          // Filter those within radius distance from the center target (cached observations might be broader)
 367          val inRadiusCached = cached.filter {
 368              calculateDistanceMeters(lat, lng, it.lat, it.lng) <= radiusKm * 1000.0
 369          }
 370  
 371          val isCacheValid = cached.isNotEmpty() && (now - cached.maxOf { it.cachedAt }) < CACHE_TTL_MS
 372  
 373          if (isCacheValid && !forceRefresh) {
 374              Log.d(TAG, "Returning ${inRadiusCached.size} cached observations from Room (cache fresh)")
 375              return@withContext inRadiusCached
 376          }
 377  
 378          // 2. Cache is stale or empty or forceRefresh requested: Fetch from network
 379          try {
 380              Log.d(TAG, "Fetching observations from iNaturalist API for ${species.scientificName} at ($lat, $lng)")
 381              // 5 years ago date
 382              val cal = Calendar.getInstance()
 383              cal.add(Calendar.YEAR, -5)
 384              val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
 385              val sinceDate = sdf.format(cal.time)
 386  
 387              val response = retryIO {
 388                  iNatApi.getObservations(
 389                      taxonName = species.scientificName,
 390                      lat = lat,
 391                      lng = lng,
 392                      radiusKm = radiusKm,
 393                      sinceDate = sinceDate
 394                  )
 395              }
 396  
 397              val iNatObsList = response.results
 398              val freshObservations = iNatObsList.mapNotNull { iObs ->
 399                  // Parse coordinates. iNaturalist usually returns coordinates in the
 400                  // `geojson` field as [lng, lat], and/or `location` as "lat,lng".
 401                  // Try the explicit lat/lng first, then location string, then geojson.
 402                  val geoLng = iObs.geojson?.coordinates?.getOrNull(0)
 403                  val geoLat = iObs.geojson?.coordinates?.getOrNull(1)
 404                  val parsedLat = iObs.latitude
 405                      ?: iObs.location?.split(",")?.getOrNull(0)?.trim()?.toDoubleOrNull()
 406                      ?: geoLat
 407                  val parsedLng = iObs.longitude
 408                      ?: iObs.location?.split(",")?.getOrNull(1)?.trim()?.toDoubleOrNull()
 409                      ?: geoLng
 410  
 411                  if (parsedLat != null && parsedLng != null) {
 412                      val obsTime = parseObsDate(iObs.observedOn)
 413                      Observation(
 414                          id = iObs.id,
 415                          speciesId = species.id,
 416                          lat = parsedLat,
 417                          lng = parsedLng,
 418                          observedAt = obsTime,
 419                          source = "iNaturalist",
 420                          photoUrl = iObs.photos?.firstOrNull()?.url,
 421                          qualityGrade = iObs.qualityGrade ?: "research",
 422                          cachedAt = now
 423                      )
 424                  } else {
 425                      null
 426                  }
 427              }
 428  
 429              // Also pull museum/herbarium records from ALA and GBIF, in PARALLEL
 430              // with each other and with retry. Failures are logged (not silently
 431              // swallowed) and degrade to empty so iNat data still comes through.
 432              val (alaObs, gbifObs) = coroutineScope {
 433                  val alaDeferred = async {
 434                      try { retryIO { getALAObservations(species, lat, lng, radiusKm) } }
 435                      catch (e: Exception) { Log.w(TAG, "ALA fetch failed for ${species.scientificName}: ${e.message}"); emptyList() }
 436                  }
 437                  val gbifDeferred = async {
 438                      try { retryIO { getGBIFObservations(species, lat, lng, radiusKm) } }
 439                      catch (e: Exception) { Log.w(TAG, "GBIF fetch failed for ${species.scientificName}: ${e.message}"); emptyList() }
 440                  }
 441                  alaDeferred.await() to gbifDeferred.await()
 442              }
 443  
 444              val allFresh = freshObservations + alaObs + gbifObs
 445  
 446              // Save new network result to local Room Cache
 447              if (allFresh.isNotEmpty()) {
 448                  // Clear and replace cache for this species to maintain fresh cache representation
 449                  dao.clearObservationsForSpecies(species.id)
 450                  dao.insertObservations(allFresh)
 451                  Log.d(TAG, "Fetched and cached ${allFresh.size} observations (iNat: ${freshObservations.size}, ALA: ${alaObs.size}, GBIF: ${gbifObs.size}) in Room.")
 452              }
 453  
 454              // Return observations filtered by radius
 455              return@withContext allFresh.filter {
 456                  calculateDistanceMeters(lat, lng, it.lat, it.lng) <= radiusKm * 1000.0
 457              }
 458  
 459          } catch (e: Exception) {
 460              Log.e(TAG, "Failed to fetch from iNaturalist API, returning stale cache", e)
 461              // Offline fallback: return whatever elements we have cached (even if stale) as it is offline-first!
 462              return@withContext inRadiusCached
 463          }
 464      }
 465  
 466      // ─── ALA (Atlas of Living Australia) ─────────────────────────────
 467  
 468      /**
 469       * Fetches fungal occurrence records from the Atlas of Living Australia.
 470       * Herbarium specimens (PRESERVED_SPECIMEN) from institutions like
 471       * Royal Botanic Gardens Melbourne are weighted highest — verified by
 472       * professional mycologists.
 473       */
 474      suspend fun getALAObservations(
 475          species: Species,
 476          lat: Double,
 477          lng: Double,
 478          radiusKm: Double
 479      ): List<Observation> = withContext(Dispatchers.IO) {
 480          try {
 481              Log.d(TAG, "Fetching ALA records for ${species.scientificName}")
 482              val response = alaApi.searchOccurrences(
 483                  query = species.scientificName,
 484                  lat = lat,
 485                  lon = lng,
 486                  radiusKm = radiusKm
 487              )
 488              val nowMs = System.currentTimeMillis()
 489              response.occurrences?.mapNotNull { occ ->
 490                  val olat = occ.decimalLatitude ?: return@mapNotNull null
 491                  val olng = occ.decimalLongitude ?: return@mapNotNull null
 492                  val obsTime = parseYearMonth(occ.year, occ.month)
 493                  // Herbarium specimens are research-grade by definition
 494                  val quality = if (occ.basisOfRecord == "PRESERVED_SPECIMEN") "research" else "needs_id"
 495                  Observation(
 496                      id = occ.uuid.hashCode().toLong(),
 497                      speciesId = species.id,
 498                      lat = olat, lng = olng,
 499                      observedAt = obsTime,
 500                      source = "ALA",
 501                      photoUrl = null,
 502                      qualityGrade = quality,
 503                      cachedAt = nowMs
 504                  )
 505              } ?: emptyList()
 506          } catch (e: Exception) {
 507              Log.w(TAG, "ALA fetch failed for ${species.scientificName}: ${e.message}")
 508              emptyList()
 509          }
 510      }
 511  
 512      // ─── GBIF (Global Biodiversity Information Facility) ──────────
 513  
 514      /**
 515       * Fetches occurrence records from GBIF for Australian fungi.
 516       * Includes museum/herbarium records with DOIs — highest scientific value.
 517       */
 518      suspend fun getGBIFObservations(
 519          species: Species,
 520          lat: Double,
 521          lng: Double,
 522          radiusKm: Double
 523      ): List<Observation> = withContext(Dispatchers.IO) {
 524          try {
 525              Log.d(TAG, "Fetching GBIF records for ${species.scientificName}")
 526              // GBIF uses bounding box via lat/lon ranges
 527              val latRange = radiusKm / 111.0
 528              val lngRange = radiusKm / (111.0 * cos(lat * PI / 180.0))
 529              val response = gbifApi.searchOccurrences(
 530                  scientificName = species.scientificName,
 531                  lat = "${String.format(Locale.US, "%.2f", lat - latRange)},${String.format(Locale.US, "%.2f", lat + latRange)}",
 532                  lon = "${String.format(Locale.US, "%.2f", lng - lngRange)},${String.format(Locale.US, "%.2f", lng + lngRange)}"
 533              )
 534              val nowMs = System.currentTimeMillis()
 535              response.results?.mapNotNull { occ ->
 536                  val olat = occ.decimalLatitude ?: return@mapNotNull null
 537                  val olng = occ.decimalLongitude ?: return@mapNotNull null
 538                  val obsTime = parseYearMonth(occ.year, occ.month)
 539                  val quality = if (occ.basisOfRecord == "PRESERVED_SPECIMEN") "research" else "needs_id"
 540                  Observation(
 541                      id = occ.key ?: occ.hashCode().toLong(),
 542                      speciesId = species.id,
 543                      lat = olat, lng = olng,
 544                      observedAt = obsTime,
 545                      source = "GBIF",
 546                      photoUrl = null,
 547                      qualityGrade = quality,
 548                      cachedAt = nowMs
 549                  )
 550              } ?: emptyList()
 551          } catch (e: Exception) {
 552              Log.w(TAG, "GBIF fetch failed for ${species.scientificName}: ${e.message}")
 553              emptyList()
 554          }
 555      }
 556  
 557      private fun parseYearMonth(year: Int?, month: Int?): Long {
 558          if (year == null) return System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000
 559          val cal = Calendar.getInstance()
 560          cal.set(Calendar.YEAR, year)
 561          cal.set(Calendar.MONTH, (month ?: 6) - 1)
 562          cal.set(Calendar.DAY_OF_MONTH, 15)
 563          return cal.timeInMillis
 564      }
 565  
 566      // ─── Weather Data ────────────────────────────────────────────────
 567  
 568      /**
 569       * Detailed weather data for the prediction engine.
 570       * Returns daily rainfall array (oldest first), avg max temp, avg min temp,
 571       * and total rainfall over the period.
 572       */
 573      data class WeatherData(
 574          val dailyRainfallMm: List<Double>,
 575          val totalRainfallMm: Double,
 576          val avgMaxTemp: Double,
 577          val avgMinTemp: Double,
 578          val avgTemp: Double,
 579          val avgSoilMoisture: Double? = null // volumetric m³/m³, 0-7cm layer; null if unavailable
 580      )
 581  
 582      suspend fun getDetailedWeather(lat: Double, lng: Double): WeatherData = withContext(Dispatchers.IO) {
 583          try {
 584              Log.d(TAG, "Fetching detailed weather from Open-Meteo for ($lat, $lng)")
 585              val response = openMeteoApi.getPastWeather(limitLat = lat, limitLng = lng)
 586  
 587              val rainfall = response.daily.precipitationSum
 588                  ?.map { it ?: 0.0 } ?: emptyList()
 589              val maxTemps = response.daily.temperatureMax
 590                  ?.mapNotNull { it } ?: emptyList()
 591              val minTemps = response.daily.temperatureMin
 592                  ?.mapNotNull { it } ?: emptyList()
 593  
 594              val totalRainfall = rainfall.sum()
 595              val avgMax = if (maxTemps.isNotEmpty()) maxTemps.average() else 15.0
 596              val avgMin = if (minTemps.isNotEmpty()) minTemps.average() else 8.0
 597  
 598              // Recent soil moisture (last ~7 days of hourly readings, 0-7cm layer).
 599              val soil = response.hourly?.soilMoisture0to7cm?.mapNotNull { it } ?: emptyList()
 600              val avgSoil = if (soil.isNotEmpty()) soil.takeLast(minOf(168, soil.size)).average() else null
 601  
 602              Log.d(TAG, "Weather (${rainfall.size} days): Rain: ${totalRainfall}mm, AvgMax: ${avgMax}C, AvgMin: ${avgMin}C, Soil: $avgSoil")
 603              WeatherData(rainfall, totalRainfall, avgMax, avgMin, (avgMax + avgMin) / 2.0, avgSoil)
 604          } catch (e: Exception) {
 605              Log.e(TAG, "Failed to fetch detailed weather, using Victorian autumn defaults", e)
 606              // Default: moderate Victorian autumn conditions
 607              val defaultDaily = List(45) { 3.0 } // ~3mm per day
 608              WeatherData(defaultDaily, 135.0, 15.5, 8.0, 11.75)
 609          }
 610      }
 611  
 612      // ── Session caches, keyed by a global ~500 m grid index ─────────────
 613      // Elevation and land cover are static; canopy/NDVI drift slowly. Caching
 614      // makes re-scoring the same area (species/radius changes, revisits, small
 615      // pans) instant and avoids repeat network / Earth-Engine cost.
 616      private val elevCache = java.util.concurrent.ConcurrentHashMap<Long, Double>()
 617      private data class EnvCell(
 618          val landcover: Int?, val canopyPct: Double?, val ndvi: Double?, val waterDistM: Double?,
 619          val soilPh: Double? = null, val soilSand: Double? = null,
 620          val soilMoisture: Double? = null, val twi: Double? = null, val forestType: Int? = null
 621      )
 622      private val envCache = java.util.concurrent.ConcurrentHashMap<Long, EnvCell>()
 623      // Deep Search uses its OWN session caches at a ~12 m snap. The overview caches
 624      // snap at ~250 m, which would collapse every fine sub-cell onto one value, so
 625      // a finer key is essential for the drill-down to actually resolve detail.
 626      private val deepElevCache = java.util.concurrent.ConcurrentHashMap<Long, Double>()
 627      private val deepEnvCache = java.util.concurrent.ConcurrentHashMap<Long, EnvCell>()
 628  
 629      /** Snap a coordinate to a global grid index so the same place always keys the
 630       *  same. [fine] uses a ~12 m snap (Deep Search, in separate caches); the
 631       *  default ~250 m snap backs the broad overview grid. The coarse formula is
 632       *  unchanged so existing on-disk caches stay valid. */
 633      private fun gridKey(lat: Double, lng: Double, fine: Boolean = false): Long {
 634          if (fine) {
 635              val la = Math.round(lat / 0.000108) + 40_000_000L
 636              val ln = Math.round(lng / 0.000137) + 40_000_000L
 637              return la * 100_000_000L + ln
 638          }
 639          val la = Math.round(lat / 0.00225) + 4_000_000L
 640          val ln = Math.round(lng / 0.00285) + 4_000_000L
 641          return la * 10_000_000L + ln
 642      }
 643  
 644      // ── Persistent (disk) backing for the session caches ────────────────
 645      // Elevation and land cover are static, so persisting them across app
 646      // restarts makes revisiting a region instant and free (no re-fetch). All
 647      // file I/O is guarded — on any error it silently behaves like in-memory.
 648      @Volatile private var cachesLoaded = false
 649      private val elevCacheFile by lazy { java.io.File(context.cacheDir, "elev_cache.tsv") }
 650      private val envCacheFile by lazy { java.io.File(context.cacheDir, "env_cache.tsv") }
 651      private val CACHE_MAX = 8000
 652  
 653      private fun ensureCachesLoaded() {
 654          if (cachesLoaded) return
 655          synchronized(this) {
 656              if (cachesLoaded) return
 657              try {
 658                  if (elevCacheFile.exists()) elevCacheFile.forEachLine { line ->
 659                      val p = line.split('\t')
 660                      if (p.size == 2) p[0].toLongOrNull()?.let { k -> p[1].toDoubleOrNull()?.let { v -> elevCache[k] = v } }
 661                  }
 662              } catch (e: Exception) { Log.w(TAG, "elev cache load failed: ${e.message}") }
 663              try {
 664                  if (envCacheFile.exists()) envCacheFile.forEachLine { line ->
 665                      val p = line.split('\t')
 666                      // ≥5 fields: core layers; ≥9 adds soil/moisture/twi; ≥10 adds
 667                      // forest_type. Older shorter rows still load (extras stay null).
 668                      if (p.size >= 5) p[0].toLongOrNull()?.let { k ->
 669                          envCache[k] = EnvCell(
 670                              p[1].toIntOrNull(), p[2].toDoubleOrNull(), p[3].toDoubleOrNull(), p[4].toDoubleOrNull(),
 671                              p.getOrNull(5)?.toDoubleOrNull(), p.getOrNull(6)?.toDoubleOrNull(),
 672                              p.getOrNull(7)?.toDoubleOrNull(), p.getOrNull(8)?.toDoubleOrNull(),
 673                              p.getOrNull(9)?.toIntOrNull()
 674                          )
 675                      }
 676                  }
 677              } catch (e: Exception) { Log.w(TAG, "env cache load failed: ${e.message}") }
 678              cachesLoaded = true
 679          }
 680      }
 681  
 682      private fun persistElevCache() {
 683          try {
 684              elevCacheFile.bufferedWriter().use { w ->
 685                  elevCache.entries.asSequence().take(CACHE_MAX).forEach { w.write("${it.key}\t${it.value}\n") }
 686              }
 687          } catch (e: Exception) { Log.w(TAG, "elev cache save failed: ${e.message}") }
 688      }
 689  
 690      private fun persistEnvCache() {
 691          try {
 692              envCacheFile.bufferedWriter().use { w ->
 693                  envCache.entries.asSequence().take(CACHE_MAX).forEach { (k, v) ->
 694                      w.write(
 695                          "$k\t${v.landcover ?: ""}\t${v.canopyPct ?: ""}\t${v.ndvi ?: ""}\t${v.waterDistM ?: ""}" +
 696                              "\t${v.soilPh ?: ""}\t${v.soilSand ?: ""}\t${v.soilMoisture ?: ""}\t${v.twi ?: ""}" +
 697                              "\t${v.forestType ?: ""}\n"
 698                      )
 699                  }
 700              }
 701          } catch (e: Exception) { Log.w(TAG, "env cache save failed: ${e.message}") }
 702      }
 703  
 704      /**
 705       * Fetches ground elevation for a list of coordinates, batched into
 706       * requests of ≤100 points (Open-Meteo's per-call limit) and served from a
 707       * session cache where possible. Returns a list aligned 1:1 with [coords];
 708       * entries are null where elevation could not be resolved, so callers can
 709       * fall back to neutral terrain scoring.
 710       */
 711      suspend fun fetchElevations(
 712          coords: List<Pair<Double, Double>>,
 713          fine: Boolean = false
 714      ): List<Double?> = withContext(Dispatchers.IO) {
 715          if (coords.isEmpty()) return@withContext emptyList()
 716          ensureCachesLoaded()
 717          val cache = if (fine) deepElevCache else elevCache
 718          val out = MutableList<Double?>(coords.size) { null }
 719          val missIdx = ArrayList<Int>()
 720          val missCoords = ArrayList<Pair<Double, Double>>()
 721          for (i in coords.indices) {
 722              val cached = cache[gridKey(coords[i].first, coords[i].second, fine)]
 723              if (cached != null) out[i] = cached else { missIdx.add(i); missCoords.add(coords[i]) }
 724          }
 725          if (missCoords.isNotEmpty()) {
 726              try {
 727                  val fetched = ArrayList<Double?>(missCoords.size)
 728                  for (chunk in missCoords.chunked(100)) {
 729                      val latCsv = chunk.joinToString(",") { String.format(Locale.US, "%.5f", it.first) }
 730                      val lngCsv = chunk.joinToString(",") { String.format(Locale.US, "%.5f", it.second) }
 731                      val resp = openMeteoApi.getElevation(latCsv, lngCsv)
 732                      val elevs = resp.elevation ?: emptyList()
 733                      for (k in chunk.indices) fetched.add(elevs.getOrNull(k))
 734                  }
 735                  for (k in missIdx.indices) {
 736                      val v = fetched.getOrNull(k)
 737                      out[missIdx[k]] = v
 738                      if (v != null) cache[gridKey(missCoords[k].first, missCoords[k].second, fine)] = v
 739                  }
 740              } catch (e: Exception) {
 741                  Log.w(TAG, "Elevation fetch failed, using neutral terrain: ${e.message}")
 742              }
 743              if (!fine) persistElevCache()
 744          }
 745          out
 746      }
 747  
 748      /**
 749       * Fetches canopy/green-cover features (woods, forests, parks, reserves)
 750       * within a bounding box from OpenStreetMap's Overpass API (free, no key).
 751       * Returns representative coordinates; empty on failure so callers fall
 752       * back to neutral canopy scoring.
 753       */
 754      suspend fun fetchCanopyFeatures(
 755          minLat: Double, minLng: Double, maxLat: Double, maxLng: Double
 756      ): List<Pair<Double, Double>> = withContext(Dispatchers.IO) {
 757          try {
 758              val bbox = String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", minLat, minLng, maxLat, maxLng)
 759              val ql = """
 760                  [out:json][timeout:25];
 761                  (
 762                    way["natural"="wood"]($bbox);
 763                    way["landuse"="forest"]($bbox);
 764                    way["leisure"="park"]($bbox);
 765                    way["boundary"="protected_area"]($bbox);
 766                  );
 767                  out center;
 768              """.trimIndent()
 769              val resp = overpassApi.query(ql)
 770              resp.elements.orEmpty().mapNotNull { el ->
 771                  val la = el.resolvedLat()
 772                  val lo = el.resolvedLon()
 773                  if (la != null && lo != null) la to lo else null
 774              }
 775          } catch (e: Exception) {
 776              Log.w(TAG, "Canopy (Overpass) fetch failed, neutral canopy: ${e.message}")
 777              emptyList()
 778          }
 779      }
 780  
 781      /**
 782       * Fetches tanbark / woodchip / mulch-bed features (centre points) in the
 783       * bbox from OpenStreetMap via Overpass. These are prime substrate for
 784       * wood-chip-loving fungi (gold tops etc.) and aren't resolvable from Earth
 785       * Engine land cover, so they're fetched separately — but only for
 786       * mulch-associated species (see [MycoMath.mulchAffinity]). Tags: explicit
 787       * `surface=woodchips|bark_mulch|tan`, `landuse=flowerbed|plant_nursery`,
 788       * `leisure=garden|playground` (play areas are commonly bark-mulched), and
 789       * garden centres. Returns empty on failure (graceful — just no bonus).
 790       */
 791      suspend fun fetchMulchFeatures(
 792          minLat: Double, minLng: Double, maxLat: Double, maxLng: Double
 793      ): List<Pair<Double, Double>> = withContext(Dispatchers.IO) {
 794          try {
 795              val bbox = String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", minLat, minLng, maxLat, maxLng)
 796              val ql = """
 797                  [out:json][timeout:25];
 798                  (
 799                    way["surface"~"woodchips|bark_mulch|tan"]($bbox);
 800                    way["landuse"="flowerbed"]($bbox);
 801                    way["landuse"="plant_nursery"]($bbox);
 802                    way["leisure"="garden"]($bbox);
 803                    way["leisure"="playground"]($bbox);
 804                    node["leisure"="playground"]($bbox);
 805                    way["shop"="garden_centre"]($bbox);
 806                  );
 807                  out center;
 808              """.trimIndent()
 809              val resp = overpassApi.query(ql)
 810              resp.elements.orEmpty().mapNotNull { el ->
 811                  val la = el.resolvedLat()
 812                  val lo = el.resolvedLon()
 813                  if (la != null && lo != null) la to lo else null
 814              }
 815          } catch (e: Exception) {
 816              Log.w(TAG, "Mulch (Overpass) fetch failed, no tanbark bonus: ${e.message}")
 817              emptyList()
 818          }
 819      }
 820  
 821      // ─── OSM land-use classification (offline habitat discrimination) ───
 822      //
 823      // When the Earth Engine backend isn't configured, this is what lets the map
 824      // actually tell good ground from bad. We pull real land-use *polygons* —
 825      // green space (forest/park/reserve/scrub/wetland…) and built-up
 826      // (residential/industrial/retail/parking/aerodrome…) — and classify each
 827      // grid cell by point-in-polygon. Green cells get a high habitat gate;
 828      // built-up cells are suppressed toward zero so suburbs go dark instead of
 829      // every cell scoring the same.
 830  
 831      /** A classified land-use ring. [ring] is a flat [lat0,lng0,lat1,lng1,…] array. */
 832      private class LandPolygon(
 833          val green: Boolean,
 834          val ring: DoubleArray,
 835          val minLat: Double, val minLng: Double, val maxLat: Double, val maxLng: Double
 836      )
 837  
 838      private enum class LandClass { GREEN, BUILT, NEUTRAL }
 839  
 840      private fun isGreenTags(tags: Map<String, String>?): Boolean {
 841          if (tags == null) return false
 842          if (tags["boundary"] == "protected_area") return true
 843          when (tags["leisure"]) { "park", "nature_reserve", "garden", "golf_course" -> return true }
 844          when (tags["natural"]) { "wood", "scrub", "heath", "grassland", "wetland", "scree" -> return true }
 845          when (tags["landuse"]) {
 846              "forest", "meadow", "recreation_ground", "village_green", "allotments", "orchard" -> return true
 847          }
 848          return false
 849      }
 850  
 851      private fun isBuiltTags(tags: Map<String, String>?): Boolean {
 852          if (tags == null) return false
 853          if (tags["amenity"] == "parking") return true
 854          if (tags["aeroway"] == "aerodrome") return true
 855          when (tags["landuse"]) {
 856              "residential", "industrial", "commercial", "retail",
 857              "construction", "garages", "railway", "quarry" -> return true
 858          }
 859          return false
 860      }
 861  
 862      /**
 863       * Fetches classified green / built-up land-use polygons in the bbox via
 864       * Overpass `out geom`. Returns empty on failure (callers fall back to the
 865       * canopy-proximity heuristic), so the map degrades gracefully.
 866       */
 867      private suspend fun fetchLandUsePolygons(
 868          minLat: Double, minLng: Double, maxLat: Double, maxLng: Double
 869      ): List<LandPolygon> = withContext(Dispatchers.IO) {
 870          try {
 871              val bbox = String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", minLat, minLng, maxLat, maxLng)
 872              val ql = """
 873                  [out:json][timeout:40];
 874                  (
 875                    way["leisure"~"park|nature_reserve|garden|golf_course"]($bbox);
 876                    way["natural"~"wood|scrub|heath|grassland|wetland|scree"]($bbox);
 877                    way["landuse"~"forest|meadow|recreation_ground|village_green|allotments|orchard"]($bbox);
 878                    way["boundary"="protected_area"]($bbox);
 879                    way["landuse"~"residential|industrial|commercial|retail|construction|garages|railway|quarry"]($bbox);
 880                    way["amenity"="parking"]($bbox);
 881                    way["aeroway"="aerodrome"]($bbox);
 882                  );
 883                  out geom;
 884              """.trimIndent()
 885              val resp = overpassApi.query(ql)
 886              resp.elements.orEmpty().mapNotNull { el ->
 887                  val geom = el.geometry ?: return@mapNotNull null
 888                  val green = isGreenTags(el.tags)
 889                  val built = isBuiltTags(el.tags)
 890                  // Green wins ties; ignore anything we can't classify.
 891                  if (!green && !built) return@mapNotNull null
 892                  val pts = geom.mapNotNull { p ->
 893                      val la = p.lat; val lo = p.lon
 894                      if (la != null && lo != null) la to lo else null
 895                  }
 896                  if (pts.size < 3) return@mapNotNull null
 897                  val ring = DoubleArray(pts.size * 2)
 898                  pts.forEachIndexed { k, (la, lo) -> ring[k * 2] = la; ring[k * 2 + 1] = lo }
 899                  LandPolygon(
 900                      green = green,
 901                      ring = ring,
 902                      minLat = pts.minOf { it.first }, minLng = pts.minOf { it.second },
 903                      maxLat = pts.maxOf { it.first }, maxLng = pts.maxOf { it.second }
 904                  )
 905              }
 906          } catch (e: Exception) {
 907              Log.w(TAG, "Land-use (Overpass) fetch failed, neutral habitat: ${e.message}")
 908              emptyList()
 909          }
 910      }
 911  
 912      /** Ray-casting point-in-polygon test. [ring] is flat [lat,lng,lat,lng,…]. */
 913      private fun pointInRing(lat: Double, lng: Double, ring: DoubleArray): Boolean {
 914          var inside = false
 915          val n = ring.size / 2
 916          var j = n - 1
 917          for (i in 0 until n) {
 918              val yi = ring[i * 2]; val xi = ring[i * 2 + 1]
 919              val yj = ring[j * 2]; val xj = ring[j * 2 + 1]
 920              if (((yi > lat) != (yj > lat)) &&
 921                  (lng < (xj - xi) * (lat - yi) / (yj - yi) + xi)
 922              ) inside = !inside
 923              j = i
 924          }
 925          return inside
 926      }
 927  
 928      /** Classify a cell against the land-use polygons; green takes priority. */
 929      private fun classifyLandCell(lat: Double, lng: Double, polys: List<LandPolygon>): LandClass {
 930          if (polys.isEmpty()) return LandClass.NEUTRAL
 931          var built = false
 932          for (p in polys) {
 933              if (lat < p.minLat || lat > p.maxLat || lng < p.minLng || lng > p.maxLng) continue
 934              if (pointInRing(lat, lng, p.ring)) {
 935                  if (p.green) return LandClass.GREEN
 936                  built = true
 937              }
 938          }
 939          return if (built) LandClass.BUILT else LandClass.NEUTRAL
 940      }
 941  
 942      /** Per-cell Earth Engine layers, aligned 1:1 with the grid points. */
 943      data class EnvLayers(
 944          val landcover: List<Int?>,
 945          val canopyPct: List<Double?>,
 946          val ndvi: List<Double?>,
 947          val waterDistM: List<Double?>,
 948          val soilPh: List<Double?>,
 949          val soilSand: List<Double?>,
 950          val soilMoisture: List<Double?>,
 951          val twi: List<Double?>,
 952          val forestType: List<Int?>
 953      )
 954  
 955      /**
 956       * Fetches Earth Engine land-cover / tree-canopy / NDVI for the grid from
 957       * the optional Cloud Run backend. Returns null when the backend isn't
 958       * configured or the call fails, so callers fall back to free OSM canopy.
 959       */
 960      suspend fun fetchEnvLayers(points: List<Pair<Double, Double>>, fine: Boolean = false): EnvLayers? {
 961          val api = envLayersApi ?: return null
 962          if (points.isEmpty()) return null
 963          return withContext(Dispatchers.IO) {
 964              ensureCachesLoaded()
 965              val cache = if (fine) deepEnvCache else envCache
 966              val landcover = arrayOfNulls<Int>(points.size)
 967              val canopyPct = arrayOfNulls<Double>(points.size)
 968              val ndvi = arrayOfNulls<Double>(points.size)
 969              val waterDist = arrayOfNulls<Double>(points.size)
 970              val soilPh = arrayOfNulls<Double>(points.size)
 971              val soilSand = arrayOfNulls<Double>(points.size)
 972              val soilMoisture = arrayOfNulls<Double>(points.size)
 973              val twi = arrayOfNulls<Double>(points.size)
 974              val forestType = arrayOfNulls<Int>(points.size)
 975              var haveAny = false
 976              val missIdx = ArrayList<Int>()
 977              val missPts = ArrayList<Pair<Double, Double>>()
 978              for (i in points.indices) {
 979                  val c = cache[gridKey(points[i].first, points[i].second, fine)]
 980                  if (c != null) {
 981                      landcover[i] = c.landcover; canopyPct[i] = c.canopyPct; ndvi[i] = c.ndvi; waterDist[i] = c.waterDistM
 982                      soilPh[i] = c.soilPh; soilSand[i] = c.soilSand; soilMoisture[i] = c.soilMoisture; twi[i] = c.twi
 983                      forestType[i] = c.forestType
 984                      haveAny = true
 985                  } else {
 986                      missIdx.add(i); missPts.add(points[i])
 987                  }
 988              }
 989              // Fetch misses in chunks — the backend caps a request at 600 points,
 990              // so a large search radius must be split or EE is lost for the grid.
 991              var k = 0
 992              while (k < missPts.size) {
 993                  val end = minOf(k + 500, missPts.size)
 994                  try {
 995                      val chunk = missPts.subList(k, end)
 996                      val resp = api.envGrid(backendToken, EnvGridRequest(chunk.map { listOf(it.first, it.second) }))
 997                      for (c in chunk.indices) {
 998                          val lc = resp.landcover?.getOrNull(c)?.toInt()
 999                          val cp = resp.canopy?.getOrNull(c)
1000                          val nv = resp.ndvi?.getOrNull(c)
1001                          val wd = resp.waterDist?.getOrNull(c)
1002                          val sph = resp.soilPh?.getOrNull(c)
1003                          val ssand = resp.soilSand?.getOrNull(c)
1004                          val smoist = resp.soilMoisture?.getOrNull(c)
1005                          val tw = resp.twi?.getOrNull(c)
1006                          val ft = resp.forestType?.getOrNull(c)?.toInt()
1007                          val orig = missIdx[k + c]
1008                          landcover[orig] = lc; canopyPct[orig] = cp; ndvi[orig] = nv; waterDist[orig] = wd
1009                          soilPh[orig] = sph; soilSand[orig] = ssand; soilMoisture[orig] = smoist; twi[orig] = tw
1010                          forestType[orig] = ft
1011                          cache[gridKey(missPts[k + c].first, missPts[k + c].second, fine)] =
1012                              EnvCell(lc, cp, nv, wd, sph, ssand, smoist, tw, ft)
1013                          haveAny = true
1014                      }
1015                  } catch (e: Exception) {
1016                      Log.w(TAG, "Earth Engine chunk fetch failed: ${e.message}")
1017                  }
1018                  k = end
1019              }
1020              if (!fine && missPts.isNotEmpty()) persistEnvCache()
1021              // Fall back to OSM canopy only if we got nothing at all.
1022              if (!haveAny) return@withContext null
1023              EnvLayers(
1024                  landcover.toList(), canopyPct.toList(), ndvi.toList(), waterDist.toList(),
1025                  soilPh.toList(), soilSand.toList(), soilMoisture.toList(), twi.toList(), forestType.toList()
1026              )
1027          }
1028      }
1029  
1030      /** A geocoded place: coordinates plus a human-readable label. */
1031      data class GeoPlace(val lat: Double, val lng: Double, val label: String)
1032  
1033      /**
1034       * Resolves a place name to coordinates for the map's "Area" search.
1035       * Prefers the Google Geocoding API when a key is configured (reliable),
1036       * and falls back to the on-device Android Geocoder otherwise.
1037       */
1038      suspend fun geocodePlace(query: String): GeoPlace? = withContext(Dispatchers.IO) {
1039          if (query.isBlank()) return@withContext null
1040          if (geocodingApi != null && googleApiKey.isNotBlank()) {
1041              try {
1042                  val resp = geocodingApi.geocode(query, googleApiKey)
1043                  val result = resp.results?.firstOrNull()
1044                  val loc = result?.geometry?.location
1045                  if (resp.status == "OK" && loc?.lat != null && loc.lng != null) {
1046                      return@withContext GeoPlace(loc.lat, loc.lng, result.formattedAddress ?: query)
1047                  }
1048                  Log.w(TAG, "Google geocoding returned status=${resp.status}; falling back")
1049              } catch (e: Exception) {
1050                  Log.w(TAG, "Google geocoding failed, trying device geocoder: ${e.message}")
1051              }
1052          }
1053          try {
1054              val geocoder = android.location.Geocoder(context, Locale.getDefault())
1055              @Suppress("DEPRECATION")
1056              val res = geocoder.getFromLocationName(query, 1)
1057              val a = res?.firstOrNull()
1058              if (a != null) GeoPlace(a.latitude, a.longitude, a.locality ?: a.subAdminArea ?: a.adminArea ?: query) else null
1059          } catch (e: Exception) {
1060              Log.w(TAG, "Device geocoder failed: ${e.message}")
1061              null
1062          }
1063      }
1064  
1065      /**
1066       * Reverse-geocodes coordinates to a short place name for the map header.
1067       * Google Geocoding when a key is set (reliable, descriptive), else the
1068       * on-device geocoder, else a GPS string.
1069       */
1070      suspend fun reverseGeocode(lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
1071          if (geocodingApi != null && googleApiKey.isNotBlank()) {
1072              try {
1073                  val resp = geocodingApi.reverseGeocode(String.format(Locale.US, "%.6f,%.6f", lat, lng), googleApiKey)
1074                  val label = resp.results?.firstOrNull()?.formattedAddress
1075                  if (resp.status == "OK" && !label.isNullOrBlank()) {
1076                      // Trim a verbose address to its first two components.
1077                      return@withContext label.split(",").take(2).joinToString(",").trim()
1078                  }
1079              } catch (e: Exception) {
1080                  Log.w(TAG, "Google reverse geocoding failed: ${e.message}")
1081              }
1082          }
1083          try {
1084              val geocoder = android.location.Geocoder(context, Locale.getDefault())
1085              @Suppress("DEPRECATION")
1086              val a = geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()
1087              if (a != null) {
1088                  val city = a.locality ?: a.subAdminArea ?: a.adminArea ?: ""
1089                  val country = a.countryCode ?: a.countryName ?: ""
1090                  listOf(city, country).filter { it.isNotEmpty() }.joinToString(", ").ifBlank { null }
1091              } else null
1092          } catch (e: Exception) {
1093              null
1094          }
1095      }
1096  
1097      /** Distance (m) to the nearest green feature, or null if none provided. */
1098      private fun nearestFeatureMeters(lat: Double, lng: Double, features: List<Pair<Double, Double>>): Double? {
1099          if (features.isEmpty()) return null
1100          var best = Double.MAX_VALUE
1101          for ((flat, flng) in features) {
1102              val d = calculateDistanceMeters(lat, lng, flat, flng)
1103              if (d < best) best = d
1104          }
1105          return best
1106      }
1107  
1108      /**
1109       * Fetches rainfall and temperature in past 30 days from Open-Meteo.
1110       * Returns total rainfall in mm, and average max recorded temperature in C.
1111       * (Legacy method — kept for weather summary display in UI)
1112       */
1113      suspend fun getWeatherLast30Days(lat: Double, lng: Double): Pair<Double, Double> = withContext(Dispatchers.IO) {
1114          try {
1115              Log.d(TAG, "Fetching rainfall and temperature from Open-Meteo for ($lat, $lng)")
1116              val response = openMeteoApi.getPastWeather(limitLat = lat, limitLng = lng)
1117              val sumList = response.daily.precipitationSum
1118              val maxList = response.daily.temperatureMax
1119  
1120              // Sum of last 30 days rainfall
1121              val totalRainfall = sumList?.filterNotNull()?.sum() ?: 0.0
1122  
1123              // Average max temp over past 30 days
1124              val avgMaxTemp = maxList?.filterNotNull()?.average() ?: 15.0
1125  
1126              Log.d(TAG, "Historical weather (last 30 days): Rainfall: $totalRainfall mm, Avg Max Temp: $avgMaxTemp C")
1127              return@withContext Pair(totalRainfall, avgMaxTemp)
1128          } catch (e: Exception) {
1129              Log.e(TAG, "Failed to fetch weather from Open-Meteo, using default fallback (95.0mm, 15.5C)", e)
1130              // Returning a typical Victorian autumn default which permits moderate fruiting
1131              return@withContext Pair(95.0, 15.5)
1132          }
1133      }
1134  
1135      /**
1136       * Clear all caches for management.
1137       */
1138      suspend fun clearCaches() = withContext(Dispatchers.IO) {
1139          dao.clearAllCachedObservations()
1140      }
1141  
1142      /**
1143       * Multi-factor Bayesian hotspot prediction engine (adaptive ~250m
1144       * resolution; cell size grows for very large radii to bound cost).
1145       *
1146       * Scoring factors and weights (sum to 1.0):
1147       *   1. Observation evidence (iNat + ALA + GBIF + user)          — 0.21
1148       *   2. Seasonal fitness (week-level precision)                 — 0.14
1149       *   3. Rainfall trigger (20mm+ event 10-21 days ago with lag)  — 0.11
1150       *   4. Canopy/forest (EE land cover/canopy/NDVI, or OSM)       — 0.08
1151       *   5. Habitat suitability (species substrate/habitat breadth) — 0.08
1152       *   6. Temperature fitness (species-specific ideal range)      — 0.06
1153       *   7. Host tree match (EE forest leaf-type vs mycorrhizal host)— 0.05
1154       *   8. Terrain moisture (per-cell slope/concavity from DEM)    — 0.05
1155       *   9. Elevation fitness (per-cell altitude vs species band)   — 0.05
1156       *  10. Soil (EE surface pH + texture, OpenLandMap)             — 0.04
1157       *  11. Background + per-cell soil moisture (rain + EE 14-day)  — 0.03
1158       *  12. Topographic Wetness Index (EE MERIT Hydro)             — 0.03
1159       *  13. Riparian (per-cell distance to surface water, EE)       — 0.03
1160       *  14. Slope aspect (per-cell; south/east-facing favoured)     — 0.03
1161       *  15. Moon phase (optional, traditional forager signal)       — 0.01
1162       *
1163       * Factors 4, 5, 7, 9 and 10 vary cell-to-cell (real elevation + EE/OSM),
1164       * so the map reflects genuine landscape instead of only record density.
1165       * The weighted score is then multiplied by a season/rain penalty AND a
1166       * habitat gate that collapses built-up/water/bare cells toward zero.
1167       */
1168      suspend fun generateHotspots(
1169          species: Species,
1170          centerLat: Double,
1171          centerLng: Double,
1172          radiusKm: Double,
1173          forceRefresh: Boolean = false
1174      ): List<HotspotCell> {
1175          // Kingdom-wide nearby fungal activity — a habitat-productivity floor on the
1176          // evidence factor, so areas thick with real sightings aren't rated
1177          // "Unlikely" just because the target species wasn't logged in that cell.
1178          val ambient = try {
1179              getAllFungiObservations(centerLat, centerLng, radiusKm, forceRefresh)
1180          } catch (e: Exception) {
1181              Log.w(TAG, "ambient fungi fetch failed: ${e.message}"); emptyList()
1182          }
1183          return runSpeciesGrid(
1184              species, centerLat, centerLng,
1185              halfExtentMeters = radiusKm * 1000.0,
1186              cellMeters = maxOf(250.0, radiusKm * 1000.0 / 60.0),
1187              obsRadiusKm = radiusKm,
1188              terrainSpacingM = 500.0,   // preserve the overview grid's terrain calibration
1189              circularClip = true,
1190              fine = false,
1191              forceRefresh = forceRefresh,
1192              ambientObs = ambient
1193          )
1194      }
1195  
1196      /**
1197       * Shared single-species scoring pipeline used by both the broad overview grid
1198       * (generateHotspots) and the fine Deep-Search sub-grid (deepSearchCell). Builds
1199       * a grid of [cellMeters] cells out to [halfExtentMeters] from the centre
1200       * (circular for the overview, square for deep), fetches per-cell terrain +
1201       * Earth-Engine layers ([fine] selects the ~12 m caches for deep), and runs the
1202       * full multi-factor scoring. [terrainSpacingM] feeds the scale-aware
1203       * terrain/aspect curves (500 m for the overview, the sub-cell size for deep).
1204       */
1205      private suspend fun runSpeciesGrid(
1206          species: Species,
1207          centerLat: Double,
1208          centerLng: Double,
1209          halfExtentMeters: Double,
1210          cellMeters: Double,
1211          obsRadiusKm: Double,
1212          terrainSpacingM: Double,
1213          circularClip: Boolean,
1214          fine: Boolean,
1215          forceRefresh: Boolean,
1216          // Kingdom-wide nearby fungal records (the "all fungi" sightings layer).
1217          // Used as a habitat-productivity floor on the evidence factor, so an area
1218          // rich in fungal activity scores up even if the TARGET species itself
1219          // hasn't been logged in this exact cell.
1220          ambientObs: List<MapObservation> = emptyList()
1221      ): List<HotspotCell> = withContext(Dispatchers.Default) {
1222          // ── 1. Gather all evidence sources ──────────────────────────
1223          val iNatObs = getObservations(species, centerLat, centerLng, obsRadiusKm, forceRefresh)
1224          val userSightings = dao.getAllUserSightings().filter {
1225              it.speciesId == species.id &&
1226              calculateDistanceMeters(centerLat, centerLng, it.lat, it.lng) <= obsRadiusKm * 1000.0
1227          }
1228          // ── 2. Fetch detailed weather for lag analysis ──────────────
1229          val weather = getDetailedWeather(centerLat, centerLng)
1230  
1231          // ── 3. Pre-compute global (non-cell-varying) factors ────────
1232          val nowMs = System.currentTimeMillis()
1233          val calendar = Calendar.getInstance()
1234          val currentMonth = calendar.get(Calendar.MONTH) + 1
1235          val dayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
1236  
1237          // 3a. Seasonal fitness (week-level, species-specific)
1238          val seasonScore = MycoMath.seasonalFitness(dayOfYear, species.seasonStart, species.seasonEnd)
1239  
1240          // 3b. Rainfall trigger (lag analysis: was there a trigger event 10-21 days ago?)
1241          val rainTriggerScore = MycoMath.rainfallTriggerScore(weather.dailyRainfallMm)
1242  
1243          // 3c. Temperature fitness (species-specific ideal range)
1244          val tempScore = MycoMath.temperatureFitness(weather.avgTemp, species.id)
1245  
1246          // 3d. Background moisture proxy (sustained rain, not just trigger events)
1247          val recentRain30d = weather.dailyRainfallMm.takeLast(minOf(30, weather.dailyRainfallMm.size)).sum()
1248          val rainMoistureScore = when {
1249              recentRain30d in 40.0..180.0 -> 1.0
1250              recentRain30d < 40.0 -> recentRain30d / 40.0
1251              else -> maxOf(0.3, 1.0 - (recentRain30d - 180.0) / 200.0)
1252          }
1253          // Blend in real 0-7cm soil moisture when available — a far better
1254          // dampness signal than rainfall totals alone.
1255          val soilMoistureScore = weather.avgSoilMoisture?.let { MycoMath.soilMoistureFitness(it) }
1256          val moistureScore = if (soilMoistureScore != null)
1257              (0.4 * rainMoistureScore + 0.6 * soilMoistureScore) else rainMoistureScore
1258  
1259          // 3e. Moon phase (low-weight traditional signal)
1260          val moonScore = MycoMath.moonFruitingScore(nowMs)
1261  
1262          // 3f. Habitat suitability (species breadth → higher baseline)
1263          val habitatScore = MycoMath.habitatDiversityScore(species.habitatTypes, species.substrates)
1264          val habitatWeight = MycoMath.speciesHabitatWeight(species.id)
1265          // Host tree groups for mycorrhizal matching against the EE forest-type
1266          // layer — derived once from the species' habitat/substrate descriptors.
1267          val hostGroups = MycoMath.hostGroupsFor(species.habitatTypes, species.substrates)
1268  
1269          // ── 4. Grid generation ──────────────────────────────────────
1270          // cellMeters / halfExtentMeters come from the caller: the overview uses an
1271          // adaptive ~250 m cell out to the search radius; Deep Search a fine sub-cell
1272          // over a single overview square.
1273          val latStep = cellMeters / 111_000.0
1274          val lngStep = cellMeters / (111_000.0 * Math.cos(Math.toRadians(centerLat)))
1275          val steps = ceil(halfExtentMeters / cellMeters).toInt()
1276          val cells = mutableListOf<HotspotCell>()
1277  
1278          val kernelRadiusMeters = 2500.0  // Wider search for evidence
1279          val maxDaysBack = 5.0 * 365.0
1280  
1281          // 4a. Enumerate the in-extent cells up front so the real terrain elevation
1282          // for the whole grid can be fetched in one batched call. The overview clips
1283          // to a circle (the search radius); Deep Search fills the square sub-area.
1284          val cellIJ = mutableListOf<Pair<Int, Int>>()
1285          val cellLL = mutableListOf<Pair<Double, Double>>()
1286          for (i in -steps..steps) {
1287              for (j in -steps..steps) {
1288                  val cLat = centerLat + i * latStep
1289                  val cLng = centerLng + j * lngStep
1290                  if (circularClip && calculateDistanceMeters(centerLat, centerLng, cLat, cLng) > halfExtentMeters) continue
1291                  cellIJ.add(i to j)
1292                  cellLL.add(cLat to cLng)
1293              }
1294          }
1295  
1296          // 4b. Per-cell ground elevation (Open-Meteo, no key). Build a lookup
1297          // so each cell can read its neighbours' elevations for slope/aspect.
1298          val elevations = fetchElevations(cellLL, fine)
1299          val elevByIJ = HashMap<Pair<Int, Int>, Double>()
1300          cellIJ.forEachIndexed { idx, ij -> elevations[idx]?.let { elevByIJ[ij] = it } }
1301  
1302          // 4c. Canopy/vegetation. Prefer Earth Engine layers (land cover, tree
1303          // canopy %, NDVI) when the backend is configured; otherwise fall back
1304          // to free OSM forest proximity.
1305          val env = fetchEnvLayers(cellLL, fine)
1306          val canopy = if (env == null && cellLL.isNotEmpty()) fetchCanopyFeatures(
1307              cellLL.minOf { it.first }, cellLL.minOf { it.second },
1308              cellLL.maxOf { it.first }, cellLL.maxOf { it.second }
1309          ) else emptyList()
1310          // Real land-use polygons drive habitat discrimination when EE is off.
1311          val landPolys = if (env == null && cellLL.isNotEmpty()) fetchLandUsePolygons(
1312              cellLL.minOf { it.first }, cellLL.minOf { it.second },
1313              cellLL.maxOf { it.first }, cellLL.maxOf { it.second }
1314          ) else emptyList()
1315          // Tanbark / woodchip beds (OSM) — only for mulch-associated species
1316          // (gold tops etc.), since neither EE land cover nor the green/built
1317          // land-use layer resolves garden-bed mulch. A strong, specific substrate
1318          // signal that lifts those species' score where they actually fruit.
1319          val speciesMulchAffinity = MycoMath.mulchAffinity(species.habitatTypes, species.substrates)
1320          val mulchFeatures = if (speciesMulchAffinity > 0.0 && cellLL.isNotEmpty()) fetchMulchFeatures(
1321              cellLL.minOf { it.first }, cellLL.minOf { it.second },
1322              cellLL.maxOf { it.first }, cellLL.maxOf { it.second }
1323          ) else emptyList()
1324  
1325          for (idx in cellIJ.indices) {
1326              coroutineContext.ensureActive()
1327              val (i, j) = cellIJ[idx]
1328              val (cellLat, cellLng) = cellLL[idx]
1329  
1330                  // ── A. Observation evidence score ───────────────────
1331                  var weightedEvidence = 0.0
1332                  var nearbyRecords = 0
1333                  val sourceCounts = mutableMapOf<String, Int>()
1334  
1335                  // All observations — weighted by quality, source, recency, proximity
1336                  for (obs in iNatObs) {
1337                      if (abs(obs.lat - cellLat) > 0.025 || abs(obs.lng - cellLng) > 0.035) continue
1338                      val d = calculateDistanceMeters(cellLat, cellLng, obs.lat, obs.lng)
1339                      if (d > kernelRadiusMeters) continue
1340                      val diffDays = (nowMs - obs.observedAt).toDouble() / (1000.0 * 60 * 60 * 24)
1341                      if (diffDays !in 0.0..maxDaysBack) continue
1342  
1343                      val quality = MycoMath.qualityWeight(obs.qualityGrade)
1344                      val sourceW = MycoMath.sourceWeight(obs.source)
1345                      val recency = MycoMath.recencyWeight(diffDays, halfLifeDays = 365.0)
1346                      val spatial = MycoMath.spatialKernel(d, sigma = 800.0)
1347                      weightedEvidence += quality * sourceW * recency * spatial
1348                      nearbyRecords++
1349                      sourceCounts[obs.source] = (sourceCounts[obs.source] ?: 0) + 1
1350                  }
1351  
1352                  // User sightings — higher trust (first-hand, georeferenced)
1353                  for (sig in userSightings) {
1354                      if (abs(sig.lat - cellLat) > 0.025 || abs(sig.lng - cellLng) > 0.035) continue
1355                      val d = calculateDistanceMeters(cellLat, cellLng, sig.lat, sig.lng)
1356                      if (d > kernelRadiusMeters) continue
1357                      val diffDays = (nowMs - sig.timestamp).toDouble() / (1000.0 * 60 * 60 * 24)
1358                      if (diffDays !in 0.0..maxDaysBack) continue
1359  
1360                      val recency = MycoMath.recencyWeight(diffDays, halfLifeDays = 365.0)
1361                      val spatial = MycoMath.spatialKernel(d, sigma = 800.0)
1362                      weightedEvidence += 1.5 * recency * spatial // 1.5x for first-hand
1363                      nearbyRecords++
1364                  }
1365  
1366                  // Saturate: ~4 strong, recent, nearby records max out evidence
1367                  val observationScore = minOf(1.0, weightedEvidence / 4.0)
1368  
1369                  // Ambient fungal activity: nearby records of ANY fungus (the
1370                  // kingdom-wide layer shown as sightings on the map) indicate
1371                  // productive, fruiting habitat. They provide a modest evidence
1372                  // FLOOR even when the target species itself isn't logged in this
1373                  // exact cell — a weaker, non-species-specific proxy, so it's
1374                  // capped well below a direct hit and never overrides real evidence.
1375                  var ambientWeighted = 0.0
1376                  for (obs in ambientObs) {
1377                      if (abs(obs.lat - cellLat) > 0.025 || abs(obs.lng - cellLng) > 0.035) continue
1378                      val d = calculateDistanceMeters(cellLat, cellLng, obs.lat, obs.lng)
1379                      if (d > kernelRadiusMeters) continue
1380                      val diffDays = (nowMs - obs.observedAt).toDouble() / (1000.0 * 60 * 60 * 24)
1381                      if (diffDays !in 0.0..maxDaysBack) continue
1382                      ambientWeighted += MycoMath.recencyWeight(diffDays, halfLifeDays = 365.0) *
1383                          MycoMath.spatialKernel(d, sigma = 1000.0)
1384                  }
1385                  val ambientActivity = minOf(1.0, ambientWeighted / 5.0)
1386                  // Direct target-species evidence wins; ambient only raises a floor.
1387                  val evidenceScore = maxOf(observationScore, 0.45 * ambientActivity)
1388  
1389                  // ── B. Per-cell terrain factors (real elevation) ────
1390                  // Elevation fitness and local slope/concavity vary cell-to-
1391                  // cell, so the map reflects genuine landscape rather than
1392                  // only observation-record density.
1393                  val cellElev = elevations[idx]
1394                  val neighbourElevs = listOf(
1395                      (i - 1) to j, (i + 1) to j, i to (j - 1), i to (j + 1),
1396                      (i - 1) to (j - 1), (i + 1) to (j + 1), (i - 1) to (j + 1), (i + 1) to (j - 1)
1397                  ).mapNotNull { elevByIJ[it] }
1398                  val elevationScore = if (cellElev != null)
1399                      MycoMath.elevationFitness(cellElev, species.id) else 0.6
1400                  val terrainScore = if (cellElev != null && neighbourElevs.isNotEmpty())
1401                      MycoMath.terrainMoistureScore(cellElev, neighbourElevs, terrainSpacingM) else 0.5
1402                  // Slope aspect (south/east-facing favoured in S. Hemisphere).
1403                  val aspectScore = if (cellElev != null) MycoMath.slopeAspectMoistureScore(
1404                      cellElev,
1405                      elevByIJ[(i + 1) to j], elevByIJ[(i - 1) to j],
1406                      elevByIJ[i to (j + 1)], elevByIJ[i to (j - 1)],
1407                      cellSpacingM = terrainSpacingM
1408                  ) else 0.7
1409                  // Canopy/vegetation suitability — Earth Engine layers when
1410                  // available, else OSM forest proximity (mycorrhizal & wood-rot).
1411                  val canopyDist = if (canopy.isEmpty()) null else nearestFeatureMeters(cellLat, cellLng, canopy)
1412                  val landClass = if (env == null) classifyLandCell(cellLat, cellLng, landPolys) else LandClass.NEUTRAL
1413                  val canopyScore = if (env != null) MycoMath.richCanopyScore(
1414                      env.canopyPct.getOrNull(idx), env.ndvi.getOrNull(idx), env.landcover.getOrNull(idx), species.id
1415                  ) else when (landClass) {
1416                      LandClass.GREEN -> maxOf(0.85, MycoMath.canopyProximityScore(canopyDist))
1417                      LandClass.BUILT -> 0.10
1418                      LandClass.NEUTRAL -> MycoMath.canopyProximityScore(canopyDist)
1419                  }
1420                  // Riparian: closeness to surface water (EE only; neutral otherwise).
1421                  val riparianScore = if (env != null) MycoMath.riparianScore(env.waterDistM.getOrNull(idx)) else 0.45
1422                  // Soil (pH + texture) and topographic wetness — Earth Engine only;
1423                  // neutral when the backend isn't configured (no penalty).
1424                  val soilScore = if (env != null)
1425                      MycoMath.richSoilScore(env.soilPh.getOrNull(idx), env.soilSand.getOrNull(idx)) else 0.6
1426                  val twiScore = if (env != null) MycoMath.twiWetnessScore(env.twi.getOrNull(idx)) else 0.5
1427                  // Host-tree (mycorrhizal) match: does this cell's forest leaf-type
1428                  // contain one of the species' host trees? Neutral when EE is off.
1429                  val hostTreeScore = if (env != null)
1430                      MycoMath.hostTreeMatchScore(env.forestType.getOrNull(idx), hostGroups) else 0.6
1431                  // Per-cell soil moisture (EE 14-day mean) blended with the area-wide
1432                  // rain/soil moisture signal, for real per-cell differentiation.
1433                  val cellSoilMoisture = env?.soilMoisture?.getOrNull(idx)?.let { MycoMath.soilMoistureFitness(it) }
1434                  val moistureScoreCell = if (cellSoilMoisture != null)
1435                      (0.5 * moistureScore + 0.5 * cellSoilMoisture) else moistureScore
1436  
1437                  // ── C. Weighted factor combination ──────────────────
1438                  // Evidence stays dominant; terrain, elevation, aspect and
1439                  // canopy give real per-cell differentiation alongside the
1440                  // global climate factors. Weights sum to 1.0.
1441                  // Tanbark / woodchip signal: how close this cell is to a mapped
1442                  // mulch bed, scaled by the species' mulch affinity (0 for forest
1443                  // fungi → no effect). For gold tops & co. a woodchip bed under
1444                  // foot is prime substrate, so it lifts the habitat factor and the
1445                  // habitat gate (so urban mulch beds aren't suppressed as "built-up").
1446                  val mulchDist = if (mulchFeatures.isEmpty()) null
1447                      else nearestFeatureMeters(cellLat, cellLng, mulchFeatures)
1448                  val mulchSignal = speciesMulchAffinity * MycoMath.mulchProximityScore(mulchDist)
1449                  val adjustedHabitat = maxOf(
1450                      (habitatScore * habitatWeight).coerceIn(0.0, 1.0),
1451                      mulchSignal
1452                  )
1453  
1454                  // Per-cell factor scores combined with the canonical, shared
1455                  // weights (MycoMath.FACTOR_WEIGHTS) so this and the aggregate
1456                  // pipeline can never drift apart.
1457                  val factorScores = mapOf(
1458                      "evidence"    to evidenceScore,
1459                      "season"      to seasonScore,
1460                      "rainTrigger" to rainTriggerScore,
1461                      "canopy"      to canopyScore,
1462                      "hostTree"    to hostTreeScore,
1463                      "terrain"     to terrainScore,
1464                      "habitat"     to adjustedHabitat,
1465                      "elevation"   to elevationScore,
1466                      "temperature" to tempScore,
1467                      "riparian"    to riparianScore,
1468                      "aspect"      to aspectScore,
1469                      "moisture"    to moistureScoreCell,
1470                      "soil"        to soilScore,
1471                      "twi"         to twiScore,
1472                      "moon"        to moonScore
1473                  )
1474  
1475                  // Weighted sum with multiplicative penalty:
1476                  // If season OR rain trigger is very low, cap the score —
1477                  // you won't find fungi out of season in dry conditions
1478                  // regardless of historical evidence.
1479                  val weightedSum = MycoMath.weightedFactorScore(factorScores)
1480                  // Conditions modifier — driven by ACTUAL ground wetness (recent
1481                  // rain trigger + soil moisture), not the calendar. Fungi fruit
1482                  // after rain regardless of the textbook season, and a shifting
1483                  // climate makes those windows unreliable, so this keys off real
1484                  // moisture; the calendar season is only a light weighted factor
1485                  // above, never a gate. Range 0.55 (bone-dry) .. 1.0 (wet).
1486                  val fruitingConditions = maxOf(rainTriggerScore, 0.85 * moistureScore)
1487                  val penaltyMultiplier = (0.55 + 0.45 * fruitingConditions).coerceIn(0.0, 1.0)
1488  
1489                  // Multiplicative HABITAT GATE — built-up/water/bare collapse the
1490                  // score toward zero so cities, roads and car parks can't rank
1491                  // high no matter how good the weather or how many records cluster
1492                  // there. Uses real EE land cover/NDVI when available, else an OSM
1493                  // canopy-proximity fallback.
1494                  val rawGate = if (env != null)
1495                      MycoMath.habitatGate(env.landcover.getOrNull(idx), env.ndvi.getOrNull(idx), species.id)
1496                  else when (landClass) {
1497                      LandClass.GREEN -> 0.95
1498                      LandClass.BUILT -> 0.10
1499                      LandClass.NEUTRAL -> (0.45 + 0.40 * MycoMath.canopyProximityScore(canopyDist))
1500                  }
1501                  // A mulch-loving species sitting in a mapped tanbark/woodchip bed
1502                  // genuinely fruits there even on "built-up" ground, so the bed
1503                  // lifts the gate floor (never lowers it) — keeping gold tops in
1504                  // suburban garden beds on the map instead of gated to zero.
1505                  val habitatGate = if (mulchSignal > 0.0)
1506                      maxOf(rawGate, (0.50 + 0.45 * mulchSignal).coerceAtMost(1.0)) else rawGate
1507  
1508                  val finalScore = (weightedSum * penaltyMultiplier * habitatGate).coerceIn(0.0, 1.0)
1509  
1510                  // ── D. 5-tier classification ────────────────────────
1511                  val tier = MycoMath.classifyTier(finalScore)
1512  
1513                  // ── E. Contributing factors (human-readable) ────────
1514                  val factors = mutableListOf<String>()
1515                  factors.add("📍 Cell (${String.format(Locale.US, "%.4f", cellLat)}, ${String.format(Locale.US, "%.4f", cellLng)})")
1516  
1517                  // Source breakdown badges
1518                  val sourceStr = sourceCounts.entries.joinToString(" | ") { "${it.key}: ${it.value}" }
1519                  val totalSources = if (sourceStr.isNotEmpty()) " [$sourceStr]" else ""
1520                  factors.add("🔬 Evidence: $nearbyRecords record(s) within 2.5 km$totalSources → ${String.format(Locale.US, "%.0f", observationScore * 100)}%")
1521                  if (0.45 * ambientActivity > observationScore && ambientActivity > 0.0)
1522                      factors.add("🍄 Fungal activity nearby: lots of recorded sightings → evidence floor ${String.format(Locale.US, "%.0f", evidenceScore * 100)}%")
1523  
1524                  factors.add("📅 Season: ${if (seasonScore > 0.5) "In window" else "Outside/shoulder"} ${monthName(species.seasonStart)}–${monthName(species.seasonEnd)} → ${String.format(Locale.US, "%.0f", seasonScore * 100)}%")
1525                  factors.add("🌧️ Rain trigger: ${if (rainTriggerScore > 0.5) "Trigger event detected" else "No strong trigger"} (10-21d lag) → ${String.format(Locale.US, "%.0f", rainTriggerScore * 100)}%")
1526                  factors.add("🌡️ Temperature: avg ${String.format(Locale.US, "%.1f", weather.avgTemp)}°C → ${String.format(Locale.US, "%.0f", tempScore * 100)}% fit for ${species.scientificName}")
1527                  factors.add("🌲 Habitat: ${species.habitatTypes.joinToString(", ")} → ${String.format(Locale.US, "%.0f", adjustedHabitat * 100)}%")
1528                  factors.add("🪵 Substrate: ${species.substrates.joinToString(", ")}")
1529                  if (mulchSignal > 0.05) factors.add(
1530                      "🌰 Tanbark/woodchip bed ${if (mulchDist != null) "~${String.format(Locale.US, "%.0f m", mulchDist)} away" else "nearby"} — prime mulch substrate → habitat lifted to ${String.format(Locale.US, "%.0f", adjustedHabitat * 100)}%"
1531                  )
1532                  factors.add("⛰️ Elevation: ${if (cellElev != null) String.format(Locale.US, "%.0f m", cellElev) else "n/a"} → ${String.format(Locale.US, "%.0f", elevationScore * 100)}% fit")
1533                  factors.add("🏞️ Terrain (slope/moisture): ${String.format(Locale.US, "%.0f", terrainScore * 100)}% | Aspect: ${String.format(Locale.US, "%.0f", aspectScore * 100)}%")
1534                  if (env != null) {
1535                      val cv = env.canopyPct.getOrNull(idx)
1536                      val nv = env.ndvi.getOrNull(idx)
1537                      factors.add("🛰️ Earth Engine: canopy ${if (cv != null) String.format(Locale.US, "%.0f%%", cv) else "n/a"}, NDVI ${if (nv != null) String.format(Locale.US, "%.2f", nv) else "n/a"} → ${String.format(Locale.US, "%.0f", canopyScore * 100)}%")
1538                  } else {
1539                      factors.add(when (landClass) {
1540                          LandClass.GREEN -> "🌳 Green space (park / forest / reserve) → strong habitat → ${String.format(Locale.US, "%.0f", canopyScore * 100)}%"
1541                          LandClass.BUILT -> "🏙️ Built-up land (residential / industrial / car park) → habitat suppressed → ${String.format(Locale.US, "%.0f", canopyScore * 100)}%"
1542                          LandClass.NEUTRAL -> "🌳 Canopy: ${if (canopyDist != null) "~${String.format(Locale.US, "%.0f m", canopyDist)} to woodland" else "no land-use map data"} → ${String.format(Locale.US, "%.0f", canopyScore * 100)}%"
1543                      })
1544                  }
1545                  if (env != null) {
1546                      val wd = env.waterDistM.getOrNull(idx)
1547                      factors.add("🌊 Water: ${if (wd != null) "~${String.format(Locale.US, "%.0f m", wd)} to water" else ">2 km away"} → ${String.format(Locale.US, "%.0f", riparianScore * 100)}%")
1548                      val ph = env.soilPh.getOrNull(idx)
1549                      val sand = env.soilSand.getOrNull(idx)
1550                      if (ph != null || sand != null) factors.add(
1551                          "🧪 Soil: ${if (ph != null) "pH ${String.format(Locale.US, "%.1f", ph)}" else "pH n/a"}, ${if (sand != null) "${String.format(Locale.US, "%.0f", sand)}% sand" else "texture n/a"} → ${String.format(Locale.US, "%.0f", soilScore * 100)}%"
1552                      )
1553                      env.soilMoisture.getOrNull(idx)?.let { sm ->
1554                          factors.add("💧 Soil moisture (14-day): ${String.format(Locale.US, "%.2f", sm)} m³/m³ → ${String.format(Locale.US, "%.0f", MycoMath.soilMoistureFitness(sm) * 100)}%")
1555                      }
1556                      env.twi.getOrNull(idx)?.let { tw ->
1557                          factors.add("🏞️ Wetness index (TWI): ${String.format(Locale.US, "%.1f", tw)} → ${String.format(Locale.US, "%.0f", twiScore * 100)}%")
1558                      }
1559                      if (hostGroups.isNotEmpty()) factors.add(
1560                          "🌲 Host tree: ${forestTypeLabel(env.forestType.getOrNull(idx))} vs ${hostGroups.joinToString("/") { hostGroupLabel(it) }} → ${String.format(Locale.US, "%.0f", hostTreeScore * 100)}%"
1561                      )
1562                  }
1563                  if (env == null) weather.avgSoilMoisture?.let { factors.add("💧 Soil moisture: ${String.format(Locale.US, "%.2f", it)} m³/m³ → ${String.format(Locale.US, "%.0f", MycoMath.soilMoistureFitness(it) * 100)}%") }
1564                  if (moonScore > 0.7) factors.add("🌙 Moon phase favourable (traditional signal)")
1565                  if (habitatGate < 0.95) factors.add("⛔ Habitat gate ×${String.format(Locale.US, "%.2f", habitatGate)} — built-up/water/bare ground suppresses this cell")
1566                  val confidence = MycoMath.predictionConfidence(nearbyRecords, weightedEvidence, env != null, cellElev != null)
1567                  factors.add("📶 Confidence: ${MycoMath.confidenceLabel(confidence)} — $nearbyRecords nearby record(s), ${if (env != null) "full" else "limited"} map data")
1568                  factors.add("Multi-factor Bayesian estimate — not a guarantee of presence.")
1569  
1570                  cells.add(HotspotCell(cellLat, cellLng, finalScore, tier, factors, cellSizeMeters = cellMeters, confidence = confidence))
1571          }
1572          return@withContext cells
1573      }
1574  
1575      // ── Deep Search (two-tier drill-down) ───────────────────────────────
1576      // In-memory cache of fine sub-grid results, keyed by parent cell + resolution.
1577      private val deepCache = java.util.concurrent.ConcurrentHashMap<String, List<HotspotCell>>()
1578  
1579      /**
1580       * Refines a single promising overview cell into a fine (~[subResolutionMeters] m)
1581       * sub-grid for pinpoint foraging, WITHOUT touching the broad overview grid.
1582       * Reuses the exact single-species scoring pipeline (runSpeciesGrid) at a finer
1583       * cell size over a small area — the parent cell grown by [extentFactor] (≈300–600 m
1584       * for a 250 m parent at factor 2). The sub-cell count is capped (~1500) by
1585       * coarsening the resolution when the area is large, and the fine terrain/Earth
1586       * Engine samples use the dedicated ~12 m caches. Results are memoised per
1587       * parent cell + resolution so re-tapping a square is instant.
1588       *
1589       * @param parentRadiusKm the overview search radius the parent cell came from —
1590       *   used only as a fallback if the cell predates the cellSizeMeters field.
1591       */
1592      suspend fun deepSearchCell(
1593          species: Species,
1594          parentCell: HotspotCell,
1595          parentRadiusKm: Double,
1596          subResolutionMeters: Double = 15.0,
1597          extentFactor: Double = 2.0
1598      ): List<HotspotCell> {
1599          val parentCellMeters = parentCell.cellSizeMeters.takeIf { it > 0.0 }
1600              ?: maxOf(250.0, parentRadiusKm * 1000.0 / 60.0)
1601          val extentMeters = parentCellMeters * extentFactor.coerceIn(1.0, 4.0)
1602          // Adaptive coarsening: keep (extent/cell)² under the cell cap.
1603          val maxSubCells = 1500
1604          val minCell = extentMeters / sqrt(maxSubCells.toDouble())
1605          val cell = maxOf(subResolutionMeters, minCell)
1606  
1607          // Key by species too: env/elevation are species-independent (shared fine
1608          // caches), but the SCORED result is per-species, so two species drilled
1609          // into the same square must not share a cached result.
1610          val key = "${species.id}@${gridKey(parentCell.lat, parentCell.lng)}@${cell.toInt()}"
1611          deepCache[key]?.let { return it }
1612  
1613          val result = runSpeciesGrid(
1614              species, parentCell.lat, parentCell.lng,
1615              halfExtentMeters = extentMeters / 2.0,
1616              cellMeters = cell,
1617              // Cover the 2.5 km evidence kernel around the parent, not just the cell.
1618              obsRadiusKm = maxOf(3.0, extentMeters / 2.0 / 1000.0 + 2.6),
1619              terrainSpacingM = cell,    // fine-scale terrain/aspect discrimination
1620              circularClip = false,      // fill the square sub-area
1621              fine = true,               // use the ~12 m elevation/Earth-Engine caches
1622              forceRefresh = false
1623          )
1624          deepCache[key] = result
1625          return result
1626      }
1627  
1628      /**
1629       * Multi-species aggregate hotspot scoring.
1630       *
1631       * Answers "where am I likely to find ANY fungi here?" — combining
1632       * evidence and environmental factors across the entire catalogue.
1633       * Diversity (how many species are recorded nearby) is a first-class
1634       * signal that differentiates genuinely rich sites from monoculture spots.
1635       */
1636      suspend fun generateMultiSpeciesHotspots(
1637          centerLat: Double,
1638          centerLng: Double,
1639          radiusKm: Double,
1640          forceRefresh: Boolean = false
1641      ): List<HotspotCell> = withContext(Dispatchers.Default) {
1642          val allSpecies = dao.getAllSpecies()
1643          if (allSpecies.isEmpty()) return@withContext emptyList()
1644  
1645          // Pull observations for every catalogue species — in bounded-parallel
1646          // batches (not one-at-a-time) so the aggregate map isn't gated on 40
1647          // sequential round trips. Each failure is isolated and logged.
1648          val allObservations = mutableListOf<Observation>()
1649          for (batch in allSpecies.chunked(6)) {
1650              coroutineContext.ensureActive()
1651              val fetched = coroutineScope {
1652                  batch.map { species ->
1653                      async {
1654                          try {
1655                              getObservations(species, centerLat, centerLng, radiusKm, forceRefresh)
1656                          } catch (e: Exception) {
1657                              Log.w(TAG, "Skipping ${species.scientificName} in aggregate fetch: ${e.message}")
1658                              emptyList()
1659                          }
1660                      }
1661                  }.awaitAll()
1662              }
1663              fetched.forEach { allObservations.addAll(it) }
1664          }
1665  
1666          // Fold in EVERY nearby fungal sighting (kingdom-wide, incl. species not
1667          // in the catalogue) so the aggregate prediction reflects real fungal
1668          // activity on the ground, not only our 40 species. Deduped by iNat id
1669          // against the per-species records above.
1670          val seenIds = allObservations.map { it.id }.toHashSet()
1671          try {
1672              getAllFungiObservations(centerLat, centerLng, radiusKm, forceRefresh).forEach { m ->
1673                  if (seenIds.add(m.id)) {
1674                      allObservations.add(
1675                          Observation(
1676                              id = m.id,
1677                              speciesId = m.taxonName,
1678                              lat = m.lat,
1679                              lng = m.lng,
1680                              observedAt = m.observedAt,
1681                              source = m.source,
1682                              photoUrl = m.photoUrl,
1683                              qualityGrade = m.qualityGrade,
1684                              cachedAt = System.currentTimeMillis()
1685                          )
1686                      )
1687                  }
1688              }
1689          } catch (e: Exception) {
1690              Log.w(TAG, "aggregate all-fungi enrichment failed: ${e.message}")
1691          }
1692          val userSightings = dao.getAllUserSightings().filter {
1693              calculateDistanceMeters(centerLat, centerLng, it.lat, it.lng) <= radiusKm * 1000.0
1694          }
1695          // Detailed weather for lag analysis
1696          val weather = getDetailedWeather(centerLat, centerLng)
1697  
1698          val nowMs = System.currentTimeMillis()
1699          val calendar = Calendar.getInstance()
1700          val currentMonth = calendar.get(Calendar.MONTH) + 1
1701          val dayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
1702  
1703          // Aggregate seasonal fitness: fraction of catalogue currently in season
1704          val inSeasonCount = allSpecies.count { isMonthInSeason(currentMonth, it.seasonStart, it.seasonEnd) }
1705          val inSeasonFraction = inSeasonCount.toDouble() / allSpecies.size
1706          val seasonScore = (0.3 + 0.7 * inSeasonFraction).coerceIn(0.0, 1.0)
1707  
1708          // Weather factors (shared across grid)
1709          val rainTriggerScore = MycoMath.rainfallTriggerScore(weather.dailyRainfallMm)
1710          val tempScore = MycoMath.temperatureFitness(weather.avgTemp, "aggregate_default")
1711          val recentRain30d = weather.dailyRainfallMm.takeLast(minOf(30, weather.dailyRainfallMm.size)).sum()
1712          val rainMoistureScore = when {
1713              recentRain30d in 40.0..180.0 -> 1.0
1714              recentRain30d < 40.0 -> recentRain30d / 40.0
1715              else -> maxOf(0.3, 1.0 - (recentRain30d - 180.0) / 200.0)
1716          }
1717          val soilMoistureScore = weather.avgSoilMoisture?.let { MycoMath.soilMoistureFitness(it) }
1718          val moistureScore = if (soilMoistureScore != null)
1719              (0.4 * rainMoistureScore + 0.6 * soilMoistureScore) else rainMoistureScore
1720          val moonScore = MycoMath.moonFruitingScore(nowMs)
1721  
1722          // Adaptive cell size: ~250 m for typical searches (fine per-cell detail),
1723          // growing only for very large radii so the cell count — and Earth Engine
1724          // cost — stays bounded (~60 steps per axis max).
1725          val cellMeters = maxOf(250.0, radiusKm * 1000.0 / 60.0)
1726          val latStep = cellMeters / 111_000.0
1727          val lngStep = cellMeters / (111_000.0 * Math.cos(Math.toRadians(centerLat)))
1728          val latRangeSteps = ceil((radiusKm * 1000.0) / cellMeters).toInt()
1729          val cells = mutableListOf<HotspotCell>()
1730          val kernelRadiusMeters = 2500.0
1731          val maxDaysBack = 5.0 * 365.0
1732  
1733          // Enumerate in-radius cells, then batch-fetch real terrain elevation.
1734          val cellIJ = mutableListOf<Pair<Int, Int>>()
1735          val cellLL = mutableListOf<Pair<Double, Double>>()
1736          for (i in -latRangeSteps..latRangeSteps) {
1737              for (j in -latRangeSteps..latRangeSteps) {
1738                  val cLat = centerLat + i * latStep
1739                  val cLng = centerLng + j * lngStep
1740                  if (calculateDistanceMeters(centerLat, centerLng, cLat, cLng) > radiusKm * 1000.0) continue
1741                  cellIJ.add(i to j)
1742                  cellLL.add(cLat to cLng)
1743              }
1744          }
1745          val elevations = fetchElevations(cellLL)
1746          val elevByIJ = HashMap<Pair<Int, Int>, Double>()
1747          cellIJ.forEachIndexed { idx, ij -> elevations[idx]?.let { elevByIJ[ij] = it } }
1748  
1749          val env = fetchEnvLayers(cellLL)
1750          val canopy = if (env == null && cellLL.isNotEmpty()) fetchCanopyFeatures(
1751              cellLL.minOf { it.first }, cellLL.minOf { it.second },
1752              cellLL.maxOf { it.first }, cellLL.maxOf { it.second }
1753          ) else emptyList()
1754          // Real land-use polygons drive habitat discrimination when EE is off.
1755          val landPolys = if (env == null && cellLL.isNotEmpty()) fetchLandUsePolygons(
1756              cellLL.minOf { it.first }, cellLL.minOf { it.second },
1757              cellLL.maxOf { it.first }, cellLL.maxOf { it.second }
1758          ) else emptyList()
1759  
1760          for (idx in cellIJ.indices) {
1761              coroutineContext.ensureActive()
1762              val (i, j) = cellIJ[idx]
1763              val (cellLat, cellLng) = cellLL[idx]
1764  
1765                  var weightedEvidence = 0.0
1766                  var nearbyRecords = 0
1767                  val nearbySpecies = mutableSetOf<String>()
1768  
1769                  for (obs in allObservations) {
1770                      if (abs(obs.lat - cellLat) > 0.025 || abs(obs.lng - cellLng) > 0.035) continue
1771                      val d = calculateDistanceMeters(cellLat, cellLng, obs.lat, obs.lng)
1772                      if (d > kernelRadiusMeters) continue
1773                      val diffDays = (nowMs - obs.observedAt).toDouble() / (1000.0 * 60 * 60 * 24)
1774                      if (diffDays !in 0.0..maxDaysBack) continue
1775  
1776                      val quality = MycoMath.qualityWeight(obs.qualityGrade)
1777                      val sourceW = MycoMath.sourceWeight(obs.source)
1778                      val recency = MycoMath.recencyWeight(diffDays)
1779                      val spatial = MycoMath.spatialKernel(d)
1780                      weightedEvidence += quality * sourceW * recency * spatial
1781                      nearbyRecords++
1782                      nearbySpecies.add(obs.speciesId)
1783                  }
1784  
1785                  for (sig in userSightings) {
1786                      if (abs(sig.lat - cellLat) > 0.025 || abs(sig.lng - cellLng) > 0.035) continue
1787                      val d = calculateDistanceMeters(cellLat, cellLng, sig.lat, sig.lng)
1788                      if (d > kernelRadiusMeters) continue
1789                      val diffDays = (nowMs - sig.timestamp).toDouble() / (1000.0 * 60 * 60 * 24)
1790                      if (diffDays !in 0.0..maxDaysBack) continue
1791                      weightedEvidence += 1.5 * MycoMath.recencyWeight(diffDays) * MycoMath.spatialKernel(d)
1792                      nearbyRecords++
1793                      nearbySpecies.add(sig.speciesId)
1794                  }
1795  
1796                  // Diversity bonus: more species nearby → richer site
1797                  val diversityBonus = minOf(0.3, nearbySpecies.size * 0.06)
1798                  val observationScore = minOf(1.0, weightedEvidence / 6.0 + diversityBonus)
1799  
1800                  // Per-cell terrain (real elevation) — same landform logic as
1801                  // the single-species engine, using a broad aggregate band.
1802                  val cellElev = elevations[idx]
1803                  val neighbourElevs = listOf(
1804                      (i - 1) to j, (i + 1) to j, i to (j - 1), i to (j + 1),
1805                      (i - 1) to (j - 1), (i + 1) to (j + 1), (i - 1) to (j + 1), (i + 1) to (j - 1)
1806                  ).mapNotNull { elevByIJ[it] }
1807                  val elevationScore = if (cellElev != null)
1808                      MycoMath.elevationFitness(cellElev, "aggregate_default") else 0.6
1809                  val terrainScore = if (cellElev != null && neighbourElevs.isNotEmpty())
1810                      MycoMath.terrainMoistureScore(cellElev, neighbourElevs) else 0.5
1811                  val aspectScore = if (cellElev != null) MycoMath.slopeAspectMoistureScore(
1812                      cellElev,
1813                      elevByIJ[(i + 1) to j], elevByIJ[(i - 1) to j],
1814                      elevByIJ[i to (j + 1)], elevByIJ[i to (j - 1)]
1815                  ) else 0.7
1816                  val canopyDist = if (canopy.isEmpty()) null else nearestFeatureMeters(cellLat, cellLng, canopy)
1817                  val landClass = if (env == null) classifyLandCell(cellLat, cellLng, landPolys) else LandClass.NEUTRAL
1818                  val canopyScore = if (env != null) MycoMath.richCanopyScore(
1819                      env.canopyPct.getOrNull(idx), env.ndvi.getOrNull(idx), env.landcover.getOrNull(idx), "aggregate_default"
1820                  ) else when (landClass) {
1821                      LandClass.GREEN -> maxOf(0.85, MycoMath.canopyProximityScore(canopyDist))
1822                      LandClass.BUILT -> 0.10
1823                      LandClass.NEUTRAL -> MycoMath.canopyProximityScore(canopyDist)
1824                  }
1825                  val riparianScore = if (env != null) MycoMath.riparianScore(env.waterDistM.getOrNull(idx)) else 0.45
1826                  val soilScore = if (env != null)
1827                      MycoMath.richSoilScore(env.soilPh.getOrNull(idx), env.soilSand.getOrNull(idx)) else 0.6
1828                  val twiScore = if (env != null) MycoMath.twiWetnessScore(env.twi.getOrNull(idx)) else 0.5
1829                  // Aggregate spans the whole catalogue, so match against all host
1830                  // groups — any forest type then rewards a likely host present.
1831                  val hostTreeScore = if (env != null) MycoMath.hostTreeMatchScore(
1832                      env.forestType.getOrNull(idx),
1833                      setOf(MycoMath.HostGroup.NEEDLELEAF, MycoMath.HostGroup.EVERGREEN_BROADLEAF, MycoMath.HostGroup.DECIDUOUS_BROADLEAF)
1834                  ) else 0.6
1835                  val cellSoilMoisture = env?.soilMoisture?.getOrNull(idx)?.let { MycoMath.soilMoistureFitness(it) }
1836                  val moistureScoreCell = if (cellSoilMoisture != null)
1837                      (0.5 * moistureScore + 0.5 * cellSoilMoisture) else moistureScore
1838  
1839                  // Weighted combination using the same canonical weights as the
1840                  // single-species grid (MycoMath.FACTOR_WEIGHTS); only the habitat
1841                  // factor differs — a flat baseline for the diverse catalogue.
1842                  val weightedSum = MycoMath.weightedFactorScore(
1843                      mapOf(
1844                          "evidence"    to observationScore,
1845                          "season"      to seasonScore,
1846                          "rainTrigger" to rainTriggerScore,
1847                          "canopy"      to canopyScore,
1848                          "hostTree"    to hostTreeScore,
1849                          "terrain"     to terrainScore,
1850                          "habitat"     to 0.7, // aggregate habitat baseline (diverse catalogue)
1851                          "elevation"   to elevationScore,
1852                          "temperature" to tempScore,
1853                          "riparian"    to riparianScore,
1854                          "aspect"      to aspectScore,
1855                          "moisture"    to moistureScoreCell,
1856                          "soil"        to soilScore,
1857                          "twi"         to twiScore,
1858                          "moon"        to moonScore
1859                      )
1860                  )
1861  
1862                  // Conditions modifier — driven by ACTUAL ground wetness (recent
1863                  // rain trigger + soil moisture), not the calendar. Fungi fruit
1864                  // after rain regardless of the textbook season, and a shifting
1865                  // climate makes those windows unreliable, so this keys off real
1866                  // moisture; the calendar season is only a light weighted factor
1867                  // above, never a gate. Range 0.55 (bone-dry) .. 1.0 (wet).
1868                  val fruitingConditions = maxOf(rainTriggerScore, 0.85 * moistureScore)
1869                  val penaltyMultiplier = (0.55 + 0.45 * fruitingConditions).coerceIn(0.0, 1.0)
1870                  // Habitat gate — suppress built-up/water/bare cells (see single-species note).
1871                  val habitatGate = if (env != null)
1872                      MycoMath.habitatGate(env.landcover.getOrNull(idx), env.ndvi.getOrNull(idx), "aggregate_default")
1873                  else when (landClass) {
1874                      LandClass.GREEN -> 0.95
1875                      LandClass.BUILT -> 0.10
1876                      LandClass.NEUTRAL -> (0.45 + 0.40 * MycoMath.canopyProximityScore(canopyDist))
1877                  }
1878                  val finalScore = (weightedSum * penaltyMultiplier * habitatGate).coerceIn(0.0, 1.0)
1879  
1880                  val tier = MycoMath.classifyTier(finalScore)
1881  
1882                  val factors = mutableListOf<String>()
1883                  factors.add("📍 Cell (${String.format(Locale.US, "%.4f", cellLat)}, ${String.format(Locale.US, "%.4f", cellLng)})")
1884                  factors.add("🔬 Evidence: $nearbyRecords record(s) across ${nearbySpecies.size} species within 2.5 km → ${String.format(Locale.US, "%.0f", observationScore * 100)}%")
1885                  factors.add("📅 $inSeasonCount of ${allSpecies.size} species fruiting in ${monthName(currentMonth)} → ${String.format(Locale.US, "%.0f", seasonScore * 100)}%")
1886                  factors.add("🌧️ Rain trigger (10-21d lag): ${String.format(Locale.US, "%.0f", rainTriggerScore * 100)}% | Background moisture: ${String.format(Locale.US, "%.0f", recentRain30d)}mm/30d")
1887                  factors.add("🌡️ Avg temp ${String.format(Locale.US, "%.1f", weather.avgTemp)}°C → ${String.format(Locale.US, "%.0f", tempScore * 100)}%")
1888                  factors.add("⛰️ Elevation: ${if (cellElev != null) String.format(Locale.US, "%.0f m", cellElev) else "n/a"} → ${String.format(Locale.US, "%.0f", elevationScore * 100)}% | 🏞️ Terrain: ${String.format(Locale.US, "%.0f", terrainScore * 100)}% | Aspect: ${String.format(Locale.US, "%.0f", aspectScore * 100)}%")
1889                  if (env != null) {
1890                      val cv = env.canopyPct.getOrNull(idx)
1891                      val nv = env.ndvi.getOrNull(idx)
1892                      factors.add("🛰️ Earth Engine: canopy ${if (cv != null) String.format(Locale.US, "%.0f%%", cv) else "n/a"}, NDVI ${if (nv != null) String.format(Locale.US, "%.2f", nv) else "n/a"} → ${String.format(Locale.US, "%.0f", canopyScore * 100)}%")
1893                      val ph = env.soilPh.getOrNull(idx)
1894                      val sand = env.soilSand.getOrNull(idx)
1895                      if (ph != null || sand != null) factors.add(
1896                          "🧪 Soil: ${if (ph != null) "pH ${String.format(Locale.US, "%.1f", ph)}" else "pH n/a"}, ${if (sand != null) "${String.format(Locale.US, "%.0f", sand)}% sand" else "texture n/a"} → ${String.format(Locale.US, "%.0f", soilScore * 100)}%"
1897                      )
1898                      env.soilMoisture.getOrNull(idx)?.let { sm ->
1899                          factors.add("💧 Soil moisture (14-day): ${String.format(Locale.US, "%.2f", sm)} m³/m³ → ${String.format(Locale.US, "%.0f", MycoMath.soilMoistureFitness(sm) * 100)}%")
1900                      }
1901                      env.twi.getOrNull(idx)?.let { tw ->
1902                          factors.add("🏞️ Wetness index (TWI): ${String.format(Locale.US, "%.1f", tw)} → ${String.format(Locale.US, "%.0f", twiScore * 100)}%")
1903                      }
1904                      env.forestType.getOrNull(idx)?.let { ft ->
1905                          factors.add("🌲 Forest type: ${forestTypeLabel(ft)} → ${String.format(Locale.US, "%.0f", hostTreeScore * 100)}%")
1906                      }
1907                  } else {
1908                      factors.add(when (landClass) {
1909                          LandClass.GREEN -> "🌳 Green space (park / forest / reserve) → strong habitat → ${String.format(Locale.US, "%.0f", canopyScore * 100)}%"
1910                          LandClass.BUILT -> "🏙️ Built-up land (residential / industrial / car park) → habitat suppressed → ${String.format(Locale.US, "%.0f", canopyScore * 100)}%"
1911                          LandClass.NEUTRAL -> "🌳 Canopy: ${if (canopyDist != null) "~${String.format(Locale.US, "%.0f m", canopyDist)} to woodland" else "no land-use map data"} → ${String.format(Locale.US, "%.0f", canopyScore * 100)}%"
1912                      })
1913                  }
1914                  if (habitatGate < 0.95) factors.add("⛔ Habitat gate ×${String.format(Locale.US, "%.2f", habitatGate)} — built-up/water/bare ground suppresses this cell")
1915                  if (nearbySpecies.size >= 3) factors.add("🌿 Diversity bonus: ${nearbySpecies.size} distinct species recorded nearby")
1916                  val confidence = MycoMath.predictionConfidence(nearbyRecords, weightedEvidence, env != null, cellElev != null)
1917                  factors.add("📶 Confidence: ${MycoMath.confidenceLabel(confidence)} — $nearbyRecords nearby record(s), ${if (env != null) "full" else "limited"} map data")
1918                  factors.add("Multi-factor aggregate estimate — not species-specific.")
1919  
1920                  cells.add(HotspotCell(cellLat, cellLng, finalScore, tier, factors, cellSizeMeters = cellMeters, confidence = confidence))
1921          }
1922          return@withContext cells
1923      }
1924  
1925      // ─── Darwin Core Export ─────────────────────────────────────────
1926  
1927      /**
1928       * Exports user sightings in Darwin Core standard format (CSV).
1929       * This format is accepted by Atlas of Living Australia, GBIF, and
1930       * Fungimap for contribution to biodiversity databases.
1931       *
1932       * Returns the CSV content as a String.
1933       */
1934      suspend fun exportDarwinCore(): String = withContext(Dispatchers.IO) {
1935          val sightings = dao.getAllUserSightings().filter { !it.isPrivate }
1936          val allSpecies = dao.getAllSpecies()
1937          val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
1938  
1939          val header = "occurrenceID,basisOfRecord,scientificName,kingdom,phylum,class,order,family,genus," +
1940                  "decimalLatitude,decimalLongitude,coordinateUncertaintyInMeters," +
1941                  "eventDate,recordedBy,occurrenceRemarks,identificationVerificationStatus"
1942  
1943          val rows = sightings.map { sighting ->
1944              val species = allSpecies.find { it.id == sighting.speciesId }
1945              val sciName = species?.scientificName ?: sighting.speciesId
1946              val family = species?.family ?: ""
1947              val genus = species?.genus ?: ""
1948              val dateStr = sdf.format(Date(sighting.timestamp))
1949              val notes = sighting.notes.replace("\"", "'").replace(",", ";")
1950  
1951              "\"MM-${sighting.id}\"," +
1952                      "\"HUMAN_OBSERVATION\"," +
1953                      "\"$sciName\"," +
1954                      "\"Fungi\"," +
1955                      "\"Basidiomycota\"," +
1956                      "\"Agaricomycetes\"," +
1957                      "\"\"," + // order not in model
1958                      "\"$family\"," +
1959                      "\"$genus\"," +
1960                      "${sighting.lat}," +
1961                      "${sighting.lng}," +
1962                      "10," + // GPS uncertainty ~10m
1963                      "\"$dateStr\"," +
1964                      "\"Myceliyums User\"," +
1965                      "\"$notes\"," +
1966                      "\"unverified\""
1967          }
1968  
1969          return@withContext "$header\n${rows.joinToString("\n")}"
1970      }
1971  
1972      // --- Support Math & Date Help Helpers ---
1973  
1974      private fun parseObsDate(dateStr: String?): Long {
1975          if (dateStr.isNullOrEmpty()) return System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000 // default 1 year ago
1976          return try {
1977              val formats = listOf(
1978                  SimpleDateFormat("yyyy-MM-dd", Locale.US),
1979                  SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US),
1980                  SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
1981              )
1982              var parsedDate: Date? = null
1983              for (f in formats) {
1984                  try {
1985                      parsedDate = f.parse(dateStr)
1986                      if (parsedDate != null) break
1987                  } catch (e: Exception) { /* continue */ }
1988              }
1989              parsedDate?.time ?: (System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
1990          } catch (e: Exception) {
1991              System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
1992          }
1993      }
1994  
1995      private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
1996          MycoMath.haversineMeters(lat1, lon1, lat2, lon2)
1997  
1998      private fun isMonthInSeason(month: Int, start: Int, end: Int): Boolean =
1999          MycoMath.isMonthInSeason(month, start, end)
2000  
2001      /** Human label for a Copernicus forest leaf-type class code. */
2002      private fun forestTypeLabel(forestType: Int?): String = when (forestType) {
2003          1 -> "evergreen-needleleaf forest"
2004          2 -> "evergreen-broadleaf forest"
2005          3 -> "deciduous-needleleaf forest"
2006          4 -> "deciduous-broadleaf forest"
2007          5 -> "mixed forest"
2008          else -> "no mapped forest"
2009      }
2010  
2011      /** Short label for a host tree group. */
2012      private fun hostGroupLabel(g: MycoMath.HostGroup): String = when (g) {
2013          MycoMath.HostGroup.NEEDLELEAF -> "pine/conifer"
2014          MycoMath.HostGroup.EVERGREEN_BROADLEAF -> "eucalypt/native"
2015          MycoMath.HostGroup.DECIDUOUS_BROADLEAF -> "oak/birch"
2016      }
2017  
2018      private fun monthName(month: Int): String {
2019          return when (month) {
2020              1 -> "Jan"
2021              2 -> "Feb"
2022              3 -> "Mar"
2023              4 -> "Apr"
2024              5 -> "May"
2025              6 -> "Jun"
2026              7 -> "Jul"
2027              8 -> "Aug"
2028              9 -> "Sep"
2029              10 -> "Oct"
2030              11 -> "Nov"
2031              12 -> "Dec"
2032              else -> ""
2033          }
2034      }
2035  }
```

---

<a id="appsrcmainjavacomexampleutilmycomathkt"></a>
## `app/src/main/java/com/example/util/MycoMath.kt`

**Role:** SCORING MATH — every per-factor function, the weights, gates, confidence, tiers. *** REVIEW CORE ***  
**Lines:** 808

**Key anchors:**
- FACTOR_WEIGHTS :735 (15 factors, sum=1.0)
- weightedFactorScore :758
- habitatGate :612 (built-up/water suppressor)
- predictionConfidence :776, confidenceLabel :790
- classifyTier :801 (Excellent>=.80 / VeryGood>=.60 / Promising>=.40 / Possible>=.20 / Unlikely)
- factor fns: season :52, rainfall :119, temp :177, habitat/mulch :221-274, elevation :290,
- terrain/slope/aspect :334-374, soil/canopy/NDVI :403-535, TWI :547, host-tree :582, moon :645-658,
- evidence kernel weights (quality/source/recency/spatial) :680-715

```kotlin
  1  package com.example.util
  2  
  3  import java.util.Calendar
  4  import kotlin.math.PI
  5  import kotlin.math.abs
  6  import kotlin.math.atan2
  7  import kotlin.math.cos
  8  import kotlin.math.exp
  9  import kotlin.math.floor
 10  import kotlin.math.ln
 11  import kotlin.math.pow
 12  import kotlin.math.sin
 13  import kotlin.math.sqrt
 14  
 15  /**
 16   * Pure, side-effect-free helpers used by the hotspot prediction engine.
 17   *
 18   * Extracted into a standalone object so the core geospatial/seasonal logic
 19   * can be unit-tested on the JVM without Android or Room dependencies.
 20   */
 21  object MycoMath {
 22  
 23      // Host-group lexicons, compiled once at class load. hostGroupsFor() runs once
 24      // per prediction request (not per cell), but there's no reason to recompile
 25      // three patterns on every call.
 26      private val needleleafRegex = Regex("pine|pinus|conifer|needle|spruce|\\bfir\\b|larch|cedar|cypress")
 27      private val evergreenBroadleafRegex = Regex("eucalypt|sclerophyll|banksia|melaleuca|acacia|wattle|myrtle|tea.?tree|native forest|gum\\b")
 28      private val deciduousBroadleafRegex = Regex("birch|betula|oak|quercus|beech|fagus|nothofagus|poplar|willow|deciduous|hazel|chestnut|exotic")
 29  
 30      // ─── Temporal helpers ────────────────────────────────────────────
 31  
 32      /**
 33       * Returns true if [month] (1-12) falls within the fruiting window
 34       * [start]..[end] (1-12), correctly handling windows that wrap across
 35       * the new year (e.g. start = 11 (Nov) through end = 2 (Feb)).
 36       */
 37      fun isMonthInSeason(month: Int, start: Int, end: Int): Boolean {
 38          return if (start <= end) {
 39              month in start..end
 40          } else {
 41              month >= start || month <= end
 42          }
 43      }
 44  
 45      /**
 46       * Week-level seasonal fitness. Returns 0.0–1.0 based on how close
 47       * the current week is to the peak of the fruiting window.
 48       * Peak = middle of the season window → 1.0.
 49       * Edges of the window → 0.6.
 50       * Outside the window → 0.0–0.3 (graceful falloff for shoulder weeks).
 51       */
 52      fun seasonalFitness(dayOfYear: Int, seasonStart: Int, seasonEnd: Int): Double {
 53          // Convert months to approximate day-of-year mid-points.
 54          val startDay = (seasonStart - 1) * 30 + 15
 55          val endDay = (seasonEnd - 1) * 30 + 15
 56  
 57          // Window length on the 365-day circle (wrap-aware, e.g. Nov-Feb), and the
 58          // peak as its mid-point. The modulo is normalised positive so the peak is
 59          // always a valid day-of-year for both wrap and non-wrap windows.
 60          val seasonLength = if (startDay <= endDay) endDay - startDay else (365 - startDay) + endDay
 61          val peakDay = ((startDay + seasonLength / 2) % 365 + 365) % 365
 62  
 63          // Circular distance from the peak.
 64          val dist = circularDistance(dayOfYear, peakDay, 365)
 65          // Plateau/edge keyed to window length, but floored so short (≈1-month)
 66          // windows still get a sensible high-confidence core and a full-month edge
 67          // — otherwise a season where start==end collapsed to a single-day plateau.
 68          val core = maxOf(seasonLength / 4.0, 15.0)     // full-confidence (≥ half a month)
 69          val edge = maxOf(seasonLength / 2.0, 30.0)     // window edge (≥ one month)
 70          val shoulder = edge + 14.0                      // +14-day shoulder grace
 71  
 72          return when {
 73              dist <= core -> 1.0                                                  // near peak
 74              dist <= edge -> 0.6 + 0.4 * (1.0 - (dist - core) / (edge - core))    // within window
 75              dist <= shoulder -> 0.3 * (1.0 - (dist - edge) / 14.0)              // shoulder
 76              else -> 0.0
 77          }.coerceIn(0.0, 1.0)
 78      }
 79  
 80      private fun circularDistance(a: Int, b: Int, period: Int): Double {
 81          val d = abs(a - b)
 82          return minOf(d, period - d).toDouble()
 83      }
 84  
 85      // ─── Geospatial helpers ──────────────────────────────────────────
 86  
 87      /**
 88       * Great-circle distance between two lat/lng points in metres
 89       * using the haversine formula.
 90       */
 91      fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
 92          val r = 6371e3 // Earth radius in metres
 93          val phi1 = lat1 * PI / 180.0
 94          val phi2 = lat2 * PI / 180.0
 95          val deltaPhi = (lat2 - lat1) * PI / 180.0
 96          val deltaLambda = (lon2 - lon1) * PI / 180.0
 97  
 98          val a = sin(deltaPhi / 2).pow(2) +
 99                  cos(phi1) * cos(phi2) * sin(deltaLambda / 2).pow(2)
100          val c = 2 * atan2(sqrt(a), sqrt(1 - a))
101          return r * c
102      }
103  
104      // ─── Rainfall lag analysis ───────────────────────────────────────
105  
106      /**
107       * Analyses daily rainfall data to detect fruiting trigger events.
108       *
109       * Fungi typically fruit 10-21 days after a significant rain event (>20mm
110       * in 48 hours). This function scans the daily precipitation array and
111       * returns a 0.0–1.0 score reflecting how strongly recent rain patterns
112       * match a fruiting trigger.
113       *
114       * @param dailyRainfallMm array of daily precipitation totals, index 0 = oldest day
115       * @param lagStartDays earliest day offset from today for trigger window (default 10)
116       * @param lagEndDays latest day offset from today for trigger window (default 21)
117       * @param triggerThresholdMm minimum 2-day cumulative rain to count as a trigger event
118       */
119      fun rainfallTriggerScore(
120          dailyRainfallMm: List<Double>,
121          lagStartDays: Int = 10,
122          lagEndDays: Int = 21,
123          triggerThresholdMm: Double = 20.0
124      ): Double {
125          if (dailyRainfallMm.size < 2) return 0.0
126  
127          val totalDays = dailyRainfallMm.size
128          var bestTriggerStrength = 0.0
129  
130          // Scan the lag window for a fruiting trigger. A 2-day burst is the classic
131          // signal, but a multi-day soaking (no single 48 h window over threshold)
132          // also triggers fruiting, so take the stronger of a 2-day and 3-day pulse.
133          for (lagDay in lagStartDays..lagEndDays) {
134              val idx = totalDays - lagDay
135              if (idx < 1 || idx >= totalDays) continue
136  
137              val twoDay = dailyRainfallMm[idx] + dailyRainfallMm[idx - 1]
138              val threeDay = twoDay + (if (idx >= 2) dailyRainfallMm[idx - 2] else 0.0)
139              // Strength scales with rain amount (diminishing returns); the 3-day
140              // pulse saturates a little higher since it accumulates over more days.
141              val strength2 = if (twoDay >= triggerThresholdMm) minOf(1.0, twoDay / 60.0) else 0.0
142              val strength3 = if (threeDay >= triggerThresholdMm * 1.25) minOf(1.0, threeDay / 80.0) else 0.0
143              val strength = maxOf(strength2, strength3)
144              if (strength > 0.0) {
145                  // Optimal trigger is around 14-17 days ago
146                  val optimalLag = 15.5
147                  val lagFitness = 1.0 - abs(lagDay - optimalLag) / (lagEndDays - lagStartDays).toDouble()
148                  val combined = strength * (0.5 + 0.5 * lagFitness)
149                  if (combined > bestTriggerStrength) bestTriggerStrength = combined
150              }
151          }
152  
153          // Also factor in sustained moisture (total rain in past 30 days). Summed
154          // with an index loop over a sublist view — no intermediate List allocation.
155          val daysToTake = minOf(30, totalDays)
156          var recentTotal = 0.0
157          for (i in (totalDays - daysToTake) until totalDays) recentTotal += dailyRainfallMm[i]
158          val moistureBase = when {
159              recentTotal in 40.0..180.0 -> 0.3  // Adequate background moisture
160              recentTotal > 180.0 -> 0.2          // Waterlogged — slightly less ideal
161              else -> recentTotal / 40.0 * 0.3    // Dry conditions
162          }
163  
164          return minOf(1.0, bestTriggerStrength + moistureBase)
165      }
166  
167      // ─── Temperature fitness ─────────────────────────────────────────
168  
169      /**
170       * Species-specific temperature suitability score.
171       * Returns 0.0–1.0 based on how well recent temperatures match the
172       * species' preferred fruiting range.
173       *
174       * Most Victorian fungi fruit best at 8-18°C. Psilocybes prefer
175       * cooler (5-15°C), tropical species need warmer (15-25°C).
176       */
177      fun temperatureFitness(
178          avgTemp: Double,
179          speciesId: String
180      ): Double {
181          val (idealMin, idealMax) = speciesTempRange(speciesId)
182          val midpoint = (idealMin + idealMax) / 2.0
183          val halfRange = (idealMax - idealMin) / 2.0
184  
185          val dist = abs(avgTemp - midpoint)
186          return when {
187              dist <= halfRange -> 1.0
188              dist <= halfRange + 5.0 -> 1.0 - (dist - halfRange) / 10.0
189              else -> maxOf(0.0, 0.5 - (dist - halfRange - 5.0) / 20.0)
190          }.coerceIn(0.0, 1.0)
191      }
192  
193      /** Species-specific ideal temperature ranges (°C) for fruiting. */
194      private fun speciesTempRange(speciesId: String): Pair<Double, Double> = when {
195          speciesId.startsWith("psilocybe") -> 5.0 to 15.0
196          speciesId.startsWith("amanita") -> 8.0 to 18.0
197          speciesId.startsWith("cortinarius") -> 6.0 to 16.0
198          speciesId.startsWith("boletus") || speciesId.startsWith("suillus") -> 10.0 to 20.0
199          speciesId.contains("tropical") || speciesId.startsWith("favolaschia") -> 12.0 to 25.0
200          speciesId.startsWith("gymnopilus") -> 8.0 to 18.0
201          speciesId.startsWith("omphalotus") -> 8.0 to 18.0
202          speciesId.startsWith("trametes") -> 10.0 to 22.0
203          speciesId.startsWith("ganoderma") -> 10.0 to 22.0
204          speciesId.startsWith("mycena") -> 5.0 to 15.0
205          speciesId.startsWith("coprinellus") || speciesId.startsWith("coprinus") -> 8.0 to 20.0
206          else -> 8.0 to 18.0 // Victorian fungi default
207      }
208  
209      // ─── Habitat suitability ─────────────────────────────────────────
210  
211      /**
212       * Scores how well a species' preferred habitat matches the likely
213       * land-cover at the given location. Since we don't have real land-cover
214       * data, this uses the species' habitat list + known micro-habitat
215       * heuristics for common Victorian species.
216       *
217       * Species with broader habitat tolerances get a higher baseline.
218       * Species-specific boosts for known micro-habitats (e.g. Psilocybe
219       * subaeruginosa in pine bark mulch parks).
220       */
221      fun habitatDiversityScore(habitatTypes: List<String>, substrates: List<String>): Double {
222          // More diverse habitat tolerance → higher baseline probability of match
223          val habitatBreadth = minOf(1.0, habitatTypes.size / 4.0)
224          val substrateBreadth = minOf(1.0, substrates.size / 3.0)
225          return (0.6 * habitatBreadth + 0.4 * substrateBreadth).coerceIn(0.2, 1.0)
226      }
227  
228      /**
229       * Species-specific habitat affinity weight. Some species are very
230       * specific to certain micro-habitats; this gives them a differentiated
231       * signal even when observation data is sparse.
232       */
233      fun speciesHabitatWeight(speciesId: String): Double = when {
234          // Very habitat-specific — score varies more with location
235          speciesId == "psilocybe_subaeruginosa" -> 1.4
236          speciesId == "amanita_muscaria" -> 1.3
237          speciesId == "amanita_phalloides" -> 1.3
238          // Broad generalists — score varies less
239          speciesId.startsWith("trametes") -> 0.8
240          speciesId.startsWith("coprinellus") -> 0.7
241          speciesId.startsWith("gymnopilus") -> 1.0
242          else -> 1.0
243      }
244  
245      // ─── Tanbark / woodchip (mulch substrate) ────────────────────────
246  
247      private val mulchSpecialistRegex = Regex("wood.?chip|tanbark|tan.bark|bark.?mulch|\\bmulch\\b|wood.?debris")
248      private val mulchLooseRegex = Regex("disturbed|urban|garden|chip|woody|saw.?dust")
249  
250      /**
251       * How strongly a species is tied to woodchip / tanbark / bark-mulch
252       * substrates (0 = not mulch-associated, 1 = mulch specialist). Derived from
253       * the species' habitat/substrate descriptors. Used to decide whether mapped
254       * tanbark/woodchip beds should lift a cell's score — gold tops
255       * (Psilocybe subaeruginosa/cyanescens), Leratiomyces, Tubaria and other
256       * wood-chip lovers score high; mycorrhizal forest fungi score 0 (a tanbark
257       * bed is irrelevant to them).
258       */
259      fun mulchAffinity(habitatTypes: List<String>, substrates: List<String>): Double {
260          val t = (habitatTypes + substrates).joinToString(" ").lowercase()
261          return when {
262              mulchSpecialistRegex.containsMatchIn(t) -> 1.0
263              mulchLooseRegex.containsMatchIn(t) -> 0.6
264              else -> 0.0
265          }
266      }
267  
268      /**
269       * Proximity score (0–1) to a mapped tanbark/woodchip/garden-bed feature.
270       * 1.0 right at/in a bed, tapering to 0 by ~300 m. A null distance (no beds
271       * mapped near this cell) is 0 — a pure positive signal that only ever lifts
272       * a mulch-loving species' score, never penalises.
273       */
274      fun mulchProximityScore(distanceMeters: Double?): Double = when {
275          distanceMeters == null -> 0.0
276          distanceMeters <= 60.0 -> 1.0
277          distanceMeters <= 300.0 -> 1.0 - (distanceMeters - 60.0) / 240.0
278          else -> 0.0
279      }.coerceIn(0.0, 1.0)
280  
281      // ─── Terrain & elevation (per-cell landscape) ────────────────────
282  
283      /**
284       * Species-specific elevation suitability (0.0–1.0).
285       *
286       * Real ground elevation at the cell is matched against the species'
287       * preferred altitude band. Unlike the global climate factors, this
288       * varies cell-to-cell, so the map reflects genuine terrain.
289       */
290      fun elevationFitness(elevationM: Double, speciesId: String): Double {
291          val (lo, hi) = speciesElevationBand(speciesId)
292          val mid = (lo + hi) / 2.0
293          val half = (hi - lo) / 2.0
294          val dist = abs(elevationM - mid)
295          return when {
296              dist <= half -> 1.0
297              dist <= half + 300.0 -> 1.0 - (dist - half) / 600.0
298              else -> maxOf(0.0, 0.5 - (dist - half - 300.0) / 1200.0)
299          }.coerceIn(0.0, 1.0)
300      }
301  
302      /** Species-specific ideal elevation band (metres ASL) for fruiting. */
303      private fun speciesElevationBand(speciesId: String): Pair<Double, Double> = when {
304          // Lowland pastures, urban mulch and coastal scrub
305          speciesId.startsWith("psilocybe") -> 0.0 to 700.0
306          speciesId.startsWith("coprinellus") || speciesId.startsWith("coprinus") -> 0.0 to 600.0
307          // Montane wet forest specialists
308          speciesId.startsWith("cortinarius") -> 200.0 to 1400.0
309          speciesId.startsWith("boletus") || speciesId.startsWith("suillus") -> 100.0 to 1200.0
310          speciesId.startsWith("amanita") -> 50.0 to 1100.0
311          speciesId.startsWith("mycena") -> 0.0 to 1200.0
312          speciesId.startsWith("gymnopilus") || speciesId.startsWith("omphalotus") -> 0.0 to 1000.0
313          else -> 0.0 to 1000.0 // Broad Victorian default
314      }
315  
316      /**
317       * Terrain moisture/landform suitability (0.0–1.0) from local relief.
318       *
319       * Derived from the cell's elevation relative to its neighbours:
320       *  - Gentle-to-moderate slopes drain well yet hold leaf-litter moisture.
321       *  - Concave hollows / gully heads accumulate moisture and organic matter
322       *    (prime fruiting ground), so sitting *below* the local mean scores up.
323       *  - Exposed local highs / very steep faces are drier and score down.
324       *
325       * @param cellElevation elevation of this cell (m)
326       * @param neighbourElevations elevations of the surrounding grid cells (m)
327       * @param cellSpacingM grid spacing between neighbours (m). The relief/concavity
328       *   thresholds below were tuned for the ~500 m overview grid, so for a finer
329       *   grid (e.g. Deep Search at ~15 m) the local relief is rescaled to its
330       *   500 m-equivalent — i.e. compared as a slope — so the same thresholds keep
331       *   discriminating instead of collapsing to "flat". Default preserves the
332       *   overview's original calibration exactly.
333       */
334      fun terrainMoistureScore(
335          cellElevation: Double,
336          neighbourElevations: List<Double>,
337          cellSpacingM: Double = 500.0
338      ): Double {
339          if (neighbourElevations.isEmpty()) return 0.5
340          val scale = 500.0 / cellSpacingM            // 1.0 for the overview; >1 amplifies fine relief
341          val meanNbr = neighbourElevations.average()
342          val relief = (neighbourElevations.maxOrNull()!! - neighbourElevations.minOrNull()!!) * scale
343          val concavity = (meanNbr - cellElevation) * scale // >0 ⇒ cell sits in a hollow
344  
345          // Local slope (relief over ~500 m cells) — gentle/moderate is best.
346          val slopeScore = when {
347              relief < 8.0 -> 0.6            // very flat — can be waterlogged or exposed
348              relief <= 40.0 -> 1.0          // gentle–moderate — ideal drainage + moisture
349              relief <= 80.0 -> 0.7          // steeper
350              else -> 0.4                    // ridge/cliff — poor footing for fruiting
351          }
352          // Landform position — hollows hold moisture, local highs shed it.
353          val concavityScore = when {
354              concavity > 6.0 -> 1.0
355              concavity > 1.0 -> 0.8
356              concavity > -3.0 -> 0.6        // ~flat / mid-slope
357              else -> 0.45                   // exposed local high
358          }
359          return (0.5 * slopeScore + 0.5 * concavityScore).coerceIn(0.0, 1.0)
360      }
361  
362      /**
363       * Slope-aspect moisture suitability (0.0–1.0) for the Southern Hemisphere.
364       *
365       * The sun sits to the north, so **south-facing** slopes stay cooler, shadier
366       * and moister (prime fungal ground), while north-facing slopes dry out.
367       * East-facing slopes (gentle morning sun) are mildly preferred over
368       * west-facing (harsh afternoon sun). Aspect is derived from the elevation
369       * difference across the cell, so flat ground scores neutrally.
370       *
371       * Pass the centre elevation plus its four cardinal neighbours; missing
372       * neighbours fall back to the centre (→ zero gradient → neutral).
373       */
374      fun slopeAspectMoistureScore(
375          elevCenter: Double,
376          elevNorth: Double?,
377          elevSouth: Double?,
378          elevEast: Double?,
379          elevWest: Double?,
380          cellSpacingM: Double = 500.0
381      ): Double {
382          val n = elevNorth ?: elevCenter
383          val s = elevSouth ?: elevCenter
384          val e = elevEast ?: elevCenter
385          val w = elevWest ?: elevCenter
386          // Aspect is the elevation drop across the cell normalised by spacing, so a
387          // gentle but consistent slope reads as a clear aspect at any resolution.
388          // The divisor scales with spacing (0.06·spacing → 30 at the 500 m default),
389          // so a finer grid stays sensitive instead of seeing ~0 gradient.
390          val divisor = 0.06 * cellSpacingM
391          // +southness ⇒ terrain falls away to the south ⇒ south-facing.
392          val southness = ((n - s) / divisor).coerceIn(-1.0, 1.0)
393          // +eastness ⇒ terrain falls away to the east ⇒ east-facing.
394          val eastness = ((w - e) / divisor).coerceIn(-1.0, 1.0)
395          return (0.70 + 0.25 * southness + 0.05 * eastness).coerceIn(0.0, 1.0)
396      }
397  
398      /**
399       * Soil-moisture suitability (0.0–1.0) from volumetric water content
400       * (m³/m³, as reported by Open-Meteo's 0–7 cm soil layer). Most fungi
401       * fruit best in consistently damp — but not waterlogged — soil.
402       */
403      fun soilMoistureFitness(vwc: Double): Double = when {
404          vwc <= 0.0 -> 0.0
405          vwc < 0.15 -> vwc / 0.15 * 0.5                       // dry
406          vwc < 0.25 -> 0.5 + (vwc - 0.15) / 0.10 * 0.4        // improving
407          vwc <= 0.40 -> 1.0                                   // ideal damp range
408          vwc <= 0.50 -> 1.0 - (vwc - 0.40) / 0.10 * 0.3       // getting waterlogged
409          else -> 0.6
410      }.coerceIn(0.0, 1.0)
411  
412      /**
413       * Canopy/forest proximity suitability (0.0–1.0). Most target fungi are
414       * woodland species (mycorrhizal or wood-rotting), so cells in or near
415       * mapped forest/wood/park features score higher. [distanceMeters] is the
416       * distance to the nearest such feature, or null when canopy data is
417       * unavailable (→ neutral, so the factor doesn't penalise on a failed fetch).
418       */
419      fun canopyProximityScore(distanceMeters: Double?): Double = when {
420          distanceMeters == null -> 0.6
421          distanceMeters <= 150.0 -> 1.0                                          // inside/at the treeline
422          distanceMeters <= 1500.0 -> 1.0 - (distanceMeters - 150.0) / 1350.0 * 0.7 // 1.0 → 0.3
423          distanceMeters <= 3000.0 -> 0.3 - (distanceMeters - 1500.0) / 1500.0 * 0.2 // 0.3 → 0.1
424          else -> 0.1
425      }.coerceIn(0.0, 1.0)
426  
427      // ─── Earth Engine layers (optional backend) ──────────────────────
428  
429      /**
430       * Land-cover suitability (0.0–1.0) from an ESA WorldCover class code.
431       * Woodland/wetland favoured; most species avoid bare/water/built ground —
432       * except pasture/urban-mulch specialists like Psilocybe subaeruginosa.
433       */
434      fun landCoverSuitability(worldCoverClass: Int?, speciesId: String): Double {
435          if (worldCoverClass == null) return 0.6
436          val psilocybe = speciesId.startsWith("psilocybe")
437          return when (worldCoverClass) {
438              10 -> 1.0                               // tree cover
439              90, 95 -> 0.9                           // wetland / mangrove
440              20, 100 -> 0.8                          // shrubland / moss-lichen
441              30 -> if (psilocybe) 0.85 else 0.6      // grassland / pasture
442              40 -> 0.45                              // cropland
443              50 -> if (psilocybe) 0.70 else 0.40     // built-up (urban mulch beds)
444              60 -> 0.20                              // bare / sparse
445              70 -> 0.10                              // snow / ice
446              80 -> 0.05                              // permanent water
447              else -> 0.5
448          }
449      }
450  
451      /** Tree-canopy suitability (0.0–1.0) from a canopy-cover percentage (0–100). */
452      fun treeCanopyFitness(percent: Double?): Double {
453          if (percent == null) return 0.6
454          val p = percent.coerceIn(0.0, 100.0)
455          return when {
456              p >= 60.0 -> 1.0
457              p >= 20.0 -> 0.6 + (p - 20.0) / 40.0 * 0.4
458              p >= 5.0 -> 0.3 + (p - 5.0) / 15.0 * 0.3
459              else -> 0.2 + p / 5.0 * 0.1
460          }.coerceIn(0.0, 1.0)
461      }
462  
463      /** Vegetation-greenness suitability (0.0–1.0) from an NDVI value (−1..1). */
464      fun ndviFitness(ndvi: Double?): Double {
465          if (ndvi == null) return 0.6
466          return when {
467              ndvi < 0.0 -> 0.05                              // water / built / bare
468              ndvi < 0.2 -> 0.2 + ndvi / 0.2 * 0.2            // sparse
469              ndvi < 0.4 -> 0.4 + (ndvi - 0.2) / 0.2 * 0.3    // moderate
470              ndvi <= 0.8 -> 1.0                              // lush vegetation
471              else -> 0.9
472          }.coerceIn(0.0, 1.0)
473      }
474  
475      /**
476       * Blends the Earth Engine layers (tree canopy %, NDVI, land-cover class)
477       * into a single per-cell canopy/vegetation suitability. Used in place of
478       * the OSM proximity heuristic when the backend is configured.
479       */
480      fun richCanopyScore(canopyPct: Double?, ndvi: Double?, worldCoverClass: Int?, speciesId: String): Double {
481          val tree = treeCanopyFitness(canopyPct)
482          val veg = ndviFitness(ndvi)
483          val land = landCoverSuitability(worldCoverClass, speciesId)
484          return (0.40 * tree + 0.35 * land + 0.25 * veg).coerceIn(0.0, 1.0)
485      }
486  
487      /**
488       * Riparian suitability (0.0–1.0) from distance to surface water (metres).
489       * Many fungi favour the damp, organic-rich ground along creeks and gully
490       * lines, so cells near water score up. This is a mild positive signal —
491       * being far from water (or having no data) is neutral, not penalised.
492       */
493      fun riparianScore(distanceMeters: Double?): Double = when {
494          distanceMeters == null -> 0.45           // no water within range / no data → neutral
495          distanceMeters <= 100.0 -> 1.0           // creek/river bank — prime
496          distanceMeters <= 500.0 -> 0.80
497          distanceMeters <= 1000.0 -> 0.60
498          distanceMeters <= 2000.0 -> 0.50
499          else -> 0.45
500      }.coerceIn(0.0, 1.0)
501  
502      /**
503       * Soil-pH suitability (0.0–1.0) from surface soil pH (H2O). Most fungi
504       * favour slightly acidic to neutral soils (≈4.5–7.0); strongly acidic or
505       * alkaline ground is less productive. Generic curve (per-species pH bands
506       * are a v2/P3 model concern) — null is neutral, never penalised.
507       */
508      fun soilPhFitness(ph: Double?): Double = when {
509          ph == null -> 0.6
510          ph < 4.0 -> 0.35                                   // strongly acidic
511          ph < 5.0 -> 0.35 + (ph - 4.0) * 0.65              // 0.35 → 1.0 across 4.0–5.0
512          ph <= 7.0 -> 1.0                                   // slightly acidic–neutral: ideal
513          ph <= 8.5 -> 1.0 - (ph - 7.0) / 1.5 * 0.5         // 1.0 → 0.5 alkaline falloff
514          else -> 0.5
515      }.coerceIn(0.0, 1.0)
516  
517      /**
518       * Soil-drainage suitability (0.0–1.0) from surface sand mass-fraction (%).
519       * Loamy ground (moderate sand) holds moisture while still draining; very
520       * clayey soils waterlog/compact and very sandy ones dry out fast.
521       */
522      fun soilDrainageFitness(sandPct: Double?): Double = when {
523          sandPct == null -> 0.6
524          sandPct < 15.0 -> 0.6                              // very clayey — waterlogs/compacts
525          sandPct <= 70.0 -> 1.0                             // loamy — ideal
526          sandPct <= 90.0 -> 1.0 - (sandPct - 70.0) / 20.0 * 0.4  // sandy — drains fast/dry
527          else -> 0.55
528      }.coerceIn(0.0, 1.0)
529  
530      /**
531       * Blends surface soil pH and texture into a single soil suitability
532       * (0.0–1.0). pH is weighted higher as the more ecologically discriminating
533       * signal. Both null → neutral (0.6).
534       */
535      fun richSoilScore(ph: Double?, sandPct: Double?): Double {
536          if (ph == null && sandPct == null) return 0.6
537          return (0.6 * soilPhFitness(ph) + 0.4 * soilDrainageFitness(sandPct)).coerceIn(0.0, 1.0)
538      }
539  
540      /**
541       * Topographic Wetness Index suitability (0.0–1.0). Higher TWI = larger
542       * upslope catchment over flatter ground, so moisture accumulates (gully
543       * lines, footslopes, flats) — ground fungi favour. Dry ridge tops shed
544       * water (low TWI); extreme values are likely water channels, tapered off.
545       * Null is neutral.
546       */
547      fun twiWetnessScore(twi: Double?): Double = when {
548          twi == null -> 0.5
549          twi < 3.0 -> 0.45                                  // dry ridgeline / steep
550          twi < 7.0 -> 0.45 + (twi - 3.0) / 4.0 * 0.45      // 0.45 → 0.90
551          twi <= 12.0 -> 1.0                                 // moist hollows / footslopes — ideal
552          twi <= 16.0 -> 1.0 - (twi - 12.0) / 4.0 * 0.3     // 1.0 → 0.7 wet channels
553          else -> 0.6                                        // likely standing water/channel
554      }.coerceIn(0.0, 1.0)
555  
556      /** Broad mycorrhizal/host tree groups, matched against the Earth Engine
557       *  forest leaf-type layer. */
558      enum class HostGroup { NEEDLELEAF, EVERGREEN_BROADLEAF, DECIDUOUS_BROADLEAF }
559  
560      /**
561       * Derives a species' host tree groups from its free-text habitat/substrate
562       * descriptors (the catalogue already names hosts, e.g. "mycorrhizal with
563       * Pinus radiata, Birch"). An empty result means the species isn't tree-bound
564       * (dung/grass/woodchip saprobes), so the host-tree factor stays neutral.
565       */
566      fun hostGroupsFor(habitatTypes: List<String>, substrates: List<String>): Set<HostGroup> {
567          val t = (habitatTypes + substrates).joinToString(" ").lowercase()
568          val out = mutableSetOf<HostGroup>()
569          if (needleleafRegex.containsMatchIn(t)) out += HostGroup.NEEDLELEAF
570          if (evergreenBroadleafRegex.containsMatchIn(t)) out += HostGroup.EVERGREEN_BROADLEAF
571          if (deciduousBroadleafRegex.containsMatchIn(t)) out += HostGroup.DECIDUOUS_BROADLEAF
572          return out
573      }
574  
575      /**
576       * Host-tree suitability (0.0–1.0) from the Copernicus forest leaf-type class
577       * (1 evergreen-needleleaf, 2 evergreen-broadleaf, 3 deciduous-needleleaf,
578       * 4 deciduous-broadleaf, 5 mixed; 0/null unknown) against a species' host
579       * groups. Neutral when the species isn't tree-bound or there's no forest-type
580       * data — a wrong host type in a clearly forested cell is gently penalised.
581       */
582      fun hostTreeMatchScore(forestType: Int?, hostGroups: Set<HostGroup>): Double {
583          if (hostGroups.isEmpty()) return 0.6                 // saprobic/dung/grass — not host-bound
584          if (forestType == null || forestType == 0) return 0.55 // no data → mild neutral
585          if (forestType == 5) return 0.85                     // mixed forest — likely contains a host
586          val cellGroup = when (forestType) {
587              1, 3 -> HostGroup.NEEDLELEAF                      // evergreen/deciduous needleleaf
588              2 -> HostGroup.EVERGREEN_BROADLEAF
589              4 -> HostGroup.DECIDUOUS_BROADLEAF
590              else -> null
591          }
592          return when {
593              cellGroup == null -> 0.5                          // unclassified / non-forest
594              cellGroup in hostGroups -> 1.0                    // host tree present
595              else -> 0.4                                       // forest, but wrong host type
596          }
597      }
598  
599      /**
600       * Multiplicative HABITAT GATE (0.05–1.0) — the counter-weight that stops
601       * cities, roads, car parks and water from scoring high.
602       *
603       * Fungi simply don't fruit on sealed/built-up, bare, or open-water ground
604       * no matter how good the season, rainfall, or how many citizen-science
605       * records cluster nearby (records are densest where people are — cities).
606       * So land cover and NDVI act as a *gate* applied on top of the weighted
607       * score, rather than just another additive term that urban factors can
608       * outvote. Woodland/wetland pass through (≈1.0); built-up/water/bare
609       * collapse toward zero, dropping those cells to the "Unlikely" tier so
610       * they neither mislead nor clutter the map.
611       */
612      fun habitatGate(worldCoverClass: Int?, ndvi: Double?, speciesId: String): Double {
613          val base = when (worldCoverClass) {
614              10 -> 1.0          // tree cover
615              90, 95 -> 0.9      // wetland / mangrove
616              20, 100 -> 0.8     // shrubland / moss-lichen
617              30 -> 0.50         // grassland / pasture
618              40 -> 0.40         // cropland
619              60 -> 0.18         // bare / sparse
620              70 -> 0.10         // snow / ice
621              80 -> 0.05         // permanent water
622              50 -> 0.12         // built-up — roads, buildings, car parks
623              null -> 0.70       // unknown (no EE data) — mild, don't over-penalise
624              else -> 0.60
625          }
626          // Psilocybe subaeruginosa genuinely fruits in urban woodchip/mulch and
627          // pasture, so it keeps a modest floor where pure-forest species get gated.
628          val adjusted = if (speciesId.startsWith("psilocybe") && (worldCoverClass == 50 || worldCoverClass == 30))
629              maxOf(base, 0.45) else base
630          // Hard non-vegetation veto: NDVI well below zero = pavement/water/rooftops.
631          val vetoed = if (ndvi != null && ndvi < 0.05) minOf(adjusted, 0.20) else adjusted
632          return vetoed.coerceIn(0.05, 1.0)
633      }
634  
635      // ─── Moon phase (optional factor) ────────────────────────────────
636  
637      /**
638       * Returns the current moon phase as a 0.0–1.0 cycle value.
639       * 0.0 / 1.0 = new moon, 0.5 = full moon.
640       *
641       * Uses a simplified synodic calculation (accurate to ~1 day).
642       * Some foragers believe fruiting peaks around new moon and in the
643       * days following full moon. This is included as a low-weight factor.
644       */
645      fun moonPhase(timestampMs: Long): Double {
646          // Known new moon: Jan 6, 2000 18:14 UTC
647          val knownNewMoonMs = 947181240000L
648          val synodicPeriodMs = 29.53058867 * 24 * 60 * 60 * 1000
649          val daysSince = (timestampMs - knownNewMoonMs).toDouble() / synodicPeriodMs
650          return daysSince - floor(daysSince) // 0.0 = new moon, 0.5 = full moon
651      }
652  
653      /**
654       * Moon phase fruiting score. Many traditional foragers claim that
655       * fungi fruit best 2-5 days after a new moon or full moon.
656       * Returns 0.0–1.0 with peaks near those phases.
657       */
658      fun moonFruitingScore(timestampMs: Long): Double {
659          val phase = moonPhase(timestampMs)
660          // Two peaks: near new moon (0.0) and full moon (0.5)
661          val distToNew = minOf(phase, 1.0 - phase)
662          val distToFull = abs(phase - 0.5)
663          val minDist = minOf(distToNew, distToFull)
664          // Peak within 0.1 of cycle (~3 days), gentle falloff
665          return when {
666              minDist < 0.05 -> 1.0
667              minDist < 0.15 -> 0.5 + 0.5 * (1.0 - (minDist - 0.05) / 0.1)
668              else -> 0.3 + 0.2 * (1.0 - minOf(1.0, (minDist - 0.15) / 0.35))
669          }.coerceIn(0.3, 1.0)
670      }
671  
672      // ─── Observation evidence scoring ────────────────────────────────
673  
674      /**
675       * Weights an observation by its verification quality.
676       * Research-grade and herbarium specimens are most reliable;
677       * casual citizen science records are down-weighted but still
678       * contribute evidence.
679       */
680      fun qualityWeight(qualityGrade: String): Double = when (qualityGrade.lowercase()) {
681          "research" -> 1.0
682          "needs_id" -> 0.6
683          "casual" -> 0.3
684          else -> 0.5
685      }
686  
687      /**
688       * Source-specific multiplier for observation evidence.
689       * Herbarium records (ALA/GBIF PRESERVED_SPECIMEN) verified by
690       * professional mycologists are the gold standard. Fungimap records
691       * from Royal Botanic Gardens Melbourne are weighted above generic iNat.
692       */
693      fun sourceWeight(source: String): Double = when (source.uppercase()) {
694          "ALA" -> 1.3            // Verified Australian biodiversity records
695          "GBIF" -> 1.2           // Global museum/herbarium records with DOIs
696          "INATURALIST" -> 1.0    // Citizen science baseline
697          "MUSHROOMOBSERVER" -> 0.9
698          "USER" -> 1.5           // First-hand, georeferenced
699          else -> 0.8
700      }
701  
702      /**
703       * Temporal decay weight with configurable half-life.
704       * More recent observations are more valuable evidence.
705       */
706      fun recencyWeight(ageDays: Double, halfLifeDays: Double = 365.0): Double {
707          if (ageDays < 0) return 0.0
708          val lambda = ln(2.0) / halfLifeDays
709          return exp(-lambda * ageDays)
710      }
711  
712      /**
713       * Gaussian spatial kernel for proximity weighting.
714       */
715      fun spatialKernel(distanceMeters: Double, sigma: Double = 800.0): Double {
716          return exp(-(distanceMeters * distanceMeters) / (2.0 * sigma * sigma))
717      }
718  
719      // ─── Canonical factor weights ────────────────────────────────────
720  
721      /**
722       * The single source of truth for the per-factor weights of the hotspot
723       * score. Both the single-species grid and the multi-species aggregate read
724       * these same constants via [weightedFactorScore], so the two pipelines can
725       * never silently drift apart. The sum is exactly 1.0 (asserted in tests).
726       *
727       * Insertion-ordered for readable "Why this score?" breakdowns.
728       */
729      // Weighting philosophy: fungi respond to ACTUAL ground conditions far more
730      // than the calendar, and a shifting climate makes textbook "seasons"
731      // increasingly unreliable. So the calendar season is only a light hint
732      // (0.08), while real, measured signals carry the weight — recent rain
733      // (0.12), soil moisture (0.05), soil pH/texture (0.05), trees/canopy (0.09)
734      // + host trees (0.05), terrain/wetness, and current temperature (0.07).
735      val FACTOR_WEIGHTS: Map<String, Double> = linkedMapOf(
736          "evidence"    to 0.21,
737          "rainTrigger" to 0.12,
738          "canopy"      to 0.09,
739          "season"      to 0.08,
740          "habitat"     to 0.08,
741          "temperature" to 0.07,
742          "hostTree"    to 0.05,
743          "terrain"     to 0.05,
744          "elevation"   to 0.05,
745          "soil"        to 0.05,
746          "moisture"    to 0.05,
747          "twi"         to 0.03,
748          "riparian"    to 0.03,
749          "aspect"      to 0.03,
750          "moon"        to 0.01
751      )
752  
753      /**
754       * Weighted sum of per-factor [factorScores] using [FACTOR_WEIGHTS]. A factor
755       * with no entry in [factorScores] contributes 0 (so a missing layer never
756       * inflates the score). Result is in 0.0–1.0 since every factor score is.
757       */
758      fun weightedFactorScore(factorScores: Map<String, Double>): Double =
759          FACTOR_WEIGHTS.entries.sumOf { (k, w) -> w * (factorScores[k] ?: 0.0) }
760  
761      // ─── Prediction confidence ───────────────────────────────────────
762  
763      /**
764       * Prediction confidence (0.0–1.0) — how much to TRUST a cell's score, which is
765       * distinct from the score itself. Driven by how much *real* data backed the
766       * score rather than neutral fallbacks: a cell scored from nearby records plus
767       * full Earth-Engine + terrain layers is far more trustworthy than one resting
768       * on climate defaults alone. Lets the UI flag "model-based / low confidence"
769       * spots honestly instead of presenting every score as equally certain.
770       *
771       * @param nearbyRecords count of observations inside the evidence kernel
772       * @param weightedEvidence the kernel's quality·source·recency·spatial sum
773       * @param hasEnvLayers real Earth-Engine/OSM land data was available for the cell
774       * @param hasElevation real ground elevation resolved for the cell
775       */
776      fun predictionConfidence(
777          nearbyRecords: Int,
778          weightedEvidence: Double,
779          hasEnvLayers: Boolean,
780          hasElevation: Boolean
781      ): Double {
782          val dataQuality = listOf(hasEnvLayers, hasElevation).count { it } / 2.0   // 0 / 0.5 / 1
783          val evidenceStrength = minOf(1.0, weightedEvidence / 2.0)
784          val anyEvidence = if (nearbyRecords > 0) 1.0 else 0.0
785          return (0.45 * dataQuality + 0.35 * evidenceStrength + 0.20 * anyEvidence)
786              .coerceIn(0.0, 1.0)
787      }
788  
789      /** Three-band label for a [predictionConfidence] value. */
790      fun confidenceLabel(confidence: Double): String = when {
791          confidence >= 0.66 -> "High"
792          confidence >= 0.33 -> "Medium"
793          else -> "Low"
794      }
795  
796      // ─── 5-tier classification ───────────────────────────────────────
797  
798      /**
799       * Maps a 0.0–1.0 score to one of five tiers.
800       */
801      fun classifyTier(score: Double): String = when {
802          score >= 0.80 -> "Excellent"
803          score >= 0.60 -> "VeryGood"
804          score >= 0.40 -> "Promising"
805          score >= 0.20 -> "Possible"
806          else -> "Unlikely"
807      }
808  }
```

---

<a id="appsrcmainjavacomexamplemodelfungimodelskt"></a>
## `app/src/main/java/com/example/model/FungiModels.kt`

**Role:** DATA MODELS — HotspotCell and the observation/species records.  
**Lines:** 105

**Key anchors:**
- HotspotCell :91 (lat/lng/score/tier/contributingFactors/cellSizeMeters/confidence)
- Observation :28, MapObservation :56, Species :7, UserSighting :70

```kotlin
  1  package com.example.model
  2  
  3  import androidx.room.Entity
  4  import androidx.room.PrimaryKey
  5  
  6  @Entity(tableName = "species")
  7  data class Species(
  8      @PrimaryKey val id: String,
  9      val scientificName: String,
 10      val commonNames: List<String>,
 11      val genus: String,
 12      val family: String,
 13      val habitatTypes: List<String>,
 14      val substrates: List<String>,
 15      val seasonStart: Int, // 1 = January, 12 = December
 16      val seasonEnd: Int,
 17      val capDescription: String,
 18      val gillDescription: String,
 19      val stemDescription: String,
 20      val sporeColor: String,
 21      val bruisingReaction: String,
 22      val lookAlikes: List<String>,
 23      val notes: String,
 24      val imageUrls: List<String>
 25  )
 26  
 27  @Entity(tableName = "observations")
 28  data class Observation(
 29      @PrimaryKey val id: Long,
 30      val speciesId: String,
 31      val lat: Double,
 32      val lng: Double,
 33      val observedAt: Long, // timestamp
 34      val source: String, // "iNaturalist", "MushroomObserver", "ALA", or "user"
 35      val photoUrl: String?,
 36      val qualityGrade: String, // "research", "needs_id", "casual"
 37      val cachedAt: Long = System.currentTimeMillis() // for TTL check
 38  )
 39  
 40  /**
 41   * A reference photo with its attribution/credit line (iNaturalist taxon
 42   * photos are CC-licensed and require attribution). Bundled images have no
 43   * attribution (null).
 44   */
 45  data class SpeciesPhoto(
 46      val url: String,
 47      val attribution: String?
 48  )
 49  
 50  /**
 51   * A lightweight, in-memory observation for the "all fungi sightings" map
 52   * layer — NOT persisted to Room (so no schema migration). Carries the taxon
 53   * label so pins can show the species/genus and common name straight from the
 54   * kingdom-wide iNaturalist query.
 55   */
 56  data class MapObservation(
 57      val id: Long,
 58      val lat: Double,
 59      val lng: Double,
 60      val taxonName: String,      // e.g. "Amanita muscaria" or "Cortinarius"
 61      val commonName: String?,    // e.g. "Fly Agaric"
 62      val source: String,         // "iNaturalist", "ALA", "GBIF"
 63      val observedAt: Long,
 64      val qualityGrade: String,
 65      val photoUrl: String?,
 66      val placeGuess: String?
 67  )
 68  
 69  @Entity(tableName = "user_sightings")
 70  data class UserSighting(
 71      @PrimaryKey(autoGenerate = true) val id: Long = 0,
 72      val speciesId: String,
 73      val lat: Double,
 74      val lng: Double,
 75      val timestamp: Long,
 76      val photoLocalPath: String?,
 77      val notes: String,
 78      val isPrivate: Boolean
 79  )
 80  
 81  /**
 82   * 5-tier hotspot cell for the prediction grid.
 83   *
 84   * Tiers:
 85   *   Excellent  (>80%)  — perfect storm of evidence, season, and conditions
 86   *   VeryGood   (60-80%) — strong indicators across multiple factors
 87   *   Promising  (40-60%) — several positive factors present
 88   *   Possible   (20-40%) — some positive indicators
 89   *   Unlikely   (<20%)  — few or no positive indicators
 90   */
 91  data class HotspotCell(
 92      val lat: Double,
 93      val lng: Double,
 94      val score: Double, // 0 to 1
 95      val tier: String,  // "Excellent" / "VeryGood" / "Promising" / "Possible" / "Unlikely"
 96      val contributingFactors: List<String>,
 97      // Edge length (metres) of this cell's square — the grid resolution it was
 98      // scored at. Lets the map draw each cell at its true size, so the broad
 99      // ~250 m overview grid and a fine Deep-Search sub-grid render correctly on
100      // the same map. Defaults to the overview grid's nominal size.
101      val cellSizeMeters: Double = 250.0,
102      // How much to TRUST this score (0.0–1.0), based on how much real evidence +
103      // environmental data backed it vs neutral fallbacks. Distinct from the score.
104      val confidence: Double = 0.5
105  )
```

---

<a id="appsrcmainjavacomexampledataremoteinaturalistapikt"></a>
## `app/src/main/java/com/example/data/remote/INaturalistApi.kt`

**Role:** DATA SOURCE — iNaturalist fungal observations (evidence + sighting pins). No key.  
**Lines:** 107

```kotlin
  1  package com.example.data.remote
  2  
  3  import com.squareup.moshi.Json
  4  import retrofit2.http.GET
  5  import retrofit2.http.Query
  6  
  7  interface INaturalistApi {
  8      @GET("observations")
  9      suspend fun getObservations(
 10          @Query("taxon_name") taxonName: String,
 11          @Query("lat") lat: Double,
 12          @Query("lng") lng: Double,
 13          @Query("radius") radiusKm: Double,
 14          @Query("d1") sinceDate: String, // YYYY-MM-DD
 15          @Query("quality_grade") qualityGrade: String = "research",
 16          @Query("per_page") perPage: Int = 100
 17      ): INatResponse
 18  
 19      /**
 20       * Taxon lookup — pulls a gallery of reference photos for a species by
 21       * scientific name. iNaturalist returns curated `taxon_photos` (CC
 22       * licensed, attributed) plus a `default_photo`, giving every species
 23       * multiple images without bundling them in the APK.
 24       */
 25      @GET("taxa")
 26      suspend fun getTaxa(
 27          @Query("q") query: String,
 28          @Query("rank") rank: String = "species",
 29          @Query("per_page") perPage: Int = 1
 30      ): INatTaxaResponse
 31  
 32      /**
 33       * All fungal observations in a map area in a SINGLE request, by querying
 34       * the whole Fungi kingdom (taxon_name=Fungi) rather than 40 per-species
 35       * calls. Powers the "all sightings" map layer and the aggregate
 36       * fungal-activity signal in predictions. `quality_grade` is left
 37       * unconstrained so casual + needs-id records show too; `photos`/`geo`
 38       * keep only mappable, evidenced records.
 39       */
 40      @GET("observations")
 41      suspend fun getAreaObservations(
 42          @Query("lat") lat: Double,
 43          @Query("lng") lng: Double,
 44          @Query("radius") radiusKm: Double,
 45          @Query("d1") sinceDate: String,
 46          @Query("taxon_name") taxonName: String = "Fungi",
 47          @Query("photos") hasPhotos: Boolean = true,
 48          @Query("geo") hasGeo: Boolean = true,
 49          @Query("order_by") orderBy: String = "observed_on",
 50          @Query("order") order: String = "desc",
 51          @Query("per_page") perPage: Int = 200
 52      ): INatResponse
 53  }
 54  
 55  data class INatTaxaResponse(
 56      @Json(name = "results") val results: List<INatTaxon>?
 57  )
 58  
 59  data class INatTaxon(
 60      @Json(name = "name") val name: String?,
 61      @Json(name = "default_photo") val defaultPhoto: INatTaxonPhoto?,
 62      @Json(name = "taxon_photos") val taxonPhotos: List<INatTaxonPhotoWrap>?
 63  )
 64  
 65  data class INatTaxonPhotoWrap(
 66      @Json(name = "photo") val photo: INatTaxonPhoto?
 67  )
 68  
 69  data class INatTaxonPhoto(
 70      @Json(name = "medium_url") val mediumUrl: String?,
 71      @Json(name = "url") val url: String?,
 72      @Json(name = "attribution") val attribution: String?
 73  )
 74  
 75  data class INatResponse(
 76      @Json(name = "results") val results: List<INatObservation>
 77  )
 78  
 79  data class INatObservation(
 80      @Json(name = "id") val id: Long,
 81      @Json(name = "latitude") val latitude: Double?,
 82      @Json(name = "longitude") val longitude: Double?,
 83      @Json(name = "location") val location: String?,
 84      @Json(name = "geojson") val geojson: INatGeoJson?,
 85      @Json(name = "observed_on") val observedOn: String?,
 86      @Json(name = "quality_grade") val qualityGrade: String?,
 87      @Json(name = "photos") val photos: List<INatPhoto>?,
 88      @Json(name = "taxon") val taxon: INatObsTaxon?,
 89      @Json(name = "place_guess") val placeGuess: String?
 90  )
 91  
 92  // Taxon attached to an observation — used to label map pins with the
 93  // species/genus name and common name when querying the whole Fungi kingdom.
 94  data class INatObsTaxon(
 95      @Json(name = "name") val name: String?,
 96      @Json(name = "preferred_common_name") val commonName: String?,
 97      @Json(name = "rank") val rank: String?
 98  )
 99  
100  // iNaturalist returns coordinates as GeoJSON: { "coordinates": [lng, lat] }
101  data class INatGeoJson(
102      @Json(name = "coordinates") val coordinates: List<Double>?
103  )
104  
105  data class INatPhoto(
106      @Json(name = "url") val url: String?
107  )
```

---

<a id="appsrcmainjavacomexampledataremoteopenmeteoapikt"></a>
## `app/src/main/java/com/example/data/remote/OpenMeteoApi.kt`

**Role:** DATA SOURCE — Open-Meteo rainfall/temperature history + elevation. No key.  
**Lines:** 60

```kotlin
 1  package com.example.data.remote
 2  
 3  import com.squareup.moshi.Json
 4  import retrofit2.http.GET
 5  import retrofit2.http.Query
 6  
 7  interface OpenMeteoApi {
 8      /**
 9       * Fetches past weather data for rainfall lag analysis.
10       *
11       * Uses 45 days of history so the lag analysis window (10-21 days ago)
12       * is fully covered with room to spare, and we can compute accurate
13       * recent averages for temperature suitability scoring.
14       */
15      @GET("v1/forecast")
16      suspend fun getPastWeather(
17          @Query("latitude") limitLat: Double,
18          @Query("longitude") limitLng: Double,
19          @Query("daily") dailyParams: String = "precipitation_sum,temperature_2m_max,temperature_2m_min",
20          @Query("hourly") hourlyParams: String = "soil_moisture_0_to_7cm",
21          @Query("past_days") pastDays: Int = 45,
22          @Query("forecast_days") forecastDays: Int = 0,
23          @Query("timezone") timezone: String = "auto"
24      ): OpenMeteoResponse
25  
26      /**
27       * Fetches ground elevation (metres above sea level) for one or more
28       * coordinates in a single call. Open-Meteo's elevation API is free,
29       * needs no API key, and accepts up to 100 comma-separated coordinate
30       * pairs per request — ideal for scoring a whole hotspot grid at once.
31       */
32      @GET("v1/elevation")
33      suspend fun getElevation(
34          @Query("latitude") latitudeCsv: String,
35          @Query("longitude") longitudeCsv: String
36      ): OpenMeteoElevationResponse
37  }
38  
39  data class OpenMeteoElevationResponse(
40      @Json(name = "elevation") val elevation: List<Double?>?
41  )
42  
43  data class OpenMeteoResponse(
44      @Json(name = "latitude") val latitude: Double,
45      @Json(name = "longitude") val longitude: Double,
46      @Json(name = "daily") val daily: OpenMeteoDaily,
47      @Json(name = "hourly") val hourly: OpenMeteoHourly? = null
48  )
49  
50  data class OpenMeteoHourly(
51      @Json(name = "time") val time: List<String>?,
52      @Json(name = "soil_moisture_0_to_7cm") val soilMoisture0to7cm: List<Double?>?
53  )
54  
55  data class OpenMeteoDaily(
56      @Json(name = "time") val time: List<String>,
57      @Json(name = "precipitation_sum") val precipitationSum: List<Double?>?,
58      @Json(name = "temperature_2m_max") val temperatureMax: List<Double?>?,
59      @Json(name = "temperature_2m_min") val temperatureMin: List<Double?>?
60  )
```

---

<a id="appsrcmainjavacomexampledataremoteoverpassapikt"></a>
## `app/src/main/java/com/example/data/remote/OverpassApi.kt`

**Role:** DATA SOURCE — OSM Overpass canopy/woodland + mulch beds. No key.  
**Lines:** 58

```kotlin
 1  package com.example.data.remote
 2  
 3  import com.squareup.moshi.Json
 4  import retrofit2.http.Field
 5  import retrofit2.http.FormUrlEncoded
 6  import retrofit2.http.POST
 7  
 8  /**
 9   * OpenStreetMap Overpass API — free, no API key.
10   *
11   * Used to pull canopy/green-cover features (woods, forests, parks, nature
12   * reserves) within the hotspot grid's bounding box in a single query. Each
13   * cell is then scored by its proximity to the nearest such feature, giving a
14   * real per-cell "is this woodland?" signal for mycorrhizal / wood-rotting fungi.
15   *
16   * Base URL: https://overpass-api.de/
17   */
18  interface OverpassApi {
19      @FormUrlEncoded
20      @POST("api/interpreter")
21      suspend fun query(@Field("data") overpassQl: String): OverpassResponse
22  }
23  
24  data class OverpassResponse(
25      @Json(name = "elements") val elements: List<OverpassElement>?
26  )
27  
28  /**
29   * An Overpass element. For ways/relations we request `out center;`, so the
30   * representative coordinate arrives in [center]; nodes carry [lat]/[lon].
31   * When a query uses `out geom;` instead, the full ring is in [geometry] and the
32   * OSM [tags] are present so callers can classify the feature (green vs built).
33   */
34  data class OverpassElement(
35      @Json(name = "type") val type: String?,
36      @Json(name = "lat") val lat: Double?,
37      @Json(name = "lon") val lon: Double?,
38      @Json(name = "center") val center: OverpassCenter?,
39      @Json(name = "tags") val tags: Map<String, String>? = null,
40      @Json(name = "geometry") val geometry: List<OverpassGeom>? = null
41  ) {
42      /** Best-available latitude for this element (node lat or way/relation centre). */
43      fun resolvedLat(): Double? = lat ?: center?.lat
44  
45      /** Best-available longitude for this element (node lon or way/relation centre). */
46      fun resolvedLon(): Double? = lon ?: center?.lon
47  }
48  
49  data class OverpassCenter(
50      @Json(name = "lat") val lat: Double?,
51      @Json(name = "lon") val lon: Double?
52  )
53  
54  /** A single vertex of a way's geometry, present when a query uses `out geom;`. */
55  data class OverpassGeom(
56      @Json(name = "lat") val lat: Double?,
57      @Json(name = "lon") val lon: Double?
58  )
```

---

<a id="appsrcmainjavacomexampledataremoteenvlayersapikt"></a>
## `app/src/main/java/com/example/data/remote/EnvLayersApi.kt`

**Role:** DATA SOURCE — POST env-grid to the Earth Engine backend (optional; null -> OSM fallback).  
**Lines:** 40

**Key anchors:**
- EnvGridRequest :25, EnvGridResponse :30

```kotlin
 1  package com.example.data.remote
 2  
 3  import com.squareup.moshi.Json
 4  import retrofit2.http.Body
 5  import retrofit2.http.Header
 6  import retrofit2.http.POST
 7  
 8  /**
 9   * Client for the Mycelium Mapper environmental-layers backend (Cloud Run +
10   * Google Earth Engine). The backend keeps the service-account credential
11   * server-side, so the app only ever talks HTTPS — no GCP key ships in the APK.
12   *
13   * Base URL is supplied at build time via BuildConfig.BACKEND_BASE_URL; when
14   * blank the app skips this entirely and falls back to the free OSM canopy.
15   */
16  interface EnvLayersApi {
17      @POST("env-grid")
18      suspend fun envGrid(
19          @Header("X-Api-Token") token: String,
20          @Body request: EnvGridRequest
21      ): EnvGridResponse
22  }
23  
24  /** Grid points as [[lat, lng], ...]. */
25  data class EnvGridRequest(
26      @Json(name = "points") val points: List<List<Double>>
27  )
28  
29  /** Arrays aligned 1:1 with the request points; entries may be null. */
30  data class EnvGridResponse(
31      @Json(name = "landcover") val landcover: List<Double?>?, // ESA WorldCover class codes
32      @Json(name = "canopy") val canopy: List<Double?>?,       // tree-canopy cover %
33      @Json(name = "ndvi") val ndvi: List<Double?>?,           // NDVI greenness (−1..1)
34      @Json(name = "water_dist") val waterDist: List<Double?>? = null, // metres to nearest surface water
35      @Json(name = "soil_ph") val soilPh: List<Double?>? = null,       // surface soil pH (H2O)
36      @Json(name = "soil_sand") val soilSand: List<Double?>? = null,   // surface sand mass-fraction %
37      @Json(name = "soil_moisture") val soilMoisture: List<Double?>? = null, // 14-day mean vol. soil water (m³/m³)
38      @Json(name = "twi") val twi: List<Double?>? = null,      // topographic wetness index
39      @Json(name = "forest_type") val forestType: List<Double?>? = null // Copernicus forest leaf-type class (1-5)
40  )
```

---

<a id="appsrcmainjavacomexampledataremotegeocodingapikt"></a>
## `app/src/main/java/com/example/data/remote/GeocodingApi.kt`

**Role:** DATA SOURCE — place search / reverse geocode (map centring only, not scoring).  
**Lines:** 48

```kotlin
 1  package com.example.data.remote
 2  
 3  import com.squareup.moshi.Json
 4  import retrofit2.http.GET
 5  import retrofit2.http.Query
 6  
 7  /**
 8   * Google Maps Geocoding API — converts a place name ("Dandenong Ranges",
 9   * "Sherbrooke Forest") into coordinates for the map's "Area" search. More
10   * reliable than the on-device Android Geocoder, which frequently returns no
11   * results when the device lacks a backing geocoder service.
12   *
13   * Base URL: https://maps.googleapis.com/  (needs BuildConfig.GOOGLE_API_KEY)
14   */
15  interface GeocodingApi {
16      @GET("maps/api/geocode/json")
17      suspend fun geocode(
18          @Query("address") address: String,
19          @Query("key") key: String
20      ): GeocodeResponse
21  
22      /** Reverse geocode: coordinates → a place name for the map header. */
23      @GET("maps/api/geocode/json")
24      suspend fun reverseGeocode(
25          @Query("latlng") latlng: String,
26          @Query("key") key: String,
27          @Query("result_type") resultType: String = "locality|administrative_area_level_2|administrative_area_level_1|natural_feature|park"
28      ): GeocodeResponse
29  }
30  
31  data class GeocodeResponse(
32      @Json(name = "status") val status: String?,
33      @Json(name = "results") val results: List<GeocodeResult>?
34  )
35  
36  data class GeocodeResult(
37      @Json(name = "formatted_address") val formattedAddress: String?,
38      @Json(name = "geometry") val geometry: GeocodeGeometry?
39  )
40  
41  data class GeocodeGeometry(
42      @Json(name = "location") val location: GeocodeLocation?
43  )
44  
45  data class GeocodeLocation(
46      @Json(name = "lat") val lat: Double?,
47      @Json(name = "lng") val lng: Double?
48  )
```

---

<a id="appsrcmainjavacomexampledataremotebiodiversityapiskt"></a>
## `app/src/main/java/com/example/data/remote/BiodiversityApis.kt`

**Role:** DATA SOURCE — additional biodiversity feeds (ALA/GBIF) for the all-fungi layer.  
**Lines:** 152

```kotlin
  1  package com.example.data.remote
  2  
  3  import com.squareup.moshi.Json
  4  import retrofit2.http.GET
  5  import retrofit2.http.Query
  6  
  7  /**
  8   * Atlas of Living Australia biocache web service.
  9   * Provides access to Australian biodiversity occurrence records including
 10   * herbarium specimens (verified by mycologists — weighted highest).
 11   *
 12   * Base URL: https://biocache-ws.ala.org.au/ws/
 13   */
 14  interface ALAApi {
 15      @GET("occurrences/search")
 16      suspend fun searchOccurrences(
 17          @Query("q") query: String,             // e.g. "Psilocybe subaeruginosa"
 18          @Query("lat") lat: Double,
 19          @Query("lon") lon: Double,
 20          @Query("radius") radiusKm: Double,
 21          @Query("fq") filterQuery: String = "kingdom:Fungi",
 22          @Query("pageSize") pageSize: Int = 100,
 23          @Query("sort") sort: String = "year",
 24          @Query("dir") dir: String = "desc"
 25      ): ALAResponse
 26  }
 27  
 28  data class ALAResponse(
 29      @Json(name = "totalRecords") val totalRecords: Int,
 30      @Json(name = "occurrences") val occurrences: List<ALAOccurrence>?
 31  )
 32  
 33  data class ALAOccurrence(
 34      @Json(name = "uuid") val uuid: String?,
 35      @Json(name = "scientificName") val scientificName: String?,
 36      @Json(name = "decimalLatitude") val decimalLatitude: Double?,
 37      @Json(name = "decimalLongitude") val decimalLongitude: Double?,
 38      @Json(name = "eventDate") val eventDate: String?,      // ISO date
 39      @Json(name = "year") val year: Int?,
 40      @Json(name = "month") val month: Int?,
 41      @Json(name = "basisOfRecord") val basisOfRecord: String?,  // "PRESERVED_SPECIMEN", "HUMAN_OBSERVATION", etc.
 42      @Json(name = "institutionCode") val institutionCode: String?,
 43      @Json(name = "dataResourceName") val dataResourceName: String?
 44  )
 45  
 46  /**
 47   * GBIF Occurrence API.
 48   * Global Biodiversity Information Facility — aggregates occurrence records
 49   * from museums, herbaria, and citizen science platforms worldwide.
 50   *
 51   * Base URL: https://api.gbif.org/v1/
 52   */
 53  interface GBIFApi {
 54      @GET("occurrence/search")
 55      suspend fun searchOccurrences(
 56          @Query("scientificName") scientificName: String,
 57          @Query("decimalLatitude") lat: String,       // e.g. "-38,-37" for range
 58          @Query("decimalLongitude") lon: String,      // e.g. "144,146" for range
 59          @Query("kingdomKey") kingdomKey: Int = 5,    // Fungi kingdom key in GBIF
 60          @Query("country") country: String = "AU",
 61          @Query("limit") limit: Int = 100,
 62          @Query("hasCoordinate") hasCoordinate: Boolean = true
 63      ): GBIFResponse
 64  
 65      /**
 66       * All fungal occurrence records (museum/herbarium + observations) in a map
 67       * area — used to plot global GBIF specimen pins alongside iNaturalist
 68       * sightings. No species filter; kingdomKey=5 keeps it to Fungi.
 69       */
 70      @GET("occurrence/search")
 71      suspend fun searchAreaOccurrences(
 72          @Query("decimalLatitude") lat: String,        // "min,max"
 73          @Query("decimalLongitude") lon: String,       // "min,max"
 74          @Query("kingdomKey") kingdomKey: Int = 5,
 75          @Query("hasCoordinate") hasCoordinate: Boolean = true,
 76          @Query("hasGeospatialIssue") hasGeoIssue: Boolean = false,
 77          @Query("limit") limit: Int = 200
 78      ): GBIFResponse
 79  
 80      /**
 81       * Total GBIF record count for a species worldwide (limit=0 returns the
 82       * count without the records). Surfaced on the detail screen as how widely
 83       * the species has been recorded.
 84       */
 85      @GET("occurrence/search")
 86      suspend fun countOccurrences(
 87          @Query("scientificName") scientificName: String,
 88          @Query("kingdomKey") kingdomKey: Int = 5,
 89          @Query("limit") limit: Int = 0
 90      ): GBIFResponse
 91  
 92      /**
 93       * Global fungal taxonomy search — the GBIF species backbone covers every
 94       * described fungus on Earth (~150k species). `highertaxonKey=5` constrains
 95       * to kingdom Fungi. This turns the catalogue into a searchable front-end
 96       * over the entire global fungal taxonomy, not just the bundled field guide.
 97       *
 98       * Deliberately loose: [rank] and [status] are nullable and omitted by
 99       * default, so synonyms, genus-level hits and infraspecific names all
100       * surface (a search for a well-known synonym or a bare genus shouldn't come
101       * back empty). Callers re-rank the results client-side by relevance.
102       */
103      @GET("species/search")
104      suspend fun searchSpecies(
105          @Query("q") query: String,
106          @Query("highertaxonKey") fungiKingdomKey: Int = 5,
107          @Query("rank") rank: String? = null,
108          @Query("status") status: String? = null,
109          @Query("limit") limit: Int = 60
110      ): GBIFSpeciesResponse
111  }
112  
113  data class GBIFSpeciesResponse(
114      @Json(name = "results") val results: List<GBIFSpecies>?
115  )
116  
117  data class GBIFSpecies(
118      @Json(name = "key") val key: Long?,
119      @Json(name = "scientificName") val scientificName: String?,
120      @Json(name = "canonicalName") val canonicalName: String?,
121      @Json(name = "kingdom") val kingdom: String?,
122      @Json(name = "phylum") val phylum: String?,
123      @Json(name = "order") val order: String?,
124      @Json(name = "family") val family: String?,
125      @Json(name = "genus") val genus: String?,
126      @Json(name = "rank") val rank: String?,
127      @Json(name = "vernacularNames") val vernacularNames: List<GBIFVernacular>?
128  )
129  
130  data class GBIFVernacular(
131      @Json(name = "vernacularName") val vernacularName: String?,
132      @Json(name = "language") val language: String?
133  )
134  
135  data class GBIFResponse(
136      @Json(name = "count") val count: Int,
137      @Json(name = "results") val results: List<GBIFOccurrence>?
138  )
139  
140  data class GBIFOccurrence(
141      @Json(name = "key") val key: Long?,
142      @Json(name = "scientificName") val scientificName: String?,
143      @Json(name = "decimalLatitude") val decimalLatitude: Double?,
144      @Json(name = "decimalLongitude") val decimalLongitude: Double?,
145      @Json(name = "eventDate") val eventDate: String?,
146      @Json(name = "year") val year: Int?,
147      @Json(name = "month") val month: Int?,
148      @Json(name = "basisOfRecord") val basisOfRecord: String?,  // "PRESERVED_SPECIMEN", "HUMAN_OBSERVATION"
149      @Json(name = "institutionCode") val institutionCode: String?,
150      @Json(name = "datasetName") val datasetName: String?,
151      @Json(name = "issues") val issues: List<String>?
152  )
```

---

<a id="backendmainpy"></a>
## `backend/main.py`

**Role:** BACKEND (optional) — Flask + Google Earth Engine; POST /env-grid returns land-cover, canopy%, NDVI, water distance, soil pH/sand/moisture, TWI, forest type.  
**Lines:** 260

**Key anchors:**
- /env-grid :70
- auth :54, ensure_ee :45, /health :65

```python
  1  """
  2  Mycelium Mapper — environmental layers proxy (Google Earth Engine).
  3  
  4  Runs on Cloud Run as a service account (Application Default Credentials),
  5  so NO service-account key file is needed at deploy time or runtime. The
  6  Android app calls this service over HTTPS to get per-cell environmental
  7  layers (land cover, tree-canopy %, NDVI vegetation greenness, and distance
  8  to surface water) that feed the hotspot prediction engine.
  9  
 10  Endpoints:
 11    GET  /health            → liveness probe
 12    POST /env-grid          → body {"points": [[lat,lng], ...]} (≤600)
 13                              → {"landcover": [...], "canopy": [...], "ndvi": [...],
 14                                 "water_dist": [...],     # metres to nearest water
 15                                 "soil_ph": [...],        # surface soil pH (H2O)
 16                                 "soil_sand": [...],      # surface sand mass %
 17                                 "soil_moisture": [...],  # 14-day mean vol. soil water (m³/m³)
 18                                 "twi": [...],            # topographic wetness index
 19                                 "forest_type": [...]}    # Copernicus forest leaf-type class
 20                              arrays are aligned 1:1 with the input points;
 21                              entries may be null where a layer has no value. The
 22                              v2 layers (soil_*, twi) are each independently
 23                              guarded — a failure in any one returns nulls for that
 24                              layer only and never affects the core layers above.
 25  
 26  Security: if BACKEND_TOKEN is set, every request (except /health) must send
 27  a matching `X-Api-Token` header. Deploy authenticated where possible; the
 28  token is a lightweight guard against casual abuse of the public endpoint.
 29  """
 30  import datetime
 31  import hmac
 32  import math
 33  import os
 34  
 35  import ee
 36  from flask import Flask, jsonify, request
 37  
 38  app = Flask(__name__)
 39  
 40  _EE_READY = False
 41  BACKEND_TOKEN = os.environ.get("BACKEND_TOKEN", "")
 42  MAX_POINTS = 600
 43  
 44  
 45  def ensure_ee() -> None:
 46      """Initialise Earth Engine once, using the runtime service account (ADC)."""
 47      global _EE_READY
 48      if not _EE_READY:
 49          project = os.environ.get("GCP_PROJECT") or os.environ.get("GOOGLE_CLOUD_PROJECT")
 50          ee.Initialize(project=project)
 51          _EE_READY = True
 52  
 53  
 54  @app.before_request
 55  def _auth():
 56      if request.path == "/health":
 57          return None
 58      if BACKEND_TOKEN and not hmac.compare_digest(
 59          request.headers.get("X-Api-Token", ""), BACKEND_TOKEN
 60      ):
 61          return jsonify({"error": "unauthorized"}), 401
 62      return None
 63  
 64  
 65  @app.get("/health")
 66  def health():
 67      return jsonify({"status": "ok"})
 68  
 69  
 70  @app.post("/env-grid")
 71  def env_grid():
 72      try:
 73          ensure_ee()
 74      except Exception as exc:  # noqa: BLE001
 75          return jsonify({"error": f"earth engine init failed: {exc}"}), 500
 76  
 77      body = request.get_json(force=True, silent=True) or {}
 78      points = body.get("points") or []
 79      if not points:
 80          return jsonify({"error": "no points supplied"}), 400
 81      if len(points) > MAX_POINTS:
 82          return jsonify({"error": f"too many points (max {MAX_POINTS})"}), 400
 83  
 84      # FeatureCollection of points (Earth Engine expects [lng, lat]).
 85      try:
 86          feats = [
 87              ee.Feature(ee.Geometry.Point([float(p[1]), float(p[0])]), {"idx": i})
 88              for i, p in enumerate(points)
 89          ]
 90      except (TypeError, ValueError, IndexError):
 91          return jsonify({"error": "points must be numeric [lat, lng] pairs"}), 400
 92      fc = ee.FeatureCollection(feats)
 93  
 94      # ── Layers ────────────────────────────────────────────────────────
 95      # ESA WorldCover 2021 — 10 m global land-cover classes.
 96      landcover = ee.Image("ESA/WorldCover/v200/2021").select("Map").rename("landcover")
 97      # Hansen Global Forest Change — continuous tree-canopy cover (%).
 98      canopy = (
 99          ee.Image("UMD/hansen/global_forest_change_2023_v1_11")
100          .select("treecover2000")
101          .rename("canopy")
102      )
103      # Recent Sentinel-2 NDVI (vegetation greenness), cloud-filtered median.
104      end = datetime.date.today()
105      start = end - datetime.timedelta(days=120)
106      s2 = (
107          ee.ImageCollection("COPERNICUS/S2_SR_HARMONIZED")
108          .filterBounds(fc)
109          .filterDate(start.isoformat(), end.isoformat())
110          .filter(ee.Filter.lt("CLOUDY_PIXEL_PERCENTAGE", 40))
111          .median()
112      )
113      ndvi = s2.normalizedDifference(["B8", "B4"]).rename("ndvi")
114  
115      stack = landcover.addBands(canopy).addBands(ndvi)
116      sampled = stack.reduceRegions(
117          collection=fc, reducer=ee.Reducer.first(), scale=30
118      )
119  
120      try:
121          features = sampled.getInfo()["features"]
122      except Exception as exc:  # noqa: BLE001
123          return jsonify({"error": f"earth engine compute failed: {exc}"}), 502
124  
125      by_idx = {f["properties"].get("idx"): f["properties"] for f in features}
126      n = len(points)
127  
128      def col(name):
129          return [by_idx.get(i, {}).get(name) for i in range(n)]
130  
131      # Distance (m) to surface water — a riparian signal. Computed and sampled
132      # separately and guarded, so any failure here never breaks the core layers.
133      water_col = [None] * n
134      try:
135          occ = ee.Image("JRC/GSW1_4/GlobalSurfaceWater").select("occurrence")
136          proj = occ.projection()  # native 30 m grid
137          # 1 = water (≥20% occurrence), 0 = land/no-data. unmask(0) drops the
138          # fixed projection, so reproject back to the native 30 m grid.
139          # NOTE: fastDistanceTransform measures distance to the nearest
140          # NON-ZERO pixel, so water must be the 1s (no .Not() inversion!).
141          water = occ.gte(20).unmask(0).reproject(proj)
142          # → squared euclidean distance (pixels) to nearest water pixel.
143          # √ → pixels, × nominalScale → metres. Beyond the 256-px neighbourhood
144          # (≈7.7 km) it returns a huge saturated value, which the app already
145          # buckets to neutral (>2 km), so no clamping is needed.
146          wd = water.fastDistanceTransform(256).sqrt().multiply(
147              ee.Number(proj.nominalScale())
148          )
149          wfeat = wd.reduceRegions(collection=fc, reducer=ee.Reducer.first(), scale=30).getInfo()["features"]
150          # Reducer.first() on a single-band image names the output property
151          # "first" (NOT the band name) — reading "water_dist" here silently
152          # yields null for every cell.
153          wby = {f["properties"].get("idx"): f["properties"].get("first") for f in wfeat}
154          water_col = [wby.get(i) for i in range(n)]
155      except Exception as exc:  # noqa: BLE001
156          app.logger.warning("water distance failed: %s", exc)
157  
158      # ── v2: soil pH/texture + topographic wetness (static layers) ───────
159      # Sampled as their own guarded group, separate from the proven core
160      # stack above, so a wrong asset id or band here can only null these
161      # columns — it can never break landcover/canopy/ndvi.
162      soil_ph_col = [None] * n
163      soil_sand_col = [None] * n
164      twi_col = [None] * n
165      try:
166          # OpenLandMap surface (0 cm) soil pH (H2O); stored ×10, so ÷10 → real pH.
167          ph = (
168              ee.Image("OpenLandMap/SOL/SOL_PH-H2O_USDA-4C1A2A_M/v02")
169              .select("b0")
170              .divide(10)
171              .rename("soil_ph")
172          )
173          # OpenLandMap surface sand mass-fraction (%). High sand → fast-draining.
174          sand = (
175              ee.Image("OpenLandMap/SOL/SOL_SAND-WFRACTION_USDA-3A1A1A_M/v02")
176              .select("b0")
177              .rename("soil_sand")
178          )
179          # Topographic Wetness Index = ln(specific catchment area / tan(slope)).
180          # MERIT Hydro 'upa' = upstream drainage area (km²) → m², divided by the
181          # ~90 m cell width for specific catchment area; slope from its DEM.
182          # tan(slope) is floored away from 0 so flats don't divide by zero.
183          merit = ee.Image("MERIT/Hydro/v1_0_1")
184          slope_rad = ee.Terrain.slope(merit.select("elv")).multiply(math.pi / 180.0)
185          tan_slope = slope_rad.tan().max(0.001)
186          twi = (
187              merit.select("upa").multiply(1e6).divide(90.0).divide(tan_slope)
188              .log().rename("twi")
189          )
190          soil_stack = ph.addBands(sand).addBands(twi)
191          # Multi-band image → reduceRegions names each output property after its
192          # band (unlike the single-band "first" quirk used for water below).
193          sfeat = soil_stack.reduceRegions(
194              collection=fc, reducer=ee.Reducer.first(), scale=90
195          ).getInfo()["features"]
196          sby = {f["properties"].get("idx"): f["properties"] for f in sfeat}
197          soil_ph_col = [sby.get(i, {}).get("soil_ph") for i in range(n)]
198          soil_sand_col = [sby.get(i, {}).get("soil_sand") for i in range(n)]
199          twi_col = [sby.get(i, {}).get("twi") for i in range(n)]
200      except Exception as exc:  # noqa: BLE001
201          app.logger.warning("soil/twi layers failed: %s", exc)
202  
203      # ── v2: antecedent soil moisture — the fruiting trigger ─────────────
204      # 14-day mean volumetric soil water (m³/m³) from ERA5-Land daily aggregates.
205      # A trailing window, not a snapshot, since fruiting follows rain by ~1-3 wks.
206      sm_col = [None] * n
207      try:
208          sm_end = datetime.date.today()
209          sm_start = sm_end - datetime.timedelta(days=14)
210          sm = (
211              ee.ImageCollection("ECMWF/ERA5_LAND/DAILY_AGGR")
212              .filterDate(sm_start.isoformat(), sm_end.isoformat())
213              .select("volumetric_soil_water_layer_1")
214              .mean()
215          )
216          # Single band → output property is "first" (same quirk as water below).
217          smfeat = sm.reduceRegions(
218              collection=fc, reducer=ee.Reducer.first(), scale=10000
219          ).getInfo()["features"]
220          smby = {f["properties"].get("idx"): f["properties"].get("first") for f in smfeat}
221          sm_col = [smby.get(i) for i in range(n)]
222      except Exception as exc:  # noqa: BLE001
223          app.logger.warning("soil moisture layer failed: %s", exc)
224  
225      # ── v2/P2: forest leaf-type → mycorrhizal host group ────────────────
226      # Copernicus 100 m forest_type: 1 evergreen-needleleaf, 2 evergreen-broadleaf,
227      # 3 deciduous-needleleaf, 4 deciduous-broadleaf, 5 mixed (0/none = unknown).
228      # Lets the app match a cell's trees to each species' host (pine/eucalypt/oak).
229      forest_col = [None] * n
230      try:
231          ft = ee.Image("COPERNICUS/Landcover/100m/Proba-V-C3/Global/2019").select("forest_type")
232          # Single band → output property is "first".
233          ftfeat = ft.reduceRegions(
234              collection=fc, reducer=ee.Reducer.first(), scale=100
235          ).getInfo()["features"]
236          ftby = {f["properties"].get("idx"): f["properties"].get("first") for f in ftfeat}
237          forest_col = []
238          for i in range(n):
239              v = ftby.get(i)
240              forest_col.append(int(v) if v is not None else None)
241      except Exception as exc:  # noqa: BLE001
242          app.logger.warning("forest type layer failed: %s", exc)
243  
244      return jsonify(
245          {
246              "landcover": col("landcover"),
247              "canopy": col("canopy"),
248              "ndvi": col("ndvi"),
249              "water_dist": water_col,
250              "soil_ph": soil_ph_col,
251              "soil_sand": soil_sand_col,
252              "soil_moisture": sm_col,
253              "twi": twi_col,
254              "forest_type": forest_col,
255          }
256      )
257  
258  
259  if __name__ == "__main__":
260      app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 8080)))
```

---

<a id="appsrctestjavacomexamplemycomathtestkt"></a>
## `app/src/test/java/com/example/MycoMathTest.kt`

**Role:** TESTS — unit coverage for the scoring math (run by CI testDebugUnitTest).  
**Lines:** 509

```kotlin
  1  package com.example
  2  
  3  import com.example.util.MycoMath
  4  import org.junit.Assert.assertEquals
  5  import org.junit.Assert.assertFalse
  6  import org.junit.Assert.assertTrue
  7  import org.junit.Test
  8  
  9  /**
 10   * Unit tests for the pure geospatial/seasonal helpers that drive the
 11   * hotspot prediction engine. These run on the JVM (no Android required).
 12   */
 13  class MycoMathTest {
 14  
 15      @Test
 16      fun `month inside a normal season window is in season`() {
 17          // Autumn window April(4) to June(6)
 18          assertTrue(MycoMath.isMonthInSeason(4, 4, 6))
 19          assertTrue(MycoMath.isMonthInSeason(5, 4, 6))
 20          assertTrue(MycoMath.isMonthInSeason(6, 4, 6))
 21      }
 22  
 23      @Test
 24      fun `month outside a normal season window is out of season`() {
 25          assertFalse(MycoMath.isMonthInSeason(3, 4, 6))
 26          assertFalse(MycoMath.isMonthInSeason(7, 4, 6))
 27          assertFalse(MycoMath.isMonthInSeason(12, 4, 6))
 28      }
 29  
 30      @Test
 31      fun `season window that wraps across the new year is handled`() {
 32          // November(11) through February(2)
 33          assertTrue(MycoMath.isMonthInSeason(11, 11, 2))
 34          assertTrue(MycoMath.isMonthInSeason(12, 11, 2))
 35          assertTrue(MycoMath.isMonthInSeason(1, 11, 2))
 36          assertTrue(MycoMath.isMonthInSeason(2, 11, 2))
 37          assertFalse(MycoMath.isMonthInSeason(3, 11, 2))
 38          assertFalse(MycoMath.isMonthInSeason(6, 11, 2))
 39      }
 40  
 41      @Test
 42      fun `haversine distance between identical points is zero`() {
 43          assertEquals(0.0, MycoMath.haversineMeters(-37.8136, 144.9631, -37.8136, 144.9631), 1e-6)
 44      }
 45  
 46      @Test
 47      fun `haversine distance between Melbourne CBD and Dandenong Ranges is in expected range`() {
 48          // Melbourne CBD (-37.8136, 144.9631) to Dandenong Ranges (-37.8386, 145.3524)
 49          val meters = MycoMath.haversineMeters(-37.8136, 144.9631, -37.8386, 145.3524)
 50          // Straight-line distance is roughly 34 km.
 51          assertTrue("Expected ~34 km, got ${meters / 1000.0} km", meters in 30_000.0..40_000.0)
 52      }
 53  
 54      // ─── Elevation fitness ───────────────────────────────────────────
 55  
 56      @Test
 57      fun `elevation inside the species band scores perfectly`() {
 58          // Cortinarius band is montane (200-1400 m); 800 m sits mid-band.
 59          assertEquals(1.0, MycoMath.elevationFitness(800.0, "cortinarius_archeri"), 1e-9)
 60      }
 61  
 62      @Test
 63      fun `elevation far outside the species band scores low`() {
 64          // Psilocybe band is lowland (0-700 m); 2500 m is well above it.
 65          val high = MycoMath.elevationFitness(2500.0, "psilocybe_subaeruginosa")
 66          assertTrue("Expected a low score, got $high", high < 0.3)
 67      }
 68  
 69      @Test
 70      fun `elevation fitness is always within 0 and 1`() {
 71          for (e in listOf(-50.0, 0.0, 500.0, 1500.0, 4000.0)) {
 72              val s = MycoMath.elevationFitness(e, "amanita_muscaria")
 73              assertTrue("score $s out of range for $e m", s in 0.0..1.0)
 74          }
 75      }
 76  
 77      // ─── Terrain moisture ────────────────────────────────────────────
 78  
 79      @Test
 80      fun `a hollow on a gentle slope scores higher than an exposed local high`() {
 81          // Cell sits below its neighbours (concave hollow) on gentle relief.
 82          val hollow = MycoMath.terrainMoistureScore(
 83              cellElevation = 290.0,
 84              neighbourElevations = listOf(300.0, 305.0, 298.0, 310.0)
 85          )
 86          // Cell sits above its neighbours (exposed knoll), same gentle relief.
 87          val knoll = MycoMath.terrainMoistureScore(
 88              cellElevation = 320.0,
 89              neighbourElevations = listOf(300.0, 305.0, 298.0, 310.0)
 90          )
 91          assertTrue("hollow ($hollow) should beat knoll ($knoll)", hollow > knoll)
 92      }
 93  
 94      @Test
 95      fun `terrain score with no neighbour data falls back to neutral`() {
 96          assertEquals(0.5, MycoMath.terrainMoistureScore(300.0, emptyList()), 1e-9)
 97      }
 98  
 99      // ─── Slope aspect (Southern Hemisphere) ──────────────────────────
100  
101      @Test
102      fun `south-facing slope scores higher than north-facing`() {
103          // South-facing: north neighbour higher, south neighbour lower.
104          val southFacing = MycoMath.slopeAspectMoistureScore(
105              elevCenter = 300.0, elevNorth = 330.0, elevSouth = 270.0, elevEast = 300.0, elevWest = 300.0
106          )
107          // North-facing: the reverse.
108          val northFacing = MycoMath.slopeAspectMoistureScore(
109              elevCenter = 300.0, elevNorth = 270.0, elevSouth = 330.0, elevEast = 300.0, elevWest = 300.0
110          )
111          assertTrue("south ($southFacing) should beat north ($northFacing)", southFacing > northFacing)
112      }
113  
114      @Test
115      fun `flat ground gives a neutral aspect score`() {
116          val flat = MycoMath.slopeAspectMoistureScore(300.0, 300.0, 300.0, 300.0, 300.0)
117          assertEquals(0.70, flat, 1e-9)
118      }
119  
120      // ─── Soil moisture ───────────────────────────────────────────────
121  
122      @Test
123      fun `ideal damp soil scores full, bone-dry scores low`() {
124          assertEquals(1.0, MycoMath.soilMoistureFitness(0.32), 1e-9)
125          assertTrue(MycoMath.soilMoistureFitness(0.05) < 0.3)
126      }
127  
128      // ─── Canopy proximity ────────────────────────────────────────────
129  
130      @Test
131      fun `closer woodland scores higher, and missing data is neutral`() {
132          assertEquals(1.0, MycoMath.canopyProximityScore(50.0), 1e-9)
133          assertTrue(MycoMath.canopyProximityScore(100.0) > MycoMath.canopyProximityScore(2000.0))
134          assertEquals(0.6, MycoMath.canopyProximityScore(null), 1e-9)
135      }
136  
137      // ─── Earth Engine layers ─────────────────────────────────────────
138  
139      @Test
140      fun `dense tree cover scores higher than sparse`() {
141          assertEquals(1.0, MycoMath.treeCanopyFitness(85.0), 1e-9)
142          assertTrue(MycoMath.treeCanopyFitness(70.0) > MycoMath.treeCanopyFitness(8.0))
143      }
144  
145      @Test
146      fun `lush ndvi scores high, water scores low`() {
147          assertEquals(1.0, MycoMath.ndviFitness(0.6), 1e-9)
148          assertTrue(MycoMath.ndviFitness(-0.2) < 0.2)
149      }
150  
151      @Test
152      fun `forest land cover beats built-up for a generalist`() {
153          val tree = MycoMath.landCoverSuitability(10, "amanita_muscaria")   // tree cover
154          val built = MycoMath.landCoverSuitability(50, "amanita_muscaria")  // built-up
155          assertTrue("tree ($tree) should beat built ($built)", tree > built)
156      }
157  
158      @Test
159      fun `psilocybe tolerates pasture and urban mulch better than a generalist`() {
160          val psiGrass = MycoMath.landCoverSuitability(30, "psilocybe_subaeruginosa")
161          val genGrass = MycoMath.landCoverSuitability(30, "amanita_muscaria")
162          assertTrue("psilocybe ($psiGrass) should beat generalist ($genGrass) on grassland", psiGrass > genGrass)
163      }
164  
165      // ─── Habitat gate (the urban counter-weight) ─────────────────────
166  
167      @Test
168      fun `built-up and water land cover are gated to near-zero`() {
169          assertTrue("built-up should be heavily gated", MycoMath.habitatGate(50, 0.05, "amanita_muscaria") < 0.2)
170          assertTrue("water should be gated to near-zero", MycoMath.habitatGate(80, -0.1, "amanita_muscaria") < 0.15)
171      }
172  
173      @Test
174      fun `forest passes the gate, far above built-up`() {
175          val forest = MycoMath.habitatGate(10, 0.6, "amanita_muscaria")
176          val city = MycoMath.habitatGate(50, 0.0, "amanita_muscaria")
177          assertEquals(1.0, forest, 1e-9)
178          assertTrue("forest ($forest) must dominate city ($city)", forest > city * 3)
179      }
180  
181      @Test
182      fun `negative ndvi vetoes a cell even when land cover is unknown`() {
183          // No EE land-cover class, but clearly non-vegetated (pavement/water).
184          assertTrue(MycoMath.habitatGate(null, -0.05, "amanita_muscaria") <= 0.2)
185      }
186  
187      @Test
188      fun `psilocybe keeps a floor on urban mulch that forest species do not`() {
189          val psi = MycoMath.habitatGate(50, 0.2, "psilocybe_subaeruginosa")
190          val gen = MycoMath.habitatGate(50, 0.2, "amanita_muscaria")
191          assertTrue("psilocybe ($psi) tolerates built-up better than generalist ($gen)", psi > gen)
192      }
193  
194      @Test
195      fun `riparian score rewards proximity to water and is neutral when far`() {
196          assertEquals(1.0, MycoMath.riparianScore(50.0), 1e-9)
197          assertTrue(MycoMath.riparianScore(200.0) > MycoMath.riparianScore(1500.0))
198          assertEquals(0.45, MycoMath.riparianScore(null), 1e-9)   // no data → neutral, not penalised
199          assertEquals(0.45, MycoMath.riparianScore(5000.0), 1e-9) // far → neutral
200      }
201  
202      @Test
203      fun `rich canopy score blends the three layers within range`() {
204          val s = MycoMath.richCanopyScore(canopyPct = 75.0, ndvi = 0.7, worldCoverClass = 10, speciesId = "cortinarius_archeri")
205          assertTrue("expected a strong woodland score, got $s", s > 0.8)
206          // All-null inputs collapse to the neutral midpoints, never out of range.
207          val neutral = MycoMath.richCanopyScore(null, null, null, "amanita_muscaria")
208          assertTrue(neutral in 0.0..1.0)
209      }
210  
211      @Test
212      fun `soil pH fitness peaks in the slightly acidic to neutral range`() {
213          assertEquals(1.0, MycoMath.soilPhFitness(6.0), 1e-9)   // ideal
214          assertEquals(1.0, MycoMath.soilPhFitness(7.0), 1e-9)   // neutral edge
215          assertTrue(MycoMath.soilPhFitness(3.5) < MycoMath.soilPhFitness(6.0)) // strongly acidic worse
216          assertTrue(MycoMath.soilPhFitness(8.5) < MycoMath.soilPhFitness(7.0)) // alkaline falls off
217          assertEquals(0.6, MycoMath.soilPhFitness(null), 1e-9)  // no data → neutral
218      }
219  
220      @Test
221      fun `soil drainage fitness favours loam over clay and pure sand`() {
222          assertEquals(1.0, MycoMath.soilDrainageFitness(40.0), 1e-9)        // loamy → ideal
223          assertTrue(MycoMath.soilDrainageFitness(95.0) < 1.0)               // very sandy → dries out
224          assertTrue(MycoMath.soilDrainageFitness(5.0) < 1.0)               // very clayey → waterlogs
225          assertEquals(0.6, MycoMath.soilDrainageFitness(null), 1e-9)        // no data → neutral
226      }
227  
228      @Test
229      fun `rich soil score stays in range and is neutral with no data`() {
230          assertTrue(MycoMath.richSoilScore(6.0, 40.0) > 0.9)
231          assertEquals(0.6, MycoMath.richSoilScore(null, null), 1e-9)
232          assertTrue(MycoMath.richSoilScore(9.0, 95.0) in 0.0..1.0)
233      }
234  
235      @Test
236      fun `twi wetness score rewards moist hollows over dry ridges`() {
237          assertEquals(1.0, MycoMath.twiWetnessScore(10.0), 1e-9)            // moist footslope → ideal
238          assertTrue(MycoMath.twiWetnessScore(2.0) < MycoMath.twiWetnessScore(10.0)) // dry ridge worse
239          assertTrue(MycoMath.twiWetnessScore(18.0) < MycoMath.twiWetnessScore(10.0)) // waterlogged tapers off
240          assertEquals(0.5, MycoMath.twiWetnessScore(null), 1e-9)            // no data → neutral
241      }
242  
243      @Test
244      fun `host groups are derived from habitat and substrate text`() {
245          val pineBirch = MycoMath.hostGroupsFor(
246              listOf("Conifer Plantation", "Exotic Deciduous Woodlands"),
247              listOf("Soil (mycorrhizal with Pinus radiata, Birch)")
248          )
249          assertTrue(MycoMath.HostGroup.NEEDLELEAF in pineBirch)
250          assertTrue(MycoMath.HostGroup.DECIDUOUS_BROADLEAF in pineBirch)
251  
252          val euc = MycoMath.hostGroupsFor(listOf("Eucalypt Woodland"), listOf("Base of living trees (Eucalyptus)"))
253          assertTrue(MycoMath.HostGroup.EVERGREEN_BROADLEAF in euc)
254  
255          // Dung/pasture saprobes are not tree-bound.
256          val dung = MycoMath.hostGroupsFor(listOf("Pasture / Grazing Land"), listOf("Cattle dung"))
257          assertTrue(dung.isEmpty())
258      }
259  
260      @Test
261      fun `host tree match rewards the right forest type and is neutral off-grid`() {
262          val pine = setOf(MycoMath.HostGroup.NEEDLELEAF)
263          assertEquals(1.0, MycoMath.hostTreeMatchScore(1, pine), 1e-9)      // evergreen-needleleaf → host present
264          assertTrue(MycoMath.hostTreeMatchScore(2, pine) < 0.6)            // eucalypt forest, wrong host
265          assertTrue(MycoMath.hostTreeMatchScore(5, pine) > 0.7)           // mixed forest → likely host
266          assertEquals(0.6, MycoMath.hostTreeMatchScore(2, emptySet()), 1e-9) // not tree-bound → neutral
267          assertEquals(0.55, MycoMath.hostTreeMatchScore(null, pine), 1e-9)   // no data → mild neutral
268      }
269  
270      @Test
271      fun `seasonal fitness peaks mid-window and falls off outside it`() {
272          // Autumn window April(4)–June(6): peak ≈ mid-May (day ~120).
273          val peak = MycoMath.seasonalFitness(135, 4, 6)      // ~mid-May (peak)
274          val shoulder = MycoMath.seasonalFitness(170, 4, 6)  // in the shoulder grace zone
275          val offSeason = MycoMath.seasonalFitness(330, 4, 6) // late Nov
276          assertEquals(1.0, peak, 1e-9)
277          assertTrue("shoulder should be a partial, non-zero score", shoulder in 0.01..0.99)
278          assertEquals(0.0, offSeason, 1e-9)
279      }
280  
281      @Test
282      fun `seasonal fitness wraps across the new year`() {
283          // Summer window November(11)–February(2): peak ≈ late December.
284          val midSummer = MycoMath.seasonalFitness(362, 11, 2) // ~28 Dec
285          val midWinter = MycoMath.seasonalFitness(166, 11, 2) // ~mid-June (opposite)
286          assertEquals(1.0, midSummer, 1e-9)
287          assertTrue("winter should score far below the summer peak", midWinter < 0.2)
288          // A day just inside the wrap (early Jan) should still score well.
289          assertTrue(MycoMath.seasonalFitness(10, 11, 2) > 0.6)
290      }
291  
292      @Test
293      fun `short single-month season keeps a sensible plateau`() {
294          // start == end (May only) must not collapse to a single high-scoring day.
295          val atPeak = MycoMath.seasonalFitness(135, 5, 5)     // mid-May
296          val tenDaysOff = MycoMath.seasonalFitness(145, 5, 5) // still within the floored core
297          assertEquals(1.0, atPeak, 1e-9)
298          assertEquals(1.0, tenDaysOff, 1e-9)
299      }
300  
301      @Test
302      fun `terrain moisture is scale-aware and backward compatible`() {
303          val cell = 100.0
304          val gentle = listOf(99.5, 99.5, 100.5, 100.5)  // ~1 m relief across the cell
305          // Default param reproduces the legacy (500 m) calibration exactly.
306          assertEquals(
307              MycoMath.terrainMoistureScore(cell, gentle),
308              MycoMath.terrainMoistureScore(cell, gentle, 500.0),
309              1e-9
310          )
311          // At a fine (15 m) spacing the same 1 m relief reads as a real slope, so
312          // the score discriminates instead of collapsing to the flat baseline.
313          assertTrue(
314              MycoMath.terrainMoistureScore(cell, gentle, 15.0) >
315                  MycoMath.terrainMoistureScore(cell, gentle, 500.0)
316          )
317      }
318  
319      @Test
320      fun `slope aspect is scale-aware and backward compatible`() {
321          // South-facing: terrain ~1 m higher to the north, lower to the south.
322          val sFineDefault = MycoMath.slopeAspectMoistureScore(100.0, 100.5, 99.5, 100.0, 100.0)
323          val sFine500 = MycoMath.slopeAspectMoistureScore(100.0, 100.5, 99.5, 100.0, 100.0, 500.0)
324          val sFine15 = MycoMath.slopeAspectMoistureScore(100.0, 100.5, 99.5, 100.0, 100.0, 15.0)
325          assertEquals(sFineDefault, sFine500, 1e-9)             // default == legacy 500 m
326          assertTrue(sFine15 > sFine500)                          // fine scale sees the aspect
327          assertTrue(sFine15 > 0.85)                              // clearly south-facing
328      }
329  
330      @Test
331      fun `rainfall trigger detects a multi-day soaking pulse`() {
332          // 45 days, all light (5mm) except a 3-day soak ~15 days ago where no single
333          // 2-day window hits 20mm but the 3-day total (24mm) does.
334          val rain = MutableList(45) { 5.0 }
335          val soakIdx = 45 - 15
336          rain[soakIdx] = 9.0; rain[soakIdx - 1] = 9.0; rain[soakIdx - 2] = 9.0  // 3-day = 27mm
337          val score = MycoMath.rainfallTriggerScore(rain)
338          // Background-only (no pulse) baseline for comparison.
339          val baseline = MycoMath.rainfallTriggerScore(MutableList(45) { 5.0 })
340          assertTrue("multi-day pulse should raise the trigger score", score > baseline)
341      }
342  
343      @Test
344      fun `rainfall trigger is zero for too-little data and rewards a burst at optimal lag`() {
345          assertEquals(0.0, MycoMath.rainfallTriggerScore(emptyList()), 1e-9)
346          assertEquals(0.0, MycoMath.rainfallTriggerScore(listOf(50.0)), 1e-9)  // <2 days
347          // A strong 2-day burst ~15 days ago (optimal) should beat the same burst
348          // sitting outside the 10-21 day fruiting window (e.g. only 2 days ago).
349          val optimal = MutableList(45) { 0.0 }
350          optimal[45 - 15] = 30.0; optimal[45 - 16] = 30.0
351          val tooRecent = MutableList(45) { 0.0 }
352          tooRecent[45 - 2] = 30.0; tooRecent[45 - 3] = 30.0
353          assertTrue(MycoMath.rainfallTriggerScore(optimal) > MycoMath.rainfallTriggerScore(tooRecent))
354      }
355  
356      // ─── Canonical factor weights (shared by both pipelines) ─────────
357  
358      @Test
359      fun `factor weights sum to exactly one`() {
360          // If this drifts, every score is silently mis-scaled — and the
361          // single-species and aggregate maps would no longer be comparable.
362          assertEquals(1.0, MycoMath.FACTOR_WEIGHTS.values.sum(), 1e-9)
363      }
364  
365      @Test
366      fun `weighted factor score honours the weights and treats missing factors as zero`() {
367          // All factors perfect → the weighted sum equals the total weight (1.0).
368          val allPerfect = MycoMath.FACTOR_WEIGHTS.keys.associateWith { 1.0 }
369          assertEquals(1.0, MycoMath.weightedFactorScore(allPerfect), 1e-9)
370          // An empty map → 0.0 (a missing layer never inflates the score).
371          assertEquals(0.0, MycoMath.weightedFactorScore(emptyMap()), 1e-9)
372          // A single factor contributes exactly its weight.
373          assertEquals(
374              MycoMath.FACTOR_WEIGHTS.getValue("evidence"),
375              MycoMath.weightedFactorScore(mapOf("evidence" to 1.0)),
376              1e-9
377          )
378          // Unknown keys are ignored, not summed.
379          assertEquals(0.0, MycoMath.weightedFactorScore(mapOf("not_a_factor" to 1.0)), 1e-9)
380      }
381  
382      // ─── Temperature fitness ─────────────────────────────────────────
383  
384      @Test
385      fun `temperature fitness peaks in the species band and decays outside it`() {
386          // Psilocybe band is 5-15 C → 10 C sits mid-band.
387          assertEquals(1.0, MycoMath.temperatureFitness(10.0, "psilocybe_subaeruginosa"), 1e-9)
388          // Boletus prefers warmer (10-20 C), so at 18 C it beats the cool-loving psilocybe.
389          assertTrue(
390              MycoMath.temperatureFitness(18.0, "boletus_edulis") >
391                  MycoMath.temperatureFitness(18.0, "psilocybe_subaeruginosa")
392          )
393          // Far outside any band scores low and stays in range.
394          val hot = MycoMath.temperatureFitness(40.0, "psilocybe_subaeruginosa")
395          assertTrue("expected a low score, got $hot", hot < 0.3)
396          assertTrue(hot in 0.0..1.0)
397      }
398  
399      // ─── Habitat breadth & affinity ──────────────────────────────────
400  
401      @Test
402      fun `habitat diversity rewards broader tolerance and stays clamped`() {
403          val broad = MycoMath.habitatDiversityScore(
404              listOf("a", "b", "c", "d"), listOf("x", "y", "z")
405          )
406          val narrow = MycoMath.habitatDiversityScore(listOf("a"), listOf("x"))
407          assertTrue("broad ($broad) should beat narrow ($narrow)", broad > narrow)
408          // Even a species with no listed habitat keeps the 0.2 floor.
409          assertEquals(0.2, MycoMath.habitatDiversityScore(emptyList(), emptyList()), 1e-9)
410          assertTrue(broad in 0.2..1.0)
411      }
412  
413      @Test
414      fun `habitat-specific species weight more than broad generalists`() {
415          assertTrue(
416              MycoMath.speciesHabitatWeight("psilocybe_subaeruginosa") >
417                  MycoMath.speciesHabitatWeight("trametes_versicolor")
418          )
419      }
420  
421      // ─── Evidence kernel components ──────────────────────────────────
422  
423      @Test
424      fun `evidence quality, source and recency weights rank as expected`() {
425          assertTrue(MycoMath.qualityWeight("research") > MycoMath.qualityWeight("casual"))
426          assertEquals(0.5, MycoMath.qualityWeight("anything-unknown"), 1e-9)
427          // First-hand user sightings and verified ALA/GBIF records outweigh casual iNat.
428          assertTrue(MycoMath.sourceWeight("USER") > MycoMath.sourceWeight("INATURALIST"))
429          assertTrue(MycoMath.sourceWeight("ALA") > MycoMath.sourceWeight("INATURALIST"))
430          // Recency: one half-life (365 d) halves the weight; today's record is full.
431          assertEquals(1.0, MycoMath.recencyWeight(0.0), 1e-9)
432          assertEquals(0.5, MycoMath.recencyWeight(365.0), 1e-3)
433          assertEquals(0.0, MycoMath.recencyWeight(-5.0), 1e-9)   // future date → no weight
434      }
435  
436      @Test
437      fun `spatial kernel is full at the centre and decays with distance`() {
438          assertEquals(1.0, MycoMath.spatialKernel(0.0), 1e-9)
439          assertTrue(MycoMath.spatialKernel(400.0) > MycoMath.spatialKernel(1600.0))
440          assertTrue(MycoMath.spatialKernel(5000.0) < 0.01)
441      }
442  
443      // ─── Moon phase ──────────────────────────────────────────────────
444  
445      @Test
446      fun `moon phase is a normalised cycle and the fruiting score stays in band`() {
447          for (t in listOf(0L, 1_600_000_000_000L, 1_700_000_000_000L)) {
448              val phase = MycoMath.moonPhase(t)
449              assertTrue("phase $phase out of [0,1)", phase >= 0.0 && phase < 1.0)
450              val s = MycoMath.moonFruitingScore(t)
451              assertTrue("moon score $s out of [0.3,1.0]", s in 0.3..1.0)
452          }
453      }
454  
455      // ─── Tier classification ─────────────────────────────────────────
456  
457      @Test
458      fun `tier thresholds classify each band`() {
459          assertEquals("Excellent", MycoMath.classifyTier(0.85))
460          assertEquals("VeryGood", MycoMath.classifyTier(0.70))
461          assertEquals("Promising", MycoMath.classifyTier(0.45))
462          assertEquals("Possible", MycoMath.classifyTier(0.25))
463          assertEquals("Unlikely", MycoMath.classifyTier(0.10))
464          // Boundaries are inclusive at the lower edge of each tier.
465          assertEquals("Excellent", MycoMath.classifyTier(0.80))
466          assertEquals("Possible", MycoMath.classifyTier(0.20))
467      }
468  
469      @Test
470      fun `prediction confidence rises with evidence and real data`() {
471          val none = MycoMath.predictionConfidence(0, 0.0, hasEnvLayers = false, hasElevation = false)
472          val layersOnly = MycoMath.predictionConfidence(0, 0.0, hasEnvLayers = true, hasElevation = true)
473          val full = MycoMath.predictionConfidence(4, 3.0, hasEnvLayers = true, hasElevation = true)
474  
475          assertTrue(none < 0.33)                                  // pure climate guess → Low
476          assertEquals("Low", MycoMath.confidenceLabel(none))
477          assertTrue(layersOnly in 0.33..0.66)                     // real layers, no records → Medium
478          assertEquals("Medium", MycoMath.confidenceLabel(layersOnly))
479          assertTrue(full >= 0.66)                                 // records + full layers → High
480          assertEquals("High", MycoMath.confidenceLabel(full))
481          assertTrue(full > layersOnly && layersOnly > none)       // monotonic with data richness
482      }
483  
484      // ─── Tanbark / woodchip (mulch) ──────────────────────────────────
485  
486      @Test
487      fun `mulch affinity flags woodchip lovers and ignores forest fungi`() {
488          // Gold tops name woodchips/mulch in their substrates → full affinity.
489          val goldTop = MycoMath.mulchAffinity(
490              listOf("Urban Woodchips", "Disturbed Paths"),
491              listOf("Wood chips", "Decaying eucalyptus mulch")
492          )
493          assertEquals(1.0, goldTop, 1e-9)
494          // A purely mycorrhizal forest species → no mulch association.
495          val forest = MycoMath.mulchAffinity(listOf("Eucalypt Woodland"), listOf("Soil (mycorrhizal with Eucalyptus)"))
496          assertEquals(0.0, forest, 1e-9)
497          // "Disturbed/urban garden" earns a partial (loose) affinity.
498          val loose = MycoMath.mulchAffinity(listOf("Disturbed urban ground"), emptyList())
499          assertTrue(loose in 0.01..0.99)
500      }
501  
502      @Test
503      fun `mulch proximity is full at the bed, tapers out, and never penalises`() {
504          assertEquals(1.0, MycoMath.mulchProximityScore(20.0), 1e-9)   // in the bed
505          assertTrue(MycoMath.mulchProximityScore(150.0) in 0.01..0.99) // tapering
506          assertEquals(0.0, MycoMath.mulchProximityScore(500.0), 1e-9)  // far → no bonus
507          assertEquals(0.0, MycoMath.mulchProximityScore(null), 1e-9)   // none mapped → no bonus, not a penalty
508      }
509  }
```
