package com.pipi.videomp3.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object YouTubeExtractor {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * 유튜브 URL에서 오디오를 추출하여 [Music/영상MP3] 폴더에 MP3로 저장합니다.
     */
    suspend fun extractAndSaveMp3(
        context: Context,
        youtubeUrl: String,
        onProgress: (percent: Int, statusText: String) -> Unit
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val normalizedUrl = normalizeYoutubeUrl(youtubeUrl)
            if (normalizedUrl.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("유효하지 않은 유튜브 링크입니다."))
            }

            withContext(Dispatchers.Main) {
                onProgress(10, "준비중")
            }

            // 1. Samplecut API 호출 (무료 초고속 320kbps MP3 변환)
            val jsonBody = JSONObject().apply {
                put("url", normalizedUrl)
                put("format", "mp3")
            }.toString()

            val apiRequest = Request.Builder()
                .url("https://api.samplecut.app/v1/downloads")
                .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val apiResponse = client.newCall(apiRequest).execute()
            if (!apiResponse.isSuccessful) {
                val errBody = apiResponse.body?.string() ?: ""
                return@withContext Result.failure(Exception("음원 추출 서버 응답 실패 ($errBody)"))
            }

            val respJson = JSONObject(apiResponse.body?.string() ?: "{}")
            val downloadUrl = respJson.optString("download_url")
            val videoTitle = respJson.optString("title", "유튜브음악_${System.currentTimeMillis()}")

            if (downloadUrl.isBlank()) {
                return@withContext Result.failure(Exception("다운로드 링크 생성 실패"))
            }

            withContext(Dispatchers.Main) {
                onProgress(30, "다운로드중 30%")
            }

            // 2. 변환된 MP3 스트림 다운로드
            val downloadRequest = Request.Builder()
                .url(downloadUrl)
                .build()

            val streamResponse = client.newCall(downloadRequest).execute()
            if (!streamResponse.isSuccessful || streamResponse.body == null) {
                return@withContext Result.failure(Exception("MP3 파일 스트림 수신 실패"))
            }

            val body = streamResponse.body!!
            val contentLength = body.contentLength()

            val savedUri = Mp3StorageHelper.saveStreamToMusicFolder(
                context = context,
                rawTitle = videoTitle,
                inputStream = body.byteStream(),
                totalBytes = contentLength,
                mimeType = "audio/mpeg",
                extension = ".mp3",
                onProgress = { pct ->
                    val scaledPct = 30 + (pct * 70 / 100)
                    onProgress(scaledPct, "다운로드중 ${scaledPct}%")
                }
            )

            if (savedUri != null) {
                withContext(Dispatchers.Main) {
                    onProgress(100, "완료")
                }
                Result.success(savedUri)
            } else {
                Result.failure(Exception("Music/영상MP3 폴더에 저장 실패"))
            }

        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private fun normalizeYoutubeUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }
        return "https://$trimmed"
    }
}
