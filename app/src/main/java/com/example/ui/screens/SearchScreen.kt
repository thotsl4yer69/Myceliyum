package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FilterAlt
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
import com.example.model.Species
import com.example.ui.viewmodel.FungiViewModel
import java.util.*

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: FungiViewModel,
    onSpeciesSelected: (Species) -> Unit
) {
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedHabitatFilter by viewModel.selectedHabitatFilter.collectAsState()
    val selectedSeasonFilter by viewModel.selectedSeasonFilter.collectAsState()
    val selectedSporeFilter by viewModel.selectedSporeFilter.collectAsState()

    val filteredList by viewModel.filteredSpecies.collectAsState()
    val fullList by viewModel.speciesList.collectAsState()
    val globalResults by viewModel.globalResults.collectAsState()
    val isGlobalSearching by viewModel.isGlobalSearching.collectAsState()

    var showFiltersPanel by remember { mutableStateOf(false) }

    // Aggregate distinct filter values from all seeded species
    val allHabitats = remember(fullList) {
        fullList.flatMap { it.habitatTypes }.distinct().sorted()
    }
    val allSporeColors = remember(fullList) {
        fullList.map { it.sporeColor }.distinct().sorted()
    }
    val months = (1..12).toList()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Search & Filter Toggle Bar
        Surface(
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.searchQuery.value = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("species_search_input"),
                        placeholder = { Text("Search scientific / common name...") },
                        leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.searchQuery.value = "" }) {
                                    Icon(imageVector = Icons.Default.Close, contentDescription = "Clear text")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    IconButton(
                        onClick = { showFiltersPanel = !showFiltersPanel },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (showFiltersPanel || selectedHabitatFilter != null || selectedSeasonFilter != null || selectedSporeFilter != null) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FilterAlt,
                            contentDescription = "Toggle filters check sheet"
                        )
                    }
                }

                // Active Filters Row
                if (selectedHabitatFilter != null || selectedSeasonFilter != null || selectedSporeFilter != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        selectedHabitatFilter?.let {
                            FilterChip(
                                selected = true,
                                onClick = { viewModel.selectedHabitatFilter.value = null },
                                label = { Text("Habitat: $it") },
                                trailingIcon = { Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp)) }
                            )
                        }

                        selectedSeasonFilter?.let {
                            FilterChip(
                                selected = true,
                                onClick = { viewModel.selectedSeasonFilter.value = null },
                                label = { Text("Active in: ${getMonthNameShort(it)}") },
                                trailingIcon = { Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp)) }
                            )
                        }

                        selectedSporeFilter?.let {
                            FilterChip(
                                selected = true,
                                onClick = { viewModel.selectedSporeFilter.value = null },
                                label = { Text("Spore: $it") },
                                trailingIcon = { Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp)) }
                            )
                        }

                        TextButton(
                            onClick = { viewModel.resetFilters() },
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Clear All", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Inline Expandable Filters Panel
        AnimatedVisibility(
            visible = showFiltersPanel,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 6.dp,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
            ) {
                Column {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "Filters",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        // 1. Habitat Filter
                        Text("Habitat", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            allHabitats.forEach { hab ->
                                val isSelected = selectedHabitatFilter == hab
                                SuggestionChip(
                                    onClick = {
                                        viewModel.selectedHabitatFilter.value = if (isSelected) null else hab
                                    },
                                    label = { Text(hab, fontSize = 11.sp) },
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                        labelColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // 2. Season Filter
                        Text("Fruiting month", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            months.forEach { mon ->
                                val isSelected = selectedSeasonFilter == mon
                                SuggestionChip(
                                    onClick = {
                                        viewModel.selectedSeasonFilter.value = if (isSelected) null else mon
                                    },
                                    label = { Text(getMonthNameShort(mon), fontSize = 11.sp) },
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                        labelColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // 3. Spore Print Filter
                        Text("Spore print colour", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            allSporeColors.forEach { color ->
                                val isSelected = selectedSporeFilter == color
                                SuggestionChip(
                                    onClick = {
                                        viewModel.selectedSporeFilter.value = if (isSelected) null else color
                                    },
                                    label = { Text(color, fontSize = 11.sp) },
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                        labelColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { viewModel.resetFilters() }) {
                                Text("Reset")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = { showFiltersPanel = false }) {
                                Text("Apply")
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                }
            }
        }

        // Result count label
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = if (filteredList.size == 1) "1 species" else "${filteredList.size} species",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Search Results List — curated catalogue + worldwide GBIF results
        val trimmedQuery = searchQuery.trim()
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(filteredList) { spec ->
                SpeciesItemCard(
                    species = spec,
                    fetchThumbnail = { viewModel.fetchSpeciesImages(it).firstOrNull() },
                    onClick = { onSpeciesSelected(spec) }
                )
            }

            // Worldwide section — every described fungus via the GBIF taxonomy
            if (trimmedQuery.length >= 3 && (globalResults.isNotEmpty() || isGlobalSearching)) {
                item {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Public,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isGlobalSearching && globalResults.isEmpty())
                                    "Searching every fungus worldwide…"
                                else
                                    "Worldwide results · ${globalResults.size}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (isGlobalSearching) {
                                Spacer(modifier = Modifier.width(10.dp))
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            }
                        }
                        Text(
                            text = "From the GBIF global taxonomy — photos load from iNaturalist.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
                        )
                    }
                }
                items(globalResults) { spec ->
                    SpeciesItemCard(
                        species = spec,
                        fetchThumbnail = { viewModel.fetchSpeciesImages(it).firstOrNull() },
                        onClick = { onSpeciesSelected(spec) }
                    )
                }
            }

            // Inline empty hint when there is nothing to show yet
            if (filteredList.isEmpty() && globalResults.isEmpty() && !isGlobalSearching) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.SearchOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (trimmedQuery.length in 1..2)
                                "Keep typing to search worldwide…"
                            else
                                "Nothing matches your filters",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Type a fungus name to search every described species on Earth.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

    }
}

@Composable
fun SpeciesItemCard(
    species: Species,
    fetchThumbnail: (suspend (String) -> String?)? = null,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail — a bundled image if the species ships one, else
            // lazily pull the first iNaturalist taxon photo; falls back to a
            // neutral placeholder icon (no stock stand-ins).
            val ctx = LocalContext.current
            var fetchedThumb by remember(species.id) { mutableStateOf<String?>(null) }
            if (species.imageUrls.isEmpty() && fetchThumbnail != null) {
                LaunchedEffect(species.id) { fetchedThumb = fetchThumbnail(species.scientificName) }
            }
            val firstImage = species.imageUrls.firstOrNull() ?: fetchedThumb
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (!firstImage.isNullOrBlank()) {
                    val imageReq = remember(firstImage) {
                        ImageRequest.Builder(ctx)
                            .data(firstImage)
                            .crossfade(true)
                            .build()
                    }
                    AsyncImage(
                        model = imageReq,
                        contentDescription = species.scientificName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.FilterVintage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = species.scientificName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = species.commonNames.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CalendarToday,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Season: ${getMonthNameShort(species.seasonStart)} - ${getMonthNameShort(species.seasonEnd)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "Substrates: ${species.substrates.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Icon(
                imageVector = Icons.Default.ArrowForwardIos,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun getMonthNameShort(month: Int): String {
    return when (month) {
        1 -> "Jan"
        2 -> "Feb"
        3 -> "Mar"
        4 -> "Apr"
        5 -> "May"
        6 -> "Jun"
        7 -> "Jul"
        8 -> "Aug"
        9 -> "Sep"
        10 -> "Oct"
        11 -> "Nov"
        12 -> "Dec"
        else -> "N/A"
    }
}
