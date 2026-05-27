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
    private val apiKey: String,
    private val onAudioReceived: (ByteArray) -> Unit,
    private val onConnected: () -> Unit
) {
    private var webSocket: WebSocket? = null
    
    private val modelsToTry = listOf(
        "models/gemini-2.0-flash",
        "models/gemini-2.0-flash-exp"
    )

    fun connect(voiceName: String = "Aoede", language: String = "العربية", index: Int = 0) {
        if (index >= modelsToTry.size) {
            Log.e("GeminiLive", "All live models exhausted")
            return
        }
        val currentModel = modelsToTry[index]
        val request = Request.Builder()
            .url("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$apiKey")
            .build()
        
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
                                        put("voiceName", voiceName)
                                    })
                                })
                            })
                        })
                        put("systemInstruction", JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", "You are GMANOOY. You speak $language perfectly. You are helpful, fast, and interruptible. You must browse the internet using Google Search tool for news/facts. Strict Sourcing Policy: When retrieving information or links, strictly prioritize fetching data and URLs from official websites, authorized press, and highly reliable sources. Completely avoid unverified social media claims.")
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
                    connect(voiceName, language, index + 1)
                }, 1000)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("GeminiLive", "WebSocket Closed: $code $reason")
                if (code != 1000) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        connect(voiceName, language, index + 1)
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
