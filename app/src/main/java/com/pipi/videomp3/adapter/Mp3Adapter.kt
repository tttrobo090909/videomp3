package com.pipi.videomp3.adapter

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.MediaPlayer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.pipi.videomp3.R
import com.pipi.videomp3.model.Mp3Item
import com.pipi.videomp3.util.Mp3StorageHelper
import java.util.Locale

class Mp3Adapter(
    private val context: Context,
    private val onItemDeleted: () -> Unit
) : RecyclerView.Adapter<Mp3Adapter.Mp3ViewHolder>() {

    private val items = mutableListOf<Mp3Item>()
    private var mediaPlayer: MediaPlayer? = null
    private var playingItemPosition: Int = -1

    fun submitList(newItems: List<Mp3Item>) {
        stopPlayback()
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Mp3ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_mp3_row, parent, false)
        return Mp3ViewHolder(view)
    }

    override fun onBindViewHolder(holder: Mp3ViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    fun stopPlayback() {
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null
        val oldPos = playingItemPosition
        playingItemPosition = -1
        if (oldPos != -1 && oldPos < items.size) {
            notifyItemChanged(oldPos)
        }
    }

    inner class Mp3ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvMp3Title)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvDuration)
        private val btnOpenSamsung: ImageButton = itemView.findViewById(R.id.btnOpenSamsungMusic)
        private val btnShare: ImageButton = itemView.findViewById(R.id.btnShare)

        fun bind(item: Mp3Item, position: Int) {
            tvTitle.text = item.title
            tvTitle.isSelected = true

            tvDuration.text = formatTime(item.durationMs)

            val isCurrentPlaying = (position == playingItemPosition && mediaPlayer != null && mediaPlayer!!.isPlaying)

            // 재생 중인 곡은 글자색 파란색(#0381FE), 멈춘 곡은 흰색
            if (isCurrentPlaying) {
                tvTitle.setTextColor(Color.parseColor("#0381FE"))
            } else {
                tvTitle.setTextColor(ContextCompat.getColor(context, R.color.on_surface))
            }

            // 파일 제목 클릭 -> 재생 / 일시정지 토글
            tvTitle.setOnClickListener {
                if (isCurrentPlaying) {
                    stopPlayback()
                } else {
                    startPlayback(item, position)
                }
            }

            // 1초 길게 누르면 삭제 기능
            tvTitle.setOnLongClickListener {
                confirmDelete(item, position)
                true
            }

            itemView.setOnLongClickListener {
                confirmDelete(item, position)
                true
            }

            // 녹색 버튼 클릭 -> 삼성 뮤직으로 열기
            btnOpenSamsung.setOnClickListener {
                openInSamsungMusic(item)
            }

            // 공유 버튼
            btnShare.setOnClickListener {
                shareMp3File(item)
            }
        }

        private fun startPlayback(item: Mp3Item, position: Int) {
            stopPlayback()
            playingItemPosition = position
            notifyItemChanged(position)

            try {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(context, item.uri)
                    prepare()
                    start()
                }

                mediaPlayer?.setOnCompletionListener {
                    stopPlayback()
                }

            } catch (e: Exception) {
                stopPlayback()
            }
        }

        private fun openInSamsungMusic(item: Mp3Item) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(item.uri, "audio/*")
                setPackage("com.sec.android.app.music")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(item.uri, "audio/*")
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                try {
                    context.startActivity(fallbackIntent)
                } catch (e2: Exception) {
                }
            }
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

        private fun confirmDelete(item: Mp3Item, position: Int) {
            AlertDialog.Builder(context)
                .setTitle("MP3 파일 삭제")
                .setMessage("'${item.title}' 파일을 정말 삭제할까요?")
                .setPositiveButton("삭제") { _, _ ->
                    if (position == playingItemPosition) stopPlayback()
                    (context as? MainActivity)?.requestDeleteMp3(item)
                }
                .setNegativeButton("취소", null)
                .show()
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
