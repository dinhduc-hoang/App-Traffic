package com.example.utt_trafficjams.ai

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AudioRecorder(private val onAudioChunk: (ByteArray) -> Unit) {
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    var isRecording = false
        private set

    @SuppressLint("MissingPermission")
    fun startRecording(scope: kotlinx.coroutines.CoroutineScope) {
        if (isRecording) return

        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            audioRecord?.startRecording()
            isRecording = true

            recordingJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(bufferSize)
                while (isActive && isRecording) {
                    val readResult = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                    if (readResult > 0) {
                        val pcmData = buffer.copyOf(readResult)
                        onAudioChunk(pcmData)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            isRecording = false
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null
    }
}
