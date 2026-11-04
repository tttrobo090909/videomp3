package com.pipi.videomp3

import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pipi.videomp3.adapter.Mp3Adapter
import com.pipi.videomp3.util.LocalVideoExtractor
import com.pipi.videomp3.util.Mp3StorageHelper
import com.pipi.videomp3.util.YouTubeExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var btnRecord: ImageButton
    private lateinit var tvHeaderStatus: TextView
    private lateinit var progressBar: ProgressBar

    private lateinit var btnFile: Button
    private lateinit var btnLinkPaste: TextView
    private lateinit var etYoutubeUrl: EditText
    private lateinit var btnClearUrl: ImageButton
    private lateinit var btnStartExtract: Button

    private lateinit var tvSubStatus: TextView
    private lateinit var btnRefresh: ImageButton
    private lateinit var rvMp3List: RecyclerView
    private lateinit var tvEmptyMessage: TextView

    private lateinit var mp3Adapter: Mp3Adapter

    // 상태 변수들
    private var isExtracting = false
    private var isRecording = false
    private var mediaRecorder: MediaRecorder? = null
    private var tempRecordingFile: File? = null
    private var recordingSeconds = 0
    private val recordingHandler = Handler(Looper.getMainLooper())
    private var recordingTimerRunnable: Runnable? = null

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
            tvSubStatus.text = "선택된 파일: $selectedLocalVideoName"
            tvSubStatus.visibility = View.VISIBLE
            updateStatusText()
            Toast.makeText(this, "동영상 선택 완료! '추출 시작'을 누르면 바로 변환됩니다.", Toast.LENGTH_SHORT).show()
        }
    }

    // 마이크 권한 요청 런처
    private val requestRecordAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
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

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) {
            stopVoiceRecording(save = false)
        }
        mp3Adapter.stopPlayback()
    }

    private fun initViews() {
        btnRecord = findViewById(R.id.btnRecord)
        tvHeaderStatus = findViewById(R.id.tvHeaderStatus)
        progressBar = findViewById(R.id.progressBar)

        btnFile = findViewById(R.id.btnFile)
        btnLinkPaste = findViewById(R.id.btnLinkPaste)
        etYoutubeUrl = findViewById(R.id.etYoutubeUrl)
        btnClearUrl = findViewById(R.id.btnClearUrl)
        btnStartExtract = findViewById(R.id.btnStartExtract)

        tvSubStatus = findViewById(R.id.tvSubStatus)
        btnRefresh = findViewById(R.id.btnRefresh)
        rvMp3List = findViewById(R.id.rvMp3List)
        tvEmptyMessage = findViewById(R.id.tvEmptyMessage)

        // 마이크 버튼 초기 상태: 짙은 회색
        btnRecord.setColorFilter(Color.parseColor("#777777"))

        mp3Adapter = Mp3Adapter(this) {
            updateStatusText()
        }

        rvMp3List.layoutManager = LinearLayoutManager(this)
        rvMp3List.adapter = mp3Adapter
    }

    private fun setupListeners() {
        // [🎙️ 마이크 녹음] 버튼 클릭 -> 녹음 시작 / 중지
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
            tvSubStatus.visibility = View.GONE
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

        // [⚡ 추출 시작] 버튼 클릭
        btnStartExtract.setOnClickListener {
            startExtractionFlow()
        }

        // 목록 새로고침 (24dp 버튼)
        btnRefresh.setOnClickListener {
            loadMp3Files()
            Toast.makeText(this, "Music/영상MP3 목록을 새로고침했습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 마이크 녹음 권한 확인 후 녹음 시작
     */
    private fun checkAndStartRecording() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoiceRecording()
        } else {
            requestRecordAudioLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceRecording() {
        if (isExtracting) {
            Toast.makeText(this, "추출 작업 중에는 녹음할 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            tempRecordingFile = File(cacheDir, "rec_${System.currentTimeMillis()}.m4a")

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(192000)
                setAudioSamplingRate(44100)
                setOutputFile(tempRecordingFile!!.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            recordingSeconds = 0

            // 마이크 버튼 연두색(#81C784)으로 변경!
            btnRecord.setColorFilter(Color.parseColor("#81C784"))

            tvSubStatus.text = "🔴 음성 녹음 중... (마이크 버튼을 다시 누르면 저장됩니다)"
            tvSubStatus.visibility = View.VISIBLE

            recordingTimerRunnable = object : Runnable {
                override fun run() {
                    if (isRecording) {
                        recordingSeconds++
                        val m = recordingSeconds / 60
                        val s = recordingSeconds % 60
                        val timeStr = String.format(Locale.getDefault(), "%02d:%02d", m, s)
                        val count = mp3Adapter.itemCount
                        tvHeaderStatus.text = "[녹음중 $timeStr] [${count}곡]"
                        recordingHandler.postDelayed(this, 1000)
                    }
                }
            }
            recordingHandler.post(recordingTimerRunnable!!)

            Toast.makeText(this, "녹음이 시작되었습니다. 마이크 버튼을 다시 누르면 저장됩니다.", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "녹음을 시작하지 못했습니다: ${e.message}", Toast.LENGTH_SHORT).show()
            isRecording = false
            btnRecord.setColorFilter(Color.parseColor("#777777"))
        }
    }

    private fun stopVoiceRecording(save: Boolean) {
        recordingTimerRunnable?.let { recordingHandler.removeCallbacks(it) }
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaRecorder = null
        isRecording = false

        // 마이크 버튼 다시 짙은 회색(#777777)으로 복귀!
        btnRecord.setColorFilter(Color.parseColor("#777777"))
        tvSubStatus.visibility = View.GONE

        val temp = tempRecordingFile
        if (save && temp != null && temp.exists() && temp.length() > 0) {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date())
            val finalTitle = "내음성_$timeStamp"

            lifecycleScope.launch(Dispatchers.IO) {
                val savedUri = FileInputStream(temp).use { input ->
                    Mp3StorageHelper.saveStreamToMusicFolder(
                        context = this@MainActivity,
                        rawTitle = finalTitle,
                        inputStream = input,
                        totalBytes = temp.length(),
                        onProgress = {}
                    )
                }
                temp.delete()

                withContext(Dispatchers.Main) {
                    if (savedUri != null) {
                        Toast.makeText(this@MainActivity, "Music/영상MP3에 녹음이 저장되었습니다! 🎙️", Toast.LENGTH_LONG).show()
                        loadMp3Files()
                    } else {
                        Toast.makeText(this@MainActivity, "녹음 파일 저장에 실패했습니다.", Toast.LENGTH_SHORT).show()
                    }
                    updateStatusText()
                }
            }
        } else {
            temp?.delete()
            updateStatusText()
        }
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
                    progressBar.progress = percent
                    tvSubStatus.text = statusText
                    val count = mp3Adapter.itemCount
                    tvHeaderStatus.text = "[추출중 ${percent}%] [${count}곡]"
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
                    progressBar.progress = percent
                    tvSubStatus.text = statusText
                    val count = mp3Adapter.itemCount
                    tvHeaderStatus.text = "[추출중 ${percent}%] [${count}곡]"
                }
            )

            setExtractingState(false)

            if (result.isSuccess) {
                Toast.makeText(this@MainActivity, "Music/영상MP3 폴더에 저장되었습니다! 🎵", Toast.LENGTH_LONG).show()
                selectedLocalVideoUri = null
                selectedLocalVideoName = ""
                tvSubStatus.visibility = View.GONE
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
        if (extracting) {
            progressBar.visibility = View.VISIBLE
            progressBar.progress = 0
            tvSubStatus.visibility = View.VISIBLE
            btnFile.isEnabled = false
            btnStartExtract.isEnabled = false
            btnRecord.isEnabled = false
        } else {
            progressBar.visibility = View.GONE
            btnFile.isEnabled = true
            btnStartExtract.isEnabled = true
            btnRecord.isEnabled = true
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
     * - 추출 시: [추출중 N%] [N곡]
     */
    private fun updateStatusText() {
        val count = mp3Adapter.itemCount

        if (isRecording) {
            // 녹음 타이머가 별도 처리
            return
        }

        if (isExtracting) {
            val pct = progressBar.progress
            tvHeaderStatus.text = "[추출중 ${pct}%] [${count}곡]"
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
                tvSubStatus.visibility = View.GONE
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
