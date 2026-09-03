package rocks.gorjan.gokixp.apps.files

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.MetroAppBar
import rocks.gorjan.gokixp.wp81.MetroPageTransition
import rocks.gorjan.gokixp.wp81.MonochromeIconProvider
import rocks.gorjan.gokixp.wp81.SvgIcon
import rocks.gorjan.gokixp.wp81.TiltEffect
import rocks.gorjan.gokixp.wp81.WP81ContextMenu
import rocks.gorjan.gokixp.wp81.WP81InputDialog
import rocks.gorjan.gokixp.wp81.WP81Palette
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Files, the app Windows Phone 8.1 finally got in 2014.
 *
 * The phone shipped for two years with no way to look at its own storage, and when the
 * app arrived it was deliberately plain: a list of folders and files, a strip of commands
 * along the bottom, and a select mode for doing something to several things at once. There
 * is no ribbon, no tree in a left pane and no two-pane copy - a phone has one screen, and
 * the whole design follows from that.
 *
 * This is that app, on this shell's own furniture. The command strip, the hold menu, the
 * prompt and the press-and-tilt are all the shell's and arrived built; what is written
 * here is the list, the sorting, and the file work behind the commands.
 *
 * Deliberately not the desktop's My Computer with a new coat of paint. My Computer is a
 * Windows window: drives named after letters, a folder rendered as icons on a grid, and
 * the whole thing framed in chrome. This is a phone's file list - one column, names set
 * large and light, everything else in the subtle colour underneath. The two shells get the
 * app each of them would have had, and neither has to pretend to be the other.
 *
 * The clipboard is on the companion rather than on an instance, because a cut is a
 * statement about the phone and not about a window: cutting something, closing the app and
 * opening it again somewhere else to paste is exactly the way anybody moves a file, and a
 * clipboard that emptied itself on the way out would break that.
 */
class MetroFilesApp(
    private val context: Context,
    private val palette: WP81Palette,
    /** Handing a file to whatever opens that kind of file. The shell decides, not this app. */
    private val onOpen: (File) -> Unit,
    /** The shell's own toast: what it says when work finishes, and when it cannot be done. */
    private val onNotify: (String, String) -> Unit
) {

    /** A place the phone keeps files, at the top of the tree. See [rootsOf]. */
    private data class Root(val label: String, val dir: File, val icon: String)

    /** What a listing is put in order by. The user's choice, and it is remembered. */
    private enum class Sort { NAME, DATE, SIZE }

    /** What is waiting to be pasted, and whether pasting it should also remove it. */
    private enum class ClipMode { COPY, CUT }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    /** Only for its ink measuring, which is the hard half of putting a glyph on a row. */
    private val icons = MonochromeIconProvider(context)

    private lateinit var root: FrameLayout
    private lateinit var column: LinearLayout
    private lateinit var title: TextView
    private lateinit var pathLine: TextView
    private lateinit var listColumn: LinearLayout
    private lateinit var scroller: ScrollView
    private lateinit var barSlot: FrameLayout
    private lateinit var contextMenu: WP81ContextMenu
    private lateinit var dialog: WP81InputDialog

    private val roots = rootsOf()

    /**
     * Where the list is now. Null is the roots page - the one screen that is not a folder.
     *
     * Only ever null on a phone that has somewhere other than its own storage to offer: a
     * roots page listing one root is a screen whose only purpose is to be tapped through,
     * so where there is nothing to choose between the app opens in the storage itself.
     */
    private var current: File? = if (roots.size > 1) null else roots.firstOrNull()?.dir

    private var sort = readSort()
    private var showHidden = prefs.getBoolean(KEY_HIDDEN, false)

    /** Select mode, and what is picked out in it. Empty and off is the ordinary listing. */
    private var selecting = false
    private val selection = LinkedHashSet<File>()

    /** True while a copy, move or delete is running. The strip is dead until it is not. */
    private var busy = false

    /** Set once the window is gone, so work finishing afterwards touches no views. */
    private var released = false

    // ------------------------------------------------------------------------ the page

    fun createView(): View {
        root = FrameLayout(context).apply { setBackgroundColor(palette.background) }

        column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // The list stops above the strip rather than running under it, so the last
            // file is reachable instead of sitting behind a button.
            setPadding(0, 0, 0, dp(MetroAppBar.HEIGHT_DP))
        }

        // A title rather than the shell's page header, which carries a back arrow.
        // Windows Phone's Files app had none: the key at the bottom of the phone was how
        // you went up a folder, and an arrow in the page that did nothing at the top of
        // the tree - which is where the app opens - is a control that appears to be broken.
        title = TextView(context).apply {
            typeface = font(R.font.segoeui_light)
            textSize = TITLE_SP
            setTextColor(palette.foreground)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(dp(PAGE_MARGIN_DP), dp(18), dp(PAGE_MARGIN_DP), dp(6))
        }
        column.addView(title, wide())

        pathLine = TextView(context).apply {
            typeface = font(R.font.segoeui_regular)
            textSize = 13f
            setTextColor(palette.foregroundSubtle)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(dp(PAGE_MARGIN_DP), 0, dp(PAGE_MARGIN_DP), dp(10))
        }
        column.addView(pathLine, wide())

        listColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
        }
        scroller = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            addView(listColumn, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        column.addView(scroller, LinearLayout.LayoutParams(MATCH, 0, 1f))

        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        // The strip is swapped rather than edited: browsing and selecting have different
        // commands on them, and MetroAppBar is built to be filled once and left alone.
        barSlot = FrameLayout(context)
        root.addView(barSlot, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        // Both of these sit over everything: a menu that dims the page it belongs to
        // cannot be inside it.
        contextMenu = WP81ContextMenu(context, palette)
        root.addView(contextMenu, FrameLayout.LayoutParams(MATCH, MATCH))
        dialog = WP81InputDialog(context, palette)
        root.addView(dialog, FrameLayout.LayoutParams(MATCH, MATCH))

        refresh()
        return root
    }

    /**
     * The back key, on the way out of wherever it currently is.
     *
     * Four things in order, and the order is the point: a prompt, then a menu, then select
     * mode, then a folder. Only when none of those is standing does the key mean what it
     * means everywhere else, and the window closes.
     */
    fun handleBack(): Boolean {
        if (dialog.isShowing()) { dialog.dismiss(); return true }
        if (contextMenu.isShowing()) { contextMenu.dismiss(); return true }
        if (selecting) { endSelecting(); return true }
        return goUp()
    }

    fun cleanup() {
        released = true
        handler.removeCallbacksAndMessages(null)
    }

    // ----------------------------------------------------------------- getting around

    /**
     * Up one, and whether there was anywhere to go.
     *
     * False is the answer that closes the window, and it is the right answer in two places:
     * at a root on a phone with only one, and on the roots page itself. Above either of
     * those there is no folder left, only the shell.
     */
    private fun goUp(): Boolean {
        val here = current ?: return false
        if (roots.any { it.dir == here }) {
            if (roots.size <= 1) return false
            navigateTo(null)
            return true
        }
        val parent = here.parentFile ?: return false
        navigateTo(parent)
        return true
    }

    private fun navigateTo(dir: File?) {
        current = dir
        // A folder left in select mode and then left behind would come back selected in
        // somewhere else entirely; the selection is about this listing and dies with it.
        endSelecting(refresh = false)
        refresh()
        // Every page in this shell swings in about its left edge. Going into a folder is
        // going somewhere, so it does too.
        MetroPageTransition(scroller).playIn()
    }

    // ------------------------------------------------------------------- drawing it all

    /**
     * Draws the page as things now stand.
     *
     * Public because the window is not the only thing that changes what is on disk: a
     * download landing or a photograph being taken while Files sits behind another window
     * leaves the listing describing a folder that has moved on without it, so the shell
     * refreshes it on the way back in.
     */
    fun refresh() {
        if (released) return
        titleThePage()
        fillList()
        installBar()
    }

    private fun titleThePage() {
        val here = current
        val rootHere = roots.firstOrNull { it.dir == here }
        title.text = when {
            // The title counts rather than names while things are picked out: what the
            // page is about in that moment is the selection, not the folder.
            selecting ->
                if (selection.size == 1) "1 selected" else "${selection.size} selected"
            // "files", "phone" and "sd card" are the platform's own words, and WP8.1 page
            // titles are lowercase - it is one of the most recognisable things about it.
            // A folder somebody named is left exactly as they typed it, because renaming
            // the user's own typing on their behalf is not this app's business.
            here == null -> "files"
            rootHere != null -> rootHere.label
            else -> here.name
        }
        pathLine.text = breadcrumb()
        pathLine.visibility = if (pathLine.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * How to get to this folder, said as a trail rather than as a path.
     *
     * "phone > Pictures", not "/storage/emulated/0/Pictures/Camera". The second is true and
     * tells the reader nothing they were wondering about; the first is the answer to the
     * only question a title one word long leaves open, which is where that word is.
     *
     * The folder itself is left off the end, because the title above is already saying it
     * and a line that reads "phone > Pictures > Camera" under the word "Camera" is spending
     * a line of the page to repeat one. Empty at a root and on the roots page, where the
     * title is the whole answer. The exception is select mode: the title counts rather than
     * names then, so the trail says the folder's name because nothing else on the page does.
     */
    private fun breadcrumb(): String {
        val here = current ?: return ""
        val rootHere = roots.firstOrNull { here.absolutePath.startsWith(it.dir.absolutePath) }
            ?: return here.parent.orEmpty()
        if (here == rootHere.dir) return if (selecting) rootHere.label else ""
        val below = here.absolutePath
            .removePrefix(rootHere.dir.absolutePath)
            .trim(File.separatorChar)
            .split(File.separatorChar)
        val trail = if (selecting) below else below.dropLast(1)
        return (listOf(rootHere.label) + trail).joinToString("  ›  ")
    }

    private fun fillList() {
        listColumn.removeAllViews()

        val here = current
        if (here == null) {
            for (r in roots) listColumn.addView(rootRow(r), wide())
            return
        }

        if (!here.canRead()) {
            say("this folder cannot be opened")
            return
        }

        val entries = entriesOf(here)
        if (entries.isEmpty()) {
            say(if (showHidden) "empty" else "nothing here")
            return
        }
        for (entry in entries) listColumn.addView(fileRow(entry), wide())
    }

    /** A sentence rather than a picture of an empty box, the way the phone said it. */
    private fun say(words: String) {
        listColumn.addView(TextView(context).apply {
            text = words
            typeface = font(R.font.segoeui_regular)
            textSize = 15f
            setTextColor(palette.foregroundSubtle)
            setPadding(dp(PAGE_MARGIN_DP), dp(10), dp(PAGE_MARGIN_DP), 0)
        }, wide())
    }

    private fun entriesOf(dir: File): List<File> {
        val all = dir.listFiles() ?: return emptyList()
        val visible = if (showHidden) all.toList() else all.filter { !it.name.startsWith(".") }
        val within = when (sort) {
            Sort.NAME -> compareBy<File> { it.name.lowercase(Locale.getDefault()) }
            // Newest and largest first: sorting by a date or a size is asking which is the
            // most of it, and answering with the least would mean scrolling to find out.
            Sort.DATE -> compareByDescending<File> { it.lastModified() }
            Sort.SIZE -> compareByDescending<File> { if (it.isDirectory) 0L else it.length() }
        }
        // Folders above files whatever the sort, because a listing is somewhere to get
        // through as much as something to read, and the ways onward belong at the top.
        return visible.sortedWith(compareByDescending<File> { it.isDirectory }.then(within))
    }

    // ------------------------------------------------------------------------- the rows

    private fun rootRow(r: Root): View {
        val row = rowShell(onTap = { navigateTo(r.dir) })
        row.addView(glyph(r.icon), glyphParams())
        row.addView(labels(r.label, spaceOn(r.dir)), textParams())
        return row
    }

    private fun fileRow(entry: File): View {
        val row = rowShell(
            onTap = {
                if (selecting) toggle(entry)
                else if (entry.isDirectory) navigateTo(entry)
                else openFile(entry)
            },
            onHold = { view ->
                // A hold in select mode would be a second way to do what a tap already
                // does there, and the menu's commands are all about one thing. It is also
                // shut while work is running: the strip's commands go dim then, and a menu
                // that stayed live would be the way to start a second copy on top of the
                // first, which is the one thing the dimming is there to prevent.
                val open = !selecting && !busy
                if (open) showItemMenu(entry, anchorY(view))
                open
            }
        )
        if (selecting) row.addView(checkMark(entry in selection), checkParams())
        row.addView(glyph(iconFor(entry)), glyphParams())
        row.addView(labels(entry.name, detailOf(entry)), textParams())
        return row
    }

    private fun rowShell(
        onTap: () -> Unit,
        onHold: ((View) -> Boolean)? = null
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(PAGE_MARGIN_DP), dp(9), dp(PAGE_MARGIN_DP), dp(9))
            isClickable = true
            // No buzz for opening something. The phone answered a command with one - a
            // thing deleted, a hold that brought up a menu - and going into a folder is
            // not a command, it is just going somewhere.
            setOnClickListener { onTap() }
            if (onHold != null) setOnLongClickListener { view -> onHold(view) }
        }
        TiltEffect.apply(row)
        return row
    }

    private fun labels(name: String, detail: String): View {
        val stack = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        stack.addView(TextView(context).apply {
            text = name
            typeface = font(R.font.segoeui_semilight)
            textSize = 21f
            setTextColor(palette.foreground)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }, wide())
        stack.addView(TextView(context).apply {
            text = detail
            typeface = font(R.font.segoeui_regular)
            textSize = 13f
            setTextColor(palette.foregroundSubtle)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }, wide())
        return stack
    }

    private fun glyph(asset: String): ImageView {
        val view = ImageView(context)
        val drawable = SvgIcon.fromAsset(context, asset)
        view.setImageDrawable(drawable)
        // The set is drawn white for tiles. A file list is not always dark, so the marks
        // take the accent here rather than vanishing on a Light theme.
        view.imageTintList = android.content.res.ColorStateList.valueOf(palette.accent)
        if (drawable == null) {
            view.scaleType = ImageView.ScaleType.FIT_CENTER
            return view
        }
        placeGlyph(view, drawable, asset)
        return view
    }

    /**
     * Puts a mark on its row at the size it should be, wherever its ink sits in the canvas.
     *
     * These icons cover about half of the 76-unit square they are drawn on, and not the
     * same half each time - a folder is wide and short, a page is tall and narrow, and both
     * carry their own margin. Fitting the canvas to the row therefore fits the *margin* to
     * the row, and the mark comes out small, off-centre, and a different size on every line.
     *
     * So the measured ink box is what gets placed, exactly as the app list places a tile's
     * glyph: its longer side scaled to [GLYPH_DP] and its centre put at the middle of the
     * slot. By matrix rather than by padding, because a mark covering half its canvas needs
     * a canvas twice the slot to show at the right size, and padding cannot give it one.
     */
    private fun placeGlyph(view: ImageView, drawable: Drawable, asset: String) {
        val ink = icons.inkFor("files:$asset", drawable)
        val canvasW = drawable.intrinsicWidth.toFloat()
        val canvasH = drawable.intrinsicHeight.toFloat()
        // Artwork that drew nothing, or that will not say how large it is: an ImageView
        // ignores the matrix for a drawable with no intrinsic size, so the plain fit is the
        // only honest answer.
        if (ink == null || canvasW <= 0f || canvasH <= 0f) {
            view.scaleType = ImageView.ScaleType.FIT_CENTER
            return
        }
        val inkW = ink.width() * canvasW
        val inkH = ink.height() * canvasH
        val scale = dp(GLYPH_DP) / maxOf(inkW, inkH)
        val centre = dp(ICON_DP) / 2f
        view.scaleType = ImageView.ScaleType.MATRIX
        view.imageMatrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(
                centre - scale * (ink.left * canvasW + inkW / 2f),
                centre - scale * (ink.top * canvasH + inkH / 2f)
            )
        }
    }

    /**
     * The square beside a row in select mode.
     *
     * Outlined when it is not picked and filled with the accent when it is, which is the
     * phone's own checkbox: the difference between the two states is a block of colour
     * rather than a tick somebody has to look for.
     */
    private fun checkMark(on: Boolean): View {
        val box = ImageView(context)
        box.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(if (on) palette.accent else Color.TRANSPARENT)
            setStroke(dp(2), if (on) palette.accent else palette.foregroundSubtle)
        }
        if (on) {
            box.setImageDrawable(SvgIcon.fromAsset(context, CHECK_ICON))
            box.scaleType = ImageView.ScaleType.FIT_CENTER
            box.setPadding(dp(3), dp(3), dp(3), dp(3))
            box.imageTintList = android.content.res.ColorStateList.valueOf(palette.onAccent())
        }
        return box
    }

    // --------------------------------------------------------------------- what a row says

    private fun detailOf(entry: File): String {
        val changed = whenOf(entry.lastModified())
        if (!entry.isDirectory) return "${sizeOf(entry.length())}   $changed"
        // Names only rather than File objects: a listing is one readdir either way, and
        // this one does not need a stat per child to be counted.
        val count = entry.list()?.size
        return when (count) {
            null -> changed
            0 -> "empty   $changed"
            1 -> "1 item   $changed"
            else -> "$count items   $changed"
        }
    }

    /** How much room is left, for the roots page - the one place the question is asked. */
    private fun spaceOn(dir: File): String = try {
        val free = dir.freeSpace
        val total = dir.totalSpace
        if (total <= 0L) "" else "${sizeOf(free)} free of ${sizeOf(total)}"
    } catch (e: Exception) {
        ""
    }

    private fun sizeOf(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        // One decimal place under ten, none above it: "1.4 MB" is worth knowing and
        // "847.3 MB" is three digits of noise on a number nobody reads that closely.
        return if (value < 10) String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
        else String.format(Locale.getDefault(), "%.0f %s", value, units[unit])
    }

    /** Built once: a folder of a thousand files is a thousand rows asking for a date. */
    private val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

    private fun whenOf(at: Long): String =
        if (at <= 0L) "" else dateFormat.format(java.util.Date(at))

    private fun iconFor(entry: File): String {
        if (entry.isDirectory) return FOLDER_ICON
        return when (entry.extension.lowercase(Locale.getDefault())) {
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "ico", "tif", "tiff" -> IMAGE_ICON
            "mp3", "wav", "ogg", "m4a", "flac", "aac", "wma", "opus", "mid", "midi" -> AUDIO_ICON
            "mp4", "mkv", "avi", "mov", "wmv", "webm", "m4v", "3gp", "mpg", "mpeg" -> VIDEO_ICON
            "pdf" -> PDF_ICON
            "txt", "md", "log", "json", "xml", "csv", "ini", "cfg" -> TEXT_ICON
            "zip", "rar", "7z", "tar", "gz", "apk" -> ARCHIVE_ICON
            else -> FILE_ICON
        }
    }

    // -------------------------------------------------------------------------- the strip

    private fun installBar() {
        barSlot.removeAllViews()

        // The roots page has nothing to command: there is no folder to make one in, and
        // nowhere to paste. It gets no strip at all rather than a row of dead buttons, and
        // the list runs to the foot of the page instead of stopping above a black band
        // with nothing on it.
        if (!selecting && current == null) {
            column.setPadding(0, 0, 0, 0)
            return
        }
        column.setPadding(0, 0, 0, dp(MetroAppBar.HEIGHT_DP))

        val bar = MetroAppBar(context, palette)
        if (selecting) fillSelectBar(bar) else fillBrowseBar(bar)
        barSlot.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    private fun fillBrowseBar(bar: MetroAppBar) {
        val here = current ?: return

        val select = bar.addCommand(SELECT_ICON) { beginSelecting() }
        bar.setCommandEnabled(select, !busy && here.canRead())

        val newFolder = bar.addCommand(NEW_FOLDER_ICON) { askForNewFolder() }
        bar.setCommandEnabled(newFolder, !busy && here.canWrite())

        val paste = bar.addCommand(PASTE_ICON) { paste() }
        bar.setCommandEnabled(paste, !busy && clipboard.isNotEmpty() && here.canWrite())

        bar.menu = {
            buildList {
                if (sort != Sort.NAME) add(MetroAppBar.Item("sort by name") { setSort(Sort.NAME) })
                if (sort != Sort.DATE) add(MetroAppBar.Item("sort by date") { setSort(Sort.DATE) })
                if (sort != Sort.SIZE) add(MetroAppBar.Item("sort by size") { setSort(Sort.SIZE) })
                add(MetroAppBar.Item(if (showHidden) "hide hidden files" else "show hidden files") {
                    showHidden = !showHidden
                    prefs.edit { putBoolean(KEY_HIDDEN, showHidden) }
                    refresh()
                })
                if (clipboard.isNotEmpty()) {
                    add(MetroAppBar.Item("clear clipboard") {
                        clipboard = emptyList()
                        clipMode = null
                        refresh()
                    })
                }
                add(MetroAppBar.Item("refresh") { refresh() })
            }
        }
    }

    private fun fillSelectBar(bar: MetroAppBar) {
        val picked = selection.isNotEmpty()
        val here = current

        val copy = bar.addCommand(COPY_ICON) { takeToClipboard(ClipMode.COPY) }
        bar.setCommandEnabled(copy, picked && !busy)

        val cut = bar.addCommand(CUT_ICON) { takeToClipboard(ClipMode.CUT) }
        bar.setCommandEnabled(cut, picked && !busy && here?.canWrite() == true)

        val remove = bar.addCommand(DELETE_ICON) { askToDelete(selection.toList()) }
        bar.setCommandEnabled(remove, picked && !busy && here?.canWrite() == true)

        bar.menu = {
            buildList {
                if (selection.size == 1) {
                    val only = selection.first()
                    add(MetroAppBar.Item("rename") { askToRename(only) })
                }
                if (picked && selection.none { it.isDirectory }) {
                    add(MetroAppBar.Item("share") { share(selection.toList()) })
                }
                val all = current?.let { entriesOf(it) }.orEmpty()
                if (all.isNotEmpty() && selection.size < all.size) {
                    add(MetroAppBar.Item("select all") {
                        selection.addAll(all)
                        refresh()
                    })
                }
                add(MetroAppBar.Item("done") { endSelecting() })
            }
        }
    }

    private fun setSort(to: Sort) {
        sort = to
        prefs.edit { putString(KEY_SORT, to.name) }
        refresh()
    }

    private fun readSort(): Sort = try {
        Sort.valueOf(prefs.getString(KEY_SORT, Sort.NAME.name) ?: Sort.NAME.name)
    } catch (e: IllegalArgumentException) {
        Sort.NAME
    }

    // ---------------------------------------------------------------------- select mode

    private fun beginSelecting() {
        selecting = true
        selection.clear()
        refresh()
    }

    private fun endSelecting(refresh: Boolean = true) {
        if (!selecting && selection.isEmpty()) return
        selecting = false
        selection.clear()
        if (refresh) refresh()
    }

    private fun toggle(entry: File) {
        if (!selection.remove(entry)) selection.add(entry)
        // The header counts and the strip switches on whether anything is picked, so both
        // are rebuilt rather than only the row that was tapped.
        refresh()
    }

    // ------------------------------------------------------------------- the hold menu

    private fun showItemMenu(entry: File, y: Float) {
        val parent = current
        val writable = parent?.canWrite() == true
        contextMenu.show(
            entry.name,
            buildList {
                add(WP81ContextMenu.Item("open") {
                    if (entry.isDirectory) navigateTo(entry) else openFile(entry)
                })
                add(WP81ContextMenu.Item("copy") { takeToClipboard(ClipMode.COPY, listOf(entry)) })
                if (writable) {
                    add(WP81ContextMenu.Item("cut") { takeToClipboard(ClipMode.CUT, listOf(entry)) })
                    add(WP81ContextMenu.Item("rename") { askToRename(entry) })
                }
                if (!entry.isDirectory) add(WP81ContextMenu.Item("share") { share(listOf(entry)) })
                if (writable) add(WP81ContextMenu.Item("delete") { askToDelete(listOf(entry)) })
            },
            y
        )
    }

    // ------------------------------------------------------------------------- commands

    private fun openFile(file: File) {
        if (!file.canRead()) {
            onNotify("Files", "${file.name} cannot be opened")
            return
        }
        onOpen(file)
    }

    private fun askForNewFolder() {
        val here = current ?: return
        dialog.show("new folder", "") { typed ->
            val name = typed.trim()
            if (!nameIsUsable(name)) return@show
            val made = File(here, name)
            if (made.exists()) {
                onNotify("Files", "There is already something called $name here")
                return@show
            }
            if (made.mkdir()) refresh()
            else onNotify("Files", "Could not make $name")
        }
    }

    private fun askToRename(entry: File) {
        dialog.show("rename", entry.name) { typed ->
            val name = typed.trim()
            if (!nameIsUsable(name) || name == entry.name) return@show
            val renamed = File(entry.parentFile, name)
            if (renamed.exists()) {
                onNotify("Files", "There is already something called $name here")
                return@show
            }
            if (entry.renameTo(renamed)) {
                // The old file is the one that was picked out, and it no longer exists.
                selection.remove(entry)
                if (selecting) selection.add(renamed)
                refresh()
            } else {
                onNotify("Files", "Could not rename ${entry.name}")
            }
        }
    }

    /**
     * Whether a typed name can be a file at all, said back to the user where it cannot.
     *
     * Only the rules the filesystem actually has - not empty, not one of the two names
     * every directory already answers to, and no separator in it. Everything else a name
     * could be is the user's business, including the ones they will regret.
     */
    private fun nameIsUsable(name: String): Boolean = when {
        name.isEmpty() -> false
        name == "." || name == ".." -> {
            onNotify("Files", "That name is not allowed")
            false
        }
        name.contains(File.separatorChar) -> {
            onNotify("Files", "A name cannot contain ${File.separator}")
            false
        }
        else -> true
    }

    private fun takeToClipboard(mode: ClipMode, what: List<File> = selection.toList()) {
        if (what.isEmpty()) return
        clipboard = what
        clipMode = mode
        val many = what.size > 1
        onNotify(
            "Files",
            if (mode == ClipMode.COPY) {
                if (many) "${what.size} items copied" else "${what.first().name} copied"
            } else {
                if (many) "${what.size} items ready to move" else "${what.first().name} ready to move"
            }
        )
        endSelecting()
    }

    private fun askToDelete(what: List<File>) {
        if (what.isEmpty()) return
        val subject = if (what.size == 1) what.first().name else "${what.size} items"
        // Named after what it is about to do rather than "OK", because the one word the
        // user reads before answering should be the answer.
        dialog.confirm(
            "delete",
            if (what.size == 1 && what.first().isDirectory)
                "Delete $subject and everything in it? This cannot be undone."
            else "Delete $subject? This cannot be undone.",
            "delete"
        ) {
            endSelecting(refresh = false)
            work {
                var gone = 0
                for (entry in what) if (deleteTree(entry)) gone++
                // Anything on the clipboard that has just been deleted would paste into
                // nothing; it leaves with the file.
                clipboard = clipboard.filterNot { it in what }
                if (clipboard.isEmpty()) clipMode = null
                when {
                    gone == what.size && gone == 1 -> "${what.first().name} deleted"
                    gone == what.size -> "$gone items deleted"
                    gone == 0 -> "Nothing could be deleted"
                    else -> "$gone of ${what.size} deleted"
                }
            }
        }
    }

    private fun paste() {
        val here = current ?: return
        val what = clipboard
        val mode = clipMode
        if (what.isEmpty() || mode == null) return

        // A folder cannot be put inside itself, and the check has to be the canonical path
        // rather than the name: /Music and /Music/Live are different strings that are very
        // much the same problem, and copying one into the other would run until the disk
        // filled up.
        val impossible = what.firstOrNull { swallows(it, here) }
        if (impossible != null) {
            onNotify("Files", "${impossible.name} cannot be put inside itself")
            return
        }

        work {
            var done = 0
            for (entry in what) {
                if (!entry.exists()) continue
                // Something cut and then pasted back where it already was. Left alone and
                // counted as done: the alternative is freeNameIn colliding the file with
                // itself and quietly renaming it to "name (2)", which is not what anybody
                // meant by pasting it into the folder it is already in.
                if (mode == ClipMode.CUT && entry.parentFile == here) {
                    done++
                    continue
                }
                val target = freeNameIn(here, entry.name)
                try {
                    if (mode == ClipMode.CUT) {
                        // A rename is the whole move when both ends are on one volume, and
                        // it is instant. Across volumes it fails and there is no shortcut:
                        // the bytes have to be written and the original taken away after.
                        if (entry.renameTo(target)) {
                            done++
                        } else {
                            copyTree(entry, target)
                            // The bytes are across either way; a source that will not go
                            // away is a stray copy left behind, not a move that failed.
                            deleteTree(entry)
                            done++
                        }
                    } else {
                        copyTree(entry, target)
                        done++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not paste ${entry.name}", e)
                }
            }
            // A cut is spent once it lands. A copy is not: copying one thing into three
            // folders in turn is a thing people do, and emptying the clipboard after the
            // first would make them go back and copy it again.
            if (mode == ClipMode.CUT) {
                clipboard = emptyList()
                clipMode = null
            }
            when {
                done == what.size && done == 1 ->
                    if (mode == ClipMode.CUT) "${what.first().name} moved" else "${what.first().name} pasted"
                done == what.size ->
                    if (mode == ClipMode.CUT) "$done items moved" else "$done items pasted"
                done == 0 -> "Nothing could be pasted"
                else -> "$done of ${what.size} pasted"
            }
        }
    }

    private fun share(what: List<File>) {
        if (what.isEmpty()) return
        try {
            val authority = "${context.packageName}.fileprovider"
            val uris = ArrayList(what.map { FileProvider.getUriForFile(context, authority, it) })
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = mimeOf(what.first())
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val chooser = Intent.createChooser(intent, "Share")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            endSelecting()
        } catch (e: Exception) {
            // The commonest cause is a file on a memory card: the provider is declared over
            // the phone's own storage, and a card is a different volume it does not cover.
            Log.w(TAG, "Could not share", e)
            onNotify("Files", "This cannot be shared from here")
        }
    }

    // --------------------------------------------------------------------- the file work

    /**
     * Runs something slow off the main thread, with the strip dead while it runs.
     *
     * Copying a folder is not a thing that finishes inside a frame, and doing it on the
     * main thread would freeze the list mid-scroll. The block hands back the sentence to
     * say when it is over, which is said on the way back in.
     */
    private fun work(job: () -> String) {
        busy = true
        installBar()
        Thread {
            val outcome = try {
                job()
            } catch (e: Exception) {
                Log.w(TAG, "File work failed", e)
                "Something went wrong"
            }
            handler.post {
                busy = false
                if (released) return@post
                refresh()
                onNotify("Files", outcome)
            }
        }.start()
    }

    private fun copyTree(source: File, target: File) {
        if (source.isDirectory) {
            if (!target.isDirectory && !target.mkdirs()) {
                throw IOException("Could not make ${target.absolutePath}")
            }
            source.listFiles()?.forEach { child -> copyTree(child, File(target, child.name)) }
        } else {
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            // A copy of a photograph taken last year is still from last year. Best effort:
            // some filesystems will not have it, and a wrong date is not worth failing over.
            target.setLastModified(source.lastModified())
        }
    }

    private fun deleteTree(entry: File): Boolean {
        if (entry.isDirectory) {
            entry.listFiles()?.forEach { child -> deleteTree(child) }
        }
        return entry.delete()
    }

    /** Whether putting [source] into [target] would put it inside itself. See [paste]. */
    private fun swallows(source: File, target: File): Boolean = try {
        if (!source.isDirectory) false
        else {
            val from = source.canonicalPath
            val into = target.canonicalPath
            into == from || into.startsWith(from + File.separator)
        }
    } catch (e: IOException) {
        // Unreadable either way: refusing is the safe answer to a question that cannot be
        // asked, because the cost of being wrong is a copy that never ends.
        true
    }

    /**
     * A name nothing in [dir] is already using.
     *
     * "photo (2).jpg", with the number before the extension rather than after it, so the
     * copy is still a JPEG to everything that reads one.
     */
    private fun freeNameIn(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val tail = if (dot > 0) name.substring(dot) else ""
        var n = 2
        while (candidate.exists()) {
            candidate = File(dir, "$stem ($n)$tail")
            n++
        }
        return candidate
    }

    private fun mimeOf(file: File): String {
        val extension = file.extension.lowercase(Locale.getDefault())
        return android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension) ?: "*/*"
    }

    // ----------------------------------------------------------------------- the roots

    /**
     * The places this phone keeps files.
     *
     * Its own storage always, and a memory card where there is one. The card is found the
     * only way an app is allowed to find it - the second entry in the private directories
     * the system hands out per volume - and then walked back up to the volume itself,
     * because the private folder is not what anybody came here to look at.
     */
    private fun rootsOf(): List<Root> = buildList {
        val phone = Environment.getExternalStorageDirectory()
        if (phone != null && phone.exists()) add(Root("phone", phone, PHONE_ICON))
        try {
            ContextCompat.getExternalFilesDirs(context, null)
                .filterNotNull()
                .drop(1)
                .mapNotNull { volumeRootOf(it) }
                .filter { it.exists() && it.canRead() }
                .distinctBy { it.absolutePath }
                .forEach { add(Root("sd card", it, CARD_ICON)) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not look for a memory card", e)
        }
    }

    private fun volumeRootOf(privateDir: File): File? {
        val marker = "${File.separator}Android${File.separator}data${File.separator}"
        val at = privateDir.absolutePath.indexOf(marker)
        return if (at > 0) File(privateDir.absolutePath.substring(0, at)) else null
    }

    // -------------------------------------------------------------------------- plumbing

    /** Where in the page a view sits, for a menu that has to come down beside it. */
    private fun anchorY(view: View): Float {
        val here = IntArray(2)
        val there = IntArray(2)
        root.getLocationOnScreen(here)
        view.getLocationOnScreen(there)
        return (there[1] - here[1] + view.height / 2f)
    }

    private fun font(res: Int) = ResourcesCompat.getFont(context, res)

    private fun wide() = LinearLayout.LayoutParams(MATCH, WRAP)

    private fun glyphParams() = LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)).apply {
        marginEnd = dp(14)
    }

    private fun checkParams() = LinearLayout.LayoutParams(dp(CHECK_DP), dp(CHECK_DP)).apply {
        marginEnd = dp(14)
    }

    private fun textParams() = LinearLayout.LayoutParams(0, WRAP, 1f)

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "MetroFiles"

        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT

        private const val PREFS = "wp81_files"
        private const val KEY_SORT = "files_sort"
        private const val KEY_HIDDEN = "files_show_hidden"

        private const val PAGE_MARGIN_DP = 22

        /** The size the platform set an app's own name in, and the shell's page titles. */
        private const val TITLE_SP = 34f
        /** The slot a mark sits in on a row. */
        private const val ICON_DP = 34

        /** The mark itself, measured across its ink rather than its canvas. */
        private const val GLYPH_DP = 24

        private const val CHECK_DP = 24

        /**
         * What is waiting to be pasted, and whether pasting should also take it away.
         *
         * Static because a clipboard is a fact about the phone rather than about a window.
         * Cutting something, leaving the app and coming back somewhere else to paste is how
         * anybody moves a file, and a clipboard that emptied itself on the way out would
         * make that impossible.
         */
        private var clipboard: List<File> = emptyList()
        private var clipMode: ClipMode? = null

        // Modern UI Icons, the set the rest of the phone shell's marks come from.
        private const val ICON_DIR = "custom_icons_8"
        private const val FOLDER_ICON = "$ICON_DIR/appbar.folder.svg"
        private const val FILE_ICON = "$ICON_DIR/appbar.page.svg"
        private const val TEXT_ICON = "$ICON_DIR/appbar.page.text.svg"
        private const val IMAGE_ICON = "$ICON_DIR/appbar.page.image.svg"
        private const val AUDIO_ICON = "$ICON_DIR/appbar.page.music.svg"
        private const val VIDEO_ICON = "$ICON_DIR/appbar.film.svg"
        private const val PDF_ICON = "$ICON_DIR/appbar.page.file.pdf.svg"
        private const val ARCHIVE_ICON = "$ICON_DIR/appbar.box.svg"
        private const val PHONE_ICON = "$ICON_DIR/appbar.os.windowsphone.svg"
        private const val CARD_ICON = "$ICON_DIR/appbar.cabinet.svg"

        private const val SELECT_ICON = "$ICON_DIR/appbar.list.select.svg"
        private const val NEW_FOLDER_ICON = "$ICON_DIR/appbar.folder.open.svg"
        private const val PASTE_ICON = "$ICON_DIR/appbar.clipboard.paste.svg"
        private const val COPY_ICON = "$ICON_DIR/appbar.page.copy.svg"
        private const val CUT_ICON = "$ICON_DIR/appbar.scissor.svg"
        private const val DELETE_ICON = "$ICON_DIR/appbar.delete.svg"
        private const val CHECK_ICON = "$ICON_DIR/appbar.checkmark.thick.svg"
    }
}
