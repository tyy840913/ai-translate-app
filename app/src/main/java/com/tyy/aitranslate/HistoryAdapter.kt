package com.tyy.aitranslate

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private val onOpen: (HistoryEntry) -> Unit,
    private val onLongPress: (HistoryEntry) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private var items: List<HistoryEntry> = emptyList()
    private val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    fun submit(history: List<HistoryEntry>) {
        items = history
        notifyDataSetChanged()
    }

    /** 当前显示列表（可能是搜索过滤后的），供滑动删除按 position 取条目 */
    fun itemAt(position: Int): HistoryEntry? = items.getOrNull(position)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = items[position]
        holder.languages.text = "${entry.source} → ${entry.target}"
        holder.time.text = formatter.format(Date(entry.time))
        holder.input.text = entry.input
        holder.output.text = entry.output

        holder.itemView.setOnClickListener { onOpen(entry) }
        holder.itemView.setOnLongClickListener {
            onLongPress(entry)
            true
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val languages: TextView = view.findViewById(R.id.history_languages)
        val time: TextView = view.findViewById(R.id.history_time)
        val input: TextView = view.findViewById(R.id.history_input)
        val output: TextView = view.findViewById(R.id.history_output)
    }
}
