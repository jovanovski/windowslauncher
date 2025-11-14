package rocks.gorjan.gokixp.apps.explorer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
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
    private val onSetCursorNormal: () -> Unit,
    private val onShowDialog: (rocks.gorjan.gokixp.apps.dialogbox.DialogType, String) -> Unit,
    private val onShowContextMenu: (List<rocks.gorjan.gokixp.ContextMenuItem>, Float, Float) -> Unit,
    private val onShowRenameDialog: (File, (String) -> Unit) -> Unit,
    private val onShowConfirmDialog: (String, String, () -> Unit) -> Unit
) {
    companion object {
        private const val TAG = "MyComputerApp"
        private const val MY_COMPUTER_ROOT = "MY_COMPUTER_ROOT"  // Virtual root path
    }

    // Navigation state
    private var currentPath: File? = null  // null means we're at My Computer root
    private val navigationHistory = mutableListOf<File?>()  // null entries represent root
    private var historyIndex = -1

    // Clipboard state for copy/cut/paste
    private var clipboardFile: File? = null
    private var clipboardOperation: ClipboardOperation? = null

    enum class ClipboardOperation {
        COPY,
        CUT
    }

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

    // Selection tracking
    private var currentSelectedView: View? = null

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

        // Setup touch listener on GridView to handle long-press on empty space
        var longPressHandler: android.os.Handler? = null
        var longPressRunnable: Runnable? = null
        var touchDownX = 0f
        var touchDownY = 0f

        folderIconsGrid?.setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    touchDownX = event.rawX
                    touchDownY = event.rawY

                    // Check if touch is on an empty space (not on a child view)
                    val gridView = view as? GridView
                    if (gridView != null) {
                        val position = gridView.pointToPosition(event.x.toInt(), event.y.toInt())
                        if (position == GridView.INVALID_POSITION && currentPath != null) {
                            // Touch is on empty space, start long-press timer
                            longPressRunnable = Runnable {
                                rocks.gorjan.gokixp.Helpers.performHapticFeedback(context)
                                showEmptySpaceContextMenu(touchDownX, touchDownY)
                            }
                            longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
                            longPressHandler?.postDelayed(longPressRunnable!!, 500)
                        }
                    }
                    false
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    // Cancel long-press if finger lifted
                    longPressRunnable?.let { longPressHandler?.removeCallbacks(it) }
                    longPressRunnable = null
                    longPressHandler = null
                    false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    // Cancel long-press if finger moved too far
                    val deltaX = Math.abs(event.rawX - touchDownX)
                    val deltaY = Math.abs(event.rawY - touchDownY)
                    if (deltaX > 10 || deltaY > 10) {
                        longPressRunnable?.let { longPressHandler?.removeCallbacks(it) }
                        longPressRunnable = null
                        longPressHandler = null
                    }
                    false
                }
                else -> false
            }
        }

        // Load initial view (My Computer root with drives)
        navigateToDirectory(null, addToHistory = true)
        onSoundPlay("click")
        return contentView
    }

    /**
     * Navigate to a specific directory (null = My Computer root)
     */
    private fun navigateToDirectory(directory: File?, addToHistory: Boolean = true) {
        // Validate if not null
        if (directory != null && (!directory.exists() || !directory.isDirectory)) {
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
            onSoundPlay("click")
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
        // If at My Computer root, can't go up
        if (currentPath == null) {
            Log.d(TAG, "Already at My Computer root, cannot navigate up")
            return
        }

        val rootDirectory = Environment.getExternalStorageDirectory()

        // If at internal storage root, go to My Computer root
        if (currentPath == rootDirectory) {
            Log.d(TAG, "Navigating up to My Computer root")
            navigateToDirectory(null, addToHistory = true)
            return
        }

        val parent = currentPath?.parentFile
        if (parent != null && parent.exists()) {
            // Don't allow going above internal storage root
            if (parent.absolutePath.length < rootDirectory.absolutePath.length) {
                Log.d(TAG, "Cannot navigate above internal storage root, going to My Computer root")
                navigateToDirectory(null, addToHistory = true)
                return
            }
            navigateToDirectory(parent, addToHistory = true)
        }
    }

    /**
     * Update the folder name and icon display
     */
    private fun updateFolderDisplay() {
        val displayName = when {
            currentPath == null -> "My Computer"
            currentPath == Environment.getExternalStorageDirectory() -> "Local Disk (C:)"
            else -> currentPath!!.name
        }

        // For the small folder name (address bar), show full Windows-style path
        val pathDisplayName = when {
            currentPath == null -> "My Computer"
            else -> getWindowsStylePath(currentPath!!)
        }

        // Update small folder name with full path (in address bar)
        folderNameSmall?.text = pathDisplayName

        // Update large folder name (in Windows 98 left panel)
        folderNameLarge?.text = displayName

        // Update window title
        onUpdateWindowTitle(displayName)

        // Update folder icons (both small and large)
        // Use My Computer icon when at root, otherwise use folder icon
        val iconRes = if (currentPath == null) {
            themeManager.getMyComputerIcon()
        } else {
            themeManager.getFolderIconRes(theme)
        }
        folderIconSmall?.setImageResource(iconRes)
        folderIconLarge?.setImageResource(iconRes)
    }

    /**
     * Convert Android file path to Windows-style path
     * Internal storage root -> C:\
     * Subdirectories -> C:\folder\subfolder
     */
    private fun getWindowsStylePath(file: File): String {
        val rootDirectory = Environment.getExternalStorageDirectory()

        return if (file == rootDirectory) {
            "C:\\"
        } else {
            val relativePath = file.absolutePath.removePrefix(rootDirectory.absolutePath)
            "C:" + relativePath.replace("/", "\\")
        }
    }

    /**
     * Load and display directory contents
     */
    private fun loadDirectoryContents() {
        // Clear selection when loading new directory
        clearSelection()

        // Set cursor to busy while loading
        onSetCursorBusy()

        // Load files in background thread
        Thread {
            try {
                val items = if (currentPath == null) {
                    // At My Computer root - show virtual drives
                    val dummyFile = Environment.getExternalStorageDirectory()
                    listOf(
                        FileSystemItem.createDrive("3.5 Floppy (A:)", DriveType.FLOPPY, dummyFile),
                        FileSystemItem.createDrive("Local Disk (C:)", DriveType.LOCAL_DISK, dummyFile),
                        FileSystemItem.createDrive("Compact Disc (D:)", DriveType.OPTICAL, dummyFile)
                    )
                } else {
                    // In a real directory - show files and folders
                    val files = currentPath?.listFiles()?.toList() ?: emptyList()

                    // Filter out hidden files (files starting with a dot)
                    val visibleFiles = files.filter { !it.name.startsWith(".") }

                    // Sort: directories first, then by name
                    val sortedFiles = visibleFiles.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

                    // Convert to FileSystemItem
                    sortedFiles.map { FileSystemItem.from(it) }
                }

                // Update UI on main thread
                (context as? android.app.Activity)?.runOnUiThread {
                    try {
                        // Create adapter and set to GridView
                        val adapter = FileSystemAdapter(
                            context = context,
                            items = items,
                            theme = theme,
                            themeManager = themeManager,
                            onItemClick = { item, view ->
                                handleItemClick(item, view)
                            },
                            onItemLongClick = { item, x, y ->
                                showFileContextMenu(item, x, y)
                            },
                            isFileCut = { file ->
                                isFileCut(file)
                            }
                        )

                        folderIconsGrid?.adapter = adapter

                        // Update item count
                        explorerNumberOfItems?.text = "${items.size} items"

                        // Restore cursor to normal after UI updates
                        onSetCursorNormal()

                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating UI: ${e.message}", e)
                        explorerNumberOfItems?.text = "0 items"
                        onSetCursorNormal()
                    }
                }

            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied reading directory: ${currentPath?.absolutePath ?: "My Computer"}", e)
                (context as? android.app.Activity)?.runOnUiThread {
                    explorerNumberOfItems?.text = "0 items"
                    onSetCursorNormal()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading directory: ${currentPath?.absolutePath ?: "My Computer"}", e)
                (context as? android.app.Activity)?.runOnUiThread {
                    explorerNumberOfItems?.text = "0 items"
                    onSetCursorNormal()
                }
            }
        }.start()
    }

    /**
     * Handle click on a file or folder
     */
    private fun handleItemClick(item: FileSystemItem, view: View) {
        onSoundPlay("click")

        if (item.isDrive) {
            // Handle drive clicks
            when (item.driveType) {
                DriveType.LOCAL_DISK -> {
                    // Navigate to internal storage
                    navigateToDirectory(Environment.getExternalStorageDirectory(), addToHistory = true)
                }
                DriveType.FLOPPY -> {
                    // Play floppy read sound with busy cursor and show error
                    playDriveSound(R.raw.floppy_read, rocks.gorjan.gokixp.apps.dialogbox.DialogType.ERROR, "A:/ is not accessible", timeout = 3000)
                }
                DriveType.OPTICAL -> {
                    // Play CD spin sound with busy cursor and show error
                    playDriveSound(R.raw.cd_spin, rocks.gorjan.gokixp.apps.dialogbox.DialogType.WARNING, "Please insert a disk into drive D:", timeout = 3000)
                }
                null -> Log.e(TAG, "Drive item with null drive type")
            }
        } else if (item.isDirectory) {
            // Navigate into directory
            navigateToDirectory(item.file, addToHistory = true)
        } else {
            // Open file with default app
            openFile(item.file)
        }
    }

    /**
     * Select a file and deselect all others
     */
    private fun selectFile(view: View) {
        // Deselect previously selected view
        currentSelectedView?.isSelected = false

        // Select the new view
        view.isSelected = true
        currentSelectedView = view
    }

    /**
     * Clear all selections
     */
    private fun clearSelection() {
        currentSelectedView?.isSelected = false
        currentSelectedView = null
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
     * Play drive sound effect with busy cursor and optionally show dialog
     */
    private fun playDriveSound(
        soundResId: Int,
        dialogType: rocks.gorjan.gokixp.apps.dialogbox.DialogType? = null,
        message: String? = null,
        timeout: Long? = null
    ) {
        // Set cursor to busy
        onSetCursorBusy()

        try {
            // Create MediaPlayer for the drive sound
            val mediaPlayer = android.media.MediaPlayer.create(context, soundResId)

            // Set listener to restore cursor when sound finishes
            mediaPlayer.setOnCompletionListener {
                onSetCursorNormal()
                it.release()
            }

            if(dialogType != null && message != null && timeout != null) {
                Handler(Looper.getMainLooper()).postDelayed({
                    onShowDialog(dialogType, message)
                }, timeout)
            }

            // Set listener to restore cursor on error
            mediaPlayer.setOnErrorListener { mp, _, _ ->
                onSetCursorNormal()
                mp.release()
                true
            }

            // Start playing
            mediaPlayer.start()

        } catch (e: Exception) {
            Log.e(TAG, "Error playing drive sound", e)
            onSetCursorNormal()
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

    /**
     * Show context menu for a file or folder
     */
    private fun showFileContextMenu(item: FileSystemItem, x: Float, y: Float) {
        val file = item.file
        val itemType = if (item.isDirectory) "Folder" else "File"

        val menuItems = listOf(
            rocks.gorjan.gokixp.ContextMenuItem(
                title = "Open",
                isEnabled = true,
                action = {
                    if (item.isDirectory) {
                        navigateToDirectory(file, addToHistory = true)
                    } else {
                        openFile(file)
                    }
                }
            ),
            rocks.gorjan.gokixp.ContextMenuItem("", isEnabled = false), // Separator
            rocks.gorjan.gokixp.ContextMenuItem(
                title = "Copy",
                isEnabled = true,
                action = {
                    copyToClipboard(file)
                }
            ),
            rocks.gorjan.gokixp.ContextMenuItem(
                title = "Cut",
                isEnabled = true,
                action = {
                    cutToClipboard(file)
                }
            ),
            rocks.gorjan.gokixp.ContextMenuItem("", isEnabled = false), // Separator
            rocks.gorjan.gokixp.ContextMenuItem(
                title = "Rename",
                isEnabled = true,
                action = {
                    onShowRenameDialog(file) { newName ->
                        if (item.isDirectory) {
                            renameFolder(file, newName)
                        } else {
                            renameFile(file, newName)
                        }
                    }
                }
            ),
            rocks.gorjan.gokixp.ContextMenuItem(
                title = "Delete",
                isEnabled = true,
                action = {
                    if (item.isDirectory) {
                        deleteFolder(file)
                    } else {
                        deleteFile(file)
                    }
                }
            )
        )

        onShowContextMenu(menuItems, x, y)
    }

    /**
     * Rename a file
     */
    private fun renameFile(file: File, newName: String) {
        try {
            val newFile = File(file.parent, newName)
            if (file.renameTo(newFile)) {
                // Refresh the current directory to show the renamed file
                loadDirectoryContents()
            } else {
                onShowDialog(
                    rocks.gorjan.gokixp.apps.dialogbox.DialogType.ERROR,
                    "Could not rename file"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error renaming file", e)
            onShowDialog(
                rocks.gorjan.gokixp.apps.dialogbox.DialogType.ERROR,
                "Error: ${e.message}"
            )
        }
    }

    /**
     * Rename a folder
     */
    private fun renameFolder(folder: File, newName: String) {
        try {
            val newFolder = File(folder.parent, newName)
            if (folder.renameTo(newFolder)) {
                // Refresh the current directory to show the renamed folder
                loadDirectoryContents()
            } else {
                onShowDialog(
                    rocks.gorjan.gokixp.apps.dialogbox.DialogType.ERROR,
                    "Could not rename folder"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error renaming folder", e)
            onShowDialog(
                rocks.gorjan.gokixp.apps.dialogbox.DialogType.ERROR,
                "Error: ${e.message}"
            )
        }
    }

    /**
     * Delete a file
     */
    private fun deleteFile(file: File) {
        onShowConfirmDialog(
            "Delete File",
            "Are you sure you want to delete '${file.name}'?"
        ) {
            try {
                if (file.delete()) {
                    // Refresh the current directory to show the file is gone
                    loadDirectoryContents()
                } else {
                    onShowDialog(
                        rocks.gorjan.gokixp.apps.dialogbox.DialogType.ERROR,
                        "Could not delete file"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting file", e)
                onShowDialog(
                    rocks.gorjan.gokixp.apps.dialogbox.DialogType.ERROR,
                    "Error: ${e.message}"
                )
            }
        }
    }

    /**
     * Delete a folder (recursively if not empty)
     */
    private fun deleteFolder(folder: File) {
        val fileCount = folder.listFiles()?.size ?: 0
        val message = if (fileCount > 0) {
            "Are you sure you want to delete '${folder.name}' and all its contents? ($fileCount items)"
        } else {
            "Are you sure you want to delete '${folder.name}'?"
        }

        onShowConfirmDialog(
            "Delete Folder",
            message
        ) {
            try {
                if (deleteRecursive(folder)) {
                    // Refresh the current directory to show the folder is gone
                    loadDirectoryContents()
                } else {
                    onShowDialog(
                        rocks.gorjan.gokixp.apps.dialogbox.DialogType.ERROR,
                        "Could not delete folder"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting folder", e)
                onShowDialog(
                    rocks.gorjan.gokixp.apps.dialogbox.DialogType.ERROR,
                    "Error: ${e.message}"
                )
            }
        }
    }

    /**
     * Recursively delete a folder and its contents
     */
    private fun deleteRecursive(file: File): Boolean {
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children != null) {
                for (child in children) {
                    if (!deleteRecursive(child)) {
                        return false
                    }
                }
            }
        }
        return file.delete()
    }

    /**
     * Show context menu for empty space (whitespace) in the grid
     */
    private fun showEmptySpaceContextMenu(x: Float, y: Float) {
        val menuItems = listOf(
            rocks.gorjan.gokixp.ContextMenuItem(
                title = "New Folder",
                isEnabled = true,
                action = {
                    showNewFolderDialog()
                }
            ),
            rocks.gorjan.gokixp.ContextMenuItem(
                title = "Paste",
                isEnabled = clipboardFile != null && clipboardOperation != null,
                action = {
                    pasteFromClipboard()
                }
        ),
        )

        onShowContextMenu(menuItems, x, y)
    }

    /**
     * Show dialog to create a new folder
     */
    private fun showNewFolderDialog() {
        val dummyFile = File(currentPath, "New Folder")
        onShowRenameDialog(dummyFile) { folderName ->
            createNewFolder(folderName)
        }
    }

    /**
     * Create a new folder in the current directory
     */
    private fun createNewFolder(folderName: String) {
        if (currentPath == null) {
            onShowDialog(
                rocks.gorjan.gokixp.apps.dialogbox.DialogType.ERROR,
                "Cannot create folder at this location"
            )
            return
        }

        try {
            val newFolder = File(currentPath, folderName)

            if (newFolder.exists()) {
                onShowDialog(
                    rocks.gorjan.gokixp.apps.dialogbox.DialogType.ERROR,
                    "A file or folder with this name already exists"
                )
                return
            }

            if (newFolder.mkdir()) {
                // Refresh the current directory to show the new folder
                loadDirectoryContents()
            } else {
                onShowDialog(
                    rocks.gorjan.gokixp.apps.dialogbox.DialogType.ERROR,
                    "Could not create folder"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating folder", e)
            onShowDialog(
                rocks.gorjan.gokixp.apps.dialogbox.DialogType.ERROR,
                "Error: ${e.message}"
            )
        }
    }

    /**
     * Copy a file/folder to clipboard
     */
    private fun copyToClipboard(file: File) {
        clipboardFile = file
        clipboardOperation = ClipboardOperation.COPY
        // Refresh to update UI (no opacity change for copy)
        loadDirectoryContents()
    }

    /**
     * Cut a file/folder to clipboard
     */
    private fun cutToClipboard(file: File) {
        clipboardFile = file
        clipboardOperation = ClipboardOperation.CUT
        // Refresh to update UI with opacity change
        loadDirectoryContents()
    }

    /**
     * Paste from clipboard to current directory
     */
    private fun pasteFromClipboard() {
        val sourceFile = clipboardFile ?: return
        val operation = clipboardOperation ?: return
        val destDir = currentPath ?: return

        val destFile = File(destDir, sourceFile.name)

        // Check if destination already exists
        if (destFile.exists()) {
            onShowConfirmDialog(
                "Overwrite",
                "A file or folder named '${sourceFile.name}' already exists. Do you want to overwrite it?"
            ) {
                performPaste(sourceFile, destFile, operation)
            }
        } else {
            performPaste(sourceFile, destFile, operation)
        }
    }

    /**
     * Perform the actual paste operation
     */
    private fun performPaste(sourceFile: File, destFile: File, operation: ClipboardOperation) {
        try {
            when (operation) {
                ClipboardOperation.COPY -> {
                    if (sourceFile.isDirectory) {
                        copyDirectoryRecursive(sourceFile, destFile)
                    } else {
                        sourceFile.copyTo(destFile, overwrite = true)
                    }
                }
                ClipboardOperation.CUT -> {
                    if (sourceFile.renameTo(destFile)) {
                        // Clear clipboard after successful cut
                        clipboardFile = null
                        clipboardOperation = null
                    } else {
                        onShowDialog(
                            rocks.gorjan.gokixp.apps.dialogbox.DialogType.ERROR,
                            "Could not move file or folder"
                        )
                        return
                    }
                }
            }

            // Refresh directory to show the pasted item
            loadDirectoryContents()

        } catch (e: Exception) {
            Log.e(TAG, "Error pasting file/folder", e)
            onShowDialog(
                rocks.gorjan.gokixp.apps.dialogbox.DialogType.ERROR,
                "Error: ${e.message}"
            )
        }
    }

    /**
     * Recursively copy a directory
     */
    private fun copyDirectoryRecursive(source: File, dest: File) {
        if (source.isDirectory) {
            if (!dest.exists()) {
                dest.mkdir()
            }
            val children = source.listFiles()
            if (children != null) {
                for (child in children) {
                    copyDirectoryRecursive(child, File(dest, child.name))
                }
            }
        } else {
            source.copyTo(dest, overwrite = true)
        }
    }

    /**
     * Reset clipboard (call when window reopens)
     */
    fun resetClipboard() {
        clipboardFile = null
        clipboardOperation = null
    }

    /**
     * Check if a file is currently cut (for opacity change)
     */
    fun isFileCut(file: File): Boolean {
        return clipboardFile?.absolutePath == file.absolutePath &&
               clipboardOperation == ClipboardOperation.CUT
    }
}
