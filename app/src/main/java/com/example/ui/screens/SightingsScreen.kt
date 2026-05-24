package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.model.Species
import com.example.model.UserSighting
import com.example.ui.viewmodel.FungiViewModel
import com.google.android.gms.location.LocationServices
import java.text.SimpleDateFormat
import java.util.*

@SuppressLint("MissingPermission")
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SightingsScreen(
    viewModel: FungiViewModel
) {
    val context = LocalContext.current
    val speciesList by viewModel.speciesList.collectAsState()
    val userSightings by viewModel.userSightings.collectAsState()

    var showAddSightingDialog by remember { mutableStateOf(false) }
    var selectedSightingForDetail by remember { mutableStateOf<UserSighting?>(null) }

    // Android Location helper
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    var detectedLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    // Rationale Dialog Permissions launchers
    var showCameraRationale by remember { mutableStateOf(false) }
    var showLocationRationale by remember { mutableStateOf(false) }

    val fileRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "Storage file access denied", Toast.LENGTH_SHORT).show()
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(context, "Camera permission allowed. Save your sighting photo voucher.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Camera permission is required to save sighting photographs.", Toast.LENGTH_LONG).show()
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc: Location? ->
                if (loc != null) {
                    detectedLocation = Pair(loc.latitude, loc.longitude)
                }
            }
        } else {
            Toast.makeText(context, "GPS Location is required to pinpoint exact fungi colonies.", Toast.LENGTH_LONG).show()
        }
    }

    // Proactive permissions checks on entering sightings screen
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            showLocationRationale = true
        } else {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc: Location? ->
                if (loc != null) {
                    detectedLocation = Pair(loc.latitude, loc.longitude)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "FIELD WORK SIGHTINGS",
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 20.sp,
                            letterSpacing = 1.2.sp
                        )
                        Text(
                            text = "Personal Voucher Logbook",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    // Check camera perm on tapping Add Sighting
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        showCameraRationale = true
                    } else {
                        showAddSightingDialog = true
                    }
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("add_sighting_fab")
            ) {
                Icon(imageVector = Icons.Default.AddPhotoAlternate, contentDescription = "Log Specimen")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (userSightings.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillParentMaxHeight(0.8f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FilterVintage,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(72.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Personal Mycology Log is Empty",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleLarge,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Keep a secure personal log of your mushroom finds. Sightings can be marked private and exported as standardized CSV tables anytime.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                                        showCameraRationale = true
                                    } else {
                                        showAddSightingDialog = true
                                    }
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("LOG FIRST DISCOVERY")
                            }
                        }
                    }
                }
            } else {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "🔒 ALL ENTRIES ARE STORED ENCRYPTED IN THE LOCAL DEVICE DATABASE ROOM CLIENT",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                items(userSightings) { sighting ->
                    SightingItemRow(
                        sighting = sighting,
                        speciesList = speciesList,
                        onSightingSelected = { selectedSightingForDetail = it }
                    )
                }
            }
        }
    }

    // Permission Rationale: CAMERA
    if (showCameraRationale) {
        AlertDialog(
            onDismissRequest = { showCameraRationale = false },
            title = { Text("Voucher Photo Permission", fontWeight = FontWeight.Bold) },
            text = {
                Text("Mycelium Mapper requires access to your camera to attach photographic documentation as physical proof for each observed fungi colony.", lineHeight = 20.sp)
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCameraRationale = false
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                ) {
                    Text("ALLOW CAMERA")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showCameraRationale = false
                        showAddSightingDialog = true // fallback logging with photo dummy path
                    }
                ) {
                    Text("SKIP PHOTO VOUCHER")
                }
            }
        )
    }

    // Permission Rationale: LOCATION
    if (showLocationRationale) {
        AlertDialog(
            onDismissRequest = { showLocationRationale = false },
            title = { Text("Location Services Required", fontWeight = FontWeight.Bold) },
            text = {
                Text("To compute probable fruiting hotspots, this research terminal overlay maps microclimate rain and soil levels corresponding directly to your actual GPS location coordinates.", lineHeight = 20.sp)
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLocationRationale = false
                        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                ) {
                    Text("ALLOW LOCATION Access")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLocationRationale = false }) {
                    Text("NO THANKS")
                }
            }
        )
    }

    // ADD SIGHTING DIALOG
    if (showAddSightingDialog) {
        AddSightingDialog(
            speciesList = speciesList,
            initialLocation = detectedLocation,
            onDismiss = { showAddSightingDialog = false },
            onSave = { speciesId, lat, lng, notes, photoPath, isPrivate ->
                viewModel.addUserSighting(speciesId, lat, lng, notes, photoPath, isPrivate)
                showAddSightingDialog = false
                Toast.makeText(context, "Specimen logged successfully!", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // DETAIL SIGHTING DIALOG
    selectedSightingForDetail?.let { sighting ->
        SightingDetailDialog(
            sighting = sighting,
            speciesList = speciesList,
            onDismiss = { selectedSightingForDetail = null },
            onDelete = {
                viewModel.deleteUserSighting(sighting)
                selectedSightingForDetail = null
                Toast.makeText(context, "Sighting deleted", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
fun SightingItemRow(
    sighting: UserSighting,
    speciesList: List<Species>,
    onSightingSelected: (UserSighting) -> Unit
) {
    val associated = speciesList.find { it.id == sighting.speciesId }
    val sciName = associated?.scientificName ?: sighting.speciesId
    val comName = associated?.commonNames?.firstOrNull() ?: "Logged Fungus Sighting"

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSightingSelected(sighting) }
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Sighting Thumbnail view
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (!sighting.photoLocalPath.isNullOrEmpty()) {
                    AsyncImage(
                        model = sighting.photoLocalPath,
                        contentDescription = sciName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Landscape,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sciName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = comName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Coordinates: ${String.format(Locale.US, "%.5f, %.5f", sighting.lat, sighting.lng)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
                if (sighting.notes.isNotEmpty()) {
                    Text(
                        text = sighting.notes,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                val formattedStr = remember(sighting.timestamp) {
                    val sdf = SimpleDateFormat("MMM dd", Locale.getDefault())
                    sdf.format(Date(sighting.timestamp))
                }
                Text(
                    text = formattedStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (sighting.isPrivate) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Private lock icon",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Public,
                        contentDescription = "Public record indicator",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SightingDetailDialog(
    sighting: UserSighting,
    speciesList: List<Species>,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    val assoc = speciesList.find { it.id == sighting.speciesId }
    val sName = assoc?.scientificName ?: sighting.speciesId
    val cName = assoc?.commonNames?.firstOrNull() ?: "Fungi Discovery Record"

    Dialog(
        onDismissRequest = onDismiss
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "VOUCHER SPECIMEN INFO",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close description")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (!sighting.photoLocalPath.isNullOrEmpty()) {
                    AsyncImage(
                        model = sighting.photoLocalPath,
                        contentDescription = sName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Text(
                    text = sName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    fontStyle = FontStyle.Italic
                )
                Text(
                    text = cName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row {
                    Icon(imageVector = Icons.Default.LocationOn, contentDescription = "Coordinates symbol", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Latitude :  ${sighting.lat}\nLongitude:  ${sighting.lng}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row {
                    Icon(imageVector = Icons.Default.AccessTime, contentDescription = "Observation time symbol", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    val fullDate = remember(sighting.timestamp) {
                        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        sdf.format(Date(sighting.timestamp))
                    }
                    Text(
                        text = "Recorded at: $fullDate UTC",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "FIELD WORK RESEARCH ANNOTATIONS :",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = sighting.notes.ifEmpty { "No explicit description notes provided." },
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onDelete,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete observation")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("DELETE SIGHTING")
                    }
                }
            }
        }
    }
}

@Composable
fun AddSightingDialog(
    speciesList: List<Species>,
    initialLocation: Pair<Double, Double>?,
    onDismiss: () -> Unit,
    onSave: (speciesId: String, lat: Double, lng: Double, notes: String, photoPath: String?, isPrivate: Boolean) -> Unit
) {
    var selectedSpecies by remember { mutableStateOf<Species?>(speciesList.firstOrNull()) }
    var dropShown by remember { mutableStateOf(false) }

    var manualLat by remember { mutableStateOf(initialLocation?.first?.toString() ?: "-37.8136") }
    var manualLng by remember { mutableStateOf(initialLocation?.second?.toString() ?: "144.9631") }
    var userNotes by remember { mutableStateOf("") }
    var isPrivateEntry by remember { mutableStateOf(false) }

    // Captured photo placeholder path
    var voucherPhotoPath by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 4.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ADD FIELD LOG SIGHTING",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = null)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 1. Select Species
                Text(
                    text = "VOUCHER TAXON ID",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )

                Box(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { dropShown = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = selectedSpecies?.scientificName ?: "Select Reference Specimen",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Start
                        )
                        Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null)
                    }

                    DropdownMenu(
                        expanded = dropShown,
                        onDismissRequest = { dropShown = false }
                    ) {
                        speciesList.forEach { s ->
                            DropdownMenuItem(
                                text = { Text("${s.scientificName} (${s.commonNames.firstOrNull() ?: ""})") },
                                onClick = {
                                    selectedSpecies = s
                                    dropShown = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 2. Latitude & Longitude inputs
                Text(
                    text = "GPS SITE COORDINATES",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = manualLat,
                        onValueChange = { manualLat = it },
                        label = { Text("Latitude") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = manualLng,
                        onValueChange = { manualLng = it },
                        label = { Text("Longitude") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 3. User Notes
                Text(
                    text = "REPRESENTATIVE HABITAT NOTES",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
                OutlinedTextField(
                    value = userNotes,
                    onValueChange = { userNotes = it },
                    placeholder = { Text("Describe substrate wood types, dampness indices, spore dusts...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .padding(vertical = 4.dp),
                    maxLines = 4
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 4. Photo Voucher Mock
                Text(
                    text = "SIGHTING PHOTO VOUCHER",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
                if (voucherPhotoPath != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                            .padding(vertical = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                    ) {
                        AsyncImage(
                            model = voucherPhotoPath,
                            contentDescription = "Voucher photograph",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    androidx.compose.ui.graphics.Brush.verticalGradient(
                                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                                    )
                                )
                        )
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "✓ VOUCHER PHOTO ACTIVE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        IconButton(
                            onClick = { voucherPhotoPath = null },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                .size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Remove photo",
                                tint = Color.Red,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                } else {
                    Surface(
                        onClick = {
                            // Dummy photo generation for simulation emulator mapping!
                            voucherPhotoPath = "https://images.unsplash.com/photo-1599059813005-11265ba4b4ce"
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .padding(vertical = 6.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(imageVector = Icons.Default.CameraAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "TAP TO TAKE DIGITAL VOUCHER",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 5. Visibility private checkbox selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isPrivateEntry,
                        onCheckedChange = { isPrivateEntry = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Keep Sighting Location Private",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Do not share with iNat or global mapping overlays.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("CANCEL")
                    }

                    Button(
                        onClick = {
                            val computedLat = manualLat.toDoubleOrNull() ?: -37.8136
                            val computedLng = manualLng.toDoubleOrNull() ?: 144.9631
                            onSave(
                                selectedSpecies?.id ?: "unidentified",
                                computedLat,
                                computedLng,
                                userNotes,
                                voucherPhotoPath,
                                isPrivateEntry
                            )
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("submit_sighting_button")
                    ) {
                        Text("SAVE FIELD RECORD")
                    }
                }
            }
        }
    }
}
