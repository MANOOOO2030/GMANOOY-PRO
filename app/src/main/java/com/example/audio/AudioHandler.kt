package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue

import android.content.Context
import android.os.Build

class AudioHandler(
    private val context: Context,
    private val onAudioData: (ByteArray) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var recordingJob: Job? = null
    private var playingJob: Job? = null
    
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private val scope = CoroutineScope(Dispatchers.IO)

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (recordingJob?.isActive == true) return
        
        recordingJob = scope.launch(Dispatchers.IO) {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = if (minBufferSize > 0) minBufferSize * 2 else 4096

            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
            } catch (e: SecurityException) {
                e.printStackTrace()
                return@launch
            } catch (e: IllegalArgumentException) {
                e.printStackTrace()
                return@launch
            }

            try {
                audioRecord?.startRecording()
            } catch (e: Exception) {
                e.printStackTrace()
                return@launch
            }

            val buffer = ByteArray(bufferSize)
            while (isActive) {
                try {
                    val record = audioRecord
                    if (record == null) {
                        kotlinx.coroutines.delay(100)
                        continue
                    }
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        onAudioData(buffer.copyOfRange(0, read))
                    } else {
                        kotlinx.coroutines.delay(10) // avoid spinning if read returns 0 or error
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    kotlinx.coroutines.delay(100)
                }
            }
            recordingJob = null
        }
    }

    fun stopRecording() {
        val job = recordingJob
        recordingJob = null
        val record = audioRecord
        audioRecord = null
        scope.launch(Dispatchers.IO) {
            try {
                record?.stop()
            } catch(e: Exception) {}
            try {
                job?.cancel()
                job?.join()
            } catch(e: Exception) {}
            try {
                record?.release()
            } catch(e: Exception) {}
        }
    }

    fun startPlaying() {
        if (playingJob?.isActive == true) return
        
        playingJob = scope.launch(Dispatchers.IO) {
            val sampleRate = 24000
            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = if (minBufferSize > 0) minBufferSize * 2 else 4096

            try {
                val builder = AudioTrack.Builder()
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)

                // No setContext on AudioTrack Builder! 
                audioTrack = builder.build()
                    
                audioTrack?.play()
            } catch (e: Exception) {
                e.printStackTrace()
                return@launch
            }

            while (isActive) {
                try {
                    val chunk = audioQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (chunk != null) {
                        try {
                            audioTrack?.write(chunk, 0, chunk.size)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            playingJob = null
        }
    }

    fun feedAudioOutput(pcmData: ByteArray) {
        audioQueue.add(pcmData)
    }

    fun stopPlaying() {
        val job = playingJob
        playingJob = null
        val track = audioTrack
        audioTrack = null
        audioQueue.clear()
        scope.launch(Dispatchers.IO) {
            try {
                track?.stop()
            } catch(e: Exception) {}
            try {
                job?.cancel()
                job?.join()
            } catch(e: Exception) {}
            try {
                track?.release()
            } catch(e: Exception) {}
        }
    }
}
