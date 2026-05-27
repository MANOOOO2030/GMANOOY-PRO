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

    private var localTts: android.speech.tts.TextToSpeech? = null

    init {
        try {
            localTts = android.speech.tts.TextToSpeech(application) { status ->
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    val locale = if (language.value == "العربية") {
                        java.util.Locale("ar")
                    } else {
                        java.util.Locale.US
                    }
                    localTts?.language = locale
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val authManager = com.example.auth.AuthManager(application)
    private val defaultApiKey = BuildConfig.GEMINI_API_KEY
    
    val showQuotaExceededDialog = MutableStateFlow(false)

    private fun getApiKey(): String? {
        val token = authManager.googleOAuthToken
        val hasRealBearerToken = !authManager.isGuestMode && !token.isNullOrBlank() && token != "google_signed_in_token_fallback"
        return if (hasRealBearerToken) {
            null
        } else {
            defaultApiKey
        }
    }

    private fun getAuthHeader(): String? {
        val token = authManager.googleOAuthToken
        return if (!authManager.isGuestMode && !token.isNullOrBlank() && token != "google_signed_in_token_fallback") {
            "Bearer $token"
        } else {
            null
        }
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
                    toastMessage.emit(if (language.value == "العربية") "مشكلة في الاتصال بالخدمة" else "Service connection lost")
                    _messages.update { it + ChatMessage(isUser = false, text = if (language.value == "العربية") "خطأ في الاتصال" else "Connection error") }
                }
            } else {
                val responseText = try {
                    runThinkingQuery(text, attachedImageBase64)
                } catch (e: Exception) {
                    e.printStackTrace()
                    toastMessage.emit(if (language.value == "العربية") "مشكلة في الاتصال بالخدمة" else "Service connection lost")
                    if (language.value == "العربية") "خطأ في الاتصال" else "Connection error"
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
            val request = GenerateContentRequest(
                contents = listOf(Content(role = "user", parts = listOf(Part(text = "Reread the following exactly, responding with nothing else: $text")))),
                generationConfig = GenerationConfig(
                    responseModalities = listOf("AUDIO"),
                    speechConfig = SpeechConfig(VoiceConfig(PrebuiltVoiceConfig(voiceName.value)))
                )
            )
            
            var success = false
            val ttsModels = listOf("gemini-3.5-flash", "gemini-2.5-flash-preview-tts")
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
                // FALLBACK to local Android TTS
                withContext(Dispatchers.Main) {
                    try {
                        val locale = if (language.value == "العربية") {
                            java.util.Locale("ar")
                        } else {
                            java.util.Locale.US
                        }
                        localTts?.language = locale
                        localTts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, null)
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
        val imageModels = listOf("imagen-3.0-generate-002", "gemini-3.5-flash")
        for (model in imageModels) {
            try {
                val url = if (model.startsWith("gemini-2") || model.startsWith("gemini-3")) "v1alpha/models/$model:generateContent" else "v1beta/models/$model:generateContent"
                val response = RetrofitClient.service.generateImage(url, getAuthHeader(), getApiKey(), request)
                val imgData = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.inlineData?.data
                if (imgData != null) return@withContext imgData
            } catch (e: retrofit2.HttpException) {
                e.printStackTrace()
                if (e.code() == 429 || e.code() == 401 || e.code() == 403) {
                    toastMessage.emit(if (language.value == "العربية") "مشكلة في الحصة أو المصادقة (يرجى تسجيل الدخول مجدداً)" else "Quota exceeded or Authentication error. Please sign in again.")
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

        val googleEmail = authManager.googleUserEmail
        val systemInstructionText = if (googleEmail != null) {
            "You are GMANOOY, an advanced and highly specialized AI Assistant. The user is logged in with their premium linked Google account ($googleEmail). Address them warmly and dynamically as your respected user, acknowledging their registered Google account. Tailor all explanations, suggestions, and responses to prioritize artificial intelligence (AI) topics, advanced tech discussions, and coding. Response format: Standard text. Respond in the requested language."
        } else {
            "You are GMANOOY, a helpful assistant. Response format: Standard text. Respond in the requested language."
        }

        var textResult = "لا يوجد رد"
        val modelsToTry = listOf("gemini-2.5-flash", "models/gemini-2.5-flash", "gemini-3.5-flash", "gemini-3.1-pro-preview")
        var isSuccess = false

        // Strategy A: Try with Google Search tool and system instructions
        for (model in modelsToTry) {
            try {
                val request = GenerateContentRequest(
                    contents = finalList,
                    tools = listOf(Tool(googleSearch = emptyMap())),
                    systemInstruction = Content(role = "system", parts = listOf(Part(text = systemInstructionText))),
                    generationConfig = GenerationConfig(
                        responseModalities = listOf("TEXT")
                    )
                )
                val url = if (model.startsWith("gemini-2") || model.startsWith("gemini-3")) {
                    "v1alpha/models/$model:generateContent"
                } else {
                    "v1beta/models/$model:generateContent"
                }
                val response = RetrofitClient.service.generateContent(url, getAuthHeader(), getApiKey(), request)
                val parts = response.candidates?.firstOrNull()?.content?.parts
                parts?.forEach { part ->
                    part.text?.let { textResult = it }
                }
                if (textResult != "لا يوجد رد" && textResult.isNotBlank()) {
                    isSuccess = true
                    break
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Strategy B: Fallback to text generation WITHOUT Google Search grounding tools (fixes search quota errors)
        if (!isSuccess) {
            for (model in modelsToTry) {
                try {
                    val request = GenerateContentRequest(
                        contents = finalList,
                        systemInstruction = Content(role = "system", parts = listOf(Part(text = systemInstructionText))),
                        generationConfig = GenerationConfig(
                            responseModalities = listOf("TEXT")
                        )
                    )
                    val endpoints = listOf(
                        "v1beta/models/$model:generateContent",
                        "v1alpha/models/$model:generateContent"
                    )
                    for (url in endpoints) {
                        try {
                            val response = RetrofitClient.service.generateContent(url, getAuthHeader(), getApiKey(), request)
                            val parts = response.candidates?.firstOrNull()?.content?.parts
                            parts?.forEach { part ->
                                part.text?.let { textResult = it }
                            }
                            if (textResult != "لا يوجد رد" && textResult.isNotBlank()) {
                                isSuccess = true
                                break
                            }
                        } catch (e2: Exception) {
                            e2.printStackTrace()
                        }
                    }
                    if (isSuccess) break
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // Strategy C: Absolute minimal request structure (ignores headers/instructs to get maximum success rate)
        if (!isSuccess) {
            for (model in modelsToTry) {
                try {
                    val request = GenerateContentRequest(contents = finalList)
                    val response = RetrofitClient.service.generateContent(
                        "v1beta/models/$model:generateContent",
                        null,
                        getApiKey(),
                        request
                    )
                    val parts = response.candidates?.firstOrNull()?.content?.parts
                    parts?.forEach { part ->
                        part.text?.let { textResult = it }
                    }
                    if (textResult != "لا يوجد رد" && textResult.isNotBlank()) {
                        isSuccess = true
                        break
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        if (!isSuccess) {
            // Friendly fallback troubleshooting message
            textResult = if (language.value == "العربية") {
                "بوابة نموذج الذكاء الاصطناعي مغلقة حالياً. يرجى إدخال مفتاح GEMINI_API_KEY صحيح وصالح وخالي من القيود في لوحة الأسرار (Secrets panel) في Google AI Studio ثم إعادة تشغيل التطبيق."
            } else {
                "AI model gateway is closed. Please enter a valid, unrestricted 'GEMINI_API_KEY' in the Secrets panel in Google AI Studio and restart the application."
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
        try {
            localTts?.stop()
            localTts?.shutdown()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
