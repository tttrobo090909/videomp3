package com.pipi.videomp3.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.pipi.videomp3.model.Mp3Item
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object Mp3StorageHelper {

    private const val SUB_FOLDER = "영상MP3"

    /**
     * 안전한 파일명으로 정제합니다 (특수문자 제거 및 지정 확장자 적용)
     */
    fun sanitizeFileName(title: String, extension: String = ".mp3"): String {
        val clean = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        val ext = if (extension.startsWith(".")) extension else ".$extension"
        val baseName = clean.removeSuffix(".mp3").removeSuffix(".m4a")
        val finalBase = if (baseName.isBlank()) "추출음악_${System.currentTimeMillis()}" else baseName
        return "$finalBase$ext"
    }

    /**
     * 중복 파일명 방지를 위한 다음 순번 파일명 탐색 (_1, _2, _3 ...)
     */
    private fun getNextSequentialFileName(context: Context, rawTitle: String, extension: String): String {
        val ext = if (extension.startsWith(".")) extension else ".$extension"
        val cleanBase = rawTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            .removeSuffix(".mp3").removeSuffix(".m4a")
        val baseName = if (cleanBase.isBlank()) "추출음악" else cleanBase

        // 1. 현재 폴더에 존재하는 파일명 목록 조회
        val existingNames = mutableSetOf<String>()
        try {
            val projection = arrayOf(MediaStore.Audio.Media.DISPLAY_NAME)
            val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Audio.Media.DATA} LIKE ?"
            } else {
                "${MediaStore.Audio.Media.DATA} LIKE ?"
            }
            val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf("%Music/영상MP3%", "%/Music/영상MP3/%")
            } else {
                arrayOf("%/Music/영상MP3/%")
            }

            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                if (nameCol != -1) {
                    while (cursor.moveToNext()) {
                        cursor.getString(nameCol)?.let { existingNames.add(it) }
                    }
                }
            }
        } catch (ignored: Exception) {}

        // 로컬 디렉터리 파일 목록도 체크
        try {
            val musicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), SUB_FOLDER)
            if (musicDir.exists()) {
                musicDir.listFiles()?.forEach { file ->
                    existingNames.add(file.name)
                }
            }
        } catch (ignored: Exception) {}

        val candidateOriginal = "$baseName$ext"
        if (!existingNames.contains(candidateOriginal)) {
            return candidateOriginal
        }

        // _1, _2, _3 ... 순번 탐색
        var index = 1
        while (true) {
            val candidate = "${baseName}_$index$ext"
            if (!existingNames.contains(candidate)) {
                return candidate
            }
            index++
        }
    }

    /**
     * 입력 스트림(다운로드 또는 변환)을 스마트폰의 [Music/영상MP3] 폴더에 파일로 저장합니다.
     */
    fun saveStreamToMusicFolder(
        context: Context,
        rawTitle: String,
        inputStream: InputStream,
        totalBytes: Long,
        durationMs: Long = 0L,
        mimeType: String = "audio/mpeg",
        extension: String = ".mp3",
        onProgress: (Int) -> Unit
    ): Uri? {
        val resolver = context.contentResolver
        val ext = if (extension.startsWith(".")) extension else ".$extension"
        val fileNameToUse = getNextSequentialFileName(context, rawTitle, ext)

        // 1차: MediaStore Scoped Storage 시도 (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileNameToUse)
                put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$SUB_FOLDER")
                if (durationMs > 0) {
                    put(MediaStore.Audio.Media.DURATION, durationMs)
                }
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }

            val uri = try {
                resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
            } catch (e: Exception) {
                null
            }

            if (uri != null) {
                try {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        val buffer = ByteArray(8 * 1024)
                        var bytesRead: Int
                        var totalWritten = 0L

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalWritten += bytesRead
                            if (totalBytes > 0) {
                                val percent = ((totalWritten * 100) / totalBytes).toInt().coerceIn(0, 100)
                                onProgress(percent)
                            }
                        }
                        outputStream.flush()
                    }

                    // 정확한 재생시간 측정 (파라미터 durationMs가 없으면 저장된 Uri에서 pfd로 직접 측정)
                    var finalDuration = durationMs
                    if (finalDuration <= 0L) {
                        try {
                            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                                val mmr = MediaMetadataRetriever()
                                mmr.setDataSource(pfd.fileDescriptor)
                                val durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                finalDuration = durStr?.toLongOrNull() ?: 0L
                                mmr.release()
                            }
                        } catch (ignored: Exception) {}
                    }

                    contentValues.clear()
                    contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
                    if (finalDuration > 0) {
                        contentValues.put(MediaStore.Audio.Media.DURATION, finalDuration)
                    }
                    resolver.update(uri, contentValues, null, null)

                    return uri
                } catch (e: Exception) {
                    try { resolver.delete(uri, null, null) } catch (ignored: Exception) {}
                    e.printStackTrace()
                }
            }
        }

        // 2차 대체 저장: 직접 파일 시스템 저장 후 MediaScanner 연동
        try {
            val musicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), SUB_FOLDER)
            if (!musicDir.exists()) musicDir.mkdirs()

            val targetFile = File(musicDir, fileNameToUse)

            FileOutputStream(targetFile).use { outputStream ->
                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                var totalWritten = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalWritten += bytesRead
                    if (totalBytes > 0) {
                        val percent = ((totalWritten * 100) / totalBytes).toInt().coerceIn(0, 100)
                        onProgress(percent)
                    }
                }
                outputStream.flush()
            }

            var scannedUri: Uri? = null
            MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf(mimeType)
            ) { _, u -> scannedUri = u }

            return scannedUri ?: Uri.fromFile(targetFile)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * 오직 [Music/영상MP3] 폴더 안에 저장된 파일만 엄격하게 필터링하여 조회합니다 (최신순).
     */
    fun getAllExtractedMp3Files(context: Context): List<Mp3Item> {
        val list = mutableListOf<Mp3Item>()
        val projectionList = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATA
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projectionList.add(MediaStore.Audio.Media.RELATIVE_PATH)
        }

        val projection = projectionList.toTypedArray()
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        val selection: String
        val selectionArgs: Array<String>

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "(${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Audio.Media.DATA} LIKE ?) AND (${MediaStore.Audio.Media.DISPLAY_NAME} LIKE '%.mp3' OR ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE '%.m4a')"
            selectionArgs = arrayOf("%Music/영상MP3%", "%/Music/영상MP3/%")
        } else {
            selection = "${MediaStore.Audio.Media.DATA} LIKE ? AND (${MediaStore.Audio.Media.DISPLAY_NAME} LIKE '%.mp3' OR ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE '%.m4a')"
            selectionArgs = arrayOf("%/Music/영상MP3/%")
        }

        try {
            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )

            cursor?.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val durCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dateCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val dataCol = it.getColumnIndex(MediaStore.Audio.Media.DATA)
                val relCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    it.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                } else -1

                while (it.moveToNext()) {
                    val id = it.getLong(idCol)
                    val name = it.getString(nameCol) ?: continue
                    val duration = it.getLong(durCol)
                    val size = it.getLong(sizeCol)
                    val dateAdded = it.getLong(dateCol)
                    val dataPath = if (dataCol != -1) it.getString(dataCol) ?: "" else ""
                    val relPath = if (relCol != -1) it.getString(relCol) ?: "" else ""

                    // 2중 검증: 경로에 반드시 '영상MP3'가 포함되어 있는지 확인
                    val isInsideSubFolder = relPath.contains(SUB_FOLDER) || dataPath.contains("/$SUB_FOLDER/")
                    if (!isInsideSubFolder) continue

                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

                    var finalDuration = duration
                    // duration이 0이거나 미등록 상태일 때 openFileDescriptor를 통해 정확한 실제 재생시간 직접 파싱
                    if (finalDuration <= 0L) {
                        try {
                            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                                val mmr = MediaMetadataRetriever()
                                mmr.setDataSource(pfd.fileDescriptor)
                                val durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                finalDuration = durStr?.toLongOrNull() ?: 0L
                                mmr.release()
                            }
                        } catch (ignored: Exception) {}
                    }

                    // 파일 직접 경로가 유효한 경우 3차 폴백
                    if (finalDuration <= 0L && dataPath.isNotBlank()) {
                        try {
                            val f = File(dataPath)
                            if (f.exists()) {
                                val mmr = MediaMetadataRetriever()
                                mmr.setDataSource(f.absolutePath)
                                val durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                finalDuration = durStr?.toLongOrNull() ?: 0L
                                mmr.release()
                            }
                        } catch (ignored: Exception) {}
                    }

                    list.add(
                        Mp3Item(
                            id = id,
                            title = name.removeSuffix(".mp3").removeSuffix(".m4a"),
                            uri = uri,
                            durationMs = finalDuration,
                            sizeBytes = size,
                            dateAddedSec = dateAdded
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return list
    }

    /**
     * 특정 MP3 파일 삭제
     */
    fun deleteMp3File(context: Context, item: Mp3Item): Boolean {
        return try {
            val rows = context.contentResolver.delete(item.uri, null, null)
            rows > 0
        } catch (e: Exception) {
            false
        }
    }
}
