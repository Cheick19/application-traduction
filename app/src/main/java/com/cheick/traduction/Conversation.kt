package com.cheick.traduction

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Message(
    val fromMe: Boolean,
    val original: String,
    val translation: String,
    val source: Language,
    val target: Language,
    val time: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("me", fromMe)
        .put("original", original)
        .put("translation", translation)
        .put("source", source.mlKitCode)
        .put("target", target.mlKitCode)
        .put("time", time)

    companion object {
        fun fromJson(json: JSONObject): Message? {
            val source = Language.byCode(json.optString("source")) ?: return null
            val target = Language.byCode(json.optString("target")) ?: return null
            return Message(
                fromMe = json.optBoolean("me"),
                original = json.optString("original"),
                translation = json.optString("translation"),
                source = source,
                target = target,
                time = json.optLong("time"),
            )
        }
    }
}

class Conversation(
    val id: Long = System.currentTimeMillis(),
    val messages: MutableList<Message> = mutableListOf(),
) {
    val lastActivity: Long get() = messages.lastOrNull()?.time ?: id
}

/** Saves every discussion as a small JSON file in the app's private storage. */
class ConversationStore(context: Context) {

    private val dir = File(context.filesDir, "conversations").apply { mkdirs() }

    private fun file(id: Long) = File(dir, "$id.json")

    fun save(conversation: Conversation) {
        val target = file(conversation.id)
        if (conversation.messages.isEmpty()) {
            target.delete()
            return
        }
        val json = JSONObject()
            .put("id", conversation.id)
            .put("messages", JSONArray().apply { conversation.messages.forEach { put(it.toJson()) } })
        val tmp = File(dir, "${conversation.id}.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(target)
    }

    fun load(id: Long): Conversation? {
        val f = file(id)
        if (!f.exists()) return null
        return try {
            val json = JSONObject(f.readText())
            val array = json.getJSONArray("messages")
            val messages = (0 until array.length()).mapNotNullTo(mutableListOf()) {
                Message.fromJson(array.getJSONObject(it))
            }
            Conversation(json.getLong("id"), messages)
        } catch (e: Exception) {
            null
        }
    }

    fun all(): List<Conversation> =
        dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { it.nameWithoutExtension.toLongOrNull()?.let(::load) }
            .sortedByDescending { it.lastActivity }

    fun delete(id: Long) {
        file(id).delete()
    }
}
