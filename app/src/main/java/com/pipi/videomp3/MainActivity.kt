package com.pipi.videomp3

import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
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
            selectedLocalVideoName = queryFileName(uri)
            etYoutubeUrl.setText("")
            updateStatusText()
            Toast.makeText(this, "동영상 선택 완료! '⚡ 추출 시작'을 누르면 변환됩니다.", Toast.LENGTH_SHORT).show()
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
        } else {
            Toast.makeText(this, "녹음 기능을 사용하려면 마이크 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    // 파일 읽기/저장 권한 요청 런처
    private val requestStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        loadMp3Files()
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

        // 녹음 버튼 초기 상태: ♤ (짙은 회색)
        btnRecord.text = "♤"
        btnRecord.setTextColor(Color.parseColor("#888888"))

        mp3Adapter = Mp3Adapter(this) {
            updateStatusText()
        }

        rvMp3List.layoutManager = LinearLayoutManager(this)
        rvMp3List.adapter = mp3Adapter
    }

    private fun setupListeners() {
        // [♤ / ♠ 녹음] 버튼 터치 -> 녹음 시작 / 중지
        btnRecord.setOnClickListener {
            if (isRecording) {
                stopVoiceRecording(save = true)
            } else {
                checkAndStartRecording()
            }
        }

        // [📁 파일] 버튼 클릭 -> 폰에서 동영상 선택
        btnFile.setOnClickListener {
            selectVideoLauncher.launch("video/*")
        }

        // 🔗 링크 아이콘 터치 -> 클립보드 내용 자동 붙여넣기
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
            override fun afterTextChanged(s: Editable?) {
                if (!isExtracting && !isRecording) {
                    updateStatusText()
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // [⚡ 추출 시작] 버튼 클릭 -> 파일 또는 유튜브 링크 자동 판별 추출
        btnStartExtract.setOnClickListener {
            startExtractionFlow()
        }

        // 목록 새로고침 버튼
        btnRefresh.setOnClickListener {
            loadMp3Files()
            Toast.makeText(this, "Music/영상MP3 목록을 새로고침했습니다.", Toast.LENGTH_SHORT).show()
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
        if (isExtracting) {
            Toast.makeText(this, "추출 작업 중에는 녹음할 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        RecordingService.startRecording(this)
    }

    private fun stopVoiceRecording(save: Boolean) {
        RecordingService.stopRecording(this, save = save)
    }

    private fun startExtractionFlow() {
        if (isExtracting) {
            Toast.makeText(this, "현재 추출 작업이 진행 중입니다.", Toast.LENGTH_SHORT).show()
            return
        }
        if (isRecording) {
            Toast.makeText(this, "녹음 중에는 추출할 수 없습니다. 먼저 녹음을 마쳐주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        // 1순위: 파일이 선택되어 있으면 로컬 동영상 음원 추출 실행!
        if (selectedLocalVideoUri != null) {
            startLocalVideoExtraction()
            return
        }

        // 2순위: 유튜브 링크가 입력되어 있으면 유튜브 추출 실행!
        val url = etYoutubeUrl.text.toString().trim()
        if (url.isNotBlank() && (url.contains("youtu.be") || url.contains("youtube.com") || url.startsWith("http"))) {
            startYoutubeExtraction(url)
            return
        }

        Toast.makeText(this, "유튜브 링크를 붙여넣거나 [파일] 버튼을 눌러 영상을 선택해 주세요.", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this@MainActivity, "Music/영상MP3 폴더에 저장되었습니다! 🎵", Toast.LENGTH_LONG).show()
                etYoutubeUrl.setText("")
                loadMp3Files()
            } else {
                val err = result.exceptionOrNull()?.message ?: "알 수 없는 오류"
                Toast.makeText(this@MainActivity, "추출 실패: $err", Toast.LENGTH_LONG).show()
            }
            updateStatusText()
        }
    }

    private fun startLocalVideoExtraction() {
        val uri = selectedLocalVideoUri ?: return

        lifecycleScope.launch {
            setExtractingState(true)

            val result = LocalVideoExtractor.extractAudioFromLocalVideo(
                context = this@MainActivity,
                videoUri = uri,
                fileName = selectedLocalVideoName,
                onProgress = { percent, statusText ->
                    val count = mp3Adapter.itemCount
                    tvHeaderStatus.text = "[$statusText] [${count}곡]"
                }
            )

            setExtractingState(false)

            if (result.isSuccess) {
                Toast.makeText(this@MainActivity, "Music/영상MP3 폴더에 저장되었습니다! 🎵", Toast.LENGTH_LONG).show()
                selectedLocalVideoUri = null
                selectedLocalVideoName = ""
                loadMp3Files()
            } else {
                val err = result.exceptionOrNull()?.message ?: "알 수 없는 오류"
                Toast.makeText(this@MainActivity, "추출 실패: $err", Toast.LENGTH_LONG).show()
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
                Toast.makeText(this, "링크를 붙여넣었습니다.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "클립보드에 복사된 내용이 없습니다.", Toast.LENGTH_SHORT).show()
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

    private fun queryFileName(uri: Uri): String {
        var name = "영상_${System.currentTimeMillis()}.mp4"
        val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    name = it.getString(index)
                }
            }
        }
        return name
    }
}
