package com.cheick.traduction

import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

/**
 * A language supported by the app.
 * [mlKitCode] is used for offline translation, [speechTag] for speech recognition and voice,
 * [sample] to let the user try a voice.
 */
data class Language(val label: String, val mlKitCode: String, val speechTag: String, val sample: String) {
    val locale: Locale get() = Locale.forLanguageTag(speechTag)

    companion object {
        fun byCode(code: String) = ALL.firstOrNull { it.mlKitCode == code }

        val ALL = listOf(
            Language("Français", TranslateLanguage.FRENCH, "fr-FR", "Bonjour, voici ma voix."),
            Language("Anglais", TranslateLanguage.ENGLISH, "en-US", "Hello, this is my voice."),
            Language("Espagnol", TranslateLanguage.SPANISH, "es-ES", "Hola, esta es mi voz."),
            Language("Allemand", TranslateLanguage.GERMAN, "de-DE", "Hallo, das ist meine Stimme."),
            Language("Italien", TranslateLanguage.ITALIAN, "it-IT", "Ciao, questa è la mia voce."),
            Language("Portugais", TranslateLanguage.PORTUGUESE, "pt-BR", "Olá, esta é a minha voz."),
            Language("Arabe", TranslateLanguage.ARABIC, "ar-SA", "مرحبا، هذا صوتي."),
            Language("Chinois", TranslateLanguage.CHINESE, "zh-CN", "你好，这是我的声音。"),
            Language("Japonais", TranslateLanguage.JAPANESE, "ja-JP", "こんにちは、これが私の声です。"),
            Language("Coréen", TranslateLanguage.KOREAN, "ko-KR", "안녕하세요, 제 목소리입니다."),
            Language("Russe", TranslateLanguage.RUSSIAN, "ru-RU", "Привет, это мой голос."),
            Language("Turc", TranslateLanguage.TURKISH, "tr-TR", "Merhaba, bu benim sesim."),
            Language("Néerlandais", TranslateLanguage.DUTCH, "nl-NL", "Hallo, dit is mijn stem."),
            Language("Hindi", TranslateLanguage.HINDI, "hi-IN", "नमस्ते, यह मेरी आवाज़ है।"),
        )
    }
}
