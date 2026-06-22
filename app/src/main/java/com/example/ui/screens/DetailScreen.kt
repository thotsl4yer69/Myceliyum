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
import androidx.compose.animation.core.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
    onBack: () -> Unit,
    onOpenSpecies: (Species) -> Unit = {}
) {
    val context = LocalContext.current
    val currentMonth = remember { Calendar.getInstance().get(Calendar.MONTH) + 1 }
    // Catalogue, so a look-alike that we actually have an entry for becomes a
    // tappable cross-link — handy for jumping straight to a deadly mimic.
    val catalogue by viewModel.speciesList.collectAsState()

    // Pull a gallery of reference photos (with CC attribution) for this species
    // from iNaturalist and merge with any bundled images, so every species
    // shows multiple photos each crediting its source.
    var remotePhotos by remember(species.id) { mutableStateOf<List<com.example.model.SpeciesPhoto>>(emptyList()) }
    LaunchedEffect(species.id) {
        remotePhotos = viewModel.fetchSpeciesPhotos(species.scientificName)
    }
    val photos = remember(species.imageUrls, remotePhotos) {
        (species.imageUrls.map { com.example.model.SpeciesPhoto(it, null) } + remotePhotos)
            .distinctBy { it.url }
    }
    val images = photos.map { it.url }

    // Worldwide GBIF record count — how widely this species has been recorded.
    var recordCount by remember(species.id) { mutableStateOf<Int?>(null) }
    LaunchedEffect(species.id) {
        recordCount = viewModel.fetchGlobalRecordCount(species.scientificName)
    }

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
            // 1. Image Carousel with Pager & Shimmer
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .background(Color.Black)
            ) {
                if (images.isNotEmpty()) {
                    val pagerState = rememberPagerState(pageCount = { images.size })

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        var isLoading by remember { mutableStateOf(true) }
                        var isError by remember { mutableStateOf(false) }

                        Box(modifier = Modifier.fillMaxSize()) {
                            // Shimmer placeholder while loading
                            if (isLoading && !isError) {
                                ShimmerPlaceholder(modifier = Modifier.fillMaxSize())
                            }

                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(images[page])
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "${species.scientificName} photo ${page + 1}",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                onSuccess = { isLoading = false; isError = false },
                                onError = { isLoading = false; isError = true }
                            )

                            // Error fallback — shown when image fails to load
                            if (isError) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color(0xFF1A1A1A)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            imageVector = Icons.Default.FilterVintage,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                            modifier = Modifier.size(48.dp)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = species.scientificName,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color.White.copy(alpha = 0.7f),
                                            fontStyle = FontStyle.Italic
                                        )
                                        Text(
                                            text = "Image not available",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White.copy(alpha = 0.4f)
                                        )
                                    }
                                }
                            }

                            // Photo attribution (CC credit) for iNaturalist images
                            val credit = photos.getOrNull(page)?.attribution
                            if (!isError && !credit.isNullOrBlank()) {
                                Text(
                                    text = credit,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.85f),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color.Black.copy(alpha = 0.45f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    // Page indicator dots
                    if (images.size > 1) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 40.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            repeat(images.size) { index ->
                                Box(
                                    modifier = Modifier
                                        .size(if (pagerState.currentPage == index) 8.dp else 6.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (pagerState.currentPage == index)
                                                Color.White
                                            else
                                                Color.White.copy(alpha = 0.4f)
                                        )
                                )
                            }
                        }
                    }
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
                        text = "Family ${species.family} · Genus ${species.genus}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        letterSpacing = 0.5.sp
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
                        recordCount?.let { n ->
                            if (n > 0) {
                                Text(
                                    text = "🌍 ${"%,d".format(n)} records worldwide (GBIF)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
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
                            text = "Find on map",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(16.dp)) {

                // 1b. Safety banner — poisoning warning (when flagged) plus the
                // universal "never eat on app ID alone" disclaimer on every species.
                SafetyBanner(species)

                Spacer(modifier = Modifier.height(16.dp))

                // 2. Botanical Details Panel
                Text(
                    text = "Description",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
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
                    text = "Habitat & substrate",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
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
                    text = "Fruiting calendar",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
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
                    text = "Look-alikes to watch for",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                species.lookAlikes.forEach { lookAlike ->
                    val linked = remember(lookAlike, catalogue) {
                        resolveLookAlike(lookAlike, catalogue, species.id)
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .then(
                                if (linked != null) Modifier.clickable { onOpenSpecies(linked) }
                                else Modifier
                            )
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
                                    text = "Look-alike",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                if (linked != null) {
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text(
                                        text = "View",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Icon(
                                        imageVector = Icons.Default.ArrowForwardIos,
                                        contentDescription = "Open ${linked.scientificName}",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .padding(start = 4.dp)
                                            .size(12.dp)
                                    )
                                }
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
                    text = "Notes",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
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

/**
 * Edibility banner for a species. Shows the curated, per-species edibility/
 * toxicity (FungiSafety) — deadly/poisonous in red, psychoactive and edible in
 * their own accents, inedible/unknown neutral — with a brief factual caption.
 */
@Composable
fun SafetyBanner(species: Species) {
    val edibility = remember(species.id) { com.example.util.FungiSafety.edibilityOf(species.id) }
    val label = remember(edibility) { com.example.util.FungiSafety.label(edibility) }

    val edibleGreen = Color(0xFF2E7D32)
    val (container, accent, onContainer, icon) = when (edibility) {
        com.example.util.FungiSafety.Edibility.DEADLY,
        com.example.util.FungiSafety.Edibility.POISONOUS -> Quad(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Default.Warning
        )
        com.example.util.FungiSafety.Edibility.PSYCHOACTIVE -> Quad(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.onTertiaryContainer,
            Icons.Default.Info
        )
        com.example.util.FungiSafety.Edibility.EDIBLE -> Quad(
            edibleGreen.copy(alpha = 0.12f),
            edibleGreen,
            MaterialTheme.colorScheme.onBackground,
            Icons.Default.CheckCircle
        )
        else -> Quad( // INEDIBLE / UNKNOWN — neutral
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Default.Info
        )
    }
    val dangerous = com.example.util.FungiSafety.isDangerous(edibility)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = container,
        border = BorderStroke(if (dangerous) 1.5.dp else 1.dp, accent.copy(alpha = if (dangerous) 1f else 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = accent,
                modifier = Modifier.size(if (dangerous) 28.dp else 22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (dangerous) FontWeight.Bold else FontWeight.SemiBold,
                    lineHeight = 20.sp,
                    color = onContainer
                )
                Text(
                    text = "Edibility from the project reference catalogue — research use.",
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp,
                    color = onContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/** Tiny 4-tuple so the banner's per-edibility style can be destructured. */
private data class Quad(
    val container: Color,
    val accent: Color,
    val onContainer: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@Composable
fun FeatureDetailRow(
    label: String,
    desc: String,
    highlighted: Boolean = false
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
fun ShimmerPlaceholder(modifier: Modifier = Modifier) {
    val shimmerColors = listOf(
        Color.DarkGray.copy(alpha = 0.3f),
        Color.DarkGray.copy(alpha = 0.1f),
        Color.DarkGray.copy(alpha = 0.3f)
    )

    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate"
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim.value - 200f, translateAnim.value - 200f),
        end = Offset(translateAnim.value, translateAnim.value)
    )

    Box(modifier = modifier.background(brush))
}

/**
 * Resolves a free-text look-alike line (e.g. "Galerina marginata (DEADLY…)")
 * to a catalogue species when we actually carry one, so the card can deep-link
 * to it. Matches on the scientific name appearing in the text and prefers the
 * most specific (longest) name to avoid a bare-genus false positive. Returns
 * null for look-alikes we don't have an entry for (shown as plain text).
 */
private fun resolveLookAlike(text: String, catalogue: List<Species>, selfId: String): Species? {
    val haystack = text.lowercase()
    return catalogue
        .asSequence()
        .filter { it.id != selfId }
        .filter { sp ->
            val sci = sp.scientificName.lowercase()
            // Ignore vague placeholder names like "Ramaria sp." / "Laccaria sp."
            sci.isNotBlank() && !sci.endsWith(" sp.") && haystack.contains(sci)
        }
        .maxByOrNull { it.scientificName.length }
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
