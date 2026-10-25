package com.cheick.traduction

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.cheick.traduction.databinding.ActivityMainBinding
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

class MainActivity : AppCompatActivity() {

    private enum class Speaker { ME, OTHER }

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: MessageAdapter
    private val handler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var listeningFor: Speaker? = null
    private var pendingSpeaker: Speaker? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var utteranceCounter = 0

    private val translators = mutableMapOf<String, Translator>()

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private val myLanguage get() = Language.ALL[binding.myLanguage.selectedItemPosition]
    private val otherLanguage get() = Language.ALL[binding.otherLanguage.selectedItemPosition]

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val speaker = pendingSpeaker
        pendingSpeaker = null
        if (granted && speaker != null) startListening(speaker) else if (!granted) setStatus(getString(R.string.permission_needed))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = MessageAdapter { message -> speak(message.translation, message.target, null) }
        binding.messages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.messages.adapter = adapter

        setupLanguagePickers()
        setupTextToSpeech()

        binding.meButton.setOnClickListener { onMicPressed(Speaker.ME) }
        binding.otherButton.setOnClickListener { onMicPressed(Speaker.OTHER) }
        binding.clearButton.setOnClickListener { adapter.clear() }
        binding.swapButton.setOnClickListener {
            val my = binding.myLanguage.selectedItemPosition
            binding.myLanguage.setSelection(binding.otherLanguage.selectedItemPosition)
            binding.otherLanguage.setSelection(my)
        }
        binding.conversationMode.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (listeningFor == null && tts?.isSpeaking != true) startListening(Speaker.OTHER)
            } else {
                stopListening()
            }
        }
    }

    // region Languages & translation

    private fun setupLanguagePickers() {
        val labels = Language.ALL.map { it.label }
        listOf(binding.myLanguage, binding.otherLanguage).forEach { spinner ->
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        }
        binding.myLanguage.setSelection(prefs.getInt("my", indexOf("fr")).coerceIn(Language.ALL.indices))
        binding.otherLanguage.setSelection(prefs.getInt("other", indexOf("en")).coerceIn(Language.ALL.indices))

        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit()
                    .putInt("my", binding.myLanguage.selectedItemPosition)
                    .putInt("other", binding.otherLanguage.selectedItemPosition)
                    .apply()
                updateButtonLabels()
                prepareModels()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.myLanguage.onItemSelectedListener = listener
        binding.otherLanguage.onItemSelectedListener = listener
    }

    private fun indexOf(code: String) = Language.ALL.indexOfFirst { it.mlKitCode == code }.coerceAtLeast(0)

    private fun updateButtonLabels() {
        binding.meButton.text = "${getString(R.string.me)}\n${myLanguage.label}"
        binding.otherButton.text = "${getString(R.string.other)}\n${otherLanguage.label}"
    }

    private fun translator(source: Language, target: Language): Translator =
        translators.getOrPut("${source.mlKitCode}>${target.mlKitCode}") {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source.mlKitCode)
                    .setTargetLanguage(target.mlKitCode)
                    .build()
            ).also { lifecycle.addObserver(it) }
        }

    /** Downloads the offline translation models for both directions (only the first time). */
    private fun prepareModels() {
        val my = myLanguage
        val other = otherLanguage
        if (my == other) return
        setStatus(getString(R.string.downloading_models))
        Tasks.whenAll(
            translator(my, other).downloadModelIfNeeded(),
            translator(other, my).downloadModelIfNeeded(),
        ).addOnSuccessListener {
            if (listeningFor == null && my == myLanguage && other == otherLanguage) {
                setStatus(getString(R.string.models_ready))
            }
        }.addOnFailureListener {
            setStatus(getString(R.string.models_error))
        }
    }

    private fun translate(text: String, source: Language, target: Language): Task<String> {
        if (source == target) return Tasks.forResult(text)
        val translator = translator(source, target)
        return translator.downloadModelIfNeeded().onSuccessTask { translator.translate(text) }
    }

    // endregion

    // region Speech recognition

    private fun onMicPressed(speaker: Speaker) {
        if (listeningFor == speaker) {
            // Second tap: finish the sentence now.
            recognizer?.stopListening()
        } else {
            startListening(speaker)
        }
    }

    private fun startListening(speaker: Speaker) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingSpeaker = speaker
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus(getString(R.string.no_recognizer))
            return
        }
        handler.removeCallbacksAndMessages(null)
        tts?.stop()

        val sr = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(recognitionListener)
            recognizer = it
        }
        sr.cancel()

        val language = if (speaker == Speaker.ME) myLanguage else otherLanguage
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.speechTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.speechTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        listeningFor = speaker
        updateButtons()
        setStatus("🎤 ${speakerName(speaker)} (${language.label}) — ${getString(R.string.listening)}")
        sr.startListening(intent)
    }

    private fun stopListening() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        listeningFor = null
        updateButtons()
        setStatus(getString(R.string.tap_to_speak))
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) {
            val active = if (listeningFor == Speaker.ME) binding.meButton else binding.otherButton
            if (listeningFor != null) {
                val scale = 1f + (rmsdB.coerceIn(0f, 10f) / 100f)
                active.scaleX = scale
                active.scaleY = scale
            }
        }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) setStatus("🎤 $text")
        }

        override fun onResults(results: Bundle?) {
            val speaker = listeningFor ?: return
            listeningFor = null
            updateButtons()
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) {
                onNothingHeard(speaker)
            } else {
                handleSpeech(speaker, text)
            }
        }

        override fun onError(error: Int) {
            // ERROR_CLIENT is raised when a previous session is cancelled: ignore it.
            if (error == SpeechRecognizer.ERROR_CLIENT) return
            val speaker = listeningFor ?: return
            listeningFor = null
            updateButtons()
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onNothingHeard(speaker)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> handler.postDelayed({ startListening(speaker) }, 500)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> setStatus(getString(R.string.permission_needed))
                else -> setStatus("${getString(R.string.not_understood)} (code $error)")
            }
        }
    }

    private fun onNothingHeard(speaker: Speaker) {
        if (binding.conversationMode.isChecked && isInForeground()) {
            // Keep waiting for the same person in conversation mode.
            handler.postDelayed({ startListening(speaker) }, 250)
        } else {
            setStatus(getString(R.string.not_understood))
        }
    }

    private fun handleSpeech(speaker: Speaker, text: String) {
        val source = if (speaker == Speaker.ME) myLanguage else otherLanguage
        val target = if (speaker == Speaker.ME) otherLanguage else myLanguage
        setStatus("${getString(R.string.translating)}  « $text »")
        translate(text, source, target)
            .addOnSuccessListener { translated ->
                adapter.add(Message(speaker == Speaker.ME, text, translated, source, target))
                binding.messages.scrollToPosition(adapter.itemCount - 1)
                setStatus("🔊 $translated")
                speak(translated, target, speaker)
            }
            .addOnFailureListener {
                setStatus(getString(R.string.models_error))
            }
    }

    // endregion

    // region Text to speech

    private fun setupTextToSpeech() {
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }.apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = runOnUiThread { onUtteranceFinished(utteranceId) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = runOnUiThread { onUtteranceFinished(utteranceId) }
            })
        }
    }

    /**
     * Reads [text] aloud in [language]. When [after] is set, the next person is listened to
     * once the voice has finished (conversation mode).
     */
    private fun speak(text: String, language: Language, after: Speaker?) {
        val engine = tts
        if (engine == null || !ttsReady) {
            after?.let { onTurnFinished(it) }
            return
        }
        val result = engine.setLanguage(language.locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            setStatus(getString(R.string.tts_missing, language.label))
            after?.let { onTurnFinished(it) }
            return
        }
        val id = "${after?.name ?: "REPLAY"}#${utteranceCounter++}"
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun onUtteranceFinished(utteranceId: String?) {
        val name = utteranceId?.substringBefore('#') ?: return
        val speaker = Speaker.entries.firstOrNull { it.name == name } ?: return
        onTurnFinished(speaker)
    }

    private fun onTurnFinished(speaker: Speaker) {
        if (binding.conversationMode.isChecked && isInForeground() && listeningFor == null) {
            // Hand over to the other person.
            startListening(if (speaker == Speaker.ME) Speaker.OTHER else Speaker.ME)
        } else if (listeningFor == null) {
            setStatus(getString(R.string.tap_to_speak))
        }
    }

    // endregion

    private fun speakerName(speaker: Speaker) =
        getString(if (speaker == Speaker.ME) R.string.me else R.string.other)

    private fun isInForeground() = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    private fun updateButtons() {
        binding.meButton.alpha = if (listeningFor == Speaker.OTHER) 0.4f else 1f
        binding.otherButton.alpha = if (listeningFor == Speaker.ME) 0.4f else 1f
        if (listeningFor == null) {
            listOf(binding.meButton, binding.otherButton).forEach { it.scaleX = 1f; it.scaleY = 1f }
        }
    }

    private fun setStatus(text: String) {
        binding.status.text = text
    }

    override fun onStop() {
        super.onStop()
        stopListening()
        tts?.stop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
