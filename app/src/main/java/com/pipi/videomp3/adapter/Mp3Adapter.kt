package com.pipi.videomp3.adapter

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.pipi.videomp3.R
import com.pipi.videomp3.model.Mp3Item
import com.pipi.videomp3.util.Mp3StorageHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Mp3Adapter(
    private val context: Context,
    private val onItemDeleted: () -> Unit
) : RecyclerView.Adapter<Mp3Adapter.Mp3ViewHolder>() {

    private val items = mutableListOf<Mp3Item>()
    private var mediaPlayer: MediaPlayer? = null
    private var playingItemPosition: Int = -1

    private val handler = Handler(Looper.getMainLooper())
    private var updateSeekBarRunnable: Runnable? = null

    fun submitList(newItems: List<Mp3Item>) {
        stopPlayback()
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Mp3ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_mp3_card, parent, false)
        return Mp3ViewHolder(view)
    }

    override fun onBindViewHolder(holder: Mp3ViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    fun stopPlayback() {
        updateSeekBarRunnable?.let { handler.removeCallbacks(it) }
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
        private val tvInfo: TextView = itemView.findViewById(R.id.tvMp3Info)
        private val btnPlayPause: Button = itemView.findViewById(R.id.btnPlayPause)
        private val seekBar: SeekBar = itemView.findViewById(R.id.seekBarPlayer)
        private val tvPlayerTime: TextView = itemView.findViewById(R.id.tvPlayerTime)
        private val btnShare: Button = itemView.findViewById(R.id.btnShare)
        private val btnOpenSamsung: Button = itemView.findViewById(R.id.btnOpenSamsungMusic)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)

        fun bind(item: Mp3Item, position: Int) {
            tvTitle.text = item.title

            val sizeMb = String.format(Locale.getDefault(), "%.1f MB", item.sizeBytes / (1024.0 * 1024.0))
            val dateStr = if (item.dateAddedSec > 0) {
                val sdf = SimpleDateFormat("M월 d일", Locale.KOREA)
                sdf.format(Date(item.dateAddedSec * 1000L))
            } else {
                "오늘"
            }
            val durStr = formatTime(item.durationMs)
            tvInfo.text = "재생시간 $durStr · $sizeMb · $dateStr 추출"

            val isCurrentPlaying = (position == playingItemPosition && mediaPlayer != null && mediaPlayer!!.isPlaying)

            if (isCurrentPlaying) {
                btnPlayPause.text = "⏸ 정지"
                seekBar.isEnabled = true
            } else {
                btnPlayPause.text = "▶ 재생"
                seekBar.progress = 0
                seekBar.isEnabled = false
                tvPlayerTime.text = durStr
            }

            btnPlayPause.setOnClickListener {
                if (isCurrentPlaying) {
                    stopPlayback()
                } else {
                    startPlayback(item, position)
                }
            }

            btnOpenSamsung.setOnClickListener {
                openInSamsungMusic(item)
            }

            btnShare.setOnClickListener {
                shareMp3File(item)
            }

            btnDelete.setOnClickListener {
                confirmDelete(item, position)
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

                val totalDuration = mediaPlayer?.duration ?: item.durationMs.toInt()
                seekBar.max = totalDuration
                seekBar.isEnabled = true
                btnPlayPause.text = "⏸ 정지"

                seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                        if (fromUser) {
                            mediaPlayer?.seekTo(prog)
                            tvPlayerTime.text = formatTime(prog.toLong())
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })

                updateSeekBarRunnable = object : Runnable {
                    override fun run() {
                        mediaPlayer?.let { mp ->
                            if (mp.isPlaying) {
                                val current = mp.currentPosition
                                seekBar.progress = current
                                tvPlayerTime.text = "${formatTime(current.toLong())} / ${formatTime(totalDuration.toLong())}"
                                handler.postDelayed(this, 500)
                            }
                        }
                    }
                }
                handler.post(updateSeekBarRunnable!!)

                mediaPlayer?.setOnCompletionListener {
                    stopPlayback()
                }

            } catch (e: Exception) {
                Toast.makeText(context, "재생할 수 없습니다: ${e.message}", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(context, "음악을 재생할 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show()
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
                    val success = Mp3StorageHelper.deleteMp3File(context, item)
                    if (success) {
                        if (position == playingItemPosition) stopPlayback()
                        items.removeAt(position)
                        notifyItemRemoved(position)
                        notifyItemRangeChanged(position, items.size)
                        onItemDeleted()
                        Toast.makeText(context, "삭제되었습니다.", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "삭제하지 못했습니다.", Toast.LENGTH_SHORT).show()
                    }
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
