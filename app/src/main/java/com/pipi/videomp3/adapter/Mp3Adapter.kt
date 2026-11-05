package com.pipi.videomp3.adapter

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.pipi.videomp3.R
import com.pipi.videomp3.model.Mp3Item
import java.util.Locale

class Mp3Adapter(
    private val context: Context,
    private val onItemClicked: (item: Mp3Item) -> Unit,
    private val onSelectionChanged: (selectedCount: Int, totalCount: Int) -> Unit,
    private val onStartSelectionMode: (initialItem: Mp3Item) -> Unit
) : RecyclerView.Adapter<Mp3Adapter.Mp3ViewHolder>() {

    private val items = mutableListOf<Mp3Item>()
    private val selectedIds = mutableSetOf<Long>()
    private val playlistIds = mutableSetOf<Long>()
    private var playingItemId: Long? = null

    var isSelectionMode = false
        private set

    fun submitList(newItems: List<Mp3Item>) {
        items.clear()
        items.addAll(newItems)
        val currentIds = items.map { it.id }.toSet()
        selectedIds.retainAll(currentIds)
        playlistIds.retainAll(currentIds)
        notifyDataSetChanged()
        if (isSelectionMode) {
            onSelectionChanged(selectedIds.size, items.size)
        }
    }

    fun setPlayingItemId(id: Long?) {
        playingItemId = id
        notifyDataSetChanged()
    }

    fun setPlaylistIds(ids: Set<Long>) {
        playlistIds.clear()
        playlistIds.addAll(ids)
        notifyDataSetChanged()
    }

    fun getPlaylistIds(): Set<Long> = playlistIds.toSet()

    fun enterSelectionMode(initialItem: Mp3Item? = null) {
        isSelectionMode = true
        selectedIds.clear()
        initialItem?.let { selectedIds.add(it.id) }
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size, items.size)
    }

    fun exitSelectionMode() {
        isSelectionMode = false
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(0, items.size)
    }

    fun selectAll(select: Boolean) {
        if (!isSelectionMode) return
        selectedIds.clear()
        if (select) {
            selectedIds.addAll(items.map { it.id })
        }
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size, items.size)
    }

    fun getSelectedItems(): List<Mp3Item> {
        return items.filter { selectedIds.contains(it.id) }
    }

    fun getAllItems(): List<Mp3Item> = items.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Mp3ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_mp3_row, parent, false)
        return Mp3ViewHolder(view)
    }

    override fun onBindViewHolder(holder: Mp3ViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    inner class Mp3ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cbSelect: CheckBox = itemView.findViewById(R.id.cbRowSelect)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvMp3Title)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvDuration)
        private val btnShare: ImageButton = itemView.findViewById(R.id.btnShare)

        fun bind(item: Mp3Item, position: Int) {
            tvTitle.text = item.title
            tvTitle.isSelected = true
            tvDuration.text = formatTime(item.durationMs)

            if (isSelectionMode) {
                cbSelect.visibility = View.VISIBLE
                val isSelected = selectedIds.contains(item.id)
                cbSelect.isChecked = isSelected

                btnShare.visibility = View.GONE

                if (isSelected) {
                    itemView.setBackgroundColor(Color.parseColor("#263238"))
                } else {
                    itemView.setBackgroundColor(ContextCompat.getColor(context, R.color.surface))
                }

                tvTitle.setTextColor(ContextCompat.getColor(context, R.color.on_surface))
                tvTitle.setTypeface(null, Typeface.NORMAL)

                val toggleListener = View.OnClickListener {
                    toggle(item)
                }
                itemView.setOnClickListener(toggleListener)
                tvTitle.setOnClickListener(toggleListener)
                itemView.setOnLongClickListener(null)
                tvTitle.setOnLongClickListener(null)

            } else {
                cbSelect.visibility = View.GONE
                btnShare.visibility = View.VISIBLE
                itemView.setBackgroundColor(ContextCompat.getColor(context, R.color.surface))

                // 색상 규칙:
                // 1) 재생 중인 파일: 파란색 굵은 글씨 (#0381FE, bold)
                // 2) 재생 목록에 있는 파일: 밝고 화사한 하늘색 글씨 (#4FC3F7, normal)
                // 3) 일반 파일: 기본 흰색 (#FFFFFF, normal)
                val isCurrentPlaying = (item.id == playingItemId)
                val isInPlaylist = playlistIds.contains(item.id)

                if (isCurrentPlaying) {
                    tvTitle.setTextColor(Color.parseColor("#0381FE"))
                    tvTitle.setTypeface(null, Typeface.BOLD)
                } else if (isInPlaylist) {
                    tvTitle.setTextColor(Color.parseColor("#4FC3F7"))
                    tvTitle.setTypeface(null, Typeface.NORMAL)
                } else {
                    tvTitle.setTextColor(ContextCompat.getColor(context, R.color.on_surface))
                    tvTitle.setTypeface(null, Typeface.NORMAL)
                }

                val clickListener = View.OnClickListener {
                    onItemClicked(item)
                }
                tvTitle.setOnClickListener(clickListener)
                itemView.setOnClickListener(clickListener)

                val longClickListener = View.OnLongClickListener {
                    onStartSelectionMode(item)
                    true
                }
                tvTitle.setOnLongClickListener(longClickListener)
                itemView.setOnLongClickListener(longClickListener)

                btnShare.setOnClickListener {
                    shareMp3File(item)
                }
            }
        }

        private fun toggle(item: Mp3Item) {
            if (selectedIds.contains(item.id)) {
                selectedIds.remove(item.id)
            } else {
                selectedIds.add(item.id)
            }
            notifyItemChanged(adapterPosition)
            onSelectionChanged(selectedIds.size, items.size)
        }

        private fun shareMp3File(item: Mp3Item) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, item.uri)
                putExtra(Intent.EXTRA_SUBJECT, item.title)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(Intent.createChooser(shareIntent, "음악 파일 공유"))
        }

        private fun formatTime(ms: Long): String {
            if (ms <= 0) return "00:00"
            val totalSec = ms / 1000
            val min = totalSec / 60
            val sec = totalSec % 60
            return String.format(Locale.getDefault(), "%02d:%02d", min, sec)
        }
    }
}
