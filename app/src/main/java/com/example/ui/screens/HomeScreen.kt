package com.example.ui.screens

import android.text.format.DateUtils
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.model.Observation
import com.example.model.Species
import com.example.model.UserSighting
import com.example.ui.viewmodel.FungiViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: FungiViewModel,
    onNavigateToSearch: () -> Unit,
    onNavigateToMap: () -> Unit,
    onNavigateToSightings: () -> Unit,
    onSpeciesSelected: (Species) -> Unit
) {
    val speciesList by viewModel.speciesList.collectAsState()
    val userSightings by viewModel.userSightings.collectAsState()
    val mapCenter by viewModel.mapCenter.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    
    // Compute list of observations from cached pins across all species
    val recentObservations by viewModel.observationPins.collectAsState()

    // Query nearby observations automatically on first launch or when mapCenter changes
    LaunchedEffect(speciesList, mapCenter) {
        if (speciesList.isNotEmpty()) {
            // Automatically queue current active coordinates to populate observations on Home!
            val mainFocusSpecies = speciesList.firstOrNull { it.id == "psilocybe_subaeruginosa" } ?: speciesList.first()
            viewModel.selectedSpeciesForHotspot.value = mainFocusSpecies
            viewModel.computeHotspots()
        }
    }

    val filteredList = remember(searchQuery, speciesList) {
        if (searchQuery.isEmpty()) emptyList()
        else speciesList.filter {
            it.scientificName.contains(searchQuery, ignoreCase = true) ||
                    it.commonNames.any { name -> name.contains(searchQuery, ignoreCase = true) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "MYCELIUM MAPPER",
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 20.sp,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            text = "Victoria Field Mycology Terminal",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToMap) {
                        Icon(imageVector = Icons.Default.Map, contentDescription = "Observation Map")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // 1. Search Bar Section
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("home_search_input"),
                        placeholder = { Text("Search species or common names...") },
                        leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = "Search") },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(imageVector = Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    // Overlay filtered quick results
                    AnimatedVisibility(
                        visible = filteredList.isNotEmpty(),
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
                            shape = RoundedCornerShape(8.dp),
                            tonalElevation = 8.dp
                        ) {
                            Column {
                                filteredList.forEach { spec ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                searchQuery = ""
                                                onSpeciesSelected(spec)
                                            }
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.FilterList,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(end = 12.dp)
                                        )
                                        Column {
                                            Text(
                                                text = spec.scientificName,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(
                                                text = spec.commonNames.joinToString(", "),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. Location Indicator Panel
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.GpsFixed,
                                contentDescription = "GPS Active",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "CURRENT FIELD GRID STATION",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Dandenong Ranges Sclerophyll Area",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Lat: ${String.format(Locale.US, "%.5f", mapCenter.first)}  Lng: ${String.format(Locale.US, "%.5f", mapCenter.second)}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Button(
                            onClick = onNavigateToMap,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("MAP")
                        }
                    }
                }
            }

            // 3. User Sightings section
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "YOUR RECENT FIELD LOGS",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    TextButton(onClick = onNavigateToSightings) {
                        Text("VIEW ALL (${userSightings.size})")
                    }
                }

                if (userSightings.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .clickable { onNavigateToSightings() },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.AddAPhoto,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "No logged sightings yet. Tap to log a discovery.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                    }
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(userSightings.take(5)) { sighting ->
                            UserSightingRowCard(
                                sighting = sighting,
                                speciesList = speciesList,
                                onClick = onNavigateToSightings
                            )
                        }
                    }
                }
            }

            // 4. Nearby iNaturalist Observations
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "iNATURALIST FIELD TRACKS",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            if (recentObservations.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Fetching nearby iNaturalist research observation layers...",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(recentObservations.take(15)) { obs ->
                    ObservationItemRow(
                        observation = obs,
                        speciesList = speciesList,
                        onSpeciesClicked = { sId ->
                            val s = speciesList.find { it.id == sId }
                            if (s != null) onSpeciesSelected(s)
                        }
                    )
                }
            }

            // Footer info
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                )
                Text(
                    text = "Mycelium Mapper Victoria Mycology Division • Offline Room Engine ACTIVE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp, start = 16.dp, end = 16.dp)
                )
            }
        }
    }
}

@Composable
fun UserSightingRowCard(
    sighting: UserSighting,
    speciesList: List<Species>,
    onClick: () -> Unit
) {
    val associatedSpecies = speciesList.find { it.id == sighting.speciesId }
    val displayTitle = associatedSpecies?.scientificName ?: sighting.speciesId

    Surface(
        modifier = Modifier
            .width(220.dp)
            .height(130.dp)
            .clickable { onClick() }
            .border(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = sighting.notes.ifEmpty { "Field record without annotations." },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 15.sp,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val formattedDate = DateUtils.getRelativeTimeSpanString(
                    sighting.timestamp,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS
                )
                Text(
                    text = formattedDate.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                if (sighting.isPrivate) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Private entry",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun ObservationItemRow(
    observation: Observation,
    speciesList: List<Species>,
    onSpeciesClicked: (String) -> Unit
) {
    val assoc = speciesList.find { it.id == observation.speciesId }
    val title = assoc?.scientificName ?: observation.speciesId
    val commonName = assoc?.commonNames?.firstOrNull() ?: "Fungus Observation"

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable { onSpeciesClicked(observation.speciesId) },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail
            val context = LocalContext.current
            val imageReq = remember(observation.photoUrl) {
                ImageRequest.Builder(context)
                    .data(observation.photoUrl ?: "https://images.unsplash.com/photo-1599059813005-11265ba4b4ce")
                    .crossfade(true)
                    .build()
            }

            AsyncImage(
                model = imageReq,
                contentDescription = title,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = commonName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = observation.qualityGrade.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Lat: ${String.format(Locale.US, "%.4f", observation.lat)} Lng: ${String.format(Locale.US, "%.4f", observation.lng)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            IconButton(onClick = { onSpeciesClicked(observation.speciesId) }) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Details",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
