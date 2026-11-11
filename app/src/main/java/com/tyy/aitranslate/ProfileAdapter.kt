package com.tyy.aitranslate

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class ProfileAdapter(
    private val onSelect: (ApiProfile) -> Unit,
    private val onMenu: (ApiProfile) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.ViewHolder>() {

    private var items: List<ApiProfile> = emptyList()
    private var activeId: Long = 0L

    fun submit(profiles: List<ApiProfile>, active: Long) {
        items = profiles
        activeId = active
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_profile, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val profile = items[position]
        val context = holder.itemView.context
        val isActive = profile.id == activeId

        holder.name.text = profile.name
        holder.model.text = profile.model
        holder.baseUrl.text = profile.baseUrl
        holder.badge.visibility = if (isActive) View.VISIBLE else View.GONE

        holder.card.strokeColor = ContextCompat.getColor(
            context,
            if (isActive) R.color.primary else R.color.card_stroke
        )
        holder.card.strokeWidth =
            ((if (isActive) 2 else 1) * context.resources.displayMetrics.density).toInt()

        holder.itemView.setOnClickListener { onSelect(profile) }
        holder.itemView.setOnLongClickListener {
            onMenu(profile)
            true
        }
        holder.more.setOnClickListener { onMenu(profile) }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.profile_card)
        val name: TextView = view.findViewById(R.id.profile_name)
        val model: TextView = view.findViewById(R.id.profile_model)
        val baseUrl: TextView = view.findViewById(R.id.profile_base_url)
        val badge: TextView = view.findViewById(R.id.profile_badge)
        val more: ImageButton = view.findViewById(R.id.btn_more)
    }
}
