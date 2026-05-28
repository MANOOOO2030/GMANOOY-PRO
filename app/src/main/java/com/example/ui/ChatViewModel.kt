package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.R
import androidx.lifecycle.viewModelScope
import com.example.api.*
import com.example.audio.AudioHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val isUser: Boolean,
    val text: String,
    val base64Image: String? = null,
    val attachedBase64Image: String? = null,
    val attachedMimeType: String? = "image/jpeg"
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLiveAudioActive = MutableStateFlow(false)
    val isLiveAudioActive: StateFlow<Boolean> = _isLiveAudioActive.asStateFlow()

    private val _isCameraActive = MutableStateFlow(false)
    val isCameraActive: StateFlow<Boolean> = _isCameraActive.asStateFlow()

    init {
        try {
            // Replaced by cloud TTS
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val authManager = com.example.auth.AuthManager(application)
    val GEMINI_API_KEY = "YOUR_API_KEY_HERE"
    
    val showQuotaExceededDialog = MutableStateFlow(false)

    private fun getApiKey(): String? {
        return GEMINI_API_KEY
    }

    private fun getAuthHeader(): String? {
        return null
    }

    private fun consumeQuotaAndCheck(): Boolean {
        if (authManager.isGuestMode) {
            authManager.incrementGuestMessageCount()
        }
        return true
    }

    private var liveWebSocket: GeminiLiveWebSocket? = null
    
    val language = MutableStateFlow(
        authManager.language.let { lang ->
            if (lang == "System Default (Auto)") {
                if (java.util.Locale.getDefault().language == "ar") "العربية" else "English"
            } else {
                lang
            }
        }
    )
    val voiceName = MutableStateFlow(
        authManager.selectedVoice
    )
    val toastMessage = kotlinx.coroutines.flow.MutableSharedFlow<String>()

    private val audioHandler = AudioHandler(application) { pcmData ->
        liveWebSocket?.sendAudio(pcmData)
    }

    fun clearChat() {
        _messages.update { emptyList() }
    }

    fun sendMessage(text: String, attachedImageBase64: String? = null, mimeType: String? = "image/jpeg") {
        if (text.isBlank() && attachedImageBase64 == null) return
        
        viewModelScope.launch(Dispatchers.IO) {
            if (!consumeQuotaAndCheck()) return@launch
            
            _messages.update { it + ChatMessage(isUser = true, text = text, attachedBase64Image = attachedImageBase64, attachedMimeType = mimeType) }

            if (isImageGenerationPrompt(text)) {
                try {
                    val base64 = runImageGenerationQuery(text)
                    if (base64 != null) {
                        _messages.update { it + ChatMessage(isUser = false, text = "تم إنشاء الصورة:", base64Image = base64) }
                    } else {
                        throw Exception("Failed to generate image")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    toastMessage.emit(getApplication<Application>().getString(R.string.service_conn_lost))
                    _messages.update { it + ChatMessage(isUser = false, text = getApplication<Application>().getString(R.string.conn_error)) }
                }
            } else {
                val responseText = try {
                    runThinkingQuery(text, attachedImageBase64)
                } catch (e: Exception) {
                    e.printStackTrace()
                    toastMessage.emit(getApplication<Application>().getString(R.string.service_conn_lost))
                    getApplication<Application>().getString(R.string.conn_error)
                }
                _messages.update { it + ChatMessage(isUser = false, text = responseText) }
            }
        }
    }

    fun processUriAndSendMessage(context: android.content.Context, text: String, uri: android.net.Uri?) {
        if (uri == null) {
            sendMessage(text)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            var base64: String? = null
            var mimeType: String? = "image/jpeg"
            try {
                mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val inputStream = context.contentResolver.openInputStream(uri)
                if (mimeType?.startsWith("image/") == true) {
                    val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()
                    if (bitmap != null) {
                        val maxDim = 800f
                        val scale = minOf(maxDim / bitmap.width.toFloat(), maxDim / bitmap.height.toFloat(), 1f)
                        val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * scale).toInt()), maxOf(1, (bitmap.height * scale).toInt()), true)
                        val outputStream = java.io.ByteArrayOutputStream()
                        scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, outputStream)
                        base64 = android.util.Base64.encodeToString(outputStream.toByteArray(), android.util.Base64.NO_WRAP)
                        mimeType = "image/jpeg"
                        if (scaledBitmap != bitmap) {
                            scaledBitmap.recycle()
                        }
                        bitmap.recycle()
                    }
                } else {
                    val bytes = inputStream?.readBytes()
                    inputStream?.close()
                    if (bytes != null) {
                        base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    }
                }
            } catch (t: Throwable) { t.printStackTrace() }
            
            sendMessage(text, base64, mimeType)
        }
    }

    private fun isImageGenerationPrompt(text: String): Boolean {
        val lower = text.lowercase().trim()
        val englishTriggers = listOf("generate image", "generate an image", "create image", "create an image", "draw a ", "draw an ", "paint a ", "paint an ")
        val arabicTriggers = listOf("ارسم", "توليد صورة", "أنشئ لي صورة", "اصنع صورة", "تخيل صورة", "صورة لـ", "صورة ل ")
        
        return englishTriggers.any { lower.contains(it) } || arabicTriggers.any { lower.contains(it) }
    }

    fun playAudioForText(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val actualVoiceName = when (voiceName.value) {
                "voice_child_male" -> "Puck"
                "voice_child_female" -> "Puck"
                "voice_young_male" -> "Charon"
                "voice_young_female" -> "Aoede"
                "voice_man" -> "Fenrir"
                "voice_woman" -> "Kore"
                "voice_grandpa" -> "Fenrir"
                "voice_grandma" -> "Kore"
                else -> "Aoede"
            }

            val instruction = when (voiceName.value) {
                "voice_child_male" -> "Reread the following exactly. Speak in the highly expressive, emotional voice of a young boy, including natural human elements like child-like expression and correct intonation. Do not add extra words: "
                "voice_child_female" -> "Reread the following exactly. Speak in the highly expressive, emotional voice of a young girl, including natural human elements like child-like expression and correct intonation. Do not add extra words: "
                "voice_young_male" -> "Reread the following exactly. Speak in the highly expressive, emotional voice of a young man, including natural human elements like breathing and correct intonation. Do not add extra words: "
                "voice_young_female" -> "Reread the following exactly. Speak in the highly expressive, emotional voice of a young woman, including natural human elements like breathing and correct intonation. Do not add extra words: "
                "voice_man" -> "Reread the following exactly. Speak in the highly expressive, emotional voice of an adult man, including natural human elements like breathing and correct intonation. Do not add extra words: "
                "voice_woman" -> "Reread the following exactly. Speak in the highly expressive, emotional voice of an adult woman, including natural human elements like breathing and correct intonation. Do not add extra words: "
                "voice_grandpa" -> "Reread the following exactly. Speak in the highly expressive, emotional voice of an old grandfather, including natural human elements like slow speaking and correct intonation. Do not add extra words: "
                "voice_grandma" -> "Reread the following exactly. Speak in the highly expressive, emotional voice of an old grandmother, including natural human elements like slow speaking and correct intonation. Do not add extra words: "
                else -> "Reread the following exactly, responding with nothing else: "
            }

            val request = GenerateContentRequest(
                contents = listOf(Content(role = "user", parts = listOf(Part(text = "$instruction$text")))),
                generationConfig = GenerationConfig(
                    responseModalities = listOf("AUDIO"),
                    speechConfig = SpeechConfig(VoiceConfig(PrebuiltVoiceConfig(actualVoiceName)))
                )
            )
            
            var success = false
            val ttsModels = listOf("gemini-2.0-flash")
            
            for (model in ttsModels) {
                try {
                    val url = "v1beta/models/$model:generateContent"
                    val response = RetrofitClient.service.generateContent(url, getAuthHeader(), getApiKey(), request)
                    var played = false
                    response.candidates?.firstOrNull()?.content?.parts?.forEach { part ->
                        part.inlineData?.let { inline ->
                            if (inline.mimeType.startsWith("audio/")) {
                                try {
                                    val decoded = android.util.Base64.decode(inline.data, android.util.Base64.DEFAULT)
                                    val tempFile = java.io.File(getApplication<Application>().cacheDir, "temp_audio.wav")
                                    tempFile.writeBytes(decoded)
                                    val mediaPlayer = android.media.MediaPlayer()
                                    mediaPlayer.setDataSource(tempFile.absolutePath)
                                    mediaPlayer.prepare()
                                    mediaPlayer.start()
                                    mediaPlayer.setOnCompletionListener {
                                        it.release()
                                    }
                                    played = true
                                    success = true
                                } catch (e: Exception) { e.printStackTrace() }
                            }
                        }
                    }
                    if (played) break
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            if (!success) {
                // cloud TTS failed
            }
        }
    }

    fun setVoiceAndGreet(newVoice: String) {
        voiceName.value = newVoice
        val sampleText = getApplication<Application>().getString(R.string.sample_greeting)
        playAudioForText(sampleText)
    }

    private suspend fun runImageGenerationQuery(prompt: String): String? = withContext(Dispatchers.IO) {
        val request = GenerateContentRequest(
            contents = listOf(Content(role = "user", parts = listOf(Part(text = prompt)))),
            generationConfig = GenerationConfig(
                imageConfig = ImageConfig(aspectRatio = "1:1", imageSize = "1K"),
                responseModalities = listOf("IMAGE")
            )
        )
        val imageModels = listOf("gemini-1.5-flash")
        
        for (model in imageModels) {
            try {
                val url = "v1beta/models/$model:generateContent"
                val response = RetrofitClient.service.generateImage(url, getAuthHeader(), getApiKey(), request)
                val imgData = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.inlineData?.data
                if (imgData != null) return@withContext imgData
            } catch (e: retrofit2.HttpException) {
                e.printStackTrace()
                if (e.code() == 429 || e.code() == 401 || e.code() == 403) {
                    toastMessage.emit(getApplication<Application>().getString(R.string.quota_exceeded))
                }
            } catch(e: Exception) {
                // Ignore and try the next model
            }
        }
        null
    }

    private suspend fun runThinkingQuery(prompt: String, attachedImageBase64: String?): String = withContext(Dispatchers.IO) {
        // Collect history
        val contents = _messages.value.map { msg ->
            val partsList = mutableListOf<Part>()
            if (msg.text.isNotEmpty()) partsList.add(Part(text = msg.text))
            
            if (msg.attachedBase64Image != null) {
                partsList.add(Part(inlineData = InlineData(msg.attachedMimeType ?: "image/jpeg", msg.attachedBase64Image)))
            } else if (msg.base64Image != null) {
                partsList.add(Part(inlineData = InlineData("image/jpeg", msg.base64Image)))
            }

            Content(
                role = if (msg.isUser) "user" else "model",
                parts = if (partsList.isEmpty()) listOf(Part(text = " ")) else partsList
            )
        }
        
        // Remove the newly added message from contents since it was already added to _messages in sendMessage
        val finalList = mutableListOf<Content>()
        for (c in contents) {
            if (finalList.isNotEmpty() && finalList.last().role == c.role) {
                val last = finalList.removeLast()
                finalList.add(Content(role = c.role, parts = last.parts + c.parts))
            } else {
                finalList.add(c)
            }
        }

        val baseEgyptianPersona = "You are GMANOOY, a highly realistic Egyptian human character. You speak with a distinct, natural Egyptian dialect and have an unmistakable Egyptian sense of humor (خفة الدم المصرية). Naturally weave Egyptian cultural nuances, jokes, and expressions into your responses. You flawlessly understand all Arabic dialects, but you always reply in your authentic Egyptian persona."
        val googleEmail = authManager.googleUserEmail
        val systemInstructionText = if (googleEmail != null) {
            "$baseEgyptianPersona The user is logged in with their premium linked Google account ($googleEmail). Address them warmly and dynamically as your respected user and friend. Prioritize artificial intelligence (AI) topics, advanced tech discussions, and coding. Response format: Standard text."
        } else {
            "$baseEgyptianPersona Response format: Standard text."
        }

        var textResult = "لا يوجد رد"
        val modelsToTry = if (!authManager.isGuestMode && googleEmail != null) {
            // Upgraded experience for logged-in users (Google Sign-In Perk)
            listOf("gemini-1.5-pro", "gemini-2.0-flash", "gemini-1.5-flash", "gemini-1.0-pro")
        } else {
            // Free-tier experience for guests
            listOf("gemini-1.5-flash", "gemini-2.0-flash", "gemini-1.5-pro", "gemini-1.0-pro")
        }
        var isSuccess = false

        // Strategy A: Only one simple and clean API call
        var lastErrorMesssage: String? = null
        for (model in modelsToTry) {
            try {
                val request = GenerateContentRequest(
                    contents = finalList,
                    systemInstruction = Content(role = "system", parts = listOf(Part(text = systemInstructionText)))
                )
                val url = "v1beta/models/$model:generateContent"
                val response = RetrofitClient.service.generateContent(url, getAuthHeader(), getApiKey(), request)
                val parts = response.candidates?.firstOrNull()?.content?.parts
                parts?.forEach { part ->
                    part.text?.let { textResult = it }
                }
                if (textResult != "لا يوجد رد" && textResult.isNotBlank()) {
                    isSuccess = true
                    break
                }
            } catch (e: retrofit2.HttpException) {
                lastErrorMesssage = "API Error (${e.code()}): ${e.message()} (Model: $model)"
                android.util.Log.e("GMANOOY_API", lastErrorMesssage, e)
                e.printStackTrace()
            } catch (e: Exception) {
                lastErrorMesssage = "Error: ${e.message}"
                android.util.Log.e("GMANOOY_API", lastErrorMesssage, e)
                e.printStackTrace()
            }
        }

        if (!isSuccess) {
            textResult = getApplication<Application>().getString(R.string.ai_conn_failed, lastErrorMesssage ?: "Unknown")
        }

        textResult
    }

    fun toggleLiveAudio() {
        if (_isLiveAudioActive.value) {
            stopLiveAudio()
        } else {
            startLiveAudio()
        }
    }

    fun toggleCamera() {
        if (_isCameraActive.value) {
            _isCameraActive.value = false
        } else {
            _isCameraActive.value = true
            // Must have live active to send camera stream
            if (!_isLiveAudioActive.value) {
                startLiveAudio()
            }
        }
    }

    fun feedCameraFrame(jpegData: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_isLiveAudioActive.value) {
                liveWebSocket?.sendImage(jpegData)
            }
        }
    }

    private fun startLiveAudio() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!consumeQuotaAndCheck()) return@launch
            
            _isLiveAudioActive.value = true
            _messages.update { it + ChatMessage(isUser = false, text = "🔊 بدأ الصوت المباشر... يمكنك التحدث الآن.") }
            
            liveWebSocket = GeminiLiveWebSocket(
                apiKey = getApiKey(),
                authHeader = getAuthHeader(),
                fallbackApiKey = GEMINI_API_KEY,
                onAudioReceived = { pcm ->
                    audioHandler.feedAudioOutput(pcm)
                },
                onConnected = {
                    audioHandler.startRecording()
                    audioHandler.startPlaying()
                }
            )
            liveWebSocket?.connect(voiceName.value, language.value, authManager.googleUserEmail)
        }
    }

    private fun stopLiveAudio() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLiveAudioActive.value = false
            audioHandler.stopRecording()
            audioHandler.stopPlaying()
            liveWebSocket?.close()
            liveWebSocket = null
            _messages.update { it + ChatMessage(isUser = false, text = "🔇 توقف الصوت المباشر.") }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopLiveAudio()
    }
}
