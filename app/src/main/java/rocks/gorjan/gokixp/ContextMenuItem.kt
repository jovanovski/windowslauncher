package rocks.gorjan.gokixp

data class ContextMenuItem(
    val title: String,
    val isEnabled: Boolean = true,
    val hasSubmenu: Boolean = false,
    val hasCheckbox: Boolean = false,
    val isChecked: Boolean = false,
    val action: (() -> Unit)? = null,
    val subActionIcon: Int? = null,  // Drawable resource ID
    val subAction: (() -> Unit)? = null
)

object ContextMenuItems {
    // Desktop context menu items
    fun getDesktopMenuItems(
        onRefresh: () -> Unit,
        onChangeWallpaper: () -> Unit,
        onOpenInternetExplorer: () -> Unit,
        onNewFolder: () -> Unit
    ): List<ContextMenuItem> {
        return listOf(
            ContextMenuItem("Arrange Icons By", isEnabled = false, hasSubmenu = true),
            ContextMenuItem("Refresh", isEnabled = true, action = onRefresh),
            ContextMenuItem("", isEnabled = false), // Divider
            ContextMenuItem("Paste", isEnabled = false),
            ContextMenuItem("Paste Shortcut", isEnabled = false),
            ContextMenuItem("", isEnabled = false), // Divider
            ContextMenuItem("New Folder", isEnabled = true, action = onNewFolder),
            ContextMenuItem("", isEnabled = false), // Divider
            ContextMenuItem("Properties", isEnabled = true, action = onChangeWallpaper)
        )
    }
    
    // Desktop icon context menu items
    fun getDesktopIconMenuItems(
        onOpen: () -> Unit,
        onMoveIcon: () -> Unit,
        onChangeIcon: () -> Unit,
        onDelete: () -> Unit,
        onRename: () -> Unit,
        onProperties: () -> Unit,
        onSetSwipeRightApp: () -> Unit,
        onSetWeatherApp: () -> Unit,
        isSystemApp: Boolean = false
    ): List<ContextMenuItem> {
        val items = mutableListOf(
            ContextMenuItem("Open", isEnabled = true, action = onOpen),
            ContextMenuItem("", isEnabled = false), // Divider
            ContextMenuItem("Move Icon", isEnabled = true, action = onMoveIcon),
            ContextMenuItem("Change Icon", isEnabled = true, action = onChangeIcon),
            ContextMenuItem("Set as Swipe Right App", isEnabled = true, action = onSetSwipeRightApp),
            ContextMenuItem("Set as Weather App", isEnabled = true, action = onSetWeatherApp),
            ContextMenuItem("", isEnabled = false), // Divider
            ContextMenuItem("Delete", isEnabled = true, action = onDelete),
            ContextMenuItem("Rename", isEnabled = true, action = onRename)
        )

        // Only add Properties for non-system apps
        if (!isSystemApp) {
            items.add(ContextMenuItem("", isEnabled = false)) // Divider
            items.add(ContextMenuItem("Properties", isEnabled = true, action = onProperties))
        }

        return items
    }
    
    // Start menu app context menu items
    fun getStartMenuAppMenuItems(
        onCreateShortcut: () -> Unit,
        onUninstall: () -> Unit,
        onProperties: () -> Unit,
        onPinToggle: () -> Unit,
        isPinned: Boolean,
        onSetSwipeRightApp: () -> Unit,
        onSetWeatherApp: () -> Unit,
        onChangeIcon: () -> Unit,
        isSystemApp: Boolean = false
    ): List<ContextMenuItem> {
        val pinText = if (isPinned) "Unpin from Start" else "Pin to Start"
        val items = mutableListOf(
            ContextMenuItem("Send to Desktop", isEnabled = true, action = onCreateShortcut),
            ContextMenuItem(pinText, isEnabled = true, action = onPinToggle),
            ContextMenuItem("", isEnabled = false), // Divider
            ContextMenuItem("Set as Swipe Right App", isEnabled = true, action = onSetSwipeRightApp),
            ContextMenuItem("Set as Weather App", isEnabled = true, action = onSetWeatherApp),
            ContextMenuItem("Change Icon", isEnabled = true, action = onChangeIcon)
        )

        // Only add Uninstall and Properties for non-system apps
        if (!isSystemApp) {
            items.add(ContextMenuItem("", isEnabled = false)) // Divider
            items.add(ContextMenuItem("Uninstall", isEnabled = true, action = onUninstall))
            items.add(ContextMenuItem("Properties", isEnabled = true, action = onProperties))
        }

        return items
    }
    
    // Pinned app context menu items (in commands panel)
    fun getPinnedAppMenuItems(
        onUnpin: () -> Unit,
        onProperties: () -> Unit
    ): List<ContextMenuItem> {
        return listOf(
            ContextMenuItem("Unpin from Start", isEnabled = true, action = onUnpin),
            ContextMenuItem("", isEnabled = false), // Divider
            ContextMenuItem("Properties", isEnabled = true, action = onProperties)
        )
    }
    
    // Recycle bin context menu items
    fun getRecycleBinMenuItems(
        onEmptyRecycleBin: () -> Unit,
        onMoveRecycleBin: () -> Unit,
        onHideRecycleBin: () -> Unit
    ): List<ContextMenuItem> {
        return listOf(
            ContextMenuItem("Empty Recycle Bin", isEnabled = true, action = onEmptyRecycleBin),
            ContextMenuItem("", isEnabled = false), // Divider
            ContextMenuItem("Move", isEnabled = true, action = onMoveRecycleBin),
            ContextMenuItem("Hide Recycle Bin", isEnabled = true, action = onHideRecycleBin)
        )
    }
    
    // Quick Glance widget context menu items
    fun getQuickGlanceMenuItems(
        onHideQuickGlance: () -> Unit,
        onRefreshCalendar: () -> Unit,
        onToggleCalendarEvents: () -> Unit,
        isCalendarEventsEnabled: Boolean
    ): List<ContextMenuItem> {
        return listOf(
            ContextMenuItem("Show Calendar Events", isEnabled = true, hasCheckbox = true, isChecked = isCalendarEventsEnabled, action = onToggleCalendarEvents),
            ContextMenuItem("", isEnabled = false), // Divider
            ContextMenuItem("Refresh Calendar", isEnabled = true, action = onRefreshCalendar),
            ContextMenuItem("", isEnabled = false), // Divider
            ContextMenuItem("Hide Quick Glance", isEnabled = true, action = onHideQuickGlance)
        )
    }
    
    // Start menu context menu items (for long press on start menu itself)
    fun getStartMenuMenuItems(
        onOpenSettings: () -> Unit,
        onRefreshAppList: () -> Unit
    ): List<ContextMenuItem> {
        return listOf(
            ContextMenuItem("Settings", isEnabled = true, action = onOpenSettings),
            ContextMenuItem("", isEnabled = false), // Divider
            ContextMenuItem("Refresh App List", isEnabled = true, action = onRefreshAppList)
        )
    }

    // Folder context menu items
    fun getFolderMenuItems(
        onMove: () -> Unit,
        onRename: () -> Unit,
        onDelete: () -> Unit,
        onChangeIcon: () -> Unit
    ): List<ContextMenuItem> {
        return listOf(
            ContextMenuItem("Move Icon", isEnabled = true, action = onMove),
            ContextMenuItem("Change Icon", isEnabled = true, action = onChangeIcon),
            ContextMenuItem("", isEnabled = false), // Divider
            ContextMenuItem("Delete", isEnabled = true, action = onDelete),
            ContextMenuItem("Rename", isEnabled = true, action = onRename)
        )
    }

    // App inside folder context menu items
    fun getFolderAppMenuItems(
        onOpen: () -> Unit,
        onMoveToDesktop: () -> Unit,
        onChangeIcon: () -> Unit,
        onDelete: () -> Unit,
        onRename: () -> Unit,
        onProperties: () -> Unit,
        onSetSwipeRightApp: () -> Unit,
        onSetWeatherApp: () -> Unit,
        isSystemApp: Boolean = false
    ): List<ContextMenuItem> {
        val items = mutableListOf(
            ContextMenuItem("Open", isEnabled = true, action = onOpen),
            ContextMenuItem("", isEnabled = false), // Divider
            ContextMenuItem("Move to Desktop", isEnabled = true, action = onMoveToDesktop),
            ContextMenuItem("Change Icon", isEnabled = true, action = onChangeIcon),
            ContextMenuItem("Set as Swipe Right App", isEnabled = true, action = onSetSwipeRightApp),
            ContextMenuItem("Set as Weather App", isEnabled = true, action = onSetWeatherApp),
            ContextMenuItem("", isEnabled = false), // Divider
            ContextMenuItem("Delete", isEnabled = true, action = onDelete),
            ContextMenuItem("Rename", isEnabled = true, action = onRename)
        )

        // Only add Properties for non-system apps
        if (!isSystemApp) {
            items.add(ContextMenuItem("", isEnabled = false)) // Divider
            items.add(ContextMenuItem("Properties", isEnabled = true, action = onProperties))
        }

        return items
    }

    // Taskbar button context menu items
    fun getTaskbarMenuItems(
        isMinimized: Boolean,
        onMinimizeRestore: () -> Unit,
        onClose: () -> Unit
    ): List<ContextMenuItem> {
        val minimizeRestoreText = if (isMinimized) "Restore" else "Minimize"
        return listOf(
            ContextMenuItem(minimizeRestoreText, isEnabled = true, action = onMinimizeRestore),
            ContextMenuItem("", isEnabled = false), // Divider
            ContextMenuItem("Close", isEnabled = true, action = onClose)
        )
    }
}