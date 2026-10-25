package com.cheick.traduction

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What the app learns from your discussions:
 * - corrections: when you fix a translation, the same sentence is translated your way next time;
 * - vocabulary: names and words you want translated (or kept) a specific way.
 */
class LearningMemory(context: Context) {

    data class Correction(val source: String, val target: String, val original: String, val translation: String)

    /** A word or name and how it is said in two languages. */
    data class Term(val langA: String, val termA: String, val langB: String, val termB: String)

    private val file = File(context.filesDir, "memory.json")
    private val correctionsByKey = linkedMapOf<String, Correction>()
    private val termList = mutableListOf<Term>()

    val corrections: List<Correction> get() = correctionsByKey.values.toList()
    val terms: List<Term> get() = termList

    init {
        load()
    }

    fun correctionFor(text: String, source: String, target: String): String? =
        correctionsByKey[key(source, target, text)]?.translation

    fun saveCorrection(original: String, translation: String, source: String, target: String) {
        correctionsByKey[key(source, target, original)] = Correction(source, target, original, translation)
        persist()
    }

    fun remove(correction: Correction) {
        correctionsByKey.remove(key(correction.source, correction.target, correction.original))
        persist()
    }

    fun addTerm(term: Term) {
        termList.removeAll { it.langA == term.langA && it.termA.equals(term.termA, ignoreCase = true) && it.langB == term.langB }
        termList.add(term)
        persist()
    }

    fun remove(term: Term) {
        termList.remove(term)
        persist()
    }

    /** Vocabulary for the direction [source] → [target], as (word in source, wanted word in target). */
    fun termsFor(source: String, target: String): List<Pair<String, String>> = termList.mapNotNull {
        when {
            it.langA == source && it.langB == target -> it.termA to it.termB
            it.langB == source && it.langA == target -> it.termB to it.termA
            else -> null
        }
    }

    /** Words the speech recognizer should expect when someone speaks [language]. */
    fun hintsFor(language: String): ArrayList<String> {
        val hints = ArrayList<String>()
        termList.forEach { term ->
            if (term.langA == language) hints.add(term.termA)
            if (term.langB == language) hints.add(term.termB)
        }
        return hints
    }

    private fun key(source: String, target: String, text: String) = "$source>$target|${normalize(text)}"

    private fun load() {
        if (!file.exists()) return
        try {
            val json = JSONObject(file.readText())
            json.optJSONArray("corrections")?.let { array ->
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    val c = Correction(o.getString("source"), o.getString("target"), o.getString("original"), o.getString("translation"))
                    correctionsByKey[key(c.source, c.target, c.original)] = c
                }
            }
            json.optJSONArray("terms")?.let { array ->
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    termList.add(Term(o.getString("langA"), o.getString("termA"), o.getString("langB"), o.getString("termB")))
                }
            }
        } catch (e: Exception) {
            // A damaged memory file must never prevent the app from starting.
        }
    }

    private fun persist() {
        val json = JSONObject()
            .put("corrections", JSONArray().apply {
                correctionsByKey.values.forEach {
                    put(JSONObject().put("source", it.source).put("target", it.target).put("original", it.original).put("translation", it.translation))
                }
            })
            .put("terms", JSONArray().apply {
                termList.forEach {
                    put(JSONObject().put("langA", it.langA).put("termA", it.termA).put("langB", it.langB).put("termB", it.termB))
                }
            })
        val tmp = File(file.parentFile, "memory.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file)
    }

    companion object {
        private val PUNCTUATION = Regex("[\\p{P}\\p{S}]")
        private val SPACES = Regex("\\s+")

        fun normalize(text: String) = text.lowercase().replace(PUNCTUATION, " ").replace(SPACES, " ").trim()

        fun containsWord(text: String, word: String) =
            Regex("(^|[^\\p{L}\\p{N}])${Regex.escape(word)}($|[^\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(text)

        /** Puts [wanted] in [translation] where the translator wrote [machine] (or left [source] untranslated). */
        fun applyTerm(translation: String, source: String, machine: String?, wanted: String): String {
            for (candidate in listOfNotNull(machine?.trim()?.trimEnd('.', '!', '?'), source)) {
                if (candidate.isBlank()) continue
                val pattern = Regex("(?<![\\p{L}\\p{N}])${Regex.escape(candidate)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
                if (pattern.containsMatchIn(translation)) {
                    return pattern.replace(translation, Regex.escapeReplacement(wanted))
                }
            }
            return translation
        }
    }
}
