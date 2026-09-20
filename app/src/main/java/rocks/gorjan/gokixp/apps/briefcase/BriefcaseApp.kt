package rocks.gorjan.gokixp.apps.briefcase

import android.content.Context
import android.view.View
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import rocks.gorjan.gokixp.ContextMenuItem
import rocks.gorjan.gokixp.Helpers
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.apps.dialogbox.DialogType
import rocks.gorjan.gokixp.apps.explorer.FileOpener
import rocks.gorjan.gokixp.apps.explorer.FileSystemAdapter
import rocks.gorjan.gokixp.apps.explorer.FileSystemItem
import rocks.gorjan.gokixp.theme.AppTheme
import rocks.gorjan.gokixp.theme.ThemeManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The My Briefcase window: one flat folder, shared with a computer.
 *
 * It borrows Explorer's chrome, the way My Computer does, and adds the one thing a Briefcase
 * has that a folder does not - a state. The status bar says it in the desktop's own words:
 * *Shared*, *Sending…*, *Not connected*. Everything else is [Briefcase]'s work; this class
 * asks, listens, and draws.
 */
class BriefcaseApp(
    private val context: Context,
    private val theme: AppTheme,
    private val themeManager: ThemeManager,
    private val onSoundPlay: () -> Unit,
    private val onUpdateWindowTitle: (String) -> Unit,
    private val onSetCursorBusy: () -> Unit,
    private val onSetCursorNormal: () -> Unit,
    private val onShowDialog: (DialogType, String) -> Unit,
    private val onShowContextMenu: (List<ContextMenuItem>, Float, Float) -> Unit,
    private val onShowRenameDialog: (File, (String) -> Unit) -> Unit,
    private val onShowConfirmDialog: (String, String, () -> Unit) -> Unit,
    /** Opens the Connect a Phone dialog. */
    private val onConnect: () -> Unit,
    /** Opens the system file picker so a file can be put in. */
    private val onAddFile: () -> Unit,
    /** Whether the window is actually on screen - a minimised one is not watched. */
    private val isWindowVisible: () -> Boolean = { true }
) {

    private val briefcase = Briefcase.get(context)

    private var folderIconsGrid: GridView? = null
    private var folderNameSmall: TextView? = null
    private var folderNameLarge: TextView? = null
    private var folderIconSmall: ImageView? = null
    private var folderIconLarge: ImageView? = null
    private var statusText: TextView? = null

    private var adapter: FileSystemAdapter? = null
    private var snapshot: Briefcase.Snapshot = briefcase.snapshot()

    /** What the grid was last built from, so a poll that changes nothing redraws nothing. */
    private var drawnSignature: String? = null

    private val listener = Briefcase.Listener { next ->
        snapshot = next
        render()
    }

    /** Held so the same instance can be handed back to [Briefcase.unwatch]. */
    private val visibility: () -> Boolean = { isWindowVisible() }
    private var closed = false

    // ---- Setting up ------------------------------------------------------------------------

    fun setupApp(contentView: View): View {
        folderIconsGrid = contentView.findViewById(R.id.folder_icons_grid)
        folderNameSmall = contentView.findViewById(R.id.folder_name_small)
        folderNameLarge = contentView.findViewById(R.id.folder_name_large)
        folderIconSmall = contentView.findViewById(R.id.folder_icon_small)
        folderIconLarge = contentView.findViewById(R.id.folder_icon_large)
        statusText = contentView.findViewById(R.id.explorer_number_of_items)

        // The Briefcase is flat - there is nowhere to go back, forward or up to.
        listOf(R.id.back_button, R.id.forward_button, R.id.up_button).forEach { id ->
            contentView.findViewById<View>(id)?.setOnClickListener { onSoundPlay() }
        }

        folderNameSmall?.text = TITLE
        folderNameLarge?.text = TITLE
        val icon = themeManager.getBriefcaseIcon()
        folderIconSmall?.setImageResource(icon)
        folderIconLarge?.setImageResource(icon)
        onUpdateWindowTitle(TITLE)

        setupEmptySpaceMenu()

        briefcase.addListener(listener)
        briefcase.watch(visibility)

        // The window's own close listener is the usual way this ends; losing the view is the
        // one that cannot be missed, whichever way the window went.
        contentView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) = onWindowClosed()
        })

        onSoundPlay()
        return contentView
    }

    /** Called when the window closes: stop asking a computer nobody is looking at. */
    fun onWindowClosed() {
        if (closed) return
        closed = true
        briefcase.removeListener(listener)
        briefcase.unwatch(visibility)
    }

    fun setupContextMenuCallback(contextMenu: rocks.gorjan.gokixp.ContextMenuView) {
        contextMenu.setOnMenuHiddenListener { adapter?.clearSelection() }
    }

    private fun setupEmptySpaceMenu() {
        var longPressHandler: android.os.Handler? = null
        var longPressRunnable: Runnable? = null
        var touchDownX = 0f
        var touchDownY = 0f

        folderIconsGrid?.setOnTouchListener { view, event ->
            val grid = view as? GridView ?: return@setOnTouchListener false
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    touchDownX = event.rawX
                    touchDownY = event.rawY
                    if (grid.pointToPosition(event.x.toInt(), event.y.toInt()) == GridView.INVALID_POSITION) {
                        longPressRunnable = Runnable {
                            Helpers.performHapticFeedback(context)
                            showFolderMenu(touchDownX, touchDownY)
                        }
                        longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
                        longPressHandler?.postDelayed(longPressRunnable!!, 500)
                    }
                    false
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { longPressHandler?.removeCallbacks(it) }
                    longPressRunnable = null
                    longPressHandler = null
                    if (event.action == android.view.MotionEvent.ACTION_UP &&
                        grid.pointToPosition(event.x.toInt(), event.y.toInt()) == GridView.INVALID_POSITION
                    ) {
                        adapter?.clearSelection()
                    }
                    false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(event.rawX - touchDownX) > 10 || Math.abs(event.rawY - touchDownY) > 10) {
                        longPressRunnable?.let { longPressHandler?.removeCallbacks(it) }
                        longPressRunnable = null
                        longPressHandler = null
                    }
                    false
                }
                else -> false
            }
        }
    }

    // ---- Drawing ---------------------------------------------------------------------------

    private fun render() {
        val entries = snapshot.entries
        val signature = entries.joinToString("|") { "${it.name}:${snapshot.stateOf(it)}" }
        if (signature != drawnSignature) {
            drawnSignature = signature
            val folder = briefcase.folder()
            val items = entries.map { entry ->
                FileSystemItem(
                    file = entry.file(folder),
                    name = entry.name,
                    isDirectory = false,
                    size = entry.size,
                    lastModified = entry.modified * 1000L
                )
            }

            adapter = FileSystemAdapter(
                context = context,
                items = items,
                theme = theme,
                themeManager = themeManager,
                onItemClick = { item, _ -> entryFor(item)?.let { openEntry(it) } },
                onItemLongClick = { item, x, y -> entryFor(item)?.let { showFileMenu(it, x, y) } },
                isFileDimmed = { item ->
                    val entry = entryFor(item)
                    entry != null && snapshot.stateOf(entry) in DIMMED
                }
            )
            folderIconsGrid?.adapter = adapter
        }

        statusText?.text = buildString {
            append(entries.size)
            append(if (entries.size == 1) " object" else " objects")
            append("   ")
            append(statusLine())
        }
    }

    /** Section 5.6: say what is happening in as few words as the status bar has room for. */
    private fun statusLine(): String {
        snapshot.message?.let { return it }
        return when (snapshot.status) {
            Briefcase.Status.NOT_CONNECTED -> "Not connected"
            Briefcase.Status.SYNCING ->
                if (snapshot.entries.any { it.pending }) "Sending..." else "Updating..."
            Briefcase.Status.WAITING_FOR_WIFI -> "Waiting for Wi-Fi"
            Briefcase.Status.OFFLINE -> "Cannot reach ${snapshot.site.ifEmpty { "the computer" }}"
            Briefcase.Status.UP_TO_DATE ->
                if (snapshot.entries.any { it.pending }) "Sending..."
                else "Shared with ${snapshot.site.ifEmpty { "the computer" }}"
        }
    }

    private fun entryFor(item: FileSystemItem): BriefcaseEntry? =
        snapshot.entries.firstOrNull { it.name == item.name }

    private fun refresh() {
        snapshot = briefcase.snapshot()
        render()
    }

    // ---- What a tap does ---------------------------------------------------------------------

    /**
     * Opens a file, fetching it first when the phone has only been told about it. Section 5.3:
     * the listing is shown whole and the bytes come when they are wanted.
     */
    private fun openEntry(entry: BriefcaseEntry) {
        onSoundPlay()
        adapter?.clearSelection()

        val local = briefcase.localFile(entry)
        if (local != null) {
            FileOpener.open(context, local)
            return
        }

        onSetCursorBusy()
        briefcase.fetch(entry) { result ->
            onSetCursorNormal()
            result.onSuccess { file -> FileOpener.open(context, file) }
            result.onFailure { error -> report(error) }
        }
    }

    // ---- The menus -----------------------------------------------------------------------------

    private fun showFolderMenu(x: Float, y: Float) {
        val connected = snapshot.connected
        val items = mutableListOf<ContextMenuItem>()

        items.add(ContextMenuItem("Update All", isEnabled = connected, action = {
            onSetCursorBusy()
            briefcase.sync(force = true) { failure ->
                onSetCursorNormal()
                failure?.let { report(it) }
            }
        }))
        items.add(ContextMenuItem("Add a File...", isEnabled = true, action = { onAddFile() }))
        items.add(ContextMenuItem("", isEnabled = false))

        if (connected) {
            items.add(ContextMenuItem("Connected Devices...", isEnabled = true, action = { showAboutConnection() }))
            items.add(ContextMenuItem("Disconnect from ${snapshot.site.ifEmpty { "the computer" }}", isEnabled = true, action = {
                onShowConfirmDialog(
                    "Disconnect",
                    "This phone will stop sharing with ${snapshot.site.ifEmpty { "the computer" }}. " +
                        "The copies in your Briefcase stay on the phone."
                ) {
                    onSetCursorBusy()
                    briefcase.disconnect { result ->
                        onSetCursorNormal()
                        result.onFailure { report(it) }
                        refresh()
                    }
                }
            }))
        } else {
            items.add(ContextMenuItem("Connect a Phone...", isEnabled = true, action = { onConnect() }))
        }

        items.add(ContextMenuItem("", isEnabled = false))
        items.add(
            ContextMenuItem(
                "Sync on mobile data",
                isEnabled = true,
                hasCheckbox = true,
                isChecked = briefcase.store.syncOnMobileData,
                action = {
                    briefcase.store.syncOnMobileData = !briefcase.store.syncOnMobileData
                    briefcase.sync(force = false)
                }
            )
        )

        onShowContextMenu(items, x, y)
    }

    private fun showFileMenu(entry: BriefcaseEntry, x: Float, y: Float) {
        val state = snapshot.stateOf(entry)
        val items = mutableListOf<ContextMenuItem>()

        items.add(ContextMenuItem("Open", isEnabled = true, action = { openEntry(entry) }))

        when (state) {
            BriefcaseFileState.NOT_DOWNLOADED -> items.add(
                ContextMenuItem("Get from the Computer", isEnabled = true, action = {
                    onSetCursorBusy()
                    briefcase.fetch(entry) { result ->
                        onSetCursorNormal()
                        result.onFailure { report(it) }
                    }
                })
            )
            BriefcaseFileState.NEEDS_SENDING, BriefcaseFileState.ORPHAN -> items.add(
                ContextMenuItem("Send to the Computer", isEnabled = snapshot.connected, action = {
                    briefcase.sync(force = false) { failure -> failure?.let { report(it) } }
                })
            )
            else -> Unit
        }

        items.add(ContextMenuItem("", isEnabled = false))
        items.add(ContextMenuItem("Rename", isEnabled = true, action = {
            adapter?.clearSelection()
            onShowRenameDialog(File(briefcase.folder(), entry.name)) { newName ->
                briefcase.rename(entry, newName) { result -> result.onFailure { report(it) } }
            }
        }))
        items.add(ContextMenuItem("Delete", isEnabled = true, action = {
            adapter?.clearSelection()
            // Section 2.9: it goes from every device, and not into anybody's Recycle Bin.
            val warning = if (entry.id != null) {
                "'${entry.name}' will be taken out of the Briefcase on ${snapshot.site.ifEmpty { "the computer" }} " +
                    "as well, and it does not go to the Recycle Bin. Delete it?"
            } else {
                "Delete '${entry.name}'?"
            }
            onShowConfirmDialog("Delete File", warning) {
                briefcase.delete(entry) { result -> result.onFailure { report(it) } }
            }
        }))
        items.add(ContextMenuItem("", isEnabled = false))
        items.add(ContextMenuItem("Properties", isEnabled = true, action = { showProperties(entry) }))

        onShowContextMenu(items, x, y)
    }

    private fun showProperties(entry: BriefcaseEntry) {
        val state = when (snapshot.stateOf(entry)) {
            BriefcaseFileState.UP_TO_DATE -> "Up-to-date"
            BriefcaseFileState.NOT_DOWNLOADED -> "On the computer only"
            BriefcaseFileState.NEEDS_SENDING -> "Changed here - waiting to go up"
            BriefcaseFileState.BUSY -> "Copying..."
            BriefcaseFileState.ORPHAN -> "Orphan - the computer has never seen it"
        }
        val when_ = if (entry.modified > 0) {
            SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(entry.modified * 1000L))
        } else {
            "unknown"
        }
        onShowDialog(
            DialogType.INFORMATION,
            "${entry.name}\n\n" +
                "Size: ${Briefcase.readableSize(entry.size)}\n" +
                "Modified: $when_\n" +
                "Status: $state"
        )
    }

    private fun showAboutConnection() {
        val store = briefcase.store
        onShowDialog(
            DialogType.INFORMATION,
            "Sharing with ${store.site.ifEmpty { "a winos computer" }} at " +
                "${store.host.removePrefix("https://")}\n\n" +
                "Account: ${store.user}\n" +
                "This phone: ${store.deviceName}\n\n" +
                "If you lose this phone, open My Briefcase on the computer and choose " +
                "Briefcase - Connected Devices - Disconnect. That stops this phone at once."
        )
    }

    /**
     * Section 4: a network failure is not worth a dialog - the Briefcase is a folder, not a
     * transaction, and the status bar has already said so. Everything else gets one.
     */
    private fun report(error: Throwable) {
        val failure = error as? BriefcaseException
        if (failure != null && failure.isNetworkFailure) return
        onShowDialog(DialogType.ERROR, error.message ?: "The Briefcase could not do that.")
    }

    companion object {
        const val TITLE = "My Briefcase"

        private val DIMMED = setOf(BriefcaseFileState.NOT_DOWNLOADED, BriefcaseFileState.BUSY)
    }
}
