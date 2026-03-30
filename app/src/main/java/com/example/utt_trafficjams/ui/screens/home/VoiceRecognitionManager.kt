package com.example.utt_trafficjams.ui.screens.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Bọc SpeechRecognizer của Android để dễ sử dụng với Compose.
 * Sử dụng tiếng Việt (vi-VN) làm ngôn ngữ nhận dạng.
 */
class VoiceRecognitionManager(
    private val context  : Context,
    private val onResult : (String) -> Unit,
    private val onStart  : () -> Unit  = {},
    private val onEnd    : () -> Unit  = {},
    private val onError  : (String) -> Unit = {}
) {
    private var recognizer: SpeechRecognizer? = null

    /** Khởi động lắng nghe giọng nói */
    fun startListening() {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { onStart() }
                override fun onBeginningOfSpeech() { }
                override fun onRmsChanged(rmsdB: Float) { }
                override fun onBufferReceived(buffer: ByteArray?) { }
                override fun onEndOfSpeech() { onEnd() }

                override fun onError(error: Int) {
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_AUDIO            -> "Lỗi âm thanh"
                        SpeechRecognizer.ERROR_CLIENT           -> "Lỗi client"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Thiếu quyền micro"
                        SpeechRecognizer.ERROR_NETWORK          -> "Lỗi mạng"
                        SpeechRecognizer.ERROR_NO_MATCH         -> "Không nhận ra giọng nói"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Đang bận"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT   -> "Hết thời gian"
                        else                                    -> "Lỗi không xác định ($error)"
                    }
                    onError(msg)
                    onEnd()
                }

                override fun onResults(results: Bundle?) {
                    val matches = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    onResult(text)
                }

                override fun onPartialResults(partialResults: Bundle?) { }
                override fun onEvent(eventType: Int, params: Bundle?) { }
            })

            startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale("vi", "VN").toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói câu hỏi của bạn...")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
            )
        }
    }

    /** Dừng lắng nghe */
    fun stopListening() {
        recognizer?.stopListening()
    }

    /** Giải phóng tài nguyên */
    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }
}
