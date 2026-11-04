package com.pipi.videomp3

import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
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

class MainActivity : AppCompatActivity() {

    private lateinit var tvHeaderStatus: TextView
    private lateinit var progressBar: ProgressBar

    private lateinit var btnFile: Button
    private lateinit var etYoutubeUrl: EditText
    private lateinit var btnPasteUrl: ImageButton
    private lateinit var btnClearUrl: ImageButton
    private lateinit var btnStartExtract: Button

    private lateinit var tvSubStatus: TextView
    private lateinit var btnRefresh: ImageButton
    private lateinit var rvMp3List: RecyclerView
    private lateinit var tvEmptyMessage: TextView

    private lateinit var mp3Adapter: Mp3Adapter
    private var isExtracting = false
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
            Toast.makeText(this, "동영상 파일이 선택되었습니다. '추출 시작'을 눌러주세요.", Toast.LENGTH_SHORT).show()
        }
    }

    // 권한 요청 런처
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        loadMp3Files()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        checkPermissions()
        loadMp3Files()
    }

    override fun onResume() {
        super.onResume()
        checkClipboardForYoutubeUrl()
    }

    override fun onDestroy() {
        super.onDestroy()
        mp3Adapter.stopPlayback()
    }

    private fun initViews() {
        tvHeaderStatus = findViewById(R.id.tvHeaderStatus)
        progressBar = findViewById(R.id.progressBar)

        btnFile = findViewById(R.id.btnFile)
        etYoutubeUrl = findViewById(R.id.etYoutubeUrl)
        btnPasteUrl = findViewById(R.id.btnPasteUrl)
        btnClearUrl = findViewById(R.id.btnClearUrl)
        btnStartExtract = findViewById(R.id.btnStartExtract)

        tvSubStatus = findViewById(R.id.tvSubStatus)
        btnRefresh = findViewById(R.id.btnRefresh)
        rvMp3List = findViewById(R.id.rvMp3List)
        tvEmptyMessage = findViewById(R.id.tvEmptyMessage)

        mp3Adapter = Mp3Adapter(this) {
            updateHeaderStatus(isExtracting = false, percent = 0)
        }

        rvMp3List.layoutManager = LinearLayoutManager(this)
        rvMp3List.adapter = mp3Adapter
    }

    private fun setupListeners() {
        // [📁 파일] 버튼 클릭 -> 폰에서 동영상 선택
        btnFile.setOnClickListener {
            selectVideoLauncher.launch("video/*")
        }

        // 링크 붙여넣기
        btnPasteUrl.setOnClickListener {
            pasteFromClipboard()
        }

        // 링크 지우기
        btnClearUrl.setOnClickListener {
            etYoutubeUrl.setText("")
            selectedLocalVideoUri = null
            selectedLocalVideoName = ""
            tvSubStatus.visibility = View.GONE
        }

        // [⚡ 추출 시작] 버튼 클릭 -> 유튜브 또는 파일 판별하여 추출
        btnStartExtract.setOnClickListener {
            startExtractionFlow()
        }

        // 목록 새로고침
        btnRefresh.setOnClickListener {
            loadMp3Files()
            Toast.makeText(this, "Music/영상MP3 목록을 새로고침했습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startExtractionFlow() {
        if (isExtracting) {
            Toast.makeText(this, "현재 추출 작업이 진행 중입니다.", Toast.LENGTH_SHORT).show()
            return
        }

        val url = etYoutubeUrl.text.toString().trim()

        if (url.isNotBlank()) {
            // 1순위: 유튜브 링크 추출
            startYoutubeExtraction(url)
        } else if (selectedLocalVideoUri != null) {
            // 2순위: 로컬 동영상 파일 오디오 추출
            startLocalVideoExtraction()
        } else {
            Toast.makeText(this, "유튜브 링크를 입력하거나 [파일] 버튼으로 영상을 선택해 주세요.", Toast.LENGTH_SHORT).show()
        }
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
                    updateHeaderStatus(isExtracting = true, percent = percent)
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
                    updateHeaderStatus(isExtracting = true, percent = percent)
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
        } else {
            progressBar.visibility = View.GONE
            btnFile.isEnabled = true
            btnStartExtract.isEnabled = true
            updateHeaderStatus(isExtracting = false, percent = 0)
        }
    }

    private fun loadMp3Files() {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = Mp3StorageHelper.getAllExtractedMp3Files(this@MainActivity)
            withContext(Dispatchers.Main) {
                mp3Adapter.submitList(list)
                updateHeaderStatus(isExtracting = isExtracting, percent = progressBar.progress)
                tvEmptyMessage.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    /**
     * 최상단 우측에 [완료/추출중] [N곡] 상태 표시
     */
    private fun updateHeaderStatus(isExtracting: Boolean, percent: Int) {
        val count = mp3Adapter.itemCount
        if (isExtracting) {
            tvHeaderStatus.text = "[추출 중 ${percent}%] [${count}곡]"
            tvHeaderStatus.setTextColor(resources.getColor(R.color.secondary, null))
        } else {
            tvHeaderStatus.text = "[완료] [${count}곡]"
            tvHeaderStatus.setTextColor(resources.getColor(R.color.secondary, null))
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
                Toast.makeText(this, "링크를 붙여넣었습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkClipboardForYoutubeUrl() {
        if (etYoutubeUrl.text.isNotBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString() ?: ""
            if (text.contains("youtu.be") || text.contains("youtube.com")) {
                selectedLocalVideoUri = null
                selectedLocalVideoName = ""
                tvSubStatus.visibility = View.GONE
                etYoutubeUrl.setText(text.trim())
            }
        }
    }

    private fun checkPermissions() {
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
            requestPermissionLauncher.launch(needed.toTypedArray())
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
