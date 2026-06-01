package com.superconverter.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object FileUtils {

    fun saveToDownloads(
        context: Context,
        data: ByteArray,
        fileName: String,
        mimeType: String
    ): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    resolver.openOutputStream(it)?.use { os -> os.write(data) }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(it, values, null, null)
                }
                uri
            } else {
                @Suppress("DEPRECATION")
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                downloads.mkdirs()
                val file = File(downloads, fileName)
                FileOutputStream(file).use { it.write(data) }
                Uri.fromFile(file)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun saveToCacheAndGetUri(context: Context, data: ByteArray, fileName: String): Uri? {
        return try {
            val file = File(context.cacheDir, fileName)
            FileOutputStream(file).use { it.write(data) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun readBytes(input: InputStream): ByteArray = input.use { it.readBytes() }

    fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1_024 -> "%.1f KB".format(bytes / 1_024.0)
            else -> "$bytes B"
        }
    }

    fun openFile(context: Context, uri: Uri, mimeType: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Open with"))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}