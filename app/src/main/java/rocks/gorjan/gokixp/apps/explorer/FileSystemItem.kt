package rocks.gorjan.gokixp.apps.explorer

import java.io.File

/**
 * Represents a file or folder in the file system for display in My Computer
 */
data class FileSystemItem(
    val file: File,
    val name: String = file.name,
    val isDirectory: Boolean = file.isDirectory,
    val size: Long = if (file.isDirectory) 0 else file.length(),
    val lastModified: Long = file.lastModified()
) {
    companion object {
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
    }
}
