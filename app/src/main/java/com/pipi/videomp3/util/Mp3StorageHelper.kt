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
     * 안전한 파일명으로 정제합니다 (특수문자 제거)
     */
    fun sanitizeFileName(title: String): String {
        val clean = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        val finalName = if (clean.isBlank()) "추출음악_${System.currentTimeMillis()}" else clean
        return if (finalName.endsWith(".mp3", ignoreCase = true)) finalName else "$finalName.mp3"
    }

    /**
     * 입력 스트림(다운로드 또는 변환)을 스마트폰의 [Music/영상MP3] 폴더에 MP3 파일로 저장합니다.
     */
    fun saveStreamToMusicFolder(
        context: Context,
        rawTitle: String,
        inputStream: InputStream,
        totalBytes: Long,
        onProgress: (Int) -> Unit
    ): Uri? {
        val fileName = sanitizeFileName(rawTitle)
        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$SUB_FOLDER")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }

            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return null

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
                resolver.update(uri, contentValues, null, null)

                return uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                e.printStackTrace()
                return null
            }
        } else {
            // Android 9 이하 레거시 저장
            val musicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), SUB_FOLDER)
            if (!musicDir.exists()) musicDir.mkdirs()

            val targetFile = File(musicDir, fileName)
            try {
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
                    arrayOf("audio/mpeg")
                ) { _, uri -> scannedUri = uri }

                return scannedUri ?: Uri.fromFile(targetFile)
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
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

                    list.add(
                        Mp3Item(
                            id = id,
                            title = name.removeSuffix(".mp3").removeSuffix(".m4a"),
                            uri = uri,
                            durationMs = duration,
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
