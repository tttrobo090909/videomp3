package com.pipi.videomp3.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.pipi.videomp3.MainActivity
import com.pipi.videomp3.R
import com.pipi.videomp3.util.Mp3StorageHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.pipi.videomp3.action.START_RECORDING"
        const val ACTION_STOP_AND_SAVE = "com.pipi.videomp3.action.STOP_AND_SAVE"
        const val ACTION_STOP_DISCARD = "com.pipi.videomp3.action.STOP_DISCARD"

        const val CHANNEL_ID = "videomp3_recording_channel"
        const val NOTIFICATION_ID = 2001

        var isServiceRunning: Boolean = false
            private set

        var recordingSeconds: Int = 0
            private set

        var stateListener: ((isRecording: Boolean, seconds: Int) -> Unit)? = null
        var onRecordingSaved: ((title: String, success: Boolean) -> Unit)? = null

        fun startRecording(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopRecording(context: Context, save: Boolean = true) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = if (save) ACTION_STOP_AND_SAVE else ACTION_STOP_DISCARD
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var mediaRecorder: MediaRecorder? = null
    private var tempRecordingFile: File? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!isServiceRunning) {
                    startRecordingInternal()
                }
            }
            ACTION_STOP_AND_SAVE -> {
                stopRecordingInternal(save = true)
            }
            ACTION_STOP_DISCARD -> {
                stopRecordingInternal(save = false)
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecordingInternal() {
        try {
            // 1. WakeLock 획득 (화면 꺼져도 CPU 유지)
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "영상MP3:RecordingWakeLock").apply {
                setReferenceCounted(false)
                acquire(60 * 60 * 1000L) // 최대 60분
            }

            // 2. 임시 파일 준비
            tempRecordingFile = File(cacheDir, "rec_bg_${System.currentTimeMillis()}.m4a")

            // 3. MediaRecorder 세팅
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
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

            isServiceRunning = true
            recordingSeconds = 0

            // 4. Foreground Service 시작
            val initialNotification = buildNotification("00:00")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }

            // 5. 타이머 가동
            timerRunnable = object : Runnable {
                override fun run() {
                    if (isServiceRunning) {
                        recordingSeconds++
                        val m = recordingSeconds / 60
                        val s = recordingSeconds % 60
                        val timeStr = String.format(Locale.getDefault(), "%02d:%02d", m, s)

                        // 알림 갱신 (1초마다)
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.notify(NOTIFICATION_ID, buildNotification(timeStr))

                        // 액티비티 콜백
                        stateListener?.invoke(true, recordingSeconds)

                        timerHandler.postDelayed(this, 1000)
                    }
                }
            }
            timerHandler.post(timerRunnable!!)
            stateListener?.invoke(true, 0)

            Toast.makeText(this, "백그라운드 녹음이 시작되었습니다. ♠ 버튼이나 알림창에서 저장할 수 있습니다.", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "녹음을 시작하지 못했습니다: ${e.message}", Toast.LENGTH_SHORT).show()
            cleanup()
            stopSelf()
        }
    }

    private fun stopRecordingInternal(save: Boolean) {
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
        timerRunnable = null

        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            mediaRecorder?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaRecorder = null

        isServiceRunning = false
        stateListener?.invoke(false, recordingSeconds)

        val temp = tempRecordingFile
        if (save && temp != null && temp.exists() && temp.length() > 0) {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date())
            val finalTitle = "내음성_$timeStamp"

            serviceScope.launch(Dispatchers.IO) {
                val savedUri = try {
                    FileInputStream(temp).use { input ->
                        Mp3StorageHelper.saveStreamToMusicFolder(
                            context = applicationContext,
                            rawTitle = finalTitle,
                            inputStream = input,
                            totalBytes = temp.length(),
                            mimeType = "audio/mp4",
                            extension = ".m4a",
                            onProgress = {}
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
                temp.delete()

                withContext(Dispatchers.Main) {
                    if (savedUri != null) {
                        Toast.makeText(applicationContext, "Music/영상MP3에 녹음이 저장되었습니다! 🎙️ ($finalTitle)", Toast.LENGTH_LONG).show()
                        onRecordingSaved?.invoke(finalTitle, true)
                    } else {
                        Toast.makeText(applicationContext, "녹음 파일 저장에 실패했습니다.", Toast.LENGTH_SHORT).show()
                        onRecordingSaved?.invoke(finalTitle, false)
                    }
                    cleanup()
                    stopSelf()
                }
            }
        } else {
            temp?.delete()
            cleanup()
            stopSelf()
        }
    }

    private fun cleanup() {
        isServiceRunning = false
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        wakeLock = null

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        cleanup()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "영상MP3 녹음 상태",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "마이크 백그라운드 녹음 상태를 표시합니다."
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(timeStr: String): Notification {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val mainPendingIntent = PendingIntent.getActivity(
            this,
            100,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_STOP_AND_SAVE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("영상MP3 음성 녹음 중")
            .setContentText("녹음 중... [$timeStr]")
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setContentIntent(mainPendingIntent)
            .addAction(android.R.drawable.ic_menu_save, "저장 및 종료 💾", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true)
            .build()
    }
}
