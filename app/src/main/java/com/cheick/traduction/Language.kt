package com.cheick.traduction

import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

/**
 * A language supported by the app.
 * [mlKitCode] is used for offline translation, [speechTag] for speech recognition and voice.
 */
data class Language(val label: String, val mlKitCode: String, val speechTag: String) {
    val locale: Locale get() = Locale.forLanguageTag(speechTag)

    companion object {
        fun byCode(code: String) = ALL.firstOrNull { it.mlKitCode == code }

        val ALL = listOf(
            Language("Français", TranslateLanguage.FRENCH, "fr-FR"),
            Language("Anglais", TranslateLanguage.ENGLISH, "en-US"),
            Language("Espagnol", TranslateLanguage.SPANISH, "es-ES"),
            Language("Allemand", TranslateLanguage.GERMAN, "de-DE"),
            Language("Italien", TranslateLanguage.ITALIAN, "it-IT"),
            Language("Portugais", TranslateLanguage.PORTUGUESE, "pt-BR"),
            Language("Arabe", TranslateLanguage.ARABIC, "ar-SA"),
            Language("Chinois", TranslateLanguage.CHINESE, "zh-CN"),
            Language("Japonais", TranslateLanguage.JAPANESE, "ja-JP"),
            Language("Coréen", TranslateLanguage.KOREAN, "ko-KR"),
            Language("Russe", TranslateLanguage.RUSSIAN, "ru-RU"),
            Language("Turc", TranslateLanguage.TURKISH, "tr-TR"),
            Language("Néerlandais", TranslateLanguage.DUTCH, "nl-NL"),
            Language("Hindi", TranslateLanguage.HINDI, "hi-IN"),
        )
    }
}
