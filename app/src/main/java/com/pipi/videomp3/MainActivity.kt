package com.pipi.videomp3

import android.app.RecoverableSecurityException
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.pipi.videomp3.adapter.Mp3Adapter
import com.pipi.videomp3.model.Mp3Item
import com.pipi.videomp3.service.RecordingService
import com.pipi.videomp3.util.LocalVideoExtractor
import com.pipi.videomp3.util.Mp3StorageHelper
import com.pipi.videomp3.util.YouTubeExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

enum class PlaybackMode {
    NORMAL, REPEAT_ALL, REPEAT_ONE, SHUFFLE
}

class MainActivity : AppCompatActivity() {

    private lateinit var tvHeaderStatus: TextView

    private lateinit var btnRecord: TextView
    private lateinit var btnFile: Button
    private lateinit var btnLinkPaste: TextView
    private lateinit var etYoutubeUrl: EditText
    private lateinit var btnClearUrl: ImageButton
    private lateinit var btnStartExtract: Button

    // 전용 플레이어 뷰들
    private lateinit var layoutPlayer: LinearLayout
    private lateinit var tvPlayerTitle: TextView
    private lateinit var sbPlayerProgress: SeekBar
    private lateinit var tvPlayerTime: TextView
    private lateinit var btnPlayerRewind5: Button
    private lateinit var btnPlayerPlayPause: Button
    private lateinit var btnPlayerForward5: Button
    private lateinit var btnModeRepeatAll: Button
    private lateinit var btnModeRepeatOne: Button
    private lateinit var btnModeShuffle: Button

    // 일반 모드 헤더
    private lateinit var layoutNormalHeader: LinearLayout
    private lateinit var btnEnterSelectMode: TextView

    // 다중 선택 모드 헤더
    private lateinit var layoutSelectionHeader: LinearLayout
    private lateinit var cbSelectAll: CheckBox
    private lateinit var tvSelectedCount: TextView
    private lateinit var btnDeleteSelected: Button
    private lateinit var btnAddToPlaylist: Button
    private lateinit var btnCancelSelection: TextView

    // 목록 영역
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var rvMp3List: RecyclerView
    private lateinit var tvEmptyMessage: TextView

    private lateinit var mp3Adapter: Mp3Adapter

    // 플레이어 상태 변수들
    private var mediaPlayer: MediaPlayer? = null
    private var currentPlayingItem: Mp3Item? = null
    private var playbackMode = PlaybackMode.NORMAL
    private var isUserTrackingSeekBar = false
    private val playlistItemIds = mutableSetOf<Long>()
    private var playerUpdateJob: Job? = null

    // 상태 변수들
    private var isExtracting = false
    private val isRecording: Boolean
        get() = RecordingService.isServiceRunning

    private var selectedLocalVideoUri: Uri? = null
    private var selectedLocalVideoName: String = ""

    // 비디오 파일 선택 런처
    private val selectVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            selectedLocalVideoUri = uri
            selectedLocalVideoName = queryRealFileName(uri)
            val cleanTitle = selectedLocalVideoName.substringBeforeLast(".")
            etYoutubeUrl.setText(cleanTitle)
            etYoutubeUrl.setSelection(etYoutubeUrl.text.length)
            updateStatusText()
        }
    }

    // 마이크 및 알림 권한 요청 런처
    private val requestRecordPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordGranted = permissions[android.Manifest.permission.RECORD_AUDIO] == true ||
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (recordGranted) {
            startVoiceRecording()
        }
    }

    // 파일 읽기/저장 권한 요청 런처
    private val requestStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        loadMp3Files()
    }

    // 안드로이드 10+ 미디어 삭제 권한 요청 런처
    private val deleteRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            exitSelectionMode()
            loadMp3Files()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        setupBackPressHandler()
        checkStoragePermissions()
        loadMp3Files()
    }

    override fun onResume() {
        super.onResume()
        attachRecordingListeners()
        syncRecordingState()
        loadMp3Files()
    }

    override fun onPause() {
        super.onPause()
        RecordingService.stateListener = null
    }

    override fun onDestroy() {
        super.onDestroy()
        RecordingService.stateListener = null
        RecordingService.onRecordingSaved = null
        stopPlayback()
    }

    private fun initViews() {
        tvHeaderStatus = findViewById(R.id.tvHeaderStatus)

        btnRecord = findViewById(R.id.btnRecord)
        btnFile = findViewById(R.id.btnFile)
        btnLinkPaste = findViewById(R.id.btnLinkPaste)
        etYoutubeUrl = findViewById(R.id.etYoutubeUrl)
        btnClearUrl = findViewById(R.id.btnClearUrl)
        btnStartExtract = findViewById(R.id.btnStartExtract)

        layoutPlayer = findViewById(R.id.layoutPlayer)
        tvPlayerTitle = findViewById(R.id.tvPlayerTitle)
        sbPlayerProgress = findViewById(R.id.sbPlayerProgress)
        tvPlayerTime = findViewById(R.id.tvPlayerTime)
        btnPlayerRewind5 = findViewById(R.id.btnPlayerRewind5)
        btnPlayerPlayPause = findViewById(R.id.btnPlayerPlayPause)
        btnPlayerForward5 = findViewById(R.id.btnPlayerForward5)
        btnModeRepeatAll = findViewById(R.id.btnModeRepeatAll)
        btnModeRepeatOne = findViewById(R.id.btnModeRepeatOne)
        btnModeShuffle = findViewById(R.id.btnModeShuffle)

        layoutNormalHeader = findViewById(R.id.layoutNormalHeader)
        btnEnterSelectMode = findViewById(R.id.btnEnterSelectMode)

        layoutSelectionHeader = findViewById(R.id.layoutSelectionHeader)
        cbSelectAll = findViewById(R.id.cbSelectAll)
        tvSelectedCount = findViewById(R.id.tvSelectedCount)
        btnDeleteSelected = findViewById(R.id.btnDeleteSelected)
        btnAddToPlaylist = findViewById(R.id.btnAddToPlaylist)
        btnCancelSelection = findViewById(R.id.btnCancelSelection)

        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        rvMp3List = findViewById(R.id.rvMp3List)
        tvEmptyMessage = findViewById(R.id.tvEmptyMessage)

        mp3Adapter = Mp3Adapter(
            context = this,
            onItemClicked = { item ->
                playTrack(item)
            },
            onSelectionChanged = { selectedCount, totalCount ->
                updateSelectionUi(selectedCount, totalCount)
            },
            onStartSelectionMode = { initialItem ->
                enterSelectionMode(initialItem)
            }
        )

        rvMp3List.layoutManager = LinearLayoutManager(this)
        rvMp3List.adapter = mp3Adapter

        updateModeButtonsUi()
    }

    private fun setupListeners() {
        // 녹음 버튼 (♤ / ♠)
        btnRecord.setOnClickListener {
            if (isRecording) {
                stopVoiceRecording(save = true)
            } else {
                checkAndStartRecording()
            }
        }

        // 파일 선택 버튼
        btnFile.setOnClickListener {
            selectVideoLauncher.launch("video/*")
        }

        // 링크 아이콘 (🔗) 누르면 클립보드 링크 즉시 붙여넣기
        btnLinkPaste.setOnClickListener {
            pasteFromClipboard()
        }

        // 링크 지우기 (✕)
        btnClearUrl.setOnClickListener {
            etYoutubeUrl.setText("")
            selectedLocalVideoUri = null
            selectedLocalVideoName = ""
            updateStatusText()
        }

        // 유튜브 링크 입력창 텍스트 변경 감지 -> 실시간 [유튜브 선택중] 반영
        etYoutubeUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                btnClearUrl.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                updateStatusText()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // 추출 시작 버튼 (⚡)
        btnStartExtract.setOnClickListener {
            startExtractionFlow()
        }

        // [선택] 버튼 클릭 -> 다중 선택 모드 진입
        btnEnterSelectMode.setOnClickListener {
            enterSelectionMode(null)
        }

        // 선택 모드 [취소] 버튼
        btnCancelSelection.setOnClickListener {
            exitSelectionMode()
        }

        // 전체 선택 체크박스
        cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
            mp3Adapter.selectAll(isChecked)
        }

        // 선택 항목 삭제 버튼
        btnDeleteSelected.setOnClickListener {
            requestDeleteSelectedMp3s()
        }

        // [재생목록 추가] 버튼
        btnAddToPlaylist.setOnClickListener {
            val selected = mp3Adapter.getSelectedItems()
            if (selected.isNotEmpty()) {
                playlistItemIds.addAll(selected.map { it.id })
                mp3Adapter.setPlaylistIds(playlistItemIds)
                exitSelectionMode()
                val count = mp3Adapter.itemCount
                tvHeaderStatus.text = "[재생목록 ${playlistItemIds.size}곡] [${count}곡]"
            }
        }

        // 아래로 당겨서 새로고침 (SwipeRefreshLayout)
        swipeRefreshLayout.setColorSchemeColors(ContextCompat.getColor(this, R.color.secondary))
        swipeRefreshLayout.setOnRefreshListener {
            loadMp3Files()
        }

        // 플레이어 조작 버튼들
        btnPlayerRewind5.setOnClickListener {
            mediaPlayer?.let { player ->
                val newPos = max(0, player.currentPosition - 5000)
                player.seekTo(newPos)
                updatePlayerTimeDisplay(newPos, player.duration)
            }
        }

        btnPlayerForward5.setOnClickListener {
            mediaPlayer?.let { player ->
                val newPos = min(player.duration, player.currentPosition + 5000)
                player.seekTo(newPos)
                updatePlayerTimeDisplay(newPos, player.duration)
            }
        }

        btnPlayerPlayPause.setOnClickListener {
            togglePlayPause()
        }

        btnModeRepeatAll.setOnClickListener {
            playbackMode = if (playbackMode == PlaybackMode.REPEAT_ALL) PlaybackMode.NORMAL else PlaybackMode.REPEAT_ALL
            updateModeButtonsUi()
        }

        btnModeRepeatOne.setOnClickListener {
            playbackMode = if (playbackMode == PlaybackMode.REPEAT_ONE) PlaybackMode.NORMAL else PlaybackMode.REPEAT_ONE
            updateModeButtonsUi()
        }

        btnModeShuffle.setOnClickListener {
            playbackMode = if (playbackMode == PlaybackMode.SHUFFLE) PlaybackMode.NORMAL else PlaybackMode.SHUFFLE
            updateModeButtonsUi()
        }

        sbPlayerProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaPlayer?.let { player ->
                        updatePlayerTimeDisplay(progress, player.duration)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingSeekBar = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingSeekBar = false
                seekBar?.let {
                    mediaPlayer?.seekTo(it.progress)
                }
            }
        })
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (mp3Adapter.isSelectionMode) {
                    exitSelectionMode()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    // 전용 플레이어 재생 로직
    private fun playTrack(item: Mp3Item) {
        stopPlayback()
        currentPlayingItem = item
        mp3Adapter.setPlayingItemId(item.id)

        tvPlayerTitle.text = item.title
        tvPlayerTitle.isSelected = true

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@MainActivity, item.uri)
                prepare()
                start()
            }

            btnPlayerPlayPause.text = "⏸ 일시정지"
            sbPlayerProgress.max = mediaPlayer?.duration ?: 0
            sbPlayerProgress.progress = 0
            updatePlayerTimeDisplay(0, mediaPlayer?.duration ?: 0)

            mediaPlayer?.setOnCompletionListener {
                onTrackCompleted()
            }

            startProgressUpdater()

        } catch (e: Exception) {
            e.printStackTrace()
            stopPlayback()
        }
    }

    private fun togglePlayPause() {
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            btnPlayerPlayPause.text = "▶ 재생"
        } else if (mediaPlayer != null) {
            mediaPlayer?.start()
            btnPlayerPlayPause.text = "⏸ 일시정지"
        } else if (currentPlayingItem != null) {
            playTrack(currentPlayingItem!!)
        } else {
            val all = mp3Adapter.getAllItems()
            if (all.isNotEmpty()) {
                playTrack(all[0])
            }
        }
    }

    private fun onTrackCompleted() {
        when (playbackMode) {
            PlaybackMode.REPEAT_ONE -> {
                mediaPlayer?.seekTo(0)
                mediaPlayer?.start()
            }
            PlaybackMode.REPEAT_ALL -> {
                val activeList = getActivePlaylist()
                if (activeList.isNotEmpty()) {
                    val currentIndex = activeList.indexOfFirst { it.id == currentPlayingItem?.id }
                    val nextIndex = if (currentIndex != -1) (currentIndex + 1) % activeList.size else 0
                    playTrack(activeList[nextIndex])
                } else {
                    stopPlayback()
                }
            }
            PlaybackMode.SHUFFLE -> {
                val activeList = getActivePlaylist()
                if (activeList.isNotEmpty()) {
                    val nextItem = if (activeList.size > 1) {
                        activeList.filter { it.id != currentPlayingItem?.id }.random()
                    } else {
                        activeList.first()
                    }
                    playTrack(nextItem)
                } else {
                    stopPlayback()
                }
            }
            PlaybackMode.NORMAL -> {
                val activeList = getActivePlaylist()
                val currentIndex = activeList.indexOfFirst { it.id == currentPlayingItem?.id }
                if (currentIndex != -1 && currentIndex + 1 < activeList.size) {
                    playTrack(activeList[currentIndex + 1])
                } else {
                    stopPlayback()
                }
            }
        }
    }

    private fun getActivePlaylist(): List<Mp3Item> {
        val all = mp3Adapter.getAllItems()
        return if (playlistItemIds.isNotEmpty()) {
            all.filter { playlistItemIds.contains(it.id) }
        } else {
            all
        }
    }

    private fun startProgressUpdater() {
        playerUpdateJob?.cancel()
        playerUpdateJob = lifecycleScope.launch {
            while (true) {
                mediaPlayer?.let { player ->
                    if (player.isPlaying && !isUserTrackingSeekBar) {
                        val current = player.currentPosition
                        val total = player.duration
                        sbPlayerProgress.max = total
                        sbPlayerProgress.progress = current
                        updatePlayerTimeDisplay(current, total)
                    }
                }
                delay(300)
            }
        }
    }

    private fun updatePlayerTimeDisplay(currentMs: Int, totalMs: Int) {
        val currentStr = formatMs(currentMs.toLong())
        val totalStr = formatMs(totalMs.toLong())
        tvPlayerTime.text = "$currentStr / $totalStr"
    }

    private fun formatMs(ms: Long): String {
        if (ms <= 0) return "00:00"
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.getDefault(), "%02d:%02d", min, sec)
    }

    private fun stopPlayback() {
        playerUpdateJob?.cancel()
        playerUpdateJob = null
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null
        btnPlayerPlayPause.text = "▶ 재생"
        sbPlayerProgress.progress = 0
        mp3Adapter.setPlayingItemId(null)
    }

    private fun updateModeButtonsUi() {
        val activeBg = ContextCompat.getColor(this, R.color.primary)
        val inactiveBg = ContextCompat.getColor(this, R.color.surface_variant)
        val activeText = Color.WHITE
        val inactiveText = ContextCompat.getColor(this, R.color.on_surface_secondary)

        // 🔁 전체반복
        if (playbackMode == PlaybackMode.REPEAT_ALL) {
            btnModeRepeatAll.backgroundTintList = ColorStateList.valueOf(activeBg)
            btnModeRepeatAll.setTextColor(activeText)
        } else {
            btnModeRepeatAll.backgroundTintList = ColorStateList.valueOf(inactiveBg)
            btnModeRepeatAll.setTextColor(inactiveText)
        }

        // 🔂 1곡반복
        if (playbackMode == PlaybackMode.REPEAT_ONE) {
            btnModeRepeatOne.backgroundTintList = ColorStateList.valueOf(activeBg)
            btnModeRepeatOne.setTextColor(activeText)
        } else {
            btnModeRepeatOne.backgroundTintList = ColorStateList.valueOf(inactiveBg)
            btnModeRepeatOne.setTextColor(inactiveText)
        }

        // 🔀 랜덤반복
        if (playbackMode == PlaybackMode.SHUFFLE) {
            btnModeShuffle.backgroundTintList = ColorStateList.valueOf(activeBg)
            btnModeShuffle.setTextColor(activeText)
        } else {
            btnModeShuffle.backgroundTintList = ColorStateList.valueOf(inactiveBg)
            btnModeShuffle.setTextColor(inactiveText)
        }
    }

    private fun enterSelectionMode(initialItem: Mp3Item?) {
        layoutNormalHeader.visibility = View.GONE
        layoutSelectionHeader.visibility = View.VISIBLE
        mp3Adapter.enterSelectionMode(initialItem)
    }

    private fun exitSelectionMode() {
        layoutSelectionHeader.visibility = View.GONE
        layoutNormalHeader.visibility = View.VISIBLE
        mp3Adapter.exitSelectionMode()
    }

    private fun updateSelectionUi(selectedCount: Int, totalCount: Int) {
        tvSelectedCount.text = "${selectedCount}개"
        btnDeleteSelected.isEnabled = selectedCount > 0
        btnDeleteSelected.alpha = if (selectedCount > 0) 1.0f else 0.5f

        btnAddToPlaylist.isEnabled = selectedCount > 0
        btnAddToPlaylist.alpha = if (selectedCount > 0) 1.0f else 0.5f

        cbSelectAll.setOnCheckedChangeListener(null)
        cbSelectAll.isChecked = (totalCount > 0 && selectedCount == totalCount)
        cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
            mp3Adapter.selectAll(isChecked)
        }
    }

    private fun attachRecordingListeners() {
        RecordingService.stateListener = { recording, seconds ->
            runOnUiThread {
                if (recording) {
                    btnRecord.text = "♠"
                    btnRecord.setTextColor(Color.WHITE)
                    val m = seconds / 60
                    val s = seconds % 60
                    val timeStr = String.format(Locale.getDefault(), "%02d:%02d", m, s)
                    val count = mp3Adapter.itemCount
                    tvHeaderStatus.text = "[녹음중 $timeStr] [${count}곡]"
                } else {
                    btnRecord.text = "♤"
                    btnRecord.setTextColor(Color.parseColor("#888888"))
                    updateStatusText()
                }
            }
        }

        RecordingService.onRecordingSaved = { _, success ->
            runOnUiThread {
                if (success) {
                    loadMp3Files(scrollToTop = true)
                }
                updateStatusText()
            }
        }
    }

    private fun syncRecordingState() {
        if (RecordingService.isServiceRunning) {
            btnRecord.text = "♠"
            btnRecord.setTextColor(Color.WHITE)
            val sec = RecordingService.recordingSeconds
            val m = sec / 60
            val s = sec % 60
            val timeStr = String.format(Locale.getDefault(), "%02d:%02d", m, s)
            val count = mp3Adapter.itemCount
            tvHeaderStatus.text = "[녹음중 $timeStr] [${count}곡]"
        } else {
            btnRecord.text = "♤"
            btnRecord.setTextColor(Color.parseColor("#888888"))
            updateStatusText()
        }
    }

    private fun checkAndStartRecording() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isEmpty()) {
            startVoiceRecording()
        } else {
            requestRecordPermissionsLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun startVoiceRecording() {
        if (isExtracting) return
        RecordingService.startRecording(this)
    }

    private fun stopVoiceRecording(save: Boolean) {
        RecordingService.stopRecording(this, save = save)
    }

    private fun startExtractionFlow() {
        if (isExtracting) return
        if (isRecording) return

        if (selectedLocalVideoUri != null) {
            val userTitle = etYoutubeUrl.text.toString().trim()
            val finalName = if (userTitle.isNotBlank() && !userTitle.startsWith("http")) {
                "$userTitle.mp4"
            } else {
                selectedLocalVideoName
            }
            startLocalVideoExtraction(finalName)
            return
        }

        val url = etYoutubeUrl.text.toString().trim()
        if (url.isNotBlank() && (url.contains("youtu.be") || url.contains("youtube.com") || url.startsWith("http"))) {
            startYoutubeExtraction(url)
            return
        }
    }

    private fun startYoutubeExtraction(url: String) {
        lifecycleScope.launch {
            setExtractingState(true)

            val result = YouTubeExtractor.extractAndSaveMp3(
                context = this@MainActivity,
                youtubeUrl = url,
                onProgress = { percent, statusText ->
                    val count = mp3Adapter.itemCount
                    tvHeaderStatus.text = "[$statusText] [${count}곡]"
                }
            )

            setExtractingState(false)

            if (result.isSuccess) {
                etYoutubeUrl.setText("")
                loadMp3Files(scrollToTop = true)
            }
            updateStatusText()
        }
    }

    private fun startLocalVideoExtraction(fileNameToUse: String) {
        val uri = selectedLocalVideoUri ?: return

        lifecycleScope.launch {
            setExtractingState(true)

            val result = LocalVideoExtractor.extractAudioFromLocalVideo(
                context = this@MainActivity,
                videoUri = uri,
                fileName = fileNameToUse,
                onProgress = { percent, statusText ->
                    val count = mp3Adapter.itemCount
                    tvHeaderStatus.text = "[$statusText] [${count}곡]"
                }
            )

            setExtractingState(false)

            if (result.isSuccess) {
                selectedLocalVideoUri = null
                selectedLocalVideoName = ""
                etYoutubeUrl.setText("")
                loadMp3Files(scrollToTop = true)
            }
            updateStatusText()
        }
    }

    private fun setExtractingState(extracting: Boolean) {
        isExtracting = extracting
        btnFile.isEnabled = !extracting
        btnStartExtract.isEnabled = !extracting
        btnRecord.isEnabled = !extracting
        if (!extracting) {
            updateStatusText()
        }
    }

    private fun loadMp3Files(scrollToTop: Boolean = false) {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = Mp3StorageHelper.getAllExtractedMp3Files(this@MainActivity)
            withContext(Dispatchers.Main) {
                mp3Adapter.submitList(list)
                updateStatusText()
                swipeRefreshLayout.isRefreshing = false
                tvEmptyMessage.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                btnEnterSelectMode.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                if (scrollToTop && list.isNotEmpty()) {
                    rvMp3List.post {
                        rvMp3List.scrollToPosition(0)
                    }
                }
                if (list.isEmpty() && mp3Adapter.isSelectionMode) {
                    exitSelectionMode()
                }
            }
        }
    }

    private fun updateStatusText() {
        val count = mp3Adapter.itemCount

        if (isRecording) {
            return
        }

        if (isExtracting) {
            return
        }

        val url = etYoutubeUrl.text.toString().trim()
        if (selectedLocalVideoUri != null) {
            tvHeaderStatus.text = "[동영상 선택중] [${count}곡]"
        } else if (url.isNotBlank()) {
            tvHeaderStatus.text = "[유튜브 선택중] [${count}곡]"
        } else {
            tvHeaderStatus.text = "[완료] [${count}곡]"
        }
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString() ?: ""
            if (text.isNotBlank()) {
                selectedLocalVideoUri = null
                selectedLocalVideoName = ""
                etYoutubeUrl.setText(text.trim())
                etYoutubeUrl.setSelection(etYoutubeUrl.text.length)
                updateStatusText()
            }
        }
    }

    private fun checkStoragePermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(android.Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            requestStoragePermissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun requestDeleteSelectedMp3s() {
        val selectedItems = mp3Adapter.getSelectedItems()
        if (selectedItems.isEmpty()) return

        lifecycleScope.launch(Dispatchers.IO) {
            val uris = selectedItems.map { it.uri }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val pi = MediaStore.createDeleteRequest(contentResolver, uris)
                    withContext(Dispatchers.Main) {
                        deleteRequestLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                    }
                    return@launch
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            var securityException: SecurityException? = null
            for (item in selectedItems) {
                try {
                    contentResolver.delete(item.uri, null, null)
                } catch (se: SecurityException) {
                    securityException = se
                }
            }

            if (securityException != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val recoverable = securityException as? RecoverableSecurityException
                if (recoverable != null) {
                    withContext(Dispatchers.Main) {
                        deleteRequestLauncher.launch(IntentSenderRequest.Builder(recoverable.userAction.actionIntent.intentSender).build())
                    }
                    return@launch
                }
            }

            withContext(Dispatchers.Main) {
                exitSelectionMode()
                loadMp3Files()
            }
        }
    }

    private fun queryRealFileName(uri: Uri): String {
        var resultName: String? = null

        try {
            var mediaId: Long? = null
            if (DocumentsContract.isDocumentUri(this, uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                if (docId.contains(":")) {
                    val idPart = docId.substringAfter(":")
                    mediaId = idPart.toLongOrNull()
                }
            } else if (uri.toString().contains("video/media/")) {
                mediaId = uri.lastPathSegment?.toLongOrNull()
            }

            if (mediaId != null) {
                val videoUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaId)
                val projection = arrayOf(
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.TITLE,
                    MediaStore.Video.Media.DATA
                )
                contentResolver.query(videoUri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val dispCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                        val titleCol = cursor.getColumnIndex(MediaStore.Video.Media.TITLE)
                        val dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA)

                        val disp = if (dispCol != -1) cursor.getString(dispCol) else null
                        val title = if (titleCol != -1) cursor.getString(titleCol) else null
                        val data = if (dataCol != -1) cursor.getString(dataCol) else null

                        val candidate = when {
                            !disp.isNullOrBlank() && !disp.substringBeforeLast(".").all { it.isDigit() } -> disp
                            !data.isNullOrBlank() && !File(data).name.substringBeforeLast(".").all { it.isDigit() } -> File(data).name
                            !title.isNullOrBlank() && !title.all { it.isDigit() } -> "$title.mp4"
                            else -> disp ?: (if (data != null) File(data).name else null) ?: title
                        }
                        if (!candidate.isNullOrBlank()) {
                            resultName = candidate
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (resultName == null || resultName!!.substringBeforeLast(".").all { it.isDigit() }) {
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val dataCol = cursor.getColumnIndex("_data")
                        val titleCol = cursor.getColumnIndex("title")

                        val name = if (nameCol != -1) cursor.getString(nameCol) else null
                        val data = if (dataCol != -1) cursor.getString(dataCol) else null
                        val title = if (titleCol != -1) cursor.getString(titleCol) else null

                        val candidate = when {
                            !name.isNullOrBlank() && !name.substringBeforeLast(".").all { it.isDigit() } -> name
                            !data.isNullOrBlank() && !File(data).name.substringBeforeLast(".").all { it.isDigit() } -> File(data).name
                            !title.isNullOrBlank() && !title.all { it.isDigit() } -> "$title.mp4"
                            else -> name ?: (if (data != null) File(data).name else null) ?: title
                        }
                        if (!candidate.isNullOrBlank()) {
                            resultName = candidate
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val finalClean = resultName?.substringBeforeLast(".") ?: ""
        return if (resultName.isNullOrBlank() || finalClean.isBlank() || finalClean.all { it.isDigit() }) {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date())
            "내영상_$timeStamp.mp4"
        } else {
            resultName!!
        }
    }
}
