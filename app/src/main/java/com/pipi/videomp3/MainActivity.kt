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
import android.widget.LinearLayout
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

    private lateinit var tvHeaderCount: TextView
    private lateinit var progressBar: ProgressBar

    private lateinit var btnTabYoutube: Button
    private lateinit var btnTabLocal: Button

    private lateinit var layoutYoutubeInput: LinearLayout
    private lateinit var etYoutubeUrl: EditText
    private lateinit var btnClearUrl: ImageButton
    private lateinit var btnPasteUrl: Button
    private lateinit var btnExtractYoutube: Button

    private lateinit var layoutLocalInput: LinearLayout
    private lateinit var btnSelectLocalVideo: Button
    private lateinit var tvSelectedVideoName: TextView
    private lateinit var btnExtractLocal: Button

    private lateinit var tvStatus: TextView
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
            tvSelectedVideoName.text = "선택된 영상: $selectedLocalVideoName"
            tvSelectedVideoName.setTextColor(resources.getColor(R.color.secondary, null))
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
        tvHeaderCount = findViewById(R.id.tvHeaderCount)
        progressBar = findViewById(R.id.progressBar)

        btnTabYoutube = findViewById(R.id.btnTabYoutube)
        btnTabLocal = findViewById(R.id.btnTabLocal)

        layoutYoutubeInput = findViewById(R.id.layoutYoutubeInput)
        etYoutubeUrl = findViewById(R.id.etYoutubeUrl)
        btnClearUrl = findViewById(R.id.btnClearUrl)
        btnPasteUrl = findViewById(R.id.btnPasteUrl)
        btnExtractYoutube = findViewById(R.id.btnExtractYoutube)

        layoutLocalInput = findViewById(R.id.layoutLocalInput)
        btnSelectLocalVideo = findViewById(R.id.btnSelectLocalVideo)
        tvSelectedVideoName = findViewById(R.id.tvSelectedVideoName)
        btnExtractLocal = findViewById(R.id.btnExtractLocal)

        tvStatus = findViewById(R.id.tvStatus)
        btnRefresh = findViewById(R.id.btnRefresh)
        rvMp3List = findViewById(R.id.rvMp3List)
        tvEmptyMessage = findViewById(R.id.tvEmptyMessage)

        mp3Adapter = Mp3Adapter(this) {
            updateHeaderCount()
        }

        rvMp3List.layoutManager = LinearLayoutManager(this)
        rvMp3List.adapter = mp3Adapter
    }

    private fun setupListeners() {
        // 탭 전환: 유튜브 링크
        btnTabYoutube.setOnClickListener {
            switchTab(isYoutube = true)
        }

        // 탭 전환: 로컬 동영상
        btnTabLocal.setOnClickListener {
            switchTab(isYoutube = false)
        }

        // 링크 지우기
        btnClearUrl.setOnClickListener {
            etYoutubeUrl.setText("")
        }

        // 링크 붙여넣기
        btnPasteUrl.setOnClickListener {
            pasteFromClipboard()
        }

        // 유튜브 MP3 추출 버튼
        btnExtractYoutube.setOnClickListener {
            startYoutubeExtraction()
        }

        // 로컬 비디오 선택 버튼
        btnSelectLocalVideo.setOnClickListener {
            selectVideoLauncher.launch("video/*")
        }

        // 로컬 비디오 MP3 추출 버튼
        btnExtractLocal.setOnClickListener {
            startLocalVideoExtraction()
        }

        // 목록 새로고침
        btnRefresh.setOnClickListener {
            loadMp3Files()
            Toast.makeText(this, "음악 목록을 새로고침했습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun switchTab(isYoutube: Boolean) {
        if (isYoutube) {
            btnTabYoutube.setBackgroundColor(resources.getColor(R.color.primary, null))
            btnTabYoutube.setTextColor(resources.getColor(R.color.on_surface, null))
            btnTabLocal.setBackgroundColor(resources.getColor(R.color.surface_variant, null))
            btnTabLocal.setTextColor(resources.getColor(R.color.on_surface_secondary, null))

            layoutYoutubeInput.visibility = View.VISIBLE
            layoutLocalInput.visibility = View.GONE
        } else {
            btnTabLocal.setBackgroundColor(resources.getColor(R.color.primary, null))
            btnTabLocal.setTextColor(resources.getColor(R.color.on_surface, null))
            btnTabYoutube.setBackgroundColor(resources.getColor(R.color.surface_variant, null))
            btnTabYoutube.setTextColor(resources.getColor(R.color.on_surface_secondary, null))

            layoutLocalInput.visibility = View.VISIBLE
            layoutYoutubeInput.visibility = View.GONE
        }
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString() ?: ""
            if (text.isNotBlank()) {
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
                etYoutubeUrl.setText(text.trim())
            }
        }
    }

    private fun startYoutubeExtraction() {
        if (isExtracting) {
            Toast.makeText(this, "현재 추출 작업이 진행 중입니다.", Toast.LENGTH_SHORT).show()
            return
        }

        val url = etYoutubeUrl.text.toString().trim()
        if (url.isBlank()) {
            Toast.makeText(this, "유튜브 링크를 입력해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            setExtractingState(true)

            val result = YouTubeExtractor.extractAndSaveMp3(
                context = this@MainActivity,
                youtubeUrl = url,
                onProgress = { percent, statusText ->
                    progressBar.progress = percent
                    tvStatus.text = statusText
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
        if (isExtracting) {
            Toast.makeText(this, "현재 추출 작업이 진행 중입니다.", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = selectedLocalVideoUri
        if (uri == null) {
            Toast.makeText(this, "먼저 동영상 파일을 선택해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            setExtractingState(true)

            val result = LocalVideoExtractor.extractAudioFromLocalVideo(
                context = this@MainActivity,
                videoUri = uri,
                fileName = selectedLocalVideoName,
                onProgress = { percent, statusText ->
                    progressBar.progress = percent
                    tvStatus.text = statusText
                }
            )

            setExtractingState(false)

            if (result.isSuccess) {
                Toast.makeText(this@MainActivity, "Music/영상MP3 폴더에 저장되었습니다! 🎵", Toast.LENGTH_LONG).show()
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
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = "음원 추출 준비 중..."
            btnExtractYoutube.isEnabled = false
            btnExtractLocal.isEnabled = false
        } else {
            progressBar.visibility = View.GONE
            tvStatus.visibility = View.GONE
            btnExtractYoutube.isEnabled = true
            btnExtractLocal.isEnabled = true
        }
    }

    private fun loadMp3Files() {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = Mp3StorageHelper.getAllExtractedMp3Files(this@MainActivity)
            withContext(Dispatchers.Main) {
                mp3Adapter.submitList(list)
                updateHeaderCount()
                tvEmptyMessage.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun updateHeaderCount() {
        val count = mp3Adapter.itemCount
        tvHeaderCount.text = "[추출 ${count}곡]"
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
