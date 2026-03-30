package com.example.utt_trafficjams.ai

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class AudioPlayer {
    private var audioTrack: AudioTrack? = null
    private val audioChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private var playJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        try {
            val sampleRate = 24000
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Lỗi khởi tạo AudioTrack: ${e.message}")
        }
    }

    fun play() {
        try {
            if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack?.play()
            }
            if (playJob == null || playJob?.isActive == false) {
                playJob = scope.launch {
                    for (pcmData in audioChannel) {
                        try {
                            audioTrack?.write(pcmData, 0, pcmData.size)
                        } catch (e: Exception) {
                            Log.e("AudioPlayer", "Lỗi ghi âm thanh: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Lỗi phát âm thanh: ${e.message}")
        }
    }

    fun write(pcmData: ByteArray) {
        audioChannel.trySend(pcmData)
    }

    private fun clearPendingAudio() {
        while (true) {
            val drained = audioChannel.tryReceive()
            if (drained.isFailure) break
        }
    }

    fun stop() {
        try {
            playJob?.cancel()
            playJob = null
            clearPendingAudio()
            audioTrack?.stop()
            audioTrack?.flush()
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Lỗi dừng âm thanh: ${e.message}")
        }
    }

    fun release() {
        stop()
        audioTrack?.release()
        audioTrack = null
        scope.cancel()
    }
}
