package rocks.gorjan.gokixp

import android.graphics.drawable.Drawable

data class ContextMenuItem(
    val title: String,
    val isEnabled: Boolean = true,
    val hasSubmenu: Boolean = false,
    val hasCheckbox: Boolean = false,
    val isChecked: Boolean = false,
    val action: (() -> Unit)? = null,
    val subActionIcon: Int? = null,  // Drawable resource ID
    val subAction: (() -> Unit)? = null,
    /**
     * The key stroke shown right-aligned on a program's menu - "Ctrl+O", "F5", "Del".
     *
     * A phone has no keyboard to press it on, so it is decoration; it is also half of what
     * makes a File menu read as a File menu, so programs still set it.
     */
    val shortcut: String? = null,
    /** What opens to the side. Setting this implies [hasSubmenu]. */
    val submenu: List<ContextMenuItem>? = null,
    /**
     * The picture beside the words, for a menu of programs - the Start menu's folders. A menu
     * where any item has one is laid out like the Start menu's, roomier and with icons.
     */
    val icon: Drawable? = null,
) {
    /** A menu separator is an item with nothing in it. */
    val isSeparator: Boolean get() = title.isEmpty()

    val opensSubmenu: Boolean get() = hasSubmenu || !submenu.isNullOrEmpty()

    companion object {
        /** The line between two groups of commands. */
        fun separator() = ContextMenuItem("", isEnabled = false)
    }
}

/** "Pin to Taskbar" / "Unpin from Taskbar", offered only by the Windows 7 superbar. */
data class TaskbarPinItem(val isPinned: Boolean, val onToggle: () -> Unit) {
    fun toMenuItem() =
        ContextMenuItem(if (isPinned) "Unpin from Taskbar" else "Pin to Taskbar", isEnabled = true, action = onToggle)
}

object ContextMenuItems {
    // Desktop context menu items
    fun getDesktopMenuItems(
        onRefresh: () -> Unit,
        onChangeWallpaper: () -> Unit,
        onOpenInternetExplorer: () -> Unit,
        onNewFolder: () -> Unit,
        onAddWidget: () -> Unit
    ): List<ContextMenuItem> {
        return listOf(
            ContextMenuItem("Arrange Icons By", isEnabled = false, hasSubmenu = true),
            ContextMenuItem("Refresh", isEnabled = true, action = onRefresh),
            ContextMenuItem("", isEnabled = false), // Divider
            ContextMenuItem("Paste", isEnabled = false),
            ContextMenuItem("Paste Shortcut", isEnabled = false),
            ContextMenuItem("", isEnabled = false), // Divider
            ContextMenuItem("New Folder", isEnabled = true, action = onNewFolder),
            ContextMenuItem("Add Widget...", isEnabled = true, action = onAddWidget),
            ContextMenuItem("", isEnabled = false), // Divider
            ContextMenuItem("Properties", isEnabled = true, action = onChangeWallpaper)
        )
    }

    // An Android app widget on the desktop. Configure only for widgets that support it.
    fun getDesktopWidgetMenuItems(
        onMoveResize: () -> Unit,
        onConfigure: (() -> Unit)?,
        onRemove: () -> Unit
    ): List<ContextMenuItem> {
        val items = mutableListOf(
            ContextMenuItem("Move / Resize", isEnabled = true, action = onMoveResize)
        )
        if (onConfigure != null) {
            items.add(ContextMenuItem("Configure...", isEnabled = true, action = onConfigure))
        }
        items.add(ContextMenuItem.separator())
        items.add(ContextMenuItem("Remove Widget", isEnabled = true, action = onRemove))
        return items
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
        isSystemApp: Boolean = false,
        isUrlShortcut: Boolean = false,
        taskbarPin: TaskbarPinItem? = null
    ): List<ContextMenuItem> {
        val items = mutableListOf(
            ContextMenuItem("Open", isEnabled = true, action = onOpen)
        )
        taskbarPin?.let { items.add(it.toMenuItem()) }
        items.addAll(listOf(
            ContextMenuItem("", isEnabled = false), // Divider
            ContextMenuItem("Move Icon", isEnabled = true, action = onMoveIcon),
            ContextMenuItem("Change Icon", isEnabled = true, action = onChangeIcon)
        ))

        // "Set as Swipe/Weather App" only make sense for launchable apps, not URL shortcuts
        if (!isUrlShortcut) {
            items.add(ContextMenuItem("Set as Swipe Right App", isEnabled = true, action = onSetSwipeRightApp))
            items.add(ContextMenuItem("Set as Weather App", isEnabled = true, action = onSetWeatherApp))
        }

        items.add(ContextMenuItem("", isEnabled = false)) // Divider
        items.add(ContextMenuItem("Delete", isEnabled = true, action = onDelete))
        items.add(ContextMenuItem("Rename", isEnabled = true, action = onRename))

        // Only add Properties for non-system apps (and not URL shortcuts, which have no app info)
        if (!isSystemApp && !isUrlShortcut) {
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
        onHideToggle: () -> Unit,
        isHidden: Boolean = false,
        isSystemApp: Boolean = false,
        taskbarPin: TaskbarPinItem? = null
    ): List<ContextMenuItem> {
        val pinText = if (isPinned) "Unpin from Start" else "Pin to Start"
        val hideText = if (isHidden) "Unhide app" else "Hide app"
        val items = mutableListOf(
            ContextMenuItem("Send to Desktop", isEnabled = true, action = onCreateShortcut),
            ContextMenuItem(pinText, isEnabled = true, action = onPinToggle)
        )
        taskbarPin?.let { items.add(it.toMenuItem()) }
        items.addAll(listOf(
            ContextMenuItem(hideText, isEnabled = true, action = onHideToggle),
            ContextMenuItem("", isEnabled = false), // Divider
            ContextMenuItem("Set as Swipe Right App", isEnabled = true, action = onSetSwipeRightApp),
            ContextMenuItem("Set as Weather App", isEnabled = true, action = onSetWeatherApp),
            ContextMenuItem("Change Icon", isEnabled = true, action = onChangeIcon)
        ))

        // Only add Uninstall and Properties for non-system apps
        if (!isSystemApp) {
            items.add(ContextMenuItem("", isEnabled = false)) // Divider
            items.add(ContextMenuItem("Uninstall", isEnabled = true, action = onUninstall))
            items.add(ContextMenuItem("Properties", isEnabled = true, action = onProperties))
        }

        return items
    }
    
    // Start menu app that lives in the private space. Pinning, hiding, desktop shortcuts and
    // icon overrides are all stored by package name, which can't tell this copy of an app
    // from the one outside, so they aren't offered. Uninstalling is on the app's info page.
    fun getPrivateSpaceAppMenuItems(
        onOpen: () -> Unit,
        onProperties: () -> Unit,
        onLock: () -> Unit
    ): List<ContextMenuItem> {
        return listOf(
            ContextMenuItem("Open", isEnabled = true, action = onOpen),
            ContextMenuItem("", isEnabled = false), // Divider
            ContextMenuItem("Lock Private Space", isEnabled = true, action = onLock),
            ContextMenuItem("", isEnabled = false), // Divider
            ContextMenuItem("Properties", isEnabled = true, action = onProperties)
        )
    }

    // The "Private" row heading the private space's apps
    fun getPrivateSpaceMenuItems(
        isLocked: Boolean,
        onToggleLock: () -> Unit,
        onSettings: () -> Unit
    ): List<ContextMenuItem> {
        return listOf(
            ContextMenuItem(if (isLocked) "Unlock" else "Lock", isEnabled = true, action = onToggleLock),
            ContextMenuItem("", isEnabled = false), // Divider
            ContextMenuItem("Private Space Settings", isEnabled = true, action = onSettings)
        )
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
        onMoveQuickGlance: () -> Unit,
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
            ContextMenuItem("Move", isEnabled = true, action = onMoveQuickGlance),
            ContextMenuItem("Hide Quick Glance", isEnabled = true, action = onHideQuickGlance)
        )
    }
    
    // Start menu context menu items (for long press on start menu itself)
    fun getStartMenuMenuItems(
        onOpenSettings: () -> Unit,
        onRefreshAppList: () -> Unit,
        onOpenWithHiddenApps: () -> Unit
    ): List<ContextMenuItem> {
        return listOf(
            ContextMenuItem("Settings", isEnabled = true, action = onOpenSettings),
            ContextMenuItem("", isEnabled = false), // Divider
            ContextMenuItem("Refresh App List", isEnabled = true, action = onRefreshAppList),
            ContextMenuItem("", isEnabled = false), // Divider
            ContextMenuItem("Open with hidden apps", isEnabled = true, action = onOpenWithHiddenApps)
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
        onClose: () -> Unit,
        taskbarPin: TaskbarPinItem? = null
    ): List<ContextMenuItem> {
        val minimizeRestoreText = if (isMinimized) "Restore" else "Minimize"
        return listOfNotNull(
            taskbarPin?.toMenuItem(),
            taskbarPin?.let { ContextMenuItem("", isEnabled = false) }, // Divider
            ContextMenuItem(minimizeRestoreText, isEnabled = true, action = onMinimizeRestore),
            ContextMenuItem("", isEnabled = false), // Divider
            ContextMenuItem("Close", isEnabled = true, action = onClose)
        )
    }

    // My Computer context menu items
    // My Briefcase context menu items
    fun getBriefcaseMenuItems(
        onOpen: () -> Unit,
        onUpdateAll: () -> Unit,
        onMove: () -> Unit,
        onHideBriefcase: () -> Unit
    ): List<ContextMenuItem> {
        return listOf(
            ContextMenuItem("Open", isEnabled = true, action = onOpen),
            ContextMenuItem("Update All", isEnabled = true, action = onUpdateAll),
            ContextMenuItem("", isEnabled = false), // Divider
            ContextMenuItem("Move Icon", isEnabled = true, action = onMove),
            ContextMenuItem("Hide My Briefcase", isEnabled = true, action = onHideBriefcase)
        )
    }

    fun getMyComputerMenuItems(
        onOpen: () -> Unit,
        onMove: () -> Unit,
        onHideMyComputer: () -> Unit,
        onProperties: () -> Unit
    ): List<ContextMenuItem> {
        return listOf(
            ContextMenuItem("Open", isEnabled = true, action = onOpen),
            ContextMenuItem("", isEnabled = false), // Divider
            ContextMenuItem("Move Icon", isEnabled = true, action = onMove),
            ContextMenuItem("Hide My Computer", isEnabled = true, action = onHideMyComputer),
            ContextMenuItem("Properties", isEnabled = true, action = onProperties)
        )
    }
}