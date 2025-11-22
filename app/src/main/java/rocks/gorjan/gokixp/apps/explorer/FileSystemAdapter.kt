package rocks.gorjan.gokixp.apps.explorer

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import rocks.gorjan.gokixp.DesktopIconView
import rocks.gorjan.gokixp.FolderView
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.theme.AppTheme
import rocks.gorjan.gokixp.theme.ThemeManager
import java.io.File

/**
 * Adapter for displaying file system items in a GridView
 * Reuses the folder_icon.xml layout and DesktopIconView infrastructure
 */
class FileSystemAdapter(
    private val context: Context,
    private val items: List<FileSystemItem>,
    private val theme: AppTheme,
    private val themeManager: ThemeManager,
    private val onItemClick: (FileSystemItem, View) -> Unit,
    private val onItemLongClick: ((FileSystemItem, Float, Float) -> Unit)? = null,
    private val isFileCut: ((File) -> Boolean)? = null,
    private val getShortcutIcon: ((String) -> android.graphics.drawable.Drawable?)? = null
) : BaseAdapter() {

    private var selectedPosition: Int = -1

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): FileSystemItem = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    /**
     * Clear the current selection
     */
    fun clearSelection() {
        selectedPosition = -1
        notifyDataSetChanged()
    }

    /**
     * Set the selected item by file path
     */
    fun setSelectedItem(file: File) {
        selectedPosition = items.indexOfFirst { it.file.absolutePath == file.absolutePath }
        notifyDataSetChanged()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val item = items[position]

        // Create DesktopIconView for both files and folders
        // Don't use FolderView here as it has desktop-specific context menu
        val iconView = (convertView as? DesktopIconView) ?: DesktopIconView(context, R.layout.folder_icon)

        // Get the appropriate icon based on file type
        val iconDrawable = if (item.isDrive) {
            // Use drive icon based on drive type
            val iconResId = when (item.driveType) {
                DriveType.FLOPPY -> themeManager.getDriveFloppyIcon()
                DriveType.LOCAL_DISK -> themeManager.getDriveLocalIcon()
                DriveType.OPTICAL -> themeManager.getDriveOpticalIcon()
                null -> themeManager.getFolderIconRes(theme) // Fallback
            }
            context.getDrawable(iconResId)
        } else if (item.isShortcut && item.shortcutPackageName != null) {
            // Use shortcut icon from callback or fallback to generic
            getShortcutIcon?.invoke(item.shortcutPackageName)
                ?: context.getDrawable(themeManager.getFileGenericIcon())
        } else if (item.isVirtualFolder) {
            // Use folder icon for virtual folders
            context.getDrawable(themeManager.getFolderIconRes(theme))
        } else if (item.isDirectory) {
            // Use theme-specific folder icon
            context.getDrawable(themeManager.getFolderIconRes(theme))
        } else {
            // Determine file type and use appropriate icon
            val fileType = FileSystemItem.getFileType(item)
            val iconResId = when (fileType) {
                FileType.IMAGE -> themeManager.getFileImageIcon()
                FileType.PDF -> themeManager.getPDFImageIcon()
                FileType.AUDIO -> themeManager.getFileAudioIcon()
                FileType.VIDEO -> themeManager.getFileVideoIcon()
                FileType.GENERIC -> themeManager.getFileGenericIcon()
                FileType.DIRECTORY -> themeManager.getFolderIconRes(theme) // Should not happen
                FileType.DRIVE -> themeManager.getFolderIconRes(theme) // Should not happen
                FileType.SHORTCUT -> themeManager.getFileGenericIcon() // Should not happen - handled above
            }
            context.getDrawable(iconResId)
        }

        // Create a pseudo DesktopIcon for the file/folder
        val desktopIcon = rocks.gorjan.gokixp.DesktopIcon(
            name = item.name,
            packageName = item.file.absolutePath,
            icon = iconDrawable!!,
            x = 0f,
            y = 0f,
            id = item.file.absolutePath,
            type = if (item.isDirectory) rocks.gorjan.gokixp.IconType.FOLDER else rocks.gorjan.gokixp.IconType.APP
        )

        // Configure the icon view
        iconView.setDesktopIcon(desktopIcon)
        val isClassic = theme is AppTheme.WindowsClassic
        iconView.setThemeFont(isClassic)
        iconView.setTextColor(Color.BLACK)
        iconView.removeTextShadow()

        // Hide shortcut arrow for files, folders, and drives (only apps should have it)
        iconView.updateShortcutArrowVisibility(false)

        // Remove padding and margins
        iconView.setIconPadding(0, 0, 0, 0)
        iconView.setIconMargin(0, 0, 0, 0)

        // Set fixed size to match folder_icon.xml dimensions (50x60dp)
        val density = context.resources.displayMetrics.density
        val iconWidth = (50 * density).toInt()
        val iconHeight = (60 * density).toInt()

        val layoutParams = ViewGroup.LayoutParams(iconWidth, iconHeight)
        iconView.layoutParams = layoutParams

        // Set click listener
        iconView.setOnClickListener {
            onItemClick(item, iconView)
        }

        // Set long click handler for files and folders (not drives, shortcuts, or virtual folders)
        if (!item.isDrive && !item.isShortcut && !item.isVirtualFolder && onItemLongClick != null) {
            iconView.setCustomLongClickHandler { x, y ->
                // Set this item as selected
                selectedPosition = position
                iconView.isSelected = true
                onItemLongClick.invoke(item, x, y)
            }
        } else {
            // Disable long click for drives, shortcuts, and virtual folders
            iconView.setCustomLongClickHandler { _, _ -> /* Do nothing */ }
        }

        // Apply selection state
        iconView.isSelected = (position == selectedPosition)

        // Apply opacity if file is cut
        if (!item.isDrive && isFileCut != null && isFileCut.invoke(item.file)) {
            iconView.alpha = 0.6f
        } else {
            iconView.alpha = 1.0f
        }

        return iconView
    }
}
