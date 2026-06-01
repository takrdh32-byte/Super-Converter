package com.superconverter.utils

import android.net.Uri

data class ImageItem(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    var status: Status = Status.PENDING
) {
    enum class Status { PENDING, PROCESSING, DONE, ERROR }
}