package com.example.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Base64
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import java.util.Locale
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.ui.theme.*
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Attachment
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import com.example.update.UpdateInfo
import com.example.update.UpdateManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
    val messages by viewModel.messages.collectAsState()
    val isLiveAudioActive by viewModel.isLiveAudioActive.collectAsState()
    val isCameraActive by viewModel.isCameraActive.collectAsState()
    val language by viewModel.language.collectAsState()
    val voiceName by viewModel.voiceName.collectAsState()
    var inputText by remember { mutableStateOf("") }
    var isBackCamera by remember { mutableStateOf(true) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> selectedImageUri = uri }

    var isScreenSharing by remember { mutableStateOf(false) }
    var screenCaptureIntentData by remember { mutableStateOf<Intent?>(null) }
    var screenCaptureResultCode by remember { mutableStateOf(0) }
    
    val screenShareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(context, com.example.ScreenShareService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            screenCaptureResultCode = result.resultCode
            screenCaptureIntentData = result.data
            isScreenSharing = true
            
            if (!viewModel.isLiveAudioActive.value) {
                viewModel.toggleLiveAudio()
            }
            android.widget.Toast.makeText(context, "بدأت مشاركة الشاشة", android.widget.Toast.LENGTH_SHORT).show()
            
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(homeIntent)
        } else {
            isScreenSharing = false
        }
    }

    DisposableEffect(isScreenSharing, screenCaptureIntentData) {
        var mediaProjection: android.media.projection.MediaProjection? = null
        var virtualDisplay: android.hardware.display.VirtualDisplay? = null
        var imageReader: android.media.ImageReader? = null
        var handlerThread: android.os.HandlerThread? = null

        if (isScreenSharing && screenCaptureIntentData != null) {
            try {
                handlerThread = android.os.HandlerThread("ScreenShare")
                handlerThread.start()
                val handler = android.os.Handler(handlerThread.looper)

                val mediaProjManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                handler.postDelayed({
                    try {
                        mediaProjection = mediaProjManager.getMediaProjection(screenCaptureResultCode, screenCaptureIntentData!!)

                        val metrics = context.resources.displayMetrics
                        val width = metrics.widthPixels / 2
                        val height = metrics.heightPixels / 2
                        val dpi = metrics.densityDpi

                        // Must be >= 1 for width/height
                        if (width > 0 && height > 0) {
                            imageReader = android.media.ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
                            
                            virtualDisplay = mediaProjection?.createVirtualDisplay(
                                "ScreenShare", width, height, dpi,
                                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                                imageReader?.surface, null, handler
                            )

                            var lastTime = 0L
                            imageReader?.setOnImageAvailableListener({ reader ->
                                try {
                                    val image = reader.acquireLatestImage()
                                    if (image != null) {
                                        val currentTime = System.currentTimeMillis()
                                        if (currentTime - lastTime >= 1000) {
                                            lastTime = currentTime
                                            try {
                                                val planes = image.planes
                                                val buffer = planes[0].buffer
                                                val pixelStride = planes[0].pixelStride
                                                val rowStride = planes[0].rowStride
                                                val rowPadding = rowStride - pixelStride * width
                                                val realWidth = width + rowPadding / pixelStride

                                                if (realWidth > 0 && height > 0) {
                                                    val bitmap = android.graphics.Bitmap.createBitmap(realWidth, height, android.graphics.Bitmap.Config.ARGB_8888)
                                                    bitmap.copyPixelsFromBuffer(buffer)
                                                    
                                                    val out = java.io.ByteArrayOutputStream()
                                                    val maxDim = 800f
                                                    val scale = minOf(maxDim / realWidth, maxDim / height, 1f)
                                                    val scaledW = maxOf(1, (realWidth * scale).toInt())
                                                    val scaledH = maxOf(1, (height * scale).toInt())
                                                    val scaled = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true) else bitmap
                                                    scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 40, out)
                                                    viewModel.feedCameraFrame(out.toByteArray())
                                                    if (scaled != bitmap) scaled.recycle()
                                                    bitmap.recycle()
                                                }
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                            }
                                        }
                                        image.close()
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }, handler)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        isScreenSharing = false
                    }
                }, 500)
            } catch (e: Exception) {
                e.printStackTrace()
                isScreenSharing = false
            }
        }

        onDispose {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            handlerThread?.quitSafely()
        }
    }

    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.RECORD_AUDIO, 
            Manifest.permission.CAMERA,
            Manifest.permission.READ_MEDIA_IMAGES
        )
    )
    val allGranted = permissionsState.allPermissionsGranted

    // Text To Speech
    val tts = remember {
        var textToSpeech: TextToSpeech? = null
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale.getDefault()
            }
        }
        textToSpeech
    }

    DisposableEffect(Unit) {
        onDispose {
            tts?.stop()
            tts?.shutdown()
        }
    }



    // Speech Recognizer
    var isListening by remember { mutableStateOf(false) }
    
    val speechRecognizer = remember {
        try {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) { isListening = false }
                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val spokenText = matches[0]
                            if (spokenText.isNotBlank()) {
                                 viewModel.sendMessage(spokenText)
                            }
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            inputText = matches[0]
                        }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                recognizer
            } else null
        } catch (e: Exception) {
            null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try { 
                speechRecognizer?.destroy()
            } catch(e: Exception) {}
        }
    }

    val speechIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
    }

    val coroutineScope = rememberCoroutineScope()
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateInfoState by remember { mutableStateOf<UpdateInfo?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    val updateManager = remember { UpdateManager(context) }

    Box(modifier = Modifier.fillMaxSize()) {
        MeshBackground()
        
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                val geminiGradient = remember {
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF4285F4),
                            Color(0xFF9B72CB),
                            Color(0xFFD96570),
                            Color(0xFFF9AB00)
                        )
                    )
                }

                CenterAlignedTopAppBar(
                    navigationIcon = {
                        Box {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .clickable { showSettingsMenu = true }
                            ) {
                                Icon(
                                    Icons.Default.Settings, 
                                    contentDescription = if (language == "العربية") "الإعدادات" else "Settings", 
                                    tint = Color.White,
                                    modifier = Modifier
                                        .size(28.dp)
                                        .graphicsLayer(alpha = 0.99f)
                                        .drawWithCache {
                                            val brush = Brush.linearGradient(listOf(Color(0xFFEAECEF), Color(0xFF7D838F)))
                                            onDrawWithContent {
                                                drawContent()
                                                drawRect(brush, blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop)
                                            }
                                        }
                                        .shadow(elevation = 6.dp, shape = CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(if (language == "العربية") "الإعدادات" else "Settings", style = MaterialTheme.typography.labelSmall, color = Slate100, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            DropdownMenu(
                                expanded = showSettingsMenu,
                                onDismissRequest = { showSettingsMenu = false },
                                modifier = Modifier.background(GlassBg)
                            ) {
                                val languages = listOf("العربية", "English")
                                languages.forEach { lang ->
                                    DropdownMenuItem(
                                        text = { Text(if (language == "العربية") "اللغة: $lang" else "Language: $lang", color = if (language == lang) Cyan500 else Slate100) },
                                        onClick = {
                                            viewModel.language.value = lang
                                            showSettingsMenu = false
                                        }
                                    )
                                }
                                val voices = listOf(
                                    "Aoede" to "امرأة (Aoede)",
                                    "Kore" to "فتاة (Kore)",
                                    "Charon" to "رجل (Charon)",
                                    "Fenrir" to "شاب (Fenrir)",
                                    "Puck" to "طفل (Puck)"
                                )
                                voices.forEach { (voiceId, voiceDesc) ->
                                    DropdownMenuItem(
                                        text = { Text(if (language == "العربية") "الصوت: $voiceDesc" else "Voice: $voiceDesc", color = if (voiceName == voiceId) Emerald400 else Slate100) },
                                        onClick = {
                                            viewModel.setVoiceAndGreet(voiceId)
                                            showSettingsMenu = false
                                        }
                                    )
                                }
                                
                                val updateLabel = if (language == "العربية") "التحقق من التحديثات" else "Check for Updates"
                                DropdownMenuItem(
                                    text = { Text(if (isCheckingUpdate) "..." else updateLabel, color = Slate100) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Sync, contentDescription = "Check for Updates", tint = Slate100)
                                    },
                                    onClick = {
                                        if (isCheckingUpdate) return@DropdownMenuItem
                                        isCheckingUpdate = true
                                        showSettingsMenu = false
                                        coroutineScope.launch {
                                            val info = updateManager.checkForUpdate()
                                            isCheckingUpdate = false
                                            if (info != null && info.hasUpdate) {
                                                updateInfoState = info
                                                showUpdateDialog = true
                                            } else {
                                                android.widget.Toast.makeText(context, if (language == "العربية") "أنت على أحدث إصدار." else "You are on the latest version.", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    },
                    title = { 
                        Text(
                            text = "GMANOOY", 
                            fontWeight = FontWeight.Black, 
                            fontSize = 28.sp,
                            style = androidx.compose.ui.text.TextStyle(
                                brush = geminiGradient,
                                shadow = androidx.compose.ui.graphics.Shadow(
                                    color = Color(0x80000000),
                                    offset = Offset(4f, 6f),
                                    blurRadius = 8f
                                )
                            )
                        )
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                    actions = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .clickable { viewModel.clearChat() }
                        ) {
                                Icon(
                                    imageVector = Icons.Default.Email,
                                    contentDescription = if (language == "العربية") "محادثة جديدة" else "New Chat",
                                    tint = Color.White,
                                    modifier = Modifier
                                        .size(28.dp)
                                        .graphicsLayer(alpha = 0.99f)
                                        .drawWithCache {
                                            val brush = Brush.linearGradient(listOf(Color(0xFF64B5F6), Color(0xFF0D47A1)))
                                            onDrawWithContent {
                                                drawContent()
                                                drawRect(brush, blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop)
                                            }
                                        }
                                        .shadow(elevation = 6.dp, shape = CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(if (language == "العربية") "محادثة جديدة" else "New Chat", style = MaterialTheme.typography.labelSmall, color = Slate100, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        if (isCameraActive) {
                            IconButton(
                                onClick = { isBackCamera = !isBackCamera },
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(GlassBg)
                                    .border(1.dp, GlassBorder, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FlipCameraAndroid,
                                    contentDescription = "Flip Camera",
                                    tint = Slate400
                                )
                            }
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (isCameraActive) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        CameraPreview(
                            isBackCamera = isBackCamera,
                            onFrameCaptured = { frameData ->
                                viewModel.feedCameraFrame(frameData)
                            }
                        )
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        ChatBubble(message, onSpeak = { text ->
                            viewModel.playAudioForText(text)
                        }, language = language)
                    }
                }

                if (isLiveAudioActive) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .background(GlassBg, RoundedCornerShape(24.dp))
                            .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        IconButton(
                            onClick = {
                                viewModel.toggleLiveAudio()
                                if (isCameraActive) viewModel.toggleCamera()
                                if (isScreenSharing) {
                                    isScreenSharing = false
                                    context.stopService(Intent(context, com.example.ScreenShareService::class.java))
                                }
                            },
                            modifier = Modifier.background(Color.Red.copy(alpha = 0.2f), CircleShape).size(48.dp)
                        ) {
                            Icon(Icons.Default.Close, "End Live Chat", tint = Color.Red)
                        }

                        IconButton(
                            onClick = {
                                android.widget.Toast.makeText(context, "المايكروفون يعمل..", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.background(Emerald400.copy(alpha = 0.2f), CircleShape).size(48.dp)
                        ) {
                            Icon(Icons.Default.Mic, "Mic Active", tint = Emerald400)
                        }

                        IconButton(
                            onClick = {
                                val recordAudioGranted = permissionsState.permissions.find { it.permission == Manifest.permission.RECORD_AUDIO }?.status?.isGranted == true
                                val cameraGranted = permissionsState.permissions.find { it.permission == Manifest.permission.CAMERA }?.status?.isGranted == true
                                
                                if (cameraGranted && recordAudioGranted) {
                                    viewModel.toggleCamera()
                                } else {
                                    permissionsState.launchMultiplePermissionRequest()
                                }
                            },
                            modifier = Modifier.background(if (isCameraActive) Cyan500.copy(alpha = 0.2f) else Color.Transparent, CircleShape).size(48.dp)
                        ) {
                            Icon(if (isCameraActive) Icons.Default.Videocam else Icons.Default.VideocamOff, "Toggle Camera", tint = if (isCameraActive) Cyan500 else Slate400)
                        }

                        IconButton(
                            onClick = {
                                if (!isScreenSharing) {
                                    val mediaProjManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                                    screenShareLauncher.launch(mediaProjManager.createScreenCaptureIntent())
                                } else {
                                    isScreenSharing = false
                                    context.stopService(Intent(context, com.example.ScreenShareService::class.java))
                                }
                            },
                            modifier = Modifier.background(if (isScreenSharing) Cyan500.copy(alpha = 0.2f) else Color.Transparent, CircleShape).size(48.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ScreenShare, "Screen Share", tint = if (isScreenSharing) Cyan500 else Slate400)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(
                            onClick = {
                                photoPickerLauncher.launch("*/*")
                            },
                        modifier = Modifier
                            .size(36.dp)
                    ) {
                             Icon(
                                imageVector = Icons.Default.Attachment,
                                contentDescription = "Attach File",
                                modifier = Modifier.size(20.dp),
                                tint = if (selectedImageUri != null) Cyan500 else Slate400
                            )
                        }

                        IconButton(
                            onClick = {
                                val recordAudioGranted = permissionsState.permissions.find { it.permission == Manifest.permission.RECORD_AUDIO }?.status?.isGranted == true
                                if (recordAudioGranted) {
                                    viewModel.toggleLiveAudio()
                                } else {
                                    permissionsState.launchMultiplePermissionRequest()
                                }
                            },
                            modifier = Modifier
                                .size(36.dp)
                        ) {
                             Icon(
                                imageVector = Icons.Default.Videocam,
                                contentDescription = "Live Chat",
                                modifier = Modifier.size(20.dp),
                                tint = Emerald400
                            )
                        }

                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 36.dp, max = 80.dp)
                                .testTag("chat_input"),
                            placeholder = {
                                Text(
                                    if (language == "العربية") "اسأل عن شيء معقد..." else "Ask about something complex...",
                                    color = Slate400,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            shape = CircleShape,
                            enabled = !isLiveAudioActive,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Slate100),
                            trailingIcon = {
                                 IconButton(
                                     modifier = Modifier.size(32.dp),
                                     onClick = {
                                     val recordAudioGranted = permissionsState.permissions.find { it.permission == Manifest.permission.RECORD_AUDIO }?.status?.isGranted == true
                                     if (recordAudioGranted) {
                                         if (speechRecognizer != null) {
                                             if (isListening) {
                                                 speechRecognizer.stopListening()
                                                 isListening = false
                                             } else {
                                                 inputText = ""
                                                 speechRecognizer.startListening(speechIntent)
                                                 isListening = true
                                             }
                                         } else {
                                             android.widget.Toast.makeText(context, "التعرف على الصوت غير متاح", android.widget.Toast.LENGTH_SHORT).show()
                                         }
                                     } else {
                                         permissionsState.launchMultiplePermissionRequest()
                                     }
                                 }) {
                                     Icon(
                                         imageVector = Icons.Default.Mic,
                                         contentDescription = "Voice Input",
                                         modifier = Modifier.size(18.dp),
                                         tint = if (isListening) Color.Red else Slate400
                                     )
                                 }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = GlassBg,
                                unfocusedContainerColor = GlassBg,
                                focusedBorderColor = Indigo500.copy(alpha = 0.5f),
                                unfocusedBorderColor = GlassBorder,
                                focusedTextColor = Slate100,
                                unfocusedTextColor = Slate100,
                                cursorColor = Indigo500
                            )
                        )

                        FloatingActionButton(
                            onClick = {
                                if (inputText.isNotBlank() || selectedImageUri != null) {
                                    val textToSend = inputText
                                    val uriToSend = selectedImageUri
                                    inputText = ""
                                    selectedImageUri = null
                                    viewModel.processUriAndSendMessage(context, textToSend, uriToSend)
                                }
                            },
                            modifier = Modifier.size(36.dp).testTag("send_button"),
                            containerColor = Indigo500,
                            contentColor = Color.White,
                            shape = CircleShape,
                            elevation = FloatingActionButtonDefaults.elevation(0.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send Message",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        if (showUpdateDialog && updateInfoState != null) {
            val uiInfo = updateInfoState!!
            AlertDialog(
                onDismissRequest = { showUpdateDialog = false },
                title = { Text(if (language == "العربية") "تحديث جديد متاح" else "New Update Available", color = Slate100) },
                text = { Text(if (language == "العربية") "هل ترغب في تنزيل وتثبيت التحديث الجديد المستند إلى التاريخ: ${uiInfo.publishedAt}؟" else "Would you like to download and install the new update published at: ${uiInfo.publishedAt}?", color = Slate400) },
                confirmButton = {
                    TextButton(onClick = {
                        showUpdateDialog = false
                        updateManager.downloadAndInstall(uiInfo)
                    }) {
                        Text(if (language == "العربية") "تنزيل الآن" else "Download Now", color = Cyan500)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showUpdateDialog = false }) {
                        Text(if (language == "العربية") "لاحقاً" else "Later", color = Slate400)
                    }
                },
                containerColor = Color(0xFF1E293B),
                titleContentColor = Slate100,
                textContentColor = Slate400
            )
        }
    }
}

@Composable
fun CameraPreview(
    isBackCamera: Boolean,
    onFrameCaptured: (ByteArray) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember { PreviewView(context) }

    DisposableEffect(isBackCamera) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var cameraProvider: ProcessCameraProvider? = null
        var isDisposed = false
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        var imageAnalysis: ImageAnalysis? = null

        cameraProviderFuture.addListener({
            if (isDisposed) {
                try { cameraProviderFuture.get().unbindAll() } catch (e: Exception) {}
                return@addListener
            }
            cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            imageAnalysis = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            var lastTime = 0L

            imageAnalysis?.setAnalyzer(executor) { imageProxy ->
                val currentTime = System.currentTimeMillis()
                // Send 1 frame per second to avoid spamming the connection and memory
                if (currentTime - lastTime >= 1000) {
                    lastTime = currentTime
                    try {
                        val bitmap = imageProxy.toBitmap()
                        val out = java.io.ByteArrayOutputStream()
                        val maxDim = 640f
                        val scale = minOf(maxDim / bitmap.width, maxDim / bitmap.height, 1f)
                        val scaled = if (scale < 1f) {
                            val w = maxOf(1, (bitmap.width * scale).toInt())
                            val h = maxOf(1, (bitmap.height * scale).toInt())
                            Bitmap.createScaledBitmap(bitmap, w, h, true)
                        } else {
                            bitmap
                        }
                        scaled.compress(Bitmap.CompressFormat.JPEG, 40, out) // highly compressed for latency
                        onFrameCaptured(out.toByteArray())
                        if (scaled != bitmap) {
                            scaled.recycle()
                        }
                        bitmap.recycle()
                    } catch (t: Throwable) {
                        t.printStackTrace()
                    }
                }
                imageProxy.close()
            }

            try {
                cameraProvider?.unbindAll()
                val cameraSelector = if (isBackCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            isDisposed = true
            imageAnalysis?.clearAnalyzer()
            executor.shutdown()
            try {
                cameraProvider?.unbindAll()
            } catch (e: Exception) {}
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
    )
}

@Composable
fun MeshBackground() {
    Canvas(modifier = Modifier.fillMaxSize().background(DarkBg)) {
        val width = size.width
        val height = size.height
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF4F46E5).copy(alpha = 0.20f), Color.Transparent),
                center = Offset(0f, 0f),
                radius = 1000f
            ),
            radius = 1000f,
            center = Offset(0f, 0f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF8B5CF6).copy(alpha = 0.20f), Color.Transparent),
                center = Offset(width, height),
                radius = 900f
            ),
            radius = 900f,
            center = Offset(width, height)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF06B6D4).copy(alpha = 0.10f), Color.Transparent),
                center = Offset(width * 0.2f, height * 0.4f),
                radius = 600f
            ),
            radius = 600f,
            center = Offset(width * 0.2f, height * 0.4f)
        )
    }
}

@Composable
fun ChatBubble(message: ChatMessage, onSpeak: ((String) -> Unit)? = null, language: String = "العربية") {
    val isUser = message.isUser
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val bgColor = if (isUser) Indigo500.copy(alpha = 0.15f) else GlassBg
    val borderColor = if (isUser) Indigo500.copy(alpha = 0.3f) else GlassBorder
    val textColor = Slate100
        val shape = RoundedCornerShape(24.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = 60.dp, max = 280.dp)
                .clip(shape)
                .background(bgColor)
                .border(1.dp, borderColor, shape)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Column {
                if (message.base64Image != null || message.attachedBase64Image != null) {
                    val b64 = message.base64Image ?: message.attachedBase64Image
                    val isImage = message.attachedMimeType?.startsWith("image/") == true
                    if (isImage) {
                        var bitmap by remember(b64) { mutableStateOf<android.graphics.Bitmap?>(null) }
                        LaunchedEffect(b64) {
                            bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                try {
                                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                                    val options = BitmapFactory.Options().apply {
                                        inJustDecodeBounds = true
                                    }
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                                    
                                    val maxDim = 1024
                                    var inSampleSize = 1
                                    if (options.outHeight > maxDim || options.outWidth > maxDim) {
                                        val halfHeight: Int = options.outHeight / 2
                                        val halfWidth: Int = options.outWidth / 2
                                        while (halfHeight / inSampleSize >= maxDim && halfWidth / inSampleSize >= maxDim) {
                                            inSampleSize *= 2
                                        }
                                    }
                                    
                                    options.inJustDecodeBounds = false
                                    options.inSampleSize = inSampleSize
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                                } catch (e: Exception) {
                                    null
                                }
                            }
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap!!.asImageBitmap(),
                                contentDescription = if (language == "العربية") "صورة مرفقة" else "Attached image",
                                modifier = Modifier.fillMaxWidth().height(160.dp).padding(bottom = 8.dp)
                            )
                        }
                    } else if (message.attachedMimeType != null) {
                        // Display non-image attachments
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 8.dp).background(Color.White.copy(alpha=0.1f), RoundedCornerShape(8.dp)).padding(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Attachment,
                                contentDescription = "File",
                                tint = Slate100,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "مرفق: ${message.attachedMimeType?.split("/")?.lastOrNull()?.uppercase() ?: "FILE"}",
                                style = MaterialTheme.typography.bodySmall.copy(color = Slate100)
                            )
                        }
                    }
                }
                val youtubeRegex = "(?:https?:\\/\\/)?(?:www\\.)?(?:youtube\\.com\\/watch\\?v=|youtu\\.be\\/)([^\\s&]+)".toRegex()
                val youtubeMatches = youtubeRegex.findAll(message.text).toList()

                val cleanText = youtubeRegex.replace(message.text, "").trim()
                
                if (cleanText.isNotEmpty()) {
                    val context = LocalContext.current
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx ->
                            android.widget.TextView(ctx).apply {
                                setTextColor(android.graphics.Color.WHITE)
                                textSize = 16f
                                autoLinkMask = android.text.util.Linkify.WEB_URLS
                                linksClickable = true
                                setLinkTextColor(android.graphics.Color.parseColor("#06b6d4"))
                                text = cleanText
                            }
                        },
                        update = { view ->
                            view.text = cleanText
                        }
                    )
                }
                
                for (match in youtubeMatches) {
                    val videoId = match.groupValues[1]
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "يوجد مقطع فيديو يمكنك فتحه عبر الرابط أعلاه",
                        style = MaterialTheme.typography.bodySmall.copy(color = Cyan500)
                    )
                }
                if (!isUser) {
                    val context = LocalContext.current
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(
                            onClick = { 
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("Copied Text", message.text)
                                clipboard.setPrimaryClip(clip)
                                android.widget.Toast.makeText(context, if (language == "العربية") "تم نسخ النص" else "Text copied", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(24.dp).padding(top = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = if (language == "العربية") "نسخ" else "Copy",
                                tint = Slate400,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = { 
                                val sendIntent: Intent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, message.text)
                                    type = "text/plain"
                                }
                                val shareIntent = Intent.createChooser(sendIntent, null)
                                context.startActivity(shareIntent)
                            },
                            modifier = Modifier.size(24.dp).padding(top = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = if (language == "العربية") "مشاركة" else "Share",
                                tint = Slate400,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = { onSpeak?.invoke(message.text) },
                            modifier = Modifier.size(48.dp).padding(top = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = if (language == "العربية") "قراءة النص" else "Read Text",
                                tint = Slate400,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
