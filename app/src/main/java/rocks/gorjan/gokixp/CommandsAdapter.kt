package rocks.gorjan.gokixp

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import rocks.gorjan.gokixp.theme.AppTheme
import rocks.gorjan.gokixp.theme.ThemeAware

data class CommandItem(
    val name: String,
    val iconResourceId: Int,
    val action: () -> Unit = {}
)

sealed class CommandListItem {
    data class Command(val commandItem: CommandItem) : CommandListItem()
    object Divider : CommandListItem()
    data class RecentApp(val appInfo: AppInfo) : CommandListItem()
    data class ProgramsCommand(val commandItem: CommandItem) : CommandListItem()
}

class CommandsAdapter(
    private val context: Context,
    private val items: List<CommandListItem>,
    private val onAppLaunched: ((AppInfo) -> Unit)? = null,
    private val onItemClicked: (() -> Unit)? = null,
    private val onAppLongClicked: ((AppInfo, Float, Float) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>(), ThemeAware {

    private var currentTheme: AppTheme = AppTheme.WindowsXP
    private var isProgramsExpanded = false

    // Backward compatible property
    private var isWindows98Theme = false
        get() = currentTheme is AppTheme.WindowsClassic

    companion object {
        private const val TYPE_COMMAND = 0
        private const val TYPE_DIVIDER = 1
        private const val TYPE_RECENT_APP = 2
        private const val TYPE_PROGRAMS = 3
    }

    inner class CommandViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.command_icon)
        val name: TextView = itemView.findViewById(R.id.command_name)

        init {
            itemView.setOnClickListener {
                val item = items[adapterPosition]
                if (item is CommandListItem.Command) {
                    item.commandItem.action()
                    onItemClicked?.invoke()
                }
            }
        }
    }

    inner class ProgramsViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.command_icon)
        val name: TextView = itemView.findViewById(R.id.command_name)
        val arrow: TextView = itemView.findViewById(R.id.arrow_indicator)

        init {
            itemView.setOnClickListener {
                val item = items[adapterPosition]
                if (item is CommandListItem.ProgramsCommand) {
                    item.commandItem.action()
                    // Don't call onItemClicked for Programs command - it should toggle, not close the menu
                }
            }
        }
    }

    class DividerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    inner class RecentAppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.command_icon)
        val name: TextView = itemView.findViewById(R.id.command_name)

        init {
            itemView.setOnClickListener {
                val item = items[adapterPosition]
                if (item is CommandListItem.RecentApp) {
                    launchApp(item.appInfo)
                    onAppLaunched?.invoke(item.appInfo)
                    onItemClicked?.invoke()
                }
            }
            
            itemView.setOnLongClickListener {
                // Disable default haptic feedback to avoid double vibration
                // (Our ContextMenuView will handle the vibration)
                itemView.isHapticFeedbackEnabled = false
                
                val item = items[adapterPosition]
                if (item is CommandListItem.RecentApp) {
                    // Get touch position for context menu
                    val location = IntArray(2)
                    itemView.getLocationOnScreen(location)
                    val screenX = location[0] + itemView.width / 2f
                    val screenY = location[1] + itemView.height / 2f
                    
                    onAppLongClicked?.invoke(item.appInfo, screenX, screenY)
                }
                true
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is CommandListItem.Command -> TYPE_COMMAND
            is CommandListItem.Divider -> TYPE_DIVIDER
            is CommandListItem.RecentApp -> TYPE_RECENT_APP
            is CommandListItem.ProgramsCommand -> TYPE_PROGRAMS
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_DIVIDER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.command_divider_item, parent, false)
                DividerViewHolder(view)
            }
            TYPE_RECENT_APP -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.command_list_item, parent, false)
                RecentAppViewHolder(view)
            }
            TYPE_PROGRAMS -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.programs_command_item, parent, false)
                ProgramsViewHolder(view)
            }
            else -> { // TYPE_COMMAND
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.command_list_item, parent, false)
                CommandViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is CommandListItem.Command -> {
                val commandHolder = holder as CommandViewHolder
                commandHolder.icon.setImageResource(item.commandItem.iconResourceId)
                commandHolder.name.text = item.commandItem.name

                // Apply theme font
                val mainActivity = context as? MainActivity
                mainActivity?.applyThemeFontToTextView(commandHolder.name)
            }
            is CommandListItem.Divider -> {
                // Nothing to bind for divider
            }
            is CommandListItem.RecentApp -> {
                val recentAppHolder = holder as RecentAppViewHolder
                val appInfo = item.appInfo
                recentAppHolder.name.text = appInfo.name

                // Use central icon function with custom icon support
                val mainActivity = context as? MainActivity
                val appIcon = mainActivity?.getAppIcon(appInfo.packageName) ?: appInfo.icon
                recentAppHolder.icon.setImageDrawable(appIcon)

                // Apply theme font
                mainActivity?.applyThemeFontToTextView(recentAppHolder.name)
            }
            is CommandListItem.ProgramsCommand -> {
                val programsHolder = holder as ProgramsViewHolder
                programsHolder.icon.setImageResource(item.commandItem.iconResourceId)
                programsHolder.name.text = item.commandItem.name

                // Apply theme font (Programs uses secondary font which could be different)
                val mainActivity = context as? MainActivity
                mainActivity?.applyThemeFontToTextView(programsHolder.name, usePrimary = false)
                mainActivity?.applyThemeFontToTextView(programsHolder.arrow, usePrimary = false)

                // Set appearance based on expanded state
                if (isProgramsExpanded) {
                    // Selected state - blue background, white text
                    programsHolder.itemView.setBackgroundColor(android.graphics.Color.parseColor("#01007b"))
                    programsHolder.name.setTextColor(android.graphics.Color.WHITE)
                    programsHolder.arrow.setTextColor(android.graphics.Color.WHITE)
                } else {
                    // Normal state - gray background, black text
                    programsHolder.itemView.setBackgroundColor(android.graphics.Color.parseColor("#d3cec7"))
                    programsHolder.name.setTextColor(android.graphics.Color.BLACK)
                    programsHolder.arrow.setTextColor(android.graphics.Color.BLACK)
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    private fun launchApp(appInfo: AppInfo) {
        try {
            // Check if it's a system app
            if (appInfo.packageName.startsWith("system.")) {
                // Launch system app through MainActivity
                val mainActivity = context as? MainActivity
                mainActivity?.launchSystemApp(appInfo.packageName)
            } else {
                // Launch regular Android app
                val packageManager = context.packageManager
                val launchIntent = packageManager.getLaunchIntentForPackage(appInfo.packageName)
                if (launchIntent != null) {
                    context.startActivity(launchIntent)
                }
            }
        } catch (e: Exception) {
            // Handle launch error
        }
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

    fun setProgramsExpanded(expanded: Boolean) {
        if (isProgramsExpanded != expanded) {
            isProgramsExpanded = expanded
            notifyDataSetChanged()
        }
    }
}