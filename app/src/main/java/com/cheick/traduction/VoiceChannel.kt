package com.cheick.traduction

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/**
 * One text-to-speech engine kept ready for one language, so speaking never waits
 * for the engine to switch voices.
 */
class VoiceChannel(context: Context, private val onFinished: (String?) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var language: Language? = null
    private var appliedLanguage: Language? = null
    private var available = false

    var rate: Float = 1.2f
        set(value) {
            field = value
            if (ready) tts.setSpeechRate(value)
        }

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        main.post {
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts.setSpeechRate(rate)
                language?.let(::apply)
            }
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                main.post { onFinished(utteranceId) }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post { onFinished(utteranceId) }
            }
        })
    }

    val isSpeaking: Boolean get() = ready && tts.isSpeaking

    /** Prepares the voice for [lang] in advance. */
    fun prepare(lang: Language) {
        language = lang
        if (ready) apply(lang)
    }

    private fun apply(lang: Language) {
        if (appliedLanguage == lang) return
        val result = tts.setLanguage(lang.locale)
        available = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        appliedLanguage = lang
    }

    /** Returns false if the voice is not available, in which case [onFinished] is not called. */
    fun speak(text: String, lang: Language, utteranceId: String): Boolean {
        if (!ready) return false
        apply(lang)
        if (!available) return false
        return tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.SUCCESS
    }

    fun stop() {
        if (ready) tts.stop()
    }

    fun shutdown() {
        tts.shutdown()
    }
}
