package rocks.gorjan.gokixp.apps.zune

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log

/**
 * The music on the phone, as Zune sees it.
 *
 * Lifted out of the player so that the car's media service reads the same library through
 * the same query rather than a second one written to match. Zune is a view inside the
 * launcher and cannot be asked anything while the launcher is not up, which is exactly the
 * situation the car is in - see car/ZuneCarMediaService.
 */
object ZuneLibrary {

    /** What MediaStore writes when a file has no tag, which is not a name to show anyone. */
    const val UNKNOWN_TAG = "<unknown>"

    fun clean(value: String?): String =
        value.orEmpty().takeUnless { it == UNKNOWN_TAG }.orEmpty()

    fun queryTracks(context: Context): List<ZuneTrack> {
        val out = mutableListOf<ZuneTrack>()
        val columns = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
            // Deprecated, and asked for anyway: the playlists Winamp wrote are lists of
            // paths, so matching them means knowing where each file is.
            @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA
        )
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                columns,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                @Suppress("DEPRECATION")
                val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    out.add(
                        ZuneTrack(
                            id = id,
                            uri = ContentUris.withAppendedId(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                            path = cursor.getString(pathCol).orEmpty(),
                            title = cursor.getString(titleCol).orEmpty().ifBlank { "unknown" },
                            artist = clean(cursor.getString(artistCol)),
                            album = clean(cursor.getString(albumCol)),
                            albumId = cursor.getLong(albumIdCol),
                            durationMs = cursor.getLong(durationCol),
                            addedAt = cursor.getLong(addedCol)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("ZuneLibrary", "Could not read the music library", e)
        }
        return out
    }
}
