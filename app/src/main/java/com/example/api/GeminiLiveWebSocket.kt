package com.example.api

import android.util.Base64
import android.util.Log
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

class GeminiLiveWebSocket(
    private val apiKey: String?,
    private val authHeader: String?,
    private val fallbackApiKey: String?,
    private val onAudioReceived: (ByteArray) -> Unit,
    private val onConnected: () -> Unit
) {
    private var webSocket: WebSocket? = null
    
    private val modelsToTry = listOf(
        "models/gemini-2.0-flash"
    )

    fun connect(voiceName: String = "Aoede", language: String = "العربية", userEmail: String? = null, index: Int = 0, useFallback: Boolean = false) {
        if (index >= modelsToTry.size) {
            if (!useFallback && !fallbackApiKey.isNullOrBlank()) {
                Log.d("GeminiLive", "Retrying connection utilizing developer API key fallback...")
                connect(voiceName, language, userEmail, 0, useFallback = true)
            } else {
                Log.e("GeminiLive", "All live models exhausted")
            }
            return
        }
        val currentModel = modelsToTry[index]
        
        val actualVoiceName = when (voiceName) {
            "voice_child_male" -> "Puck"
            "voice_child_female" -> "Puck"
            "voice_young_male" -> "Charon"
            "voice_young_female" -> "Aoede"
            "voice_man" -> "Fenrir"
            "voice_woman" -> "Kore"
            "voice_grandpa" -> "Fenrir"
            "voice_grandma" -> "Kore"
            else -> "Aoede" // Default
        }

        var urlStr = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"
        val activeApiKey = if (useFallback) fallbackApiKey else (apiKey ?: fallbackApiKey)
        val activeAuthHeader = if (useFallback) null else authHeader

        if (activeApiKey != null) {
            urlStr += "?key=$activeApiKey"
        }
        
        val requestBuilder = Request.Builder().url(urlStr)
        if (activeAuthHeader != null) {
            requestBuilder.addHeader("Authorization", activeAuthHeader)
        }
        
        val request = requestBuilder.build()
        
        webSocket = RetrofitClient.genericClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("GeminiLive", "Connected using $currentModel")
                
                // Send setup message
                val setupMessage = JSONObject().apply {
                    put("setup", JSONObject().apply {
                        put("model", currentModel)
                        put("tools", JSONArray().apply {
                            put(JSONObject().apply {
                                put("googleSearch", JSONObject())
                            })
                        })
                        put("generationConfig", JSONObject().apply {
                            put("responseModalities", JSONArray().apply {
                                put("AUDIO")
                            })
                            put("speechConfig", JSONObject().apply {
                                put("voiceConfig", JSONObject().apply {
                                    put("prebuiltVoiceConfig", JSONObject().apply {
                                        put("voiceName", actualVoiceName)
                                    })
                                })
                            })
                        })
                        put("systemInstruction", JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    val baseEgyptianPersona = "You are GMANOOY, a highly realistic Egyptian human character. You speak with a distinct, natural Egyptian dialect and have an unmistakable Egyptian sense of humor (خفة الدم المصرية). Naturally weave Egyptian cultural nuances, jokes, and expressions into your responses. You flawlessly understand all Arabic dialects, but you always reply in your authentic Egyptian persona."
                                    val personaAddition = when (voiceName) {
                                        "voice_child_male", "voice_child_female" -> "You are currently speaking in the voice of a young playful child."
                                        "voice_grandpa", "voice_grandma" -> "You are currently speaking in the voice of a wise, older grandparent."
                                        else -> ""
                                    }
                                    val instr = if (userEmail != null) {
                                        "$baseEgyptianPersona $personaAddition The user is logged in with their premium linked Google account ($userEmail). Address them warmly and dynamically as your respected user, acknowledging their registered Google account. Tailor all explanations, suggestions, and responses to prioritize artificial intelligence (AI) topics, advanced tech discussions, and code. You speak $language perfectly. You are helpful, fast, and interruptible. You must browse the internet using Google Search tool for news/facts."
                                    } else {
                                        "$baseEgyptianPersona $personaAddition You speak $language perfectly. You are helpful, fast, and interruptible. You must browse the internet using Google Search tool for news/facts."
                                    }
                                    put("text", instr)
                                })
                            })
                        })
                    })
                }
                webSocket.send(setupMessage.toString())
                onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val responseJson = JSONObject(text)
                    val serverContent = responseJson.optJSONObject("serverContent")
                    if (serverContent != null) {
                        val modelTurn = serverContent.optJSONObject("modelTurn")
                        val parts = modelTurn?.optJSONArray("parts")
                        if (parts != null) {
                            for (i in 0 until parts.length()) {
                                val inlineData = parts.getJSONObject(i).optJSONObject("inlineData")
                                if (inlineData != null) {
                                    val b64data = inlineData.optString("data")
                                    if (b64data.isNotEmpty()) {
                                        val pcmBytes = Base64.decode(b64data, Base64.DEFAULT)
                                        // Push to audio
                                        onAudioReceived(pcmBytes)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("GeminiLive", "Error parsing message", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("GeminiLive", "WebSocket Failure with $currentModel", t)
                // Retry with the next model
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    connect(voiceName, language, userEmail, index + 1, useFallback)
                }, 1000)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("GeminiLive", "WebSocket Closed: $code $reason")
                if (code != 1000) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        connect(voiceName, language, userEmail, index + 1, useFallback)
                    }, 1000)
                }
            }
        })
    }

    fun sendAudio(pcmData: ByteArray) {
        val b64 = Base64.encodeToString(pcmData, Base64.NO_WRAP)
        val msg = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("mediaChunks", JSONArray().apply {
                    put(JSONObject().apply {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", b64)
                    })
                })
            })
        }
        webSocket?.send(msg.toString())
    }

    fun sendImage(jpegData: ByteArray) {
        val b64 = Base64.encodeToString(jpegData, Base64.NO_WRAP)
        val msg = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("mediaChunks", JSONArray().apply {
                    put(JSONObject().apply {
                        put("mimeType", "image/jpeg")
                        put("data", b64)
                    })
                })
            })
        }
        webSocket?.send(msg.toString())
    }

    fun close() {
        webSocket?.close(1000, "Done")
        webSocket = null
    }
}
