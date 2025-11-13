package rocks.gorjan.gokixp.apps.explorer

import java.io.File

/**
 * File type categories for icon selection
 */
enum class FileType {
    DIRECTORY,
    IMAGE,
    AUDIO,
    VIDEO,
    GENERIC,
    DRIVE  // Virtual drive entry
}

/**
 * Drive type for virtual My Computer root level
 */
enum class DriveType {
    FLOPPY,      // 3.5 Floppy (A:)
    LOCAL_DISK,  // Local Disk (C:)
    OPTICAL      // Compact Disc (D:)
}

/**
 * Represents a file or folder in the file system for display in My Computer
 */
data class FileSystemItem(
    val file: File,
    val name: String = file.name,
    val isDirectory: Boolean = file.isDirectory,
    val size: Long = if (file.isDirectory) 0 else file.length(),
    val lastModified: Long = file.lastModified(),
    val isDrive: Boolean = false,  // True for virtual drive entries
    val driveType: DriveType? = null  // Type of drive if isDrive is true
) {
    companion object {
        // Known file extensions by type
        private val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico", "tiff", "tif"
        )

        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "wav", "ogg", "m4a", "flac", "aac", "wma", "opus", "mid", "midi"
        )

        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "avi", "mkv", "mov", "wmv", "flv", "webm", "m4v", "mpg", "mpeg", "3gp"
        )

        /**
         * Creates a FileSystemItem from a File object
         */
        fun from(file: File): FileSystemItem {
            return FileSystemItem(
                file = file,
                name = file.name,
                isDirectory = file.isDirectory,
                size = if (file.isDirectory) 0 else file.length(),
                lastModified = file.lastModified()
            )
        }

        /**
         * Creates a virtual drive item for My Computer root
         */
        fun createDrive(name: String, driveType: DriveType, dummyFile: File): FileSystemItem {
            return FileSystemItem(
                file = dummyFile,
                name = name,
                isDirectory = true,
                size = 0,
                lastModified = 0,
                isDrive = true,
                driveType = driveType
            )
        }

        /**
         * Determine the file type based on extension
         */
        fun getFileType(item: FileSystemItem): FileType {
            if (item.isDrive) {
                return FileType.DRIVE
            }

            val file = item.file
            if (file.isDirectory) {
                return FileType.DIRECTORY
            }

            val extension = file.extension.lowercase()
            return when {
                extension in IMAGE_EXTENSIONS -> FileType.IMAGE
                extension in AUDIO_EXTENSIONS -> FileType.AUDIO
                extension in VIDEO_EXTENSIONS -> FileType.VIDEO
                else -> FileType.GENERIC
            }
        }
    }
}
