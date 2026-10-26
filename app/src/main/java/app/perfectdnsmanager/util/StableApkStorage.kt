package app.perfectdnsmanager.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Public Downloads survives uninstall; never export an unverified APK here. */
object StableApkStorage {
    fun save(context: Context, verifiedApk: File): Uri {
        require(verifiedApk.isFile && verifiedApk.length() > 0)
        val name = "PerfectDNSManager-stable-${System.currentTimeMillis()}.apk"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/PerfectDNSManager")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Cannot create stable APK")
            try {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    verifiedApk.inputStream().use { it.copyTo(output) }
                } ?: error("Cannot write stable APK")
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                check(resolver.update(uri, values, null, null) == 1)
                return uri
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        }
        // Android <= 9 requires WRITE_EXTERNAL_STORAGE, requested by the caller.
        @Suppress("DEPRECATION")
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PerfectDNSManager")
        check(directory.isDirectory || directory.mkdirs())
        val destination = File(directory, name)
        try {
            verifiedApk.inputStream().use { input -> destination.outputStream().use { input.copyTo(it) } }
            return Uri.fromFile(destination)
        } catch (e: Exception) { destination.delete(); throw e }
    }
}
