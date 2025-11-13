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

/**
 * Adapter for displaying file system items in a GridView
 * Reuses the folder_icon.xml layout and DesktopIconView infrastructure
 */
class FileSystemAdapter(
    private val context: Context,
    private val items: List<FileSystemItem>,
    private val theme: AppTheme,
    private val themeManager: ThemeManager,
    private val onItemClick: (FileSystemItem, View) -> Unit
) : BaseAdapter() {

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): FileSystemItem = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val item = items[position]

        // Create appropriate view based on whether it's a folder or file
        val iconView = (convertView as? DesktopIconView) ?: run {
            if (item.isDirectory) {
                // Use FolderView for directories
                FolderView(context, theme, R.layout.folder_icon).apply {
                    // Disable context menu for read-only Windows Explorer
                    setContextMenuEnabled(false)
                }
            } else {
                // Use regular DesktopIconView for files
                DesktopIconView(context, R.layout.folder_icon)
            }
        }

        // Make sure context menu is disabled for recycled FolderViews too
        if (item.isDirectory && iconView is FolderView) {
            iconView.setContextMenuEnabled(false)
        }

        // Get the appropriate icon
        val iconDrawable = if (item.isDirectory) {
            // Use theme-specific folder icon
            context.getDrawable(themeManager.getFolderIconRes(theme))
        } else {
            // Use generic file icon (you can enhance this with file type detection)
            context.getDrawable(R.drawable.notepad_icon_xp) // Placeholder for files
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

        // Disable long click context menu for read-only mode
        iconView.setCustomLongClickHandler { _, _ -> /* Do nothing */ }

        return iconView
    }
}
