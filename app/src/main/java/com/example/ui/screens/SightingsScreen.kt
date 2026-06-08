package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
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
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.model.Species
import com.example.model.UserSighting
import com.example.ui.viewmodel.FungiViewModel
import com.google.android.gms.location.LocationServices
import java.io.File
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
            Toast.makeText(context, "Camera ready.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Camera permission is needed to attach a photo.", Toast.LENGTH_LONG).show()
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
            Toast.makeText(context, "Location is needed to tag where you found something.", Toast.LENGTH_LONG).show()
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
                            text = "Sightings",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Text(
                            text = "Your private field log",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
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
                Icon(
                    imageVector = Icons.Default.AddPhotoAlternate,
                    contentDescription = "Log Specimen"
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
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
                                    text = "No sightings yet",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleLarge,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Log your finds privately on this device. Mark them private and export as CSV anytime.",
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
                                    Text("Log first sighting")
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
                                text = "Stored privately on this device — nothing is uploaded",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
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
    }

    // Permission Rationale: CAMERA
    if (showCameraRationale) {
        AlertDialog(
            onDismissRequest = { showCameraRationale = false },
            title = { Text("Camera access", fontWeight = FontWeight.Bold) },
            text = {
                Text("To attach a photo to each sighting we need access to your camera. Photos are stored only on this device.", lineHeight = 20.sp)
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCameraRationale = false
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                ) {
                    Text("Allow camera")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showCameraRationale = false
                        showAddSightingDialog = true // log without a photo
                    }
                ) {
                    Text("Skip photo")
                }
            }
        )
    }

    // Permission Rationale: LOCATION
    if (showLocationRationale) {
        AlertDialog(
            onDismissRequest = { showLocationRationale = false },
            title = { Text("Location access", fontWeight = FontWeight.Bold) },
            text = {
                Text("Sharing your GPS location lets the app suggest hotspots near you and tag your sightings accurately.", lineHeight = 20.sp)
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLocationRationale = false
                        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                ) {
                    Text("Allow location")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLocationRationale = false }) {
                    Text("Not now")
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
                Toast.makeText(context, "Sighting saved.", Toast.LENGTH_SHORT).show()
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
                .fillMaxHeight(0.85f)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Sighting details",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
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
                    text = "Notes",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = sighting.notes.ifEmpty { "No notes." },
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
                        Text("Delete sighting")
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
    val context = LocalContext.current

    var selectedSpecies by remember { mutableStateOf<Species?>(speciesList.firstOrNull()) }
    var dropShown by remember { mutableStateOf(false) }

    var manualLat by remember { mutableStateOf(initialLocation?.first?.toString() ?: "-37.8136") }
    var manualLng by remember { mutableStateOf(initialLocation?.second?.toString() ?: "144.9631") }
    var userNotes by remember { mutableStateOf("") }
    var isPrivateEntry by remember { mutableStateOf(false) }

    // Captured voucher photo (content:// URI string from our FileProvider)
    var voucherPhotoPath by remember { mutableStateOf<String?>(null) }
    // URI we asked the camera app to write the full-resolution image into
    var pendingPhotoUri by remember { mutableStateOf<Uri?>(null) }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && pendingPhotoUri != null) {
            voucherPhotoPath = pendingPhotoUri.toString()
        } else {
            Toast.makeText(context, "No photo captured.", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchVoucherCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, "Camera permission is required to capture a voucher photo.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val uri = createVoucherImageUri(context)
            pendingPhotoUri = uri
            takePictureLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to start the camera: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 4.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Log a sighting",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = null)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 1. Select Species
                Text(
                    text = "Species",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            text = selectedSpecies?.scientificName ?: "Pick a species",
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
                    text = "Location",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    text = "Notes",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = userNotes,
                    onValueChange = { userNotes = it },
                    placeholder = { Text("Substrate, weather, surrounding trees, anything you noticed…") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .padding(vertical = 4.dp),
                    maxLines = 4
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 4. Photo capture
                Text(
                    text = "Photo",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                text = "Photo attached",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
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
                        onClick = { launchVoucherCamera() },
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
                                    text = "Tap to take a photo",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 13.sp
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
                            text = "Keep this sighting private",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Already on-device only — this just hides it from any future sharing or export.",
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
                        Text("Cancel")
                    }

                    Button(
                        enabled = selectedSpecies != null,
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
                        Text("Save sighting")
                    }
                }
            }
        }
    }
}

/**
 * Creates a destination file in the app's internal storage for a captured
 * voucher photo and returns a FileProvider content:// URI that the camera
 * app can write to. The URI string is later persisted on the UserSighting.
 */
private fun createVoucherImageUri(context: Context): Uri {
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val dir = File(context.filesDir, "voucher_photos")
    if (!dir.exists()) dir.mkdirs()
    val photoFile = File(dir, "voucher_$timeStamp.jpg")
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        photoFile
    )
}
