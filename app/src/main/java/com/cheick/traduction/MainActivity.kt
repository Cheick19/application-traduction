package com.cheick.traduction

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.cheick.traduction.databinding.ActivityMainBinding
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.File
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    private enum class Speaker { ME, OTHER }

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: MessageAdapter
    private val handler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var listeningFor: Speaker? = null
    private var pendingSpeaker: Speaker? = null
    /** Languages whose offline recognition failed: they go back to the online service. */
    private val noOfflineRecognition = mutableSetOf<String>()

    // Live translation of what is being said, so the result is ready the moment the person stops.
    private var partialResult: Pair<String, String>? = null
    private var partialInFlight = false
    private var queuedPartial: String? = null

    /** One voice per language, kept warm: what I hear (my language) and what they hear. */
    private lateinit var myVoice: VoiceChannel
    private lateinit var otherVoice: VoiceChannel
    private var utteranceCounter = 0

    private val translators = mutableMapOf<String, Translator>()
    private val readyPairs = mutableSetOf<String>()
    private val termTranslations = mutableMapOf<String, String>()

    private lateinit var voiceExporter: VoiceExporter
    private lateinit var store: ConversationStore
    private lateinit var memory: LearningMemory
    private lateinit var conversation: Conversation

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

        store = ConversationStore(this)
        memory = LearningMemory(this)
        voiceExporter = VoiceExporter(this)
        val rate = prefs.getFloat("rate", 1.2f)
        myVoice = VoiceChannel(this, ::onUtteranceFinished).also { it.rate = rate }
        otherVoice = VoiceChannel(this, ::onUtteranceFinished).also { it.rate = rate }

        adapter = MessageAdapter(
            onReplay = { message -> speak(message.translation, message.target, null) },
            onShare = { message -> shareMessage(message) },
            onEdit = { index, message -> editTranslation(index, message) },
        )
        binding.messages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.messages.adapter = adapter

        setupToolbar()
        setupLanguagePickers()
        openConversation(store.load(prefs.getLong("current", 0L)) ?: Conversation())

        binding.meButton.setOnClickListener { onMicPressed(Speaker.ME) }
        binding.otherButton.setOnClickListener { onMicPressed(Speaker.OTHER) }
        binding.swapButton.setOnClickListener {
            val my = binding.myLanguage.selectedItemPosition
            binding.myLanguage.setSelection(binding.otherLanguage.selectedItemPosition)
            binding.otherLanguage.setSelection(my)
        }
        binding.conversationMode.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (listeningFor == null && !myVoice.isSpeaking && !otherVoice.isSpeaking) startListening(Speaker.OTHER)
            } else {
                stopListening()
            }
        }
    }

    private fun setupToolbar() {
        binding.toolbar.inflateMenu(R.menu.main)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_new -> startNewConversation()
                R.id.action_history -> showHistory()
                R.id.action_share -> shareConversation(conversation)
                R.id.action_memory -> showMemory()
                R.id.action_add_term -> addTerm()
                R.id.action_speed -> chooseVoiceSpeed()
            }
            true
        }
    }

    // region Discussions

    private fun openConversation(target: Conversation) {
        stopListening()
        conversation = target
        prefs.edit().putLong("current", target.id).apply()
        adapter.setAll(target.messages)
        if (target.messages.isNotEmpty()) binding.messages.scrollToPosition(target.messages.size - 1)
        binding.toolbar.subtitle = getString(R.string.conversation_title, formatDate(target.id))
    }

    private fun startNewConversation() {
        store.save(conversation)
        openConversation(Conversation())
        setStatus(getString(R.string.new_conversation_started))
    }

    private fun addMessage(message: Message) {
        conversation.messages.add(message)
        adapter.add(message)
        binding.messages.scrollToPosition(adapter.itemCount - 1)
        store.save(conversation)
    }

    private fun showHistory() {
        val conversations = store.all()
        if (conversations.isEmpty()) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.history).setMessage(R.string.no_history)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val labels = conversations.map { c ->
            val first = c.messages.firstOrNull()
            val languages = first?.let { "${it.source.label} ↔ ${it.target.label}" }.orEmpty()
            val current = if (c.id == conversation.id) "● " else ""
            "$current${formatDate(c.lastActivity)} · $languages · ${c.messages.size} msg\n« ${first?.original.orEmpty().take(60)} »"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history)
            .setItems(labels) { _, which -> conversationOptions(conversations[which]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun conversationOptions(target: Conversation) {
        val options = arrayOf(getString(R.string.open), getString(R.string.share), getString(R.string.delete))
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.conversation_title, formatDate(target.id)))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openConversation(target)
                    1 -> shareConversation(target)
                    2 -> {
                        store.delete(target.id)
                        if (target.id == conversation.id) openConversation(Conversation())
                        toast(getString(R.string.deleted))
                    }
                }
            }
            .show()
    }

    private fun formatDate(time: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))

    // endregion

    // region Learning: corrections & vocabulary

    private fun editTranslation(index: Int, message: Message) {
        val input = TextInputEditText(this).apply {
            setText(message.translation)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSelection(text?.length ?: 0)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.correct_title)
            .setMessage("« ${message.original} »")
            .setView(padded(input))
            .setPositiveButton(R.string.save) { _, _ ->
                val fixed = input.text?.toString()?.trim().orEmpty()
                if (fixed.isEmpty() || fixed == message.translation) return@setPositiveButton
                memory.saveCorrection(message.original, fixed, message.source.mlKitCode, message.target.mlKitCode)
                val updated = message.copy(translation = fixed)
                if (index in conversation.messages.indices) conversation.messages[index] = updated
                adapter.update(index, updated)
                store.save(conversation)
                toast(getString(R.string.correction_saved))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun addTerm() {
        val my = myLanguage
        val other = otherLanguage
        val mine = TextInputEditText(this)
        val theirs = TextInputEditText(this)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextInputLayout(this@MainActivity).apply { hint = getString(R.string.term_in, my.label); addView(mine) })
            addView(TextInputLayout(this@MainActivity).apply { hint = getString(R.string.term_in, other.label); addView(theirs) })
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_term)
            .setView(padded(form))
            .setPositiveButton(R.string.save) { _, _ ->
                val a = mine.text?.toString()?.trim().orEmpty()
                val b = theirs.text?.toString()?.trim().orEmpty()
                if (a.isEmpty() || b.isEmpty()) return@setPositiveButton
                memory.addTerm(LearningMemory.Term(my.mlKitCode, a, other.mlKitCode, b))
                toast(getString(R.string.term_saved))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showMemory() {
        val corrections = memory.corrections
        val terms = memory.terms
        if (corrections.isEmpty() && terms.isEmpty()) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.memory).setMessage(R.string.memory_empty)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val labels = terms.map { "📖 ${it.termA}  ↔  ${it.termB}" } +
            corrections.map { "✏️ « ${it.original} »\n→ ${it.translation}" }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.memory)
            .setItems(labels.toTypedArray()) { _, which ->
                MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.memory_delete)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        if (which < terms.size) memory.remove(terms[which]) else memory.remove(corrections[which - terms.size])
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseVoiceSpeed() {
        val rates = floatArrayOf(1.0f, 1.2f, 1.5f)
        val labels = arrayOf(getString(R.string.speed_normal), getString(R.string.speed_fast), getString(R.string.speed_very_fast))
        val current = rates.indexOfFirst { it == myVoice.rate }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.voice_speed)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                myVoice.rate = rates[which]
                otherVoice.rate = rates[which]
                prefs.edit().putFloat("rate", rates[which]).apply()
                dialog.dismiss()
            }
            .show()
    }

    private fun padded(view: View): View {
        val pad = (20 * resources.displayMetrics.density).toInt()
        return LinearLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    // endregion

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
                myVoice.prepare(myLanguage)
                otherVoice.prepare(otherLanguage)
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

    private fun pairKey(source: Language, target: Language) = "${source.mlKitCode}>${target.mlKitCode}"

    private fun translator(source: Language, target: Language): Translator =
        translators.getOrPut(pairKey(source, target)) {
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
            readyPairs += pairKey(my, other)
            readyPairs += pairKey(other, my)
            // Warm the engines up so the very first sentence is fast too.
            translator(my, other).translate("ok")
            translator(other, my).translate("ok")
            if (listeningFor == null && my == myLanguage && other == otherLanguage) {
                setStatus(getString(R.string.models_ready))
            }
        }.addOnFailureListener {
            setStatus(getString(R.string.models_error))
        }
    }

    /** Translates with the learned corrections and vocabulary applied. */
    private fun translate(text: String, source: Language, target: Language): Task<String> {
        if (source == target) return Tasks.forResult(text)
        memory.correctionFor(text, source.mlKitCode, target.mlKitCode)?.let { return Tasks.forResult(it) }

        val key = pairKey(source, target)
        val translator = translator(source, target)
        val machine = if (key in readyPairs) {
            translator.translate(text)
        } else {
            translator.downloadModelIfNeeded().onSuccessTask {
                readyPairs += key
                translator.translate(text)
            }
        }

        val terms = memory.termsFor(source.mlKitCode, target.mlKitCode)
            .filter { (word, _) -> LearningMemory.containsWord(text, word) }
        if (terms.isEmpty()) return machine

        return machine.onSuccessTask { raw ->
            val defaults = terms.map { (word, _) -> termTranslation(translator, key, word) }
            Tasks.whenAllComplete(defaults).continueWith {
                var result = raw
                terms.forEachIndexed { i, (word, wanted) ->
                    val default = defaults[i].takeIf { t -> t.isSuccessful }?.result
                    result = LearningMemory.applyTerm(result, word, default, wanted)
                }
                result
            }
        }
    }

    /** How the translator says a vocabulary word on its own (cached). */
    private fun termTranslation(translator: Translator, pair: String, word: String): Task<String> {
        val cacheKey = "$pair|${word.lowercase()}"
        termTranslations[cacheKey]?.let { return Tasks.forResult(it) }
        return translator.translate(word).addOnSuccessListener { termTranslations[cacheKey] = it }
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
        myVoice.stop()
        otherVoice.stop()

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
            // Stop listening quickly once the person has finished their sentence.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
            // On-device recognition answers faster than the network when the language pack is installed.
            if (language.speechTag !in noOfflineRecognition) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            val hints = memory.hintsFor(language.mlKitCode)
            if (hints.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, hints)
            }
        }
        listeningFor = speaker
        partialResult = null
        queuedPartial = null
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
            val speaker = listeningFor ?: return
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) return
            val ready = partialResult?.takeIf { it.first == text }?.second
            setStatus(if (ready != null) "🎤 $text\n➜ $ready" else "🎤 $text")
            translatePartial(speaker, text)
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
            val language = if (speaker == Speaker.ME) myLanguage else otherLanguage
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onNothingHeard(speaker)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> handler.postDelayed({ startListening(speaker) }, 300)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> setStatus(getString(R.string.permission_needed))
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_SERVER ->
                    if (language.speechTag !in noOfflineRecognition) {
                        // The offline pack is missing: retry right away with the online service.
                        noOfflineRecognition += language.speechTag
                        startListening(speaker)
                    } else {
                        setStatus("${getString(R.string.not_understood)} (code $error)")
                    }
                else -> setStatus("${getString(R.string.not_understood)} (code $error)")
            }
        }
    }

    /** Translates the sentence while it is being spoken; only one request at a time, latest text wins. */
    private fun translatePartial(speaker: Speaker, text: String) {
        if (partialInFlight) {
            queuedPartial = text
            return
        }
        partialInFlight = true
        val source = if (speaker == Speaker.ME) myLanguage else otherLanguage
        val target = if (speaker == Speaker.ME) otherLanguage else myLanguage
        translate(text, source, target).addOnCompleteListener { task ->
            partialInFlight = false
            if (task.isSuccessful && listeningFor == speaker) {
                partialResult = text to task.result
                setStatus("🎤 $text\n➜ ${task.result}")
            }
            val next = queuedPartial
            queuedPartial = null
            if (next != null && listeningFor == speaker) translatePartial(speaker, next)
        }
    }

    private fun onNothingHeard(speaker: Speaker) {
        if (binding.conversationMode.isChecked && isInForeground()) {
            // Keep waiting for the same person in conversation mode.
            handler.postDelayed({ startListening(speaker) }, 100)
        } else {
            setStatus(getString(R.string.not_understood))
        }
    }

    private fun handleSpeech(speaker: Speaker, text: String) {
        val source = if (speaker == Speaker.ME) myLanguage else otherLanguage
        val target = if (speaker == Speaker.ME) otherLanguage else myLanguage
        val onTranslated = { translated: String ->
            addMessage(Message(speaker == Speaker.ME, text, translated, source, target))
            setStatus("🔊 $translated")
            speak(translated, target, speaker)
        }
        // Already translated while the person was speaking: answer instantly.
        val ready = partialResult?.takeIf { it.first == text }?.second
        partialResult = null
        if (ready != null) {
            onTranslated(ready)
            return
        }
        setStatus("${getString(R.string.translating)}  « $text »")
        translate(text, source, target)
            .addOnSuccessListener { onTranslated(it) }
            .addOnFailureListener { setStatus(getString(R.string.models_error)) }
    }

    // endregion

    // region Text to speech

    /**
     * Reads [text] aloud in [language]. When [after] is set, the next person is listened to
     * once the voice has finished (conversation mode).
     */
    private fun speak(text: String, language: Language, after: Speaker?) {
        val channel = when (language) {
            myLanguage -> myVoice
            otherLanguage -> otherVoice
            else -> otherVoice // Replaying an old discussion in another language.
        }
        val id = "${after?.name ?: "REPLAY"}#${utteranceCounter++}"
        if (!channel.speak(text, language, id)) {
            setStatus(getString(R.string.tts_missing, language.label))
            after?.let { onTurnFinished(it) }
        }
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

    // region Sharing (WhatsApp, SMS, e-mail…)

    private fun shareMessage(message: Message) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.share_title)
            .setItems(arrayOf(getString(R.string.share_voice), getString(R.string.share_text))) { _, which ->
                if (which == 0) shareVoice(message) else shareText(message.translation)
            }
            .show()
    }

    private fun shareVoice(message: Message) {
        setStatus(getString(R.string.preparing_voice))
        voiceExporter.export(message.translation, message.target) { file ->
            if (file == null) {
                setStatus(getString(R.string.voice_error))
            } else {
                setStatus(getString(R.string.tap_to_speak))
                shareAudioFile(file)
            }
        }
    }

    private fun shareAudioFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (file.extension == "m4a") "audio/mp4" else "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_title)))
    }

    private fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_title)))
    }

    private fun shareConversation(target: Conversation) {
        if (target.messages.isEmpty()) {
            setStatus(getString(R.string.nothing_to_share))
            return
        }
        val transcript = target.messages.joinToString("\n\n") { m ->
            val who = getString(if (m.fromMe) R.string.me else R.string.other)
            "$who (${m.source.label}) : ${m.original}\n→ (${m.target.label}) : ${m.translation}"
        }
        shareText(transcript)
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

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    override fun onStop() {
        super.onStop()
        stopListening()
        myVoice.stop()
        otherVoice.stop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        myVoice.shutdown()
        otherVoice.shutdown()
        voiceExporter.shutdown()
        super.onDestroy()
    }
}
