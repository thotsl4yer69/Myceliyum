package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.data.local.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

data class ChatMessage(
    val role: String, // "user" or "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class IdentificationResult(
    val speciesName: String,
    val commonName: String,
    val confidence: String,
    val safetyRating: String,
    val keyFeatures: List<String>,
    val lookAlikes: List<String>,
    val habitat: String,
    val fullResponse: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentifyScreen() {
    val context = LocalContext.current
    // The Anthropic API key is supplied by the user in Settings and stored only
    // on-device — it is never embedded in the published app. Identification is
    // disabled until the user provides their own key.
    val settingsStore = remember { SettingsStore(context) }
    val anthropicApiKey by settingsStore.anthropicApiKey.collectAsState(initial = "")
    val coroutineScope = rememberCoroutineScope()

    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var identificationResult by remember { mutableStateOf<IdentificationResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Chat state
    var chatMessages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var chatInput by remember { mutableStateOf("") }
    var isChatLoading by remember { mutableStateOf(false) }
    var showChat by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Camera setup
    var pendingPhotoUri by remember { mutableStateOf<Uri?>(null) }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && pendingPhotoUri != null) {
            selectedImageUri = pendingPhotoUri
            identificationResult = null
            chatMessages = emptyList()
            showChat = false
            errorMessage = null
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            selectedImageUri = uri
            identificationResult = null
            chatMessages = emptyList()
            showChat = false
            errorMessage = null
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val uri = createTempImageUri(context)
            pendingPhotoUri = uri
            takePictureLauncher.launch(uri)
        } else {
            Toast.makeText(context, "Camera permission required", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            val uri = createTempImageUri(context)
            pendingPhotoUri = uri
            takePictureLauncher.launch(uri)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun analyzeImage() {
        val uri = selectedImageUri ?: return
        isAnalyzing = true
        errorMessage = null
        coroutineScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    callClaudeVisionAPI(context, uri, anthropicApiKey)
                }
                identificationResult = result
                chatMessages = listOf(
                    ChatMessage("assistant", result.fullResponse)
                )
            } catch (e: Exception) {
                errorMessage = e.message ?: "Analysis failed"
            } finally {
                isAnalyzing = false
            }
        }
    }

    fun sendChatMessage() {
        if (chatInput.isBlank() || isChatLoading) return
        val userMessage = chatInput.trim()
        chatInput = ""
        chatMessages = chatMessages + ChatMessage("user", userMessage)
        isChatLoading = true

        coroutineScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    callClaudeChatAPI(chatMessages, anthropicApiKey)
                }
                chatMessages = chatMessages + ChatMessage("assistant", response)
            } catch (e: Exception) {
                chatMessages = chatMessages + ChatMessage("assistant", "Sorry, I couldn't process that. ${e.message}")
            } finally {
                isChatLoading = false
            }
            // Scroll to bottom
            if (chatMessages.isNotEmpty()) {
                listState.animateScrollToItem(chatMessages.size - 1)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AI Identify", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(
                            "Powered by Claude Vision",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
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
        ) {
            if (!showChat) {
                // Image selection and analysis view
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    // Image preview area
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selectedImageUri != null) {
                            AsyncImage(
                                model = selectedImageUri,
                                contentDescription = "Selected mushroom photo",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )

                            // Retake button overlay
                            IconButton(
                                onClick = {
                                    selectedImageUri = null
                                    identificationResult = null
                                    errorMessage = null
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(12.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            ) {
                                Icon(Icons.Default.Close, "Clear", tint = Color.White)
                            }
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                                Text(
                                    "Take or select a photo of a mushroom",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    // Action buttons
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { launchCamera() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.CameraAlt, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Camera")
                        }
                        OutlinedButton(
                            onClick = { galleryLauncher.launch("image/*") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.PhotoLibrary, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Gallery")
                        }
                    }

                    // Analyze button
                    if (selectedImageUri != null && identificationResult == null) {
                        Button(
                            onClick = { analyzeImage() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .height(52.dp),
                            enabled = !isAnalyzing,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isAnalyzing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(12.dp))
                                Text("Analyzing with Claude AI...")
                            } else {
                                Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Identify Mushroom", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Error message
                    errorMessage?.let { err ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Error,
                                    null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }

                    // Results display
                    identificationResult?.let { result ->
                        Spacer(Modifier.height(8.dp))

                        // Safety banner
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = when {
                                result.safetyRating.contains("DEADLY", ignoreCase = true) ||
                                result.safetyRating.contains("DANGER", ignoreCase = true) ||
                                result.safetyRating.contains("TOXIC", ignoreCase = true) ->
                                    Color(0xFFFFCDD2)
                                result.safetyRating.contains("CAUTION", ignoreCase = true) ||
                                result.safetyRating.contains("UNKNOWN", ignoreCase = true) ->
                                    Color(0xFFFFF9C4)
                                else -> Color(0xFFC8E6C9)
                            },
                            border = BorderStroke(
                                1.dp,
                                when {
                                    result.safetyRating.contains("DEADLY", ignoreCase = true) ||
                                    result.safetyRating.contains("DANGER", ignoreCase = true) ||
                                    result.safetyRating.contains("TOXIC", ignoreCase = true) ->
                                        Color(0xFFD32F2F)
                                    result.safetyRating.contains("CAUTION", ignoreCase = true) ->
                                        Color(0xFFF9A825)
                                    else -> Color(0xFF388E3C)
                                }
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFFD32F2F),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        "Safety: ${result.safetyRating}",
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF1B1B1B)
                                    )
                                    Text(
                                        "Never eat wild mushrooms based on AI identification alone.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF424242)
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // Species identification card
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    result.speciesName,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    fontStyle = FontStyle.Italic
                                )
                                Text(
                                    result.commonName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Analytics,
                                        null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "Confidence: ${result.confidence}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                if (result.keyFeatures.isNotEmpty()) {
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        "Key Features",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    result.keyFeatures.forEach { feature ->
                                        Row(
                                            modifier = Modifier.padding(vertical = 2.dp),
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            Text("  •  ", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            Text(feature, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp)
                                        }
                                    }
                                }

                                if (result.lookAlikes.isNotEmpty()) {
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        "Look-alikes",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    result.lookAlikes.forEach { la ->
                                        Row(
                                            modifier = Modifier.padding(vertical = 2.dp),
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            Icon(
                                                Icons.Default.Warning,
                                                null,
                                                modifier = Modifier.size(12.dp),
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(la, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp)
                                        }
                                    }
                                }

                                if (result.habitat.isNotBlank()) {
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        "Habitat: ${result.habitat}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // Chat button
                        Button(
                            onClick = { showChat = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(Icons.Default.Chat, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Ask follow-up questions", fontWeight = FontWeight.Bold)
                        }

                        Spacer(Modifier.height(32.dp))
                    }

                    // Empty state instructions
                    if (selectedImageUri == null) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Text(
                                    "How it works",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(12.dp))
                                listOf(
                                    "1. Take a clear photo of the mushroom (cap, gills, stem)",
                                    "2. AI analyzes morphological features",
                                    "3. Get species ID, safety rating, and look-alikes",
                                    "4. Ask follow-up questions in chat"
                                ).forEach { step ->
                                    Text(
                                        step,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(vertical = 3.dp),
                                        lineHeight = 18.sp
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "Never eat wild mushrooms based solely on AI identification. Always consult an expert mycologist.",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(12.dp),
                                        color = MaterialTheme.colorScheme.error,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Chat view
                Column(modifier = Modifier.fillMaxSize()) {
                    // Chat header
                    Surface(
                        tonalElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { showChat = false }) {
                                Icon(Icons.Default.ArrowBack, "Back to results")
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Mushroom Q&A",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    identificationResult?.speciesName ?: "AI Chat",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // Messages list
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        items(chatMessages) { message ->
                            val isUser = message.role == "user"
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(
                                        topStart = 16.dp,
                                        topEnd = 16.dp,
                                        bottomStart = if (isUser) 16.dp else 4.dp,
                                        bottomEnd = if (isUser) 4.dp else 16.dp
                                    ),
                                    color = if (isUser)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else
                                        MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
                                    modifier = Modifier.widthIn(max = 300.dp)
                                ) {
                                    Text(
                                        text = message.content,
                                        modifier = Modifier.padding(12.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        lineHeight = 20.sp
                                    )
                                }
                            }
                        }

                        if (isChatLoading) {
                            item {
                                Row(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Thinking...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // Chat input
                    Surface(
                        tonalElevation = 4.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = chatInput,
                                onValueChange = { chatInput = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Ask about this mushroom...", fontSize = 14.sp) },
                                singleLine = true,
                                shape = RoundedCornerShape(24.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            IconButton(
                                onClick = { sendChatMessage() },
                                enabled = chatInput.isNotBlank() && !isChatLoading,
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary,
                                        CircleShape
                                    )
                            ) {
                                Icon(
                                    Icons.Default.Send,
                                    "Send",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun createTempImageUri(context: Context): Uri {
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val dir = File(context.filesDir, "identify_photos")
    if (!dir.exists()) dir.mkdirs()
    val photoFile = File(dir, "identify_$timeStamp.jpg")
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        photoFile
    )
}

// One client for both Anthropic endpoints: reuses the connection pool and
// threads instead of building a fresh client (and pool) per request.
private val anthropicHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
}

private const val ANTHROPIC_MODEL = "claude-sonnet-4-6"

private fun imageUriToBase64(context: Context, uri: Uri): String {
    // Two-pass decode: read only the dimensions first, then decode subsampled.
    // Decoding a full 48MP camera photo just to scale it down can OOM on
    // low-RAM devices; inSampleSize keeps the in-memory bitmap near the target.
    val maxSize = 1024
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        ?: throw Exception("Cannot read image")
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw Exception("Cannot decode image")

    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= maxSize && bounds.outHeight / (sampleSize * 2) >= maxSize) {
        sampleSize *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        ?: throw Exception("Cannot read image")

    // Fine-tune to exactly maxSize on the longest side
    val scale = minOf(maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height, 1f)
    val scaledBitmap = if (scale < 1f) {
        Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
    } else bitmap

    val outputStream = ByteArrayOutputStream()
    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
    if (scaledBitmap !== bitmap) bitmap.recycle()
    return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
}

private fun callClaudeVisionAPI(context: Context, imageUri: Uri, apiKey: String): IdentificationResult {
    if (apiKey.isBlank()) throw Exception("Add your Anthropic API key in Settings to enable AI identification.")

    val base64Image = imageUriToBase64(context, imageUri)

    val systemPrompt = """You are an expert Victorian mycologist specializing in fungi found in southeastern Australia.
Analyze the provided mushroom photograph and provide:
1. Species identification (scientific name)
2. Common name(s)
3. Confidence level (Low/Medium/High/Very High)
4. Safety rating (EDIBLE, CAUTION, TOXIC, or DEADLY POISONOUS)
5. Key identifying features visible in the photo
6. Dangerous look-alikes to watch for
7. Typical habitat

Format your response as follows:
SPECIES: [Scientific name]
COMMON_NAME: [Common name]
CONFIDENCE: [Level]
SAFETY: [Rating]
FEATURES: [feature1] | [feature2] | [feature3]
LOOK_ALIKES: [species1] | [species2]
HABITAT: [description]
DISCUSSION: [Your detailed analysis paragraph]

IMPORTANT: Always emphasize that AI identification should NEVER be used as the sole basis for consuming wild mushrooms. Always recommend expert consultation."""

    val contentArray = JSONArray().apply {
        put(JSONObject().apply {
            put("type", "image")
            put("source", JSONObject().apply {
                put("type", "base64")
                put("media_type", "image/jpeg")
                put("data", base64Image)
            })
        })
        put(JSONObject().apply {
            put("type", "text")
            put("text", "Please identify this mushroom. Provide a detailed analysis following the structured format.")
        })
    }

    val messagesArray = JSONArray().apply {
        put(JSONObject().apply {
            put("role", "user")
            put("content", contentArray)
        })
    }

    val requestBody = JSONObject().apply {
        put("model", ANTHROPIC_MODEL)
        put("max_tokens", 1500)
        put("system", systemPrompt)
        put("messages", messagesArray)
    }

    val request = Request.Builder()
        .url("https://api.anthropic.com/v1/messages")
        .addHeader("x-api-key", apiKey)
        .addHeader("anthropic-version", "2023-06-01")
        .addHeader("content-type", "application/json")
        .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
        .build()

    val response = anthropicHttpClient.newCall(request).execute()
    val responseBody = response.body?.string() ?: throw Exception("Empty response")

    if (!response.isSuccessful) {
        throw Exception("API error ${response.code}: $responseBody")
    }

    val json = JSONObject(responseBody)
    val contentBlocks = json.getJSONArray("content")
    val fullText = StringBuilder()
    for (i in 0 until contentBlocks.length()) {
        val block = contentBlocks.getJSONObject(i)
        if (block.getString("type") == "text") {
            fullText.append(block.getString("text"))
        }
    }
    val text = fullText.toString()

    return parseIdentificationResponse(text)
}

private fun parseIdentificationResponse(text: String): IdentificationResult {
    fun extractField(label: String): String {
        val regex = Regex("$label:\\s*(.+)", RegexOption.IGNORE_CASE)
        return regex.find(text)?.groupValues?.get(1)?.trim() ?: ""
    }

    val species = extractField("SPECIES").ifBlank { "Unknown species" }
    val common = extractField("COMMON_NAME").ifBlank { "Unknown" }
    val confidence = extractField("CONFIDENCE").ifBlank { "Low" }
    val safety = extractField("SAFETY").ifBlank { "CAUTION - Unknown" }
    val featuresRaw = extractField("FEATURES")
    val lookAlikesRaw = extractField("LOOK_ALIKES")
    val habitat = extractField("HABITAT")

    val features = if (featuresRaw.isNotBlank())
        featuresRaw.split("|").map { it.trim() }.filter { it.isNotBlank() }
    else emptyList()

    val lookAlikes = if (lookAlikesRaw.isNotBlank())
        lookAlikesRaw.split("|").map { it.trim() }.filter { it.isNotBlank() }
    else emptyList()

    return IdentificationResult(
        speciesName = species,
        commonName = common,
        confidence = confidence,
        safetyRating = safety,
        keyFeatures = features,
        lookAlikes = lookAlikes,
        habitat = habitat,
        fullResponse = text
    )
}

private fun callClaudeChatAPI(messages: List<ChatMessage>, apiKey: String): String {
    if (apiKey.isBlank()) throw Exception("Add your Anthropic API key in Settings to chat with the mycologist assistant.")

    val systemPrompt = """You are an expert mycologist assistant specializing in Victorian (Australian) fungi.
Answer questions about mushroom identification, safety, ecology, and foraging.
Be concise but thorough. Always emphasize safety — never encourage eating unidentified wild mushrooms."""

    val messagesArray = JSONArray()
    for (msg in messages) {
        messagesArray.put(JSONObject().apply {
            put("role", msg.role)
            put("content", msg.content)
        })
    }

    val requestBody = JSONObject().apply {
        put("model", ANTHROPIC_MODEL)
        put("max_tokens", 1000)
        put("system", systemPrompt)
        put("messages", messagesArray)
    }

    val request = Request.Builder()
        .url("https://api.anthropic.com/v1/messages")
        .addHeader("x-api-key", apiKey)
        .addHeader("anthropic-version", "2023-06-01")
        .addHeader("content-type", "application/json")
        .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
        .build()

    val response = anthropicHttpClient.newCall(request).execute()
    val responseBody = response.body?.string() ?: throw Exception("Empty response")

    if (!response.isSuccessful) {
        throw Exception("API error ${response.code}")
    }

    val json = JSONObject(responseBody)
    val contentBlocks = json.getJSONArray("content")
    val result = StringBuilder()
    for (i in 0 until contentBlocks.length()) {
        val block = contentBlocks.getJSONObject(i)
        if (block.getString("type") == "text") {
            result.append(block.getString("text"))
        }
    }
    return result.toString()
}
