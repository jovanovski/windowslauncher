package rocks.gorjan.gokixp

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import androidx.core.view.marginBottom
import rocks.gorjan.gokixp.theme.AppTheme

class FolderIconAdapter(
    private val context: Context,
    private val icons: List<DesktopIcon>,
    private val theme: AppTheme,
    private val onIconClick: (DesktopIcon, DesktopIconView) -> Unit,
    private val onIconLongClick: (DesktopIcon, DesktopIconView, Float, Float) -> Boolean
) : BaseAdapter() {

    // Backward compatibility: support boolean parameter
    constructor(
        context: Context,
        icons: List<DesktopIcon>,
        isWindows98: Boolean,
        onIconClick: (DesktopIcon, DesktopIconView) -> Unit,
        onIconLongClick: (DesktopIcon, DesktopIconView, Float, Float) -> Boolean
    ) : this(
        context,
        icons,
        if (isWindows98) AppTheme.WindowsClassic else AppTheme.WindowsXP,
        onIconClick,
        onIconLongClick
    )

    override fun getCount(): Int = icons.size

    override fun getItem(position: Int): DesktopIcon = icons[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val icon = icons[position]

        // Create new icon view using folder_icon.xml layout or reuse existing
        val iconView = (convertView as? DesktopIconView) ?: run {
            val view = when (icon.type) {
                IconType.RECYCLE_BIN -> RecycleBinView(context, R.layout.folder_icon)
                IconType.FOLDER -> FolderView(context, theme, R.layout.folder_icon)
                IconType.MY_COMPUTER -> rocks.gorjan.gokixp.apps.explorer.MyComputerView(context, R.layout.folder_icon)
                IconType.APP -> DesktopIconView(context, R.layout.folder_icon)
            }
            view
        }

        // Configure the icon view
        iconView.setDesktopIcon(icon)
        val isClassic = theme is AppTheme.WindowsClassic
        iconView.setThemeFont(isClassic)
        iconView.setTextColor(android.graphics.Color.BLACK)
        iconView.removeTextShadow()

        // Remove padding and margins to eliminate spacing
        iconView.setIconPadding(0, 0, 0, 0)
        iconView.setIconMargin(0, 0, 0, 0)

        // No scaling needed - folder_icon.xml is already the correct size

        // Set fixed size to match folder_icon.xml dimensions
        val density = context.resources.displayMetrics.density
        val iconWidth = (50 * density).toInt()
        val iconHeight = (60 * density).toInt()

        val layoutParams = ViewGroup.LayoutParams(iconWidth, iconHeight)
        iconView.layoutParams = layoutParams

        // Set click listeners
        iconView.setOnClickListener {
            onIconClick(icon, iconView)
        }

        // Use custom long click handler to override default desktop context menu
        iconView.setCustomLongClickHandler { touchX, touchY ->
            onIconLongClick(icon, iconView, touchX, touchY)
        }

        return iconView
    }
}
