package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
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

    private val authManager = com.example.auth.AuthManager(application)
    private val defaultApiKey = BuildConfig.GEMINI_API_KEY
    
    val showQuotaExceededDialog = MutableStateFlow(false)

    private fun getApiKey(): String? {
        return if (authManager.isGuestMode) defaultApiKey else null
    }

    private fun getAuthHeader(): String? {
        return if (!authManager.isGuestMode && authManager.googleOAuthToken != null) {
            "Bearer ${authManager.googleOAuthToken}"
        } else null
    }

    private fun consumeQuotaAndCheck(): Boolean {
        if (authManager.isGuestMode) {
            if (authManager.hasReachedGuestQuota(limit = 5)) {
                showQuotaExceededDialog.value = true
                return false
            } else {
                authManager.incrementGuestMessageCount()
            }
        }
        return true
    }

    private var liveWebSocket: GeminiLiveWebSocket? = null
    
    val language = MutableStateFlow("العربية")
    val voiceName = MutableStateFlow("Aoede")

    private val audioHandler = AudioHandler(application) { pcmData ->
        liveWebSocket?.sendAudio(pcmData)
    }

    fun clearChat() {
        _messages.update { emptyList() }
    }

    fun sendMessage(text: String, attachedImageBase64: String? = null, mimeType: String? = "image/jpeg") {
        if (text.isBlank() && attachedImageBase64 == null) return
        
        if (!consumeQuotaAndCheck()) return
        
        _messages.update { it + ChatMessage(isUser = true, text = text, attachedBase64Image = attachedImageBase64, attachedMimeType = mimeType) }

        viewModelScope.launch {
            if (isImageGenerationPrompt(text)) {
                try {
                    val base64 = runImageGenerationQuery(text)
                    _messages.update { it + ChatMessage(isUser = false, text = "تم إنشاء الصورة:", base64Image = base64) }
                } catch (e: Exception) {
                    _messages.update { it + ChatMessage(isUser = false, text = "خطأ في إنشاء الصورة: ${e.message}") }
                }
            } else {
                val responseText = try {
                    runThinkingQuery(text, attachedImageBase64)
                } catch (e: Exception) {
                    "خطأ: ${e.message}"
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
            } catch (e: Exception) { e.printStackTrace() }
            
            withContext(Dispatchers.Main) {
                sendMessage(text, base64, mimeType)
            }
        }
    }

    private fun isImageGenerationPrompt(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("generate image") || lower.contains("ارسم") || lower.contains("صورة") || lower.contains("generate an image")
    }

    fun playAudioForText(text: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val request = GenerateContentRequest(
                    contents = listOf(Content(role = "user", parts = listOf(Part(text = "Reread the following exactly, responding with nothing else: $text")))),
                    generationConfig = GenerationConfig(
                        responseModalities = listOf("AUDIO"),
                        speechConfig = SpeechConfig(VoiceConfig(PrebuiltVoiceConfig(voiceName.value)))
                    )
                )
                
                val ttsModels = listOf(
                    "gemini-2.0-flash",
                    "gemini-2.0-flash-exp"
                )
                for (model in ttsModels) {
                    try {
                        val url = if (model.startsWith("gemini-2") || model.startsWith("gemini-3")) "v1alpha/models/$model:generateContent" else "v1beta/models/$model:generateContent"
                        val response = RetrofitClient.service.generateContent(url, getAuthHeader(), getApiKey(), request)
                        var played = false
                        response.candidates?.firstOrNull()?.content?.parts?.forEach { part ->
                            part.inlineData?.let { inline ->
                                if (inline.mimeType.startsWith("audio/")) {
                                    try {
                                        val decoded = android.util.Base64.decode(inline.data, android.util.Base64.DEFAULT)
                                        audioHandler.startPlaying()
                                        val pcmData = if (decoded.size > 44 && decoded[0] == 'R'.code.toByte() && decoded[1] == 'I'.code.toByte()) {
                                            decoded.copyOfRange(44, decoded.size)
                                        } else {
                                            decoded
                                        }
                                        audioHandler.feedAudioOutput(pcmData)
                                        played = true
                                    } catch (e: Exception) { e.printStackTrace() }
                                }
                            }
                        }
                        if (played) break
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    fun setVoiceAndGreet(newVoice: String) {
        voiceName.value = newVoice
        val sampleText = if (language.value == "العربية") "مرحباً، أنا جي مانوي. كيف يمكنني مساعدتك؟" else "Hello, I am GMANOOY. How can I help you?"
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
        val imageModels = listOf(
            "gemini-2.0-flash",
            "gemini-2.0-flash-exp"
        )
        for (model in imageModels) {
            try {
                val url = if (model.startsWith("gemini-2") || model.startsWith("gemini-3")) "v1alpha/models/$model:generateContent" else "v1beta/models/$model:generateContent"
                val response = RetrofitClient.service.generateImage(url, getAuthHeader(), getApiKey(), request)
                val imgData = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.inlineData?.data
                if (imgData != null) return@withContext imgData
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

        val request = GenerateContentRequest(
            contents = finalList,
            tools = listOf(Tool(googleSearch = emptyMap())),
            systemInstruction = Content(role = "system", parts = listOf(Part(text = "You are GMANOOY, a helpful assistant. You must browse the internet using Google Search tool whenever the user asks for news, events, or factual topics. Strict Sourcing Policy: When retrieving information or links, strictly prioritize fetching data and URLs from official websites, authorized press, and highly reliable sources. Completely avoid unverified social media claims. Respond in the requested language."))),
            generationConfig = GenerationConfig(
                responseModalities = listOf("AUDIO", "TEXT"),
                speechConfig = SpeechConfig(VoiceConfig(PrebuiltVoiceConfig(voiceName.value)))
            )
        )

        var textResult = "لا يوجد رد"
        val modelsToTry = listOf(
            "gemini-2.0-flash",
            "gemini-2.0-flash-exp"
        )
        
        for (model in modelsToTry) {
            try {
                val currentRequest = request
                val url = if (model.startsWith("gemini-2") || model.startsWith("gemini-3")) {
                    "v1alpha/models/$model:generateContent"
                } else {
                    "v1beta/models/$model:generateContent"
                }
                val response = RetrofitClient.service.generateContent(url, getAuthHeader(), getApiKey(), currentRequest)
                val parts = response.candidates?.firstOrNull()?.content?.parts
                parts?.forEach { part ->
                    part.text?.let { textResult = it }
                    part.inlineData?.let { inline ->
                        if (inline.mimeType.startsWith("audio/")) {
                            try {
                                val decoded = android.util.Base64.decode(inline.data, android.util.Base64.DEFAULT)
                                audioHandler.startPlaying()
                                val pcmData = if (decoded.size > 44 && decoded[0] == 'R'.code.toByte() && decoded[1] == 'I'.code.toByte()) {
                                    decoded.copyOfRange(44, decoded.size)
                                } else {
                                    decoded
                                }
                                audioHandler.feedAudioOutput(pcmData)
                            } catch (e: Exception) { e.printStackTrace() }
                        }
                    }
                }
                if (textResult == "لا يوجد رد" && parts?.any { it.inlineData?.mimeType?.startsWith("audio/") == true } == true) {
                    textResult = "🎶 (الرد الصوتي)"
                }
                break // Success, exit loop
            } catch (e: retrofit2.HttpException) {
                e.printStackTrace()
                textResult = "خطأ في الاتصال بنموذج $model: ${e.code()} ${e.message()}"
                if (e.code() == 429) {
                    kotlinx.coroutines.delay(1000) // Wait 1 second before trying the next model
                }
            } catch (e: Exception) {
                e.printStackTrace()
                textResult = "خطأ عام في الاتصال... ${e.message}"
            }
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
        if (_isLiveAudioActive.value) {
            liveWebSocket?.sendImage(jpegData)
        }
    }

    private fun startLiveAudio() {
        if (!consumeQuotaAndCheck()) return
        
        _isLiveAudioActive.value = true
        _messages.update { it + ChatMessage(isUser = false, text = "🔊 بدأ الصوت المباشر... يمكنك التحدث الآن.") }
        
        liveWebSocket = GeminiLiveWebSocket(
            apiKey = getApiKey(),
            authHeader = getAuthHeader(),
            onAudioReceived = { pcm ->
                audioHandler.feedAudioOutput(pcm)
            },
            onConnected = {
                audioHandler.startRecording()
                audioHandler.startPlaying()
            }
        )
        liveWebSocket?.connect(voiceName.value, language.value)
    }

    private fun stopLiveAudio() {
        _isLiveAudioActive.value = false
        audioHandler.stopRecording()
        audioHandler.stopPlaying()
        liveWebSocket?.close()
        liveWebSocket = null
        _messages.update { it + ChatMessage(isUser = false, text = "🔇 توقف الصوت المباشر.") }
    }

    override fun onCleared() {
        super.onCleared()
        stopLiveAudio()
    }
}
