package com.cheick.traduction

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice

/**
 * One text-to-speech engine kept ready for one language, so speaking never waits
 * for the engine to switch voices.
 *
 * [chosenVoice] returns the name of the voice the user picked for a language (null = engine default).
 */
class VoiceChannel(
    context: Context,
    private val chosenVoice: (Language) -> String?,
    private val onFinished: (String?) -> Unit,
) {

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

    /** Below 1 the voice is deeper, above 1 it is higher. */
    var pitch: Float = 1f
        set(value) {
            field = value
            if (ready) tts.setPitch(value)
        }

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        main.post {
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts.setSpeechRate(rate)
                tts.setPitch(pitch)
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

    /** Re-reads the chosen voice next time something is spoken. */
    fun invalidate() {
        appliedLanguage = null
    }

    private fun apply(lang: Language) {
        if (appliedLanguage == lang) return
        available = selectVoice(tts, lang, chosenVoice(lang))
        appliedLanguage = lang
    }

    /** Returns false if the voice is not available, in which case [onFinished] is not called. */
    fun speak(text: String, lang: Language, utteranceId: String): Boolean {
        if (!ready) return false
        apply(lang)
        if (!available) return false
        return tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.SUCCESS
    }

    /** Installed voices for [lang]: local ones and the right country first. */
    fun voicesFor(lang: Language): List<Voice> {
        if (!ready) return emptyList()
        return installedVoices(tts, lang).sortedWith(
            compareBy<Voice>({ it.isNetworkConnectionRequired }, { it.locale.country != lang.locale.country }, { it.name })
        )
    }

    /** Lets the user hear [voice] (or the default voice when null) without saving it. */
    fun preview(lang: Language, voice: Voice?) {
        if (!ready) return
        tts.stop()
        if (voice != null) tts.voice = voice else tts.setLanguage(lang.locale)
        appliedLanguage = null
        tts.speak(lang.sample, TextToSpeech.QUEUE_FLUSH, null, "PREVIEW#0")
    }

    fun stop() {
        if (ready) tts.stop()
    }

    fun shutdown() {
        tts.shutdown()
    }

    companion object {
        /** Sets the language on [tts], then the user's voice if it is installed. Returns false if the language has no voice. */
        fun selectVoice(tts: TextToSpeech, lang: Language, voiceName: String?): Boolean {
            val result = tts.setLanguage(lang.locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) return false
            if (voiceName != null) {
                installedVoices(tts, lang).firstOrNull { it.name == voiceName }?.let { tts.voice = it }
            }
            return true
        }

        fun installedVoices(tts: TextToSpeech, lang: Language): List<Voice> {
            val voices = runCatching { tts.voices }.getOrNull().orEmpty()
            val wanted = lang.locale.language
            return voices.filter { voice ->
                val code = voice.locale.language
                val sameLanguage = code == wanted || (wanted == "zh" && code in setOf("cmn", "yue"))
                val installed = voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
                sameLanguage && installed
            }
        }
    }
}
