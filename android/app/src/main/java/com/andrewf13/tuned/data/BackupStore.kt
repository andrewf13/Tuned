package com.andrewf13.tuned.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BackupFile(val name: String, val modifiedMs: Long, val uri: Uri)

/**
 * Backup files live in the device's shared Download/Tuned folder, so they survive
 * clearing or uninstalling the app and can be copied off with a file manager.
 */
class BackupStore(private val context: Context) {
    val folderLabel = "Download/Tuned"
    private val legacyDir get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Tuned")

    fun newName(): String = "tuned-backup-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date()) + ".tunedbak"

    /** Android 9 and older need the storage permission to write to Download. */
    val needsWritePermission get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    /** Up to Android 12, the read permission lets us also find backups made before a reinstall. */
    val canAskReadPermission get() = Build.VERSION.SDK_INT <= 32

    suspend fun save(name: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Tuned")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Couldn't create the backup file")
            context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        } else {
            legacyDir.mkdirs()
            File(legacyDir, name).writeBytes(bytes)
        }
        "$folderLabel/$name"
    }

    suspend fun list(): List<BackupFile> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, BackupFile>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val proj = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.DATE_MODIFIED)
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj,
                    "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?", arrayOf("%.tunedbak"), null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val name = c.getString(1)
                        val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                        found[name] = BackupFile(name, c.getLong(2) * 1000, uri)
                    }
                }
            }
        }
        // Files from before a reinstall (or Android 9): readable directly where permitted.
        runCatching {
            legacyDir.listFiles { f -> f.name.endsWith(".tunedbak") }?.forEach { f ->
                if (f.canRead() && f.name !in found) found[f.name] = BackupFile(f.name, f.lastModified(), Uri.fromFile(f))
            }
        }
        found.values.sortedByDescending { it.modifiedMs }
    }

    suspend fun read(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    }
}
