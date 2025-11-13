package rocks.gorjan.gokixp.apps.explorer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.FileProvider
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.theme.AppTheme
import rocks.gorjan.gokixp.theme.ThemeManager
import java.io.File

/**
 * My Computer file browser app
 * Read-only file explorer that uses the Windows Explorer UI
 */
class MyComputerApp(
    private val context: Context,
    private val theme: AppTheme,
    private val themeManager: ThemeManager,
    private val onSoundPlay: (String) -> Unit,
    private val onUpdateWindowTitle: (String) -> Unit,
    private val onSetCursorBusy: () -> Unit,
    private val onSetCursorNormal: () -> Unit
) {
    companion object {
        private const val TAG = "MyComputerApp"
    }

    // Navigation state
    private var currentPath: File = Environment.getExternalStorageDirectory()
    private val navigationHistory = mutableListOf<File>()
    private var historyIndex = -1

    // UI references
    private var folderIconsGrid: GridView? = null
    private var folderNameSmall: TextView? = null
    private var folderNameLarge: TextView? = null
    private var folderIconSmall: ImageView? = null
    private var folderIconLarge: ImageView? = null
    private var explorerNumberOfItems: TextView? = null
    private var backButton: View? = null
    private var forwardButton: View? = null
    private var upButton: View? = null

    /**
     * Initialize the app UI
     */
    fun setupApp(contentView: View): View {
        // Get references to views
        folderIconsGrid = contentView.findViewById(R.id.folder_icons_grid)
        folderNameSmall = contentView.findViewById(R.id.folder_name_small)
        folderNameLarge = contentView.findViewById(R.id.folder_name_large)
        folderIconSmall = contentView.findViewById(R.id.folder_icon_small)
        folderIconLarge = contentView.findViewById(R.id.folder_icon_large)
        explorerNumberOfItems = contentView.findViewById(R.id.explorer_number_of_items)
        backButton = contentView.findViewById(R.id.back_button)
        forwardButton = contentView.findViewById(R.id.forward_button)
        upButton = contentView.findViewById(R.id.up_button)

        // Setup navigation buttons
        backButton?.setOnClickListener {
            onSoundPlay("click")
            navigateBack()
        }

        forwardButton?.setOnClickListener {
            onSoundPlay("click")
            navigateForward()
        }

        upButton?.setOnClickListener {
            onSoundPlay("click")
            navigateUp()
        }

        // Load initial directory (root of internal storage)
        navigateToDirectory(currentPath, addToHistory = true)

        return contentView
    }

    /**
     * Navigate to a specific directory
     */
    private fun navigateToDirectory(directory: File, addToHistory: Boolean = true) {
        if (!directory.exists() || !directory.isDirectory) {
            Log.e(TAG, "Invalid directory: ${directory.absolutePath}")
            return
        }

        currentPath = directory

        // Add to navigation history
        if (addToHistory) {
            // Remove any forward history if we're navigating to a new location
            if (historyIndex < navigationHistory.size - 1) {
                navigationHistory.subList(historyIndex + 1, navigationHistory.size).clear()
            }
            navigationHistory.add(directory)
            historyIndex = navigationHistory.size - 1
        }

        // Update UI
        updateFolderDisplay()
        loadDirectoryContents()
    }

    /**
     * Navigate back in history
     */
    private fun navigateBack() {
        if (historyIndex > 0) {
            historyIndex--
            currentPath = navigationHistory[historyIndex]
            updateFolderDisplay()
            loadDirectoryContents()
        }
    }

    /**
     * Check if the browser can navigate back in history (public API for gesture handling)
     */
    fun canNavigateBack(): Boolean {
        return historyIndex > 0
    }

    /**
     * Navigate back in browser history (public API for gesture handling)
     */
    fun navigateBackPublic() {
        navigateBack()
    }

    /**
     * Navigate forward in history
     */
    private fun navigateForward() {
        if (historyIndex < navigationHistory.size - 1) {
            historyIndex++
            currentPath = navigationHistory[historyIndex]
            updateFolderDisplay()
            loadDirectoryContents()
        }
    }

    /**
     * Navigate up to parent directory
     */
    private fun navigateUp() {
        // Don't allow navigation above internal storage root
        val rootDirectory = Environment.getExternalStorageDirectory()
        if (currentPath == rootDirectory) {
            Log.d(TAG, "Already at root directory, cannot navigate up")
            return
        }

        val parent = currentPath.parentFile
        if (parent != null && parent.exists()) {
            // Don't allow going above internal storage root
            if (parent.absolutePath.length < rootDirectory.absolutePath.length) {
                Log.d(TAG, "Cannot navigate above internal storage root")
                return
            }
            navigateToDirectory(parent, addToHistory = true)
        }
    }

    /**
     * Update the folder name and icon display
     */
    private fun updateFolderDisplay() {
        val displayName = if (currentPath == Environment.getExternalStorageDirectory()) {
            "Internal Storage"
        } else {
            currentPath.name
        }

        // Update small folder name and icon (in address bar)
        folderNameSmall?.text = displayName

        // Update large folder name (in Windows 98 left panel)
        folderNameLarge?.text = displayName

        // Update window title
        onUpdateWindowTitle(displayName)

        // Update folder icons (both small and large)
        val folderIconRes = themeManager.getFolderIconRes(theme)
        folderIconSmall?.setImageResource(folderIconRes)
        folderIconLarge?.setImageResource(folderIconRes)
    }

    /**
     * Load and display directory contents
     */
    private fun loadDirectoryContents() {
        // Set cursor to busy while loading
        onSetCursorBusy()

        try {
            // Get list of files and folders
            val files = currentPath.listFiles()?.toList() ?: emptyList()

            // Sort: directories first, then by name
            val sortedFiles = files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

            // Convert to FileSystemItem
            val items = sortedFiles.map { FileSystemItem.from(it) }

            // Create adapter and set to GridView
            val adapter = FileSystemAdapter(
                context = context,
                items = items,
                theme = theme,
                themeManager = themeManager,
                onItemClick = { item, _ ->
                    handleItemClick(item)
                }
            )

            folderIconsGrid?.adapter = adapter

            // Update item count
            explorerNumberOfItems?.text = "${items.size} items"

        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied reading directory: ${currentPath.absolutePath}", e)
            explorerNumberOfItems?.text = "0 items"
        } catch (e: Exception) {
            Log.e(TAG, "Error reading directory: ${currentPath.absolutePath}", e)
            explorerNumberOfItems?.text = "0 items"
        } finally {
            // Always restore cursor to normal after loading
            onSetCursorNormal()
        }
    }

    /**
     * Handle click on a file or folder
     */
    private fun handleItemClick(item: FileSystemItem) {
        onSoundPlay("click")

        if (item.isDirectory) {
            // Navigate into directory
            navigateToDirectory(item.file, addToHistory = true)
        } else {
            // Open file with default app
            openFile(item.file)
        }
    }

    /**
     * Open a file with the default system app
     */
    private fun openFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, getMimeType(file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Check if there's an app that can handle this file type
            val packageManager = context.packageManager
            if (intent.resolveActivity(packageManager) != null) {
                context.startActivity(intent)
            } else {
                Log.w(TAG, "No app found to open file: ${file.name}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error opening file: ${file.name}", e)
        }
    }

    /**
     * Get MIME type for a file based on extension
     */
    private fun getMimeType(file: File): String {
        val extension = file.extension.lowercase()
        return when (extension) {
            "txt" -> "text/plain"
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            else -> "*/*"
        }
    }
}
