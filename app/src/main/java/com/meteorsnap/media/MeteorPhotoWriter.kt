package com.meteorsnap.media

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 成功保存流星叠加照片后的结果。 */
data class SavedPhoto(
    val uri: Uri,
    val displayName: String,
    val absolutePath: String?,
    val width: Int,
    val height: Int
)

/** 把最大亮度叠加图保存为 Pictures/MeteorSnap/流星照片_yyyyMMdd_HHmmss.jpg。 */
class MeteorPhotoWriter(context: Context) {
    private val appContext = context.applicationContext

    @Throws(IOException::class)
    fun write(
        bitmap: Bitmap,
        wallClockMillis: Long = System.currentTimeMillis()
    ): SavedPhoto {
        require(!bitmap.isRecycled) { "Bitmap 已被回收" }
        val displayName = buildDisplayName(wallClockMillis)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeToMediaStore(bitmap, displayName)
        } else {
            writeToLegacyPictures(bitmap, displayName)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeToMediaStore(bitmap: Bitmap, displayName: String): SavedPhoto {
        val resolver = appContext.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE_JPEG)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore 无法创建照片条目")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    throw IOException("JPEG 编码失败")
                }
            } ?: throw IOException("MediaStore 无法打开照片输出流")

            val published = resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null
            )
            if (published != 1) throw IOException("照片写入成功，但 MediaStore 发布失败")
            return SavedPhoto(uri, displayName, null, bitmap.width, bitmap.height)
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun writeToLegacyPictures(bitmap: Bitmap, displayName: String): SavedPhoto {
        if (appContext.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Android 9 及以下保存公共 Pictures 需要 WRITE_EXTERNAL_STORAGE")
        }
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
            throw IOException("共享存储当前不可写")
        }
        val album = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            ALBUM_NAME
        )
        if (!album.exists() && !album.mkdirs()) {
            throw IOException("无法创建目录：${album.absolutePath}")
        }
        val outputFile = uniqueFile(album, displayName)
        try {
            outputFile.outputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    throw IOException("JPEG 编码失败")
                }
            }
            MediaScannerConnection.scanFile(
                appContext,
                arrayOf(outputFile.absolutePath),
                arrayOf(MIME_TYPE_JPEG),
                null
            )
            return SavedPhoto(
                uri = Uri.fromFile(outputFile),
                displayName = outputFile.name,
                absolutePath = outputFile.absolutePath,
                width = bitmap.width,
                height = bitmap.height
            )
        } catch (error: Throwable) {
            runCatching { if (outputFile.exists()) outputFile.delete() }
            throw error
        }
    }

    private fun buildDisplayName(wallClockMillis: Long): String {
        val timestamp = SimpleDateFormat(FILE_TIME_PATTERN, Locale.CHINA)
            .format(Date(wallClockMillis))
        return "流星照片_$timestamp.jpg"
    }

    private fun uniqueFile(directory: File, requestedName: String): File {
        val direct = File(directory, requestedName)
        if (!direct.exists()) return direct

        val stem = requestedName.removeSuffix(".jpg")
        var suffix = 1
        while (true) {
            val candidate = File(directory, "${stem}_$suffix.jpg")
            if (!candidate.exists()) return candidate
            suffix++
        }
    }

    private companion object {
        const val ALBUM_NAME = "MeteorSnap"
        const val MIME_TYPE_JPEG = "image/jpeg"
        const val JPEG_QUALITY = 92
        const val FILE_TIME_PATTERN = "yyyyMMdd_HHmmss"
    }
}
