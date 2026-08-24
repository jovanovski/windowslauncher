package rocks.gorjan.gokixp

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import rocks.gorjan.gokixp.theme.AppTheme
import rocks.gorjan.gokixp.theme.ThemeAware

class AppsAdapter(
    private val context: Context,
    private val originalItems: List<Any>, // Can be AppInfo or String (separator)
    private val onAppClick: () -> Unit,
    private val onAppLongClick: ((AppInfo, Float, Float) -> Unit)? = null,
    private val pinnedApps: Set<String> = emptySet(),
    private val onAppLaunched: ((AppInfo) -> Unit)? = null,
    private val recentApps: Set<String> = emptySet(),
    // Apps the user chose to hide. They only reach this adapter at all when the menu was
    // opened via "Open Start with hidden apps", where they're drawn dimmed.
    private val hiddenApps: Set<String> = emptySet()
) : RecyclerView.Adapter<RecyclerView.ViewHolder>(), ThemeAware {

    private var filteredItems: List<Any> = originalItems
    private var currentTheme: AppTheme = AppTheme.WindowsXP

    // Lowercased names, aligned with originalItems, so filtering doesn't allocate two
    // strings per app on every keystroke. Null for non-app entries (separators).
    private val searchKeys: List<String?> = originalItems.map {
        (it as? AppInfo)?.name?.trim()?.lowercase()
    }

    // Resolved once instead of per bind - looking these up in onBindViewHolder meant a
    // resource lookup and a font resolution for every row, every frame.
    private var textColors: ColorStateList = context.getColorStateList(R.color.context_menu_text_selector)
    private var themeTypeface: Typeface? = (context as? MainActivity)?.getThemePrimaryFont()

    // Backward compatible property
    private var isWindows98Theme = false
        get() = currentTheme is AppTheme.WindowsClassic

    companion object {
        private const val TYPE_APP = 0
        private const val TYPE_SEPARATOR = 1
    }

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val appIcon: ImageView = itemView.findViewById(R.id.app_icon)
        val appName: TextView = itemView.findViewById(R.id.app_name)

        init {
            // Listeners are attached once per holder rather than on every bind; they read
            // the app back out of the list by position when they actually fire.
            itemView.setOnClickListener {
                val app = boundApp() ?: return@setOnClickListener
                if (MainActivity.isSystemApp(app.packageName)) {
                    (context as? MainActivity)?.launchSystemApp(app.packageName)
                } else {
                    val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                    intent?.let {
                        it.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(it)
                        // Track as recently used app
                        onAppLaunched?.invoke(app)
                    }
                }
                // Close the start menu
                onAppClick()
            }

            itemView.setOnLongClickListener {
                val app = boundApp() ?: return@setOnLongClickListener false

                // Disable default haptic feedback to avoid double vibration
                // (Our ContextMenuView will handle the vibration)
                itemView.isHapticFeedbackEnabled = false

                // Get touch position (simplified - just use view center for now)
                val location = IntArray(2)
                itemView.getLocationOnScreen(location)
                val screenX = location[0] + itemView.width / 2f
                val screenY = location[1] + itemView.height / 2f

                onAppLongClick?.invoke(app, screenX, screenY)
                true
            }
        }

        private fun boundApp(): AppInfo? = filteredItems.getOrNull(bindingAdapterPosition) as? AppInfo
    }

    class SeparatorViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    override fun getItemViewType(position: Int): Int {
        return when (filteredItems[position]) {
            is AppInfo -> TYPE_APP
            is String -> TYPE_SEPARATOR
            else -> TYPE_APP
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_SEPARATOR -> {
                // Create a container for the separator with proper padding
                val containerView = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(0, 8, 0, 8) // Add vertical padding around the separator
                }

                // Create the actual separator line
                val separatorLine = View(parent.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (1 * context.resources.displayMetrics.density).toInt() // 1dp height
                    ).apply {
                        // Add horizontal margins for the line
                        val horizontalMargin = (16 * context.resources.displayMetrics.density).toInt()
                        setMargins(horizontalMargin, 0, horizontalMargin, 0)
                    }
                    setBackgroundColor(context.getColor(R.color.context_menu_divider))
                }

                containerView.addView(separatorLine)
                SeparatorViewHolder(containerView)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.app_list_item, parent, false)
                AppViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder !is AppViewHolder) return // Separators need no binding

        val app = filteredItems[position] as AppInfo

        // Use pre-loaded icon from AppInfo (icons loaded when start menu opened)
        holder.appIcon.setImageDrawable(app.icon)
        holder.appName.text = app.name

        // Hidden apps show through at half opacity so they read as "not normally here"
        holder.itemView.alpha = if (hiddenApps.contains(app.packageName)) 0.5f else 1f

        // Both are no-ops when the value hasn't changed, so this only costs anything
        // on the bind right after a theme switch.
        holder.appName.setTextColor(textColors)
        holder.appName.typeface = themeTypeface
    }

    override fun getItemCount(): Int = filteredItems.size

    /**
     * Narrows the list to apps matching [query]. Returns true if the visible set actually
     * changed, so the caller can decide whether to scroll back to the top.
     */
    fun filter(query: String): Boolean {
        val trimmedQuery = query.trim().lowercase()
        val previous = filteredItems
        val updated = if (trimmedQuery.isEmpty()) {
            originalItems
        } else {
            originalItems.filterIndexed { index, _ ->
                searchKeys[index]?.contains(trimmedQuery) == true
            }
        }

        if (previous.size == updated.size && previous.indices.all { previous[it] === updated[it] }) {
            return false
        }

        // A diff instead of notifyDataSetChanged: rows that survive the keystroke keep their
        // views and never get rebound, which is most of them for a typical query.
        val diff = DiffUtil.calculateDiff(FilterDiffCallback(previous, updated), false)
        filteredItems = updated
        diff.dispatchUpdatesTo(this)
        return true
    }

    private class FilterDiffCallback(
        private val old: List<Any>,
        private val new: List<Any>
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = old.size
        override fun getNewListSize(): Int = new.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldItem = old[oldItemPosition]
            val newItem = new[newItemPosition]
            // AppInfo.equals already compares by packageName only
            return if (oldItem is AppInfo && newItem is AppInfo) oldItem == newItem
            else oldItem === newItem
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldItem = old[oldItemPosition]
            val newItem = new[newItemPosition]
            return if (oldItem is AppInfo && newItem is AppInfo) {
                oldItem.name == newItem.name && oldItem.icon === newItem.icon
            } else true
        }
    }

    // Phase 3: Implement ThemeAware interface
    override fun onThemeChanged(theme: AppTheme) {
        currentTheme = theme
        textColors = context.getColorStateList(R.color.context_menu_text_selector)
        themeTypeface = (context as? MainActivity)?.getThemePrimaryFont()
        notifyDataSetChanged()
    }

    // Backward compatible method
    fun setTheme(isWindows98: Boolean) {
        onThemeChanged(if (isWindows98) AppTheme.WindowsClassic else AppTheme.WindowsXP)
    }
}
