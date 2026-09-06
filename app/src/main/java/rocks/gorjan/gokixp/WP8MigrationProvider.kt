package rocks.gorjan.gokixp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

/**
 * Hands a Windows Phone 8.1 setup to the launcher that inherited it.
 *
 * Reads only the copy [WP8Migration] set aside - never the live preferences - so what the
 * phone launcher receives is the arrangement as it stood before this app swept its own
 * retired programs out of it.
 *
 * Guarded by a signature-level permission, so the only app that can read it is one signed
 * with the same key. That is the whole of the access control: there is no user consent
 * prompt here, because the two apps are the same author's and the data never leaves the
 * device.
 *
 * Two things worth knowing:
 *
 * - It takes the copy itself if nobody has yet. A provider can be the first thing in this
 *   process to run - the phone launcher may be asking before the desktop has been opened
 *   even once since it updated - and without this the answer would be an empty directory.
 *   [WP8Migration.captureIfNeeded] is idempotent, so calling it here costs nothing when
 *   the copy already exists.
 * - It serves files, not rows. The snapshot is a JSON file plus a directory of icons and a
 *   background image, and [query] exists only to list what is there so the reader knows
 *   what to ask for.
 */
class WP8MigrationProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    /**
     * Lists what the snapshot holds: one row per file, with its path relative to the
     * snapshot root and its size.
     */
    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        val root = snapshotRoot() ?: return null
        val cursor = MatrixCursor(arrayOf(COLUMN_PATH, COLUMN_SIZE))
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            cursor.addRow(arrayOf(file.relativeTo(root).path, file.length()))
        }
        return cursor
    }

    /** Opens one file from the snapshot, read-only. */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("This provider is read-only")
        val root = snapshotRoot() ?: return null

        val relative = uri.path?.trimStart('/').orEmpty()
        if (relative.isEmpty()) return null

        // A path from another process is not to be trusted with "..". Resolved against the
        // snapshot root and checked to still be inside it before anything is opened.
        val target = File(root, relative).canonicalFile
        if (!target.path.startsWith(root.canonicalFile.path + File.separator)) {
            throw SecurityException("Path escapes the snapshot: $relative")
        }
        if (!target.isFile) return null

        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /**
     * The snapshot directory, taking the copy first if it has not been taken.
     *
     * Null when this phone was never running the phone theme - there is nothing to hand
     * over, and the reader should treat that as "no setup to import" rather than an error.
     */
    private fun snapshotRoot(): File? {
        val context = context ?: return null
        return try {
            if (!WP8Migration.captureIfNeeded(context)) return null
            File(context.filesDir, WP8Migration.DIR).takeIf { it.isDirectory }
        } catch (e: Exception) {
            Log.w(TAG, "Could not prepare the Windows Phone snapshot", e)
            null
        }
    }

    override fun getType(uri: Uri): String? = "application/octet-stream"

    // Read-only: the phone launcher takes a copy, it does not edit this one.
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?) = 0
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0

    companion object {
        private const val TAG = "WP8MigrationProvider"

        const val AUTHORITY = "rocks.gorjan.gokixp.migration"

        /** Columns [query] returns: a path relative to the snapshot root, and a size. */
        const val COLUMN_PATH = "path"
        const val COLUMN_SIZE = "size"
    }
}
