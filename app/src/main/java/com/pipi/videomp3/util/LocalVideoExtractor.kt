package com.pipi.videomp3.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

object LocalVideoExtractor {

    /**
     * 로컬 동영상 파일에서 오디오 트랙을 100% 무손실로 추출하여 [Music/영상MP3]에 저장합니다.
     * 삼성 갤러리 [앨범] 등 가상 Uri 환경에서도 100% 정상 추출되도록 캐시 스트림 복사 파이프라인을 사용합니다.
     */
    suspend fun extractAudioFromLocalVideo(
        context: Context,
        videoUri: Uri,
        fileName: String,
        onProgress: (percent: Int, statusText: String) -> Unit
    ): Result<Uri> = withContext(Dispatchers.IO) {
        val tempInputFile = File(context.cacheDir, "input_vid_${System.currentTimeMillis()}.tmp")
        val tempOutputFile = File(context.cacheDir, "extracted_${System.currentTimeMillis()}.m4a")
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            withContext(Dispatchers.Main) {
                onProgress(5, "준비중")
            }

            // 1. 삼성 갤러리 [앨범] 등 가상 Provider Uri의 C++ 바인더 접근 실패(0초 오류)를 원천 차단하기 위해 임시 캐시로 스트림 복사
            context.contentResolver.openInputStream(videoUri)?.use { input ->
                tempInputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                return@withContext Result.failure(Exception("동영상 파일을 열 수 없습니다."))
            }

            if (tempInputFile.length() == 0L) {
                return@withContext Result.failure(Exception("동영상 데이터가 비어 있습니다."))
            }

            withContext(Dispatchers.Main) {
                onProgress(15, "분석중")
            }

            // 2. 물리 파일 경로로 MediaExtractor 연결 (100% Seek 가능 & 무손실)
            extractor.setDataSource(tempInputFile.absolutePath)

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || audioFormat == null) {
                return@withContext Result.failure(Exception("동영상에 오디오 트랙이 없습니다."))
            }

            extractor.selectTrack(audioTrackIndex)

            // 원본 오디오 길이(ms) 사전 측정
            var trackDurationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                audioFormat.getLong(MediaFormat.KEY_DURATION)
            } else 0L

            if (trackDurationUs <= 0L) {
                try {
                    val mmr = MediaMetadataRetriever()
                    mmr.setDataSource(tempInputFile.absolutePath)
                    val durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    trackDurationUs = (durStr?.toLongOrNull() ?: 0L) * 1000L
                    mmr.release()
                } catch (ignored: Exception) {}
            }

            val durationMs = if (trackDurationUs > 0) trackDurationUs / 1000L else 0L

            muxer = MediaMuxer(tempOutputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerAudioTrack = muxer.addTrack(audioFormat)
            muxer.start()

            val maxBufferSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                1024 * 1024
            }
            val buffer = ByteBuffer.allocate(maxBufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            withContext(Dispatchers.Main) {
                onProgress(40, "추출중")
            }

            var samplesCount = 0
            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) {
                    break
                }
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags

                muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
                samplesCount++
                extractor.advance()
            }

            if (samplesCount == 0) {
                return@withContext Result.failure(Exception("오디오 데이터를 읽지 못했습니다."))
            }

            muxer.stop()
            muxer.release()
            muxer = null

            extractor.release()

            withContext(Dispatchers.Main) {
                onProgress(85, "저장중")
            }

            val cleanTitle = fileName.substringBeforeLast(".")
            val savedUri = FileInputStream(tempOutputFile).use { input ->
                Mp3StorageHelper.saveStreamToMusicFolder(
                    context = context,
                    rawTitle = cleanTitle,
                    inputStream = input,
                    totalBytes = tempOutputFile.length(),
                    durationMs = durationMs,
                    mimeType = "audio/mp4",
                    extension = ".m4a",
                    onProgress = { onProgress(85 + (it * 15 / 100), "저장중") }
                )
            }

            tempOutputFile.delete()
            tempInputFile.delete()

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
            try { muxer?.release() } catch (ex: Exception) {}
            try { extractor.release() } catch (ex: Exception) {}
            tempOutputFile.delete()
            tempInputFile.delete()
            Result.failure(e)
        } finally {
            tempOutputFile.delete()
            tempInputFile.delete()
        }
    }
}
