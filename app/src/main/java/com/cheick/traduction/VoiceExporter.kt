package com.cheick.traduction

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Turns a translation into an audio file (voice message) that can be shared to WhatsApp & co.
 * Uses its own TextToSpeech instance so it never interferes with the live conversation.
 */
class VoiceExporter(
    context: Context,
    private val chosenVoice: (Language) -> String?,
    private val pitch: () -> Float,
) {

    private class Pending(val wav: File, val callback: (File?) -> Unit)

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val encoder = Executors.newSingleThreadExecutor()
    private val pending = ConcurrentHashMap<String, Pending>()

    /** null while the engine is starting. */
    private var ready: Boolean? = null
    private val waiting = mutableListOf<() -> Unit>()

    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        main.post {
            ready = status == TextToSpeech.SUCCESS
            waiting.forEach { it() }
            waiting.clear()
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finish(utteranceId, success = true)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId, success = false)
        })
    }

    /** Creates the voice message; [callback] runs on the main thread with the file, or null on failure. */
    fun export(text: String, language: Language, callback: (File?) -> Unit) {
        val start = { synthesize(text, language, callback) }
        if (ready == null) waiting += start else start()
    }

    private fun synthesize(text: String, language: Language, callback: (File?) -> Unit) {
        if (ready != true) return callback(null)
        if (!VoiceChannel.selectVoice(tts, language, chosenVoice(language))) return callback(null)
        tts.setPitch(pitch())
        val dir = File(appContext.cacheDir, "shared").apply { mkdirs() }
        // Remove voice messages from previous sessions.
        val oneHourAgo = System.currentTimeMillis() - 3_600_000
        dir.listFiles()?.filter { it.lastModified() < oneHourAgo }?.forEach { it.delete() }

        val stamp = System.currentTimeMillis()
        val wav = File(dir, "traduction_$stamp.wav")
        val id = "export#$stamp"
        pending[id] = Pending(wav, callback)
        if (tts.synthesizeToFile(text, null, wav, id) != TextToSpeech.SUCCESS) {
            pending.remove(id)
            callback(null)
        }
    }

    private fun finish(utteranceId: String?, success: Boolean) {
        val job = pending.remove(utteranceId ?: return) ?: return
        if (!success) {
            main.post { job.callback(null) }
            return
        }
        encoder.execute {
            // WhatsApp plays .m4a (AAC) as a voice/audio message; keep the WAV if encoding fails.
            val m4a = File(job.wav.parentFile, job.wav.nameWithoutExtension + ".m4a")
            val file = try {
                AacEncoder.wavToM4a(job.wav, m4a)
                job.wav.delete()
                m4a
            } catch (e: Exception) {
                m4a.delete()
                job.wav.takeIf { it.length() > 44 }
            }
            main.post { job.callback(file) }
        }
    }

    fun shutdown() {
        tts.shutdown()
        encoder.shutdown()
    }
}
