package com.pipi.videomp3.model

import android.net.Uri

data class Mp3Item(
    val id: Long,
    val title: String,
    val uri: Uri,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long
)
