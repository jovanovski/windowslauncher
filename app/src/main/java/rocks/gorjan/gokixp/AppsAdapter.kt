package rocks.gorjan.gokixp

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
    private val recentApps: Set<String> = emptySet()
) : RecyclerView.Adapter<RecyclerView.ViewHolder>(), ThemeAware {

    private var filteredItems: List<Any> = originalItems
    private var currentTheme: AppTheme = AppTheme.WindowsXP

    // Backward compatible property
    private var isWindows98Theme = false
        get() = currentTheme is AppTheme.WindowsClassic

    companion object {
        private const val TYPE_APP = 0
        private const val TYPE_SEPARATOR = 1
    }

    class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val appIcon: ImageView = itemView.findViewById(R.id.app_icon)
        val appName: TextView = itemView.findViewById(R.id.app_name)
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
        when (holder) {
            is AppViewHolder -> {
                val app = filteredItems[position] as AppInfo

                // Use pre-loaded icon from AppInfo (icons loaded when start menu opened)
                holder.appIcon.setImageDrawable(app.icon)
                holder.appName.text = app.name

                // Set text color using ColorStateList to support pressed states
                holder.appName.setTextColor(context.getColorStateList(R.color.context_menu_text_selector))

                // Apply theme font
                val mainActivity = context as? MainActivity
                mainActivity?.applyThemeFontToTextView(holder.appName)
                
                // Set background based on whether app is in the recent section (before separator)
                val isInRecentSection = isAppInRecentSection(position)
                if (isInRecentSection) {
                    // For recent apps (at top of list), create a layered drawable with pinned background and pressed state
                    val pinnedBackground = context.getDrawable(R.drawable.start_menu_item_background_pinned)
                    holder.itemView.background = pinnedBackground
                } else {
                    // For regular apps, use the standard background with pressed state
                    holder.itemView.setBackgroundResource(R.drawable.start_menu_item_background)
                }
                
                holder.itemView.setOnClickListener {
                    // Check if this is a system app
                    if (MainActivity.isSystemApp(app.packageName)) {
                        // Launch system app
                        val mainActivity = context as? MainActivity
                        mainActivity?.launchSystemApp(app.packageName)
                    } else {
                        // Launch regular app
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
                
                // Add long press listener for desktop icon creation
                holder.itemView.setOnLongClickListener {
                    // Disable default haptic feedback to avoid double vibration
                    // (Our ContextMenuView will handle the vibration)
                    holder.itemView.isHapticFeedbackEnabled = false
                    
                    // Get touch position (simplified - just use view center for now)
                    val location = IntArray(2)
                    holder.itemView.getLocationOnScreen(location)
                    val screenX = location[0] + holder.itemView.width / 2f
                    val screenY = location[1] + holder.itemView.height / 2f
                    
                    onAppLongClick?.invoke(app, screenX, screenY)
                    true
                }
            }
            is SeparatorViewHolder -> {
                // No binding needed for separator
            }
        }
    }

    override fun getItemCount(): Int = filteredItems.size

    private fun isAppInRecentSection(position: Int): Boolean {
        // With the new pinned apps system, no apps in the main list should have blue background
        // Blue background was only for recent apps that were at the top of the list
        // Now all apps are treated equally in the main list
        return false
    }

    fun filter(query: String) {
        filteredItems = if (query.isEmpty()) {
            originalItems
        } else {
            originalItems.filter { item ->
                when (item) {
                    is AppInfo -> {
                        // Include all apps that match the query, including recent apps
                        item.name.lowercase().contains(query.lowercase())
                    }
                    is String -> false // Hide separators during search
                    else -> false
                }
            }
        }
        notifyDataSetChanged()
    }

    // Phase 3: Implement ThemeAware interface
    override fun onThemeChanged(theme: AppTheme) {
        currentTheme = theme
        notifyDataSetChanged()
    }

    // Backward compatible method
    fun setTheme(isWindows98: Boolean) {
        currentTheme = if (isWindows98) AppTheme.WindowsClassic else AppTheme.WindowsXP
        notifyDataSetChanged()
    }
}