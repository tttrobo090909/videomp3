package com.pipi.videomp3.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
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
     */
    suspend fun extractAudioFromLocalVideo(
        context: Context,
        videoUri: Uri,
        fileName: String,
        onProgress: (percent: Int, statusText: String) -> Unit
    ): Result<Uri> = withContext(Dispatchers.IO) {
        val tempOutputFile = File(context.cacheDir, "extracted_${System.currentTimeMillis()}.m4a")
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            withContext(Dispatchers.Main) {
                onProgress(10, "동영상 오디오 트랙 분석 중...")
            }

            val pfd = context.contentResolver.openFileDescriptor(videoUri, "r")
            if (pfd != null) {
                extractor.setDataSource(pfd.fileDescriptor)
                pfd.close()
            } else {
                extractor.setDataSource(context, videoUri, null)
            }

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
                onProgress(40, "초고속 오디오 트랙 분리 추출 중...")
            }

            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) {
                    break
                }
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags

                muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
                extractor.advance()
            }

            muxer.stop()
            muxer.release()
            muxer = null

            extractor.release()

            withContext(Dispatchers.Main) {
                onProgress(85, "Music/영상MP3 폴더에 저장 중...")
            }

            val cleanTitle = fileName.substringBeforeLast(".")
            val savedUri = FileInputStream(tempOutputFile).use { input ->
                Mp3StorageHelper.saveStreamToMusicFolder(
                    context = context,
                    rawTitle = cleanTitle,
                    inputStream = input,
                    totalBytes = tempOutputFile.length(),
                    onProgress = { onProgress(85 + (it * 15 / 100), "저장 중...") }
                )
            }

            tempOutputFile.delete()

            if (savedUri != null) {
                withContext(Dispatchers.Main) {
                    onProgress(100, "추출 완료! 🎉")
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
            Result.failure(e)
        }
    }
}
