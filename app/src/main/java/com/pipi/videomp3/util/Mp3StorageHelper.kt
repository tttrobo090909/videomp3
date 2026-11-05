package com.pipi.videomp3.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
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
     * 입력 스트림(다운로드 또는 변환)을 스마트폰의 [Music/영상MP3] 폴더에 파일로 저장합니다.
     * MediaStore 저장을 1차 시도하고, 실패 시 직접 파일 시스템 및 MediaScanner를 통한 2차 안전 저장을 수행합니다.
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
        val baseFileName = sanitizeFileName(rawTitle, extension)
        val resolver = context.contentResolver

        // 1차: MediaStore Scoped Storage 시도 (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var fileName = baseFileName
            val contentValues = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$SUB_FOLDER")
                if (durationMs > 0) {
                    put(MediaStore.Audio.Media.DURATION, durationMs)
                }
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }

            var uri = try {
                resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
            } catch (e: Exception) {
                null
            }

            // 중복 파일명 등으로 실패할 경우 타임스탬프를 덧붙여 재시도
            if (uri == null) {
                val timeStamp = System.currentTimeMillis() % 100000
                fileName = sanitizeFileName("${rawTitle}_$timeStamp", extension)
                contentValues.put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                uri = try {
                    resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
                } catch (e: Exception) {
                    null
                }
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

                    contentValues.clear()
                    contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
                    if (durationMs > 0) {
                        contentValues.put(MediaStore.Audio.Media.DURATION, durationMs)
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

            var targetFile = File(musicDir, baseFileName)
            if (targetFile.exists()) {
                val timeStamp = System.currentTimeMillis() % 100000
                targetFile = File(musicDir, sanitizeFileName("${rawTitle}_$timeStamp", extension))
            }

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
     * 스마트폰의 다른 음악이나 통화녹음 등은 일절 조회되지 않습니다.
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
                    if (finalDuration <= 0L) {
                        try {
                            val mmr = android.media.MediaMetadataRetriever()
                            mmr.setDataSource(context, uri)
                            val durStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                            finalDuration = durStr?.toLongOrNull() ?: 0L
                            mmr.release()
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
