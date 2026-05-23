package com.example.ui.screens

import androidx.compose.foundation.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.model.Species
import com.example.ui.viewmodel.FungiViewModel
import java.util.*

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    species: Species,
    viewModel: FungiViewModel,
    onNavigateToMap: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val currentMonth = remember { Calendar.getInstance().get(Calendar.MONTH) + 1 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = species.scientificName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            fontStyle = FontStyle.Italic
                        )
                        Text(
                            text = species.commonNames.firstOrNull() ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
        ) {
            // 1. Image Carousel (Displays seeded URLs)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(Color.Black)
            ) {
                if (species.imageUrls.isNotEmpty()) {
                    val imageReq = remember(species.imageUrls) {
                        ImageRequest.Builder(context)
                            .data(species.imageUrls.first())
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
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.FilterVintage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(64.dp)
                        )
                    }
                }

                // Botanical Classification Tag
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "FAMILY: ${species.family.uppercase()}  •  GENUS: ${species.genus.uppercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }
            }

            // Quick Actions Block
            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = species.scientificName,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            fontStyle = FontStyle.Italic
                        )
                        Text(
                            text = species.commonNames.joinToString(", "),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = {
                            viewModel.selectedSpeciesForHotspot.value = species
                            viewModel.computeHotspots()
                            onNavigateToMap()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("find_in_area_button"),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Radar, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "FORECAST",
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(16.dp)) {

                // 2. Botanical Details Panel
                Text(
                    text = "SPECIMEN DESCRIPTION",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        FeatureDetailRow(label = "Cap (Pileus)", desc = species.capDescription)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                        FeatureDetailRow(label = "Gills (Lamellae)", desc = species.gillDescription)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                        FeatureDetailRow(label = "Stem (Stipe)", desc = species.stemDescription)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                        FeatureDetailRow(label = "Spore Print Color", desc = species.sporeColor, highlighted = true)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                        FeatureDetailRow(label = "Bruising Reaction", desc = species.bruisingReaction, highlighted = true)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 3. Ecological Settings (Habitats & Substrates)
                Text(
                    text = "ECOLOGICAL SIGNALS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    species.habitatTypes.forEach { hab ->
                        SuggestionChip(
                            onClick = {},
                            label = { Text("🌲 $hab", fontSize = 11.sp) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp),
                                labelColor = MaterialTheme.colorScheme.onSurface
                            ),
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        )
                    }

                    species.substrates.forEach { sub ->
                        SuggestionChip(
                            onClick = {},
                            label = { Text("🪵 $sub", fontSize = 11.sp) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp),
                                labelColor = MaterialTheme.colorScheme.onSurface
                            ),
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 4. Season Calendar Visualization
                Text(
                    text = "FRUITING CALENDAR",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Typical fruiting period: ${getMonthNameShort(species.seasonStart)} to ${getMonthNameShort(species.seasonEnd)} in SE Australia.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        // 12 Months Grid
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            (1..12).forEach { month ->
                                val isActive = isMonthInSeason(month, species.seasonStart, species.seasonEnd)
                                val isCurrent = month == currentMonth

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            when {
                                                isActive -> MaterialTheme.colorScheme.primaryContainer
                                                else -> MaterialTheme.colorScheme.surfaceVariant
                                            }
                                        )
                                        .border(
                                            width = if (isCurrent) 1.5.dp else 0.dp,
                                            color = if (isCurrent) MaterialTheme.colorScheme.error else Color.Transparent,
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                        .padding(vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = getMonthNameShort(month).take(1),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (isActive || isCurrent) FontWeight.Bold else FontWeight.Normal,
                                        fontFamily = FontFamily.Monospace,
                                        color = when {
                                            isActive -> MaterialTheme.colorScheme.onPrimaryContainer
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        }
                                    )
                                    if (isCurrent) {
                                        Box(
                                            modifier = Modifier
                                                .padding(top = 4.dp)
                                                .size(4.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.error)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 5. Look-Alikes Side-By-Side Section
                Text(
                    text = "CRITICAL FIELD COMPARISON (LOOK-ALIKES)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                species.lookAlikes.forEach { lookAlike ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Warning",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Be Aware of Look-alike:",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = lookAlike,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 6. Notes Panel
                Text(
                    text = "FIELD WORK RESEARCH NOTES",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp)
                ) {
                    Text(
                        text = species.notes,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 22.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun FeatureDetailRow(
    label: String,
    desc: String,
    highlighted: Boolean = false
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = desc,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 20.sp
        )
    }
}

private fun isMonthInSeason(month: Int, start: Int, end: Int): Boolean {
    return if (start <= end) {
        month in start..end
    } else {
        month >= start || month <= end
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
