package com.pipi.videomp3

import android.app.RecoverableSecurityException
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
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
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pipi.videomp3.adapter.Mp3Adapter
import com.pipi.videomp3.service.RecordingService
import com.pipi.videomp3.util.LocalVideoExtractor
import com.pipi.videomp3.util.Mp3StorageHelper
import com.pipi.videomp3.util.YouTubeExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvHeaderStatus: TextView

    private lateinit var btnRecord: TextView
    private lateinit var btnFile: Button
    private lateinit var btnLinkPaste: TextView
    private lateinit var etYoutubeUrl: EditText
    private lateinit var btnClearUrl: ImageButton
    private lateinit var btnStartExtract: Button

    private lateinit var btnRefresh: ImageButton
    private lateinit var rvMp3List: RecyclerView
    private lateinit var tvEmptyMessage: TextView

    private lateinit var mp3Adapter: Mp3Adapter

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
            loadMp3Files()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
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
        mp3Adapter.stopPlayback()
    }

    private fun initViews() {
        tvHeaderStatus = findViewById(R.id.tvHeaderStatus)

        btnRecord = findViewById(R.id.btnRecord)
        btnFile = findViewById(R.id.btnFile)
        btnLinkPaste = findViewById(R.id.btnLinkPaste)
        etYoutubeUrl = findViewById(R.id.etYoutubeUrl)
        btnClearUrl = findViewById(R.id.btnClearUrl)
        btnStartExtract = findViewById(R.id.btnStartExtract)

        btnRefresh = findViewById(R.id.btnRefresh)
        rvMp3List = findViewById(R.id.rvMp3List)
        tvEmptyMessage = findViewById(R.id.tvEmptyMessage)

        mp3Adapter = Mp3Adapter(this) {
            loadMp3Files()
        }

        rvMp3List.layoutManager = LinearLayoutManager(this)
        rvMp3List.adapter = mp3Adapter
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

        // 목록 새로고침 버튼
        btnRefresh.setOnClickListener {
            loadMp3Files()
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
                    loadMp3Files()
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

    /**
     * 마이크 및 알림 권한 확인 후 녹음 시작
     */
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

        // 1순위: 파일이 선택되어 있으면 로컬 동영상 음원 추출 실행!
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

        // 2순위: 유튜브 링크가 입력되어 있으면 유튜브 추출 실행!
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
                loadMp3Files()
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
                loadMp3Files()
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

    private fun loadMp3Files() {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = Mp3StorageHelper.getAllExtractedMp3Files(this@MainActivity)
            withContext(Dispatchers.Main) {
                mp3Adapter.submitList(list)
                updateStatusText()
                tvEmptyMessage.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    /**
     * 최상단 우측 상태 텍스트 갱신:
     * - 평소 / 완료 시: [완료] [N곡]
     * - 파일 선택 시: [동영상 선택중] [N곡]
     * - 링크 입력 시: [유튜브 선택중] [N곡]
     * - 추출 시: [추출중] [N곡] / [저장중] [N곡]
     */
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

    /**
     * 안드로이드 Scoped Storage 권한을 고려한 안전한 파일 삭제
     */
    fun requestDeleteMp3(item: com.pipi.videomp3.model.Mp3Item) {
        lifecycleScope.launch(Dispatchers.IO) {
            var deleted = false
            try {
                val rows = contentResolver.delete(item.uri, null, null)
                deleted = rows > 0
            } catch (securityException: SecurityException) {
                withContext(Dispatchers.Main) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            val pi = MediaStore.createDeleteRequest(contentResolver, listOf(item.uri))
                            deleteRequestLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val recoverable = securityException as? RecoverableSecurityException
                        if (recoverable != null) {
                            deleteRequestLauncher.launch(IntentSenderRequest.Builder(recoverable.userAction.actionIntent.intentSender).build())
                        }
                    }
                }
                return@launch
            }

            if (deleted) {
                withContext(Dispatchers.Main) {
                    loadMp3Files()
                }
            }
        }
    }

    /**
     * 안드로이드 Content URI에서 미디어 관리 번호(ID)가 아닌 실제 원본 동영상 파일명을 역추적하여 가져옵니다.
     */
    private fun queryRealFileName(uri: Uri): String {
        var resultName: String? = null

        // 1. DocumentsContract 또는 MediaStore URI에서 Media ID 역추적
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

        // 2. URI 자체에서 OpenableColumns.DISPLAY_NAME 및 DATA 직접 조회
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

        // 3. 만약 여전히 null이거나 순수 숫자라면 '내영상_날짜_시간'으로 생성
        val finalClean = resultName?.substringBeforeLast(".") ?: ""
        return if (resultName.isNullOrBlank() || finalClean.isBlank() || finalClean.all { it.isDigit() }) {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date())
            "내영상_$timeStamp.mp4"
        } else {
            resultName!!
        }
    }
}
