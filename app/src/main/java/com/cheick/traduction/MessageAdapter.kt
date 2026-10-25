package com.cheick.traduction

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.cheick.traduction.databinding.ItemMessageBinding

data class Message(
    val fromMe: Boolean,
    val original: String,
    val translation: String,
    val source: Language,
    val target: Language,
)

class MessageAdapter(
    private val onClick: (Message) -> Unit,
) : RecyclerView.Adapter<MessageAdapter.Holder>() {

    private val items = mutableListOf<Message>()

    class Holder(val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root)

    fun add(message: Message) {
        items.add(message)
        notifyItemInserted(items.size - 1)
    }

    fun clear() {
        val count = items.size
        items.clear()
        notifyItemRangeRemoved(0, count)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val message = items[position]
        val b = holder.binding
        val context = b.root.context
        val accent = ContextCompat.getColor(context, if (message.fromMe) R.color.me_color else R.color.other_color)
        val background = ContextCompat.getColor(context, if (message.fromMe) R.color.bubble_me else R.color.bubble_other)

        val who = context.getString(if (message.fromMe) R.string.me else R.string.other)
        b.speaker.text = "$who · ${message.source.label} → ${message.target.label}"
        b.speaker.setTextColor(accent)
        b.translation.text = message.translation
        b.original.text = message.original
        b.bubble.background.mutate().setTint(background)
        b.bubble.layoutParams = (b.bubble.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = if (message.fromMe) Gravity.END else Gravity.START
        }
        b.bubble.setOnClickListener { onClick(message) }
    }
}
