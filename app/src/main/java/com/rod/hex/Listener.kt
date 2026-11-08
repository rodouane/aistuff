package com.rod.hex

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/** Transcription de la voix de Rod en texte (reconnaissance vocale d'Android, en français). */
class Listener(private val context: Context) {
    interface Callback {
        fun onLevel(level: Float)
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onNothing()
    }

    private var sr: SpeechRecognizer? = null

    /** À appeler sur le thread principal. */
    fun listen(cb: Callback, offline: Boolean, followUp: Boolean) {
        cancel()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { cb.onNothing(); return }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        sr = r
        var done = false
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { cb.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                Log.d("HexListen", "erreur $error")
                if (!done) { done = true; cb.onNothing() }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (!done) { done = true; if (text.isBlank()) cb.onNothing() else cb.onFinal(text) }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(cb::onPartial)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
            // Laisse Rod finir sa phrase : 1,6 s de silence avant de couper
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L)
        }
        r.startListening(i)
    }

    fun cancel() {
        sr?.let { try { it.cancel(); it.destroy() } catch (_: Exception) {} }
        sr = null
    }
}
