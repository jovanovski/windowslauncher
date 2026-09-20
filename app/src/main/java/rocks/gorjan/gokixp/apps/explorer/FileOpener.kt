package rocks.gorjan.gokixp.apps.explorer

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import rocks.gorjan.gokixp.MainActivity
import java.io.File

/**
 * What the shell does with a file when somebody taps it.
 *
 * The rules are the desktop's: a sound belongs to Winamp, a film to Media Player, a bitmap
 * to Paint because bitmaps are Paint's own files, a picture or a PDF to the Photo Viewer, and
 * anything else to whatever Android has for it. Shared by My Computer and My Briefcase so a
 * file opens the same way wherever it is looked at.
 */
object FileOpener {

    private const val TAG = "FileOpener"

    private val AUDIO = setOf("mp3", "wav", "ogg", "flac", "m4a", "aac", "wma")
    private val VIDEO = setOf("mp4", "avi", "mkv", "mov", "wmv", "flv", "webm", "3gp", "m4v")
    private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
    private val BITMAP = setOf("bmp", "dib")

    fun isAudio(file: File): Boolean = file.extension.lowercase() in AUDIO
    fun isVideo(file: File): Boolean = file.extension.lowercase() in VIDEO
    fun isImage(file: File): Boolean = file.extension.lowercase() in IMAGE
    fun isPdf(file: File): Boolean = file.extension.lowercase() == "pdf"

    /** Bitmaps open in Paint rather than the viewer, the way they always have. */
    fun isBitmap(file: File): Boolean = file.extension.lowercase() in BITMAP

    fun open(context: Context, file: File) {
        val mainActivity = context as? MainActivity
        when {
            isAudio(file) -> mainActivity?.openWinamp(file.absolutePath)
            isVideo(file) -> mainActivity?.openWmp(file.absolutePath)
            isBitmap(file) -> mainActivity?.openPaint(file.absolutePath)
            isImage(file) || isPdf(file) -> mainActivity?.openPhotoViewer(file.absolutePath)
            else -> openExternally(context, file)
        }
    }

    /** Hands the file to whatever else is installed, through the launcher's own provider. */
    fun openExternally(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType(file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else {
                Log.w(TAG, "No app found to open file: ${file.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening file: ${file.name}", e)
        }
    }

    fun mimeType(file: File): String = when (file.extension.lowercase()) {
        "txt" -> "text/plain"
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "webp" -> "image/webp"
        "mp4", "m4v" -> "video/mp4"
        "avi" -> "video/x-msvideo"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "wmv" -> "video/x-ms-wmv"
        "flv" -> "video/x-flv"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "wma" -> "audio/x-ms-wma"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        else -> "*/*"
    }
}
