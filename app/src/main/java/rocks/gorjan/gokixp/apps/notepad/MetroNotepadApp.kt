package rocks.gorjan.gokixp.apps.notepad

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import rocks.gorjan.gokixp.MainActivity
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.Haptics
import rocks.gorjan.gokixp.wp81.MetroPageHeader
import rocks.gorjan.gokixp.wp81.MetroPageTransition
import rocks.gorjan.gokixp.wp81.MetroPanorama
import rocks.gorjan.gokixp.wp81.SvgIcon
import rocks.gorjan.gokixp.wp81.TiltEffect
import rocks.gorjan.gokixp.wp81.WP81ContextMenu
import rocks.gorjan.gokixp.wp81.WP81InputDialog
import rocks.gorjan.gokixp.wp81.WP81Palette
import rocks.gorjan.gokixp.wp81.WP81SecondaryBar
import rocks.gorjan.gokixp.wp81.applyToPageText
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Notepad, as the phone would have had it.
 *
 * The desktop version is a window with a list of notes down one side and the note itself
 * in the other, which is the right shape for a mouse and the wrong one for a phone: a
 * screen this size holds one thing at a time. So the app is two places instead of one -
 * a panorama of the notes you have, and a page per note - and going between them is the
 * whole of the navigation.
 *
 * The panorama carries the two lists the notes are already filed into: what you are
 * keeping, and what you have archived. The desktop version switches between those with a
 * button that renames itself; here they are sections of one wide surface, which is what
 * the platform did with a pair of lists and why it never needed the button.
 *
 * Everything you can *do* is on the strip along the bottom, and nothing is anywhere else -
 * no menu bar, no toolbar, no buttons among the text. Four things fit on that strip, so
 * the four are the ones a note is actually for: a picture from the camera, a picture from
 * the phone, getting rid of it, and behind the dots the rest.
 *
 * It is the same notepad. The notes are the desktop app's own, read from and written back
 * to the same place in the same format, so a note typed under Windows 98 is on the phone's
 * Start screen a theme switch later - see [loadNotes]. What changes when the theme changes
 * is the shape of the thing, not what is in it.
 */
class MetroNotepadApp(
    private val context: Context,
    private val palette: WP81Palette,
    private val onShowNotification: (String, String) -> Unit,
    private val onUpdateWindowTitle: (String) -> Unit,
    private val galleryPickerLauncher: ActivityResultLauncher<String>,
    private val onCameraCapture: (Uri) -> Unit,
    private val onShowFullscreenImage: (Uri) -> Unit
) {

    private val prefs =
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

    private val notes = mutableListOf<Note>()

    private lateinit var root: FrameLayout
    private lateinit var panorama: MetroPanorama

    /** The two lists the panorama pages between. Rebuilt whenever the notes change. */
    private lateinit var notesColumn: LinearLayout
    private lateinit var archiveColumn: LinearLayout

    private lateinit var contextMenu: WP81ContextMenu
    private lateinit var renameDialog: WP81InputDialog

    // --- the open note -----------------------------------------------------------------
    // Built when a note is opened and thrown away when it is closed, like every other page
    // in this shell: a page is what you are looking at, not something the app keeps a
    // spare of.

    private var current: Note? = null
    private var notePage: View? = null
    private var noteTransition: MetroPageTransition? = null
    private var noteHeader: MetroPageHeader? = null
    private var body: EditText? = null
    private var pictureStrip: HorizontalScrollView? = null
    private var pictureRow: LinearLayout? = null
    private var noteBarMenu: LinearLayout? = null

    /** Set while the editor is being filled in, so loading a note does not count as typing. */
    private var binding = false

    private val main = Handler(Looper.getMainLooper())

    /**
     * Writes the notes back a moment after the typing stops.
     *
     * A note is saved as it is written - there is no save button on this platform and
     * nowhere to put one - but writing the whole list out on every keystroke is a JSON
     * encode per letter. The pause is short enough that leaving by any route finds the
     * note already written, and every one of those routes saves outright anyway.
     */
    private val saveSoon = Runnable { saveNotes() }

    private val decoder = Executors.newSingleThreadExecutor()

    fun createView(): View {
        notes.clear()
        notes.addAll(loadNotes())

        root = FrameLayout(context).apply { setBackgroundColor(palette.background) }

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        panorama = MetroPanorama(context, palette).apply {
            setPadding(dp(PAGE_MARGIN_DP), 0, 0, 0)
            clipToPadding = false
            clipChildren = false
        }
        // Lowercase and light, in the size the platform wrote an app's own name in.
        panorama.setTitle("notepad")

        notesColumn = listColumn()
        archiveColumn = listColumn()
        panorama.addPage("notes", scroller(notesColumn))
        panorama.addPage("archive", scroller(archiveColumn))

        column.addView(panorama, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        root.addView(
            buildListBar(),
            FrameLayout.LayoutParams(MATCH, dp(BAR_DP), Gravity.BOTTOM)
        )
        // The lists stop above the strip rather than running under it, so the last note is
        // reachable instead of sitting behind the button.
        column.setPadding(0, 0, 0, dp(BAR_DP))

        // Both of these are the shell's own, and both sit over everything: a menu that dims
        // the page it belongs to cannot be inside it.
        contextMenu = WP81ContextMenu(context, palette)
        root.addView(contextMenu, FrameLayout.LayoutParams(MATCH, MATCH))
        renameDialog = WP81InputDialog(context, palette)
        root.addView(renameDialog, FrameLayout.LayoutParams(MATCH, MATCH))

        refreshLists()
        onUpdateWindowTitle("Notepad")
        return root
    }

    // ------------------------------------------------------------------- the lists

    private fun listColumn() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        // No left padding: the panorama gives the page its margin, and a second one here
        // would leave the notes indented from their own section name.
        setPadding(0, dp(6), dp(PAGE_MARGIN_DP), dp(24))
    }

    private fun scroller(column: View) = ScrollView(context).apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    /** Fills both sections from the notes as they now stand. */
    private fun refreshLists() {
        fill(notesColumn, notes.filterNot { it.isArchived }, "no notes yet")
        fill(archiveColumn, notes.filter { it.isArchived }, "nothing archived")
    }

    private fun fill(column: LinearLayout, entries: List<Note>, empty: String) {
        column.removeAllViews()
        if (entries.isEmpty()) {
            column.addView(TextView(context).apply {
                // A sentence rather than a picture of an empty box: the phone said what was
                // not there in the same type as everything else and left it at that.
                text = empty
                typeface = font(R.font.segoeui_regular)
                textSize = 15f
                setTextColor(palette.foregroundSubtle)
                setPadding(0, dp(10), 0, 0)
            }, wide())
            return
        }
        for (note in entries) column.addView(noteRow(note), wide())
    }

    /**
     * One note in a list: its name, and the first thing it says.
     *
     * The two lines are the phone's own list item - a name set large and light, and one
     * line under it in the subtle colour saying what is inside without opening it. A note
     * that is nothing but pictures says so, because the alternative is a blank second line
     * that reads as a note with nothing in it.
     */
    private fun noteRow(note: Note): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            // No buzz for opening a note. The phone answered a command with one - a button
            // pressed, a thing archived, a hold that brought up a menu - and going
            // somewhere is not a command, it is just going somewhere.
            setOnClickListener { openNote(note) }
            setOnLongClickListener { view ->
                // No tick fired here: the framework gives a claimed long press the shell's
                // own, and one on top of it made the hold feel like two knocks.
                showNoteMenu(note, anchorY(view))
                true
            }
        }
        TiltEffect.apply(row)

        row.addView(TextView(context).apply {
            text = note.title
            typeface = font(R.font.segoeui_semilight)
            textSize = 24f
            setTextColor(palette.foreground)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, wide())

        row.addView(TextView(context).apply {
            text = previewOf(note)
            typeface = font(R.font.segoeui_regular)
            textSize = 14f
            setTextColor(palette.foregroundSubtle)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        }, wide())

        return row
    }

    private fun previewOf(note: Note): String {
        val line = note.content.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        if (!line.isNullOrEmpty()) return line
        val pictures = note.imageUris.size
        return when {
            pictures == 1 -> "1 picture"
            pictures > 1 -> "$pictures pictures"
            else -> "empty"
        }
    }

    /** The commands for a note, from a hold on its row. Where the phone put them. */
    private fun showNoteMenu(note: Note, y: Float) {
        contextMenu.show(
            note.title,
            listOf(
                WP81ContextMenu.Item("rename") { rename(note) },
                WP81ContextMenu.Item(if (note.isArchived) "restore" else "archive") {
                    setArchived(note, !note.isArchived)
                },
                WP81ContextMenu.Item("delete") { delete(note) }
            ),
            y
        )
    }

    // ---------------------------------------------------------------- the note page

    /**
     * A note, on a page of its own.
     *
     * Built fresh each time and thrown away on the way out, so the page is only ever the
     * note that is open - and it swings in about its left edge like every other page in
     * this shell, because that is what going somewhere looks like here.
     */
    private fun openNote(note: Note) {
        closeNote(save = true, animated = false)
        current = note

        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            // Nothing behind it is reachable while it is up, and the panorama underneath
            // does not page when the note is swiped across.
            isClickable = true
        }

        val header = MetroPageHeader(context, palette).apply {
            // The name as the user typed it, capitals and all - see MetroPageHeader.setName.
            setName(note.title)
            onBack = { closeNote(save = true, animated = true) }
            // The name of the thing you are looking at is the obvious place to rename it,
            // and a page in this shell has no menu of its own to put it in.
            isClickable = true
            setOnClickListener { rename(note) }
        }
        noteHeader = header
        page.addView(header, wide())

        body = EditText(context).apply {
            setText(note.content)
            typeface = font(R.font.segoeui_regular)
            textSize = 17f
            // Page colours, not a text box's: the note is the page. See
            // WP81Palette.applyToPageText.
            palette.applyToPageText(this)
            hint = "tap to write"
            // No box and no line under it: the note *is* the page, and a text field drawn
            // on a page that is nothing but text would be drawing a border around itself.
            background = null
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(PAGE_MARGIN_DP), dp(4), dp(PAGE_MARGIN_DP), dp(16))
            setLineSpacing(0f, 1.15f)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (binding) return
                    note.content = s?.toString().orEmpty()
                    main.removeCallbacks(saveSoon)
                    main.postDelayed(saveSoon, SAVE_DELAY_MS)
                }
            })
        }
        page.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))

        pictureRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(PAGE_MARGIN_DP), 0, dp(PAGE_MARGIN_DP), dp(12))
        }
        pictureStrip = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(pictureRow, FrameLayout.LayoutParams(WRAP, WRAP))
        }
        page.addView(pictureStrip, wide())

        page.addView(buildNoteBar(note), LinearLayout.LayoutParams(MATCH, WRAP))

        notePage = page
        root.addView(page, root.indexOfChild(contextMenu), FrameLayout.LayoutParams(MATCH, MATCH))
        bindPictures(note)
        onUpdateWindowTitle(note.title)

        // Deferred a frame: a view that has just been added has no height yet, and a turn
        // measured against one pivots around the wrong place.
        noteTransition = MetroPageTransition(page)
        page.post { noteTransition?.playIn() }
    }

    /**
     * Puts the open note away.
     *
     * Everything that leaves the page comes through here - back, the bin, putting a note in
     * the archive, and the window closing - so there is one place that knows the text on
     * screen has to reach the note before the page holding it goes.
     */
    private fun closeNote(save: Boolean, animated: Boolean) {
        val page = notePage ?: return
        if (save) commit()
        // Before the fields are let go of: the keyboard is dismissed through the view that
        // raised it, and there is no window token to reach once that view has been dropped.
        hideKeyboard()
        current = null
        notePage = null
        noteHeader = null
        body = null
        pictureRow = null
        pictureStrip = null
        noteBarMenu = null
        onUpdateWindowTitle("Notepad")
        refreshLists()
        if (animated) {
            noteTransition?.playOut { root.removeView(page) } ?: root.removeView(page)
        } else {
            root.removeView(page)
        }
        noteTransition = null
    }

    /** Takes what is on screen into the note and writes it down. */
    private fun commit() {
        val note = current ?: return
        body?.let { note.content = it.text.toString() }
        main.removeCallbacks(saveSoon)
        saveNotes()
    }

    // ------------------------------------------------------------------ the app bars

    /**
     * The strip under the lists, which has one thing on it.
     *
     * Centred, not in the left corner: a lone button against one end reads as the first of
     * a row that never arrives, and a plus on its own says "another one" without help.
     *
     * Given the strip's own height by the caller rather than wrapping the button it holds.
     * Wrapped, the bar was the ring and nothing else - 44dp of strip around a 44dp ring,
     * where the note's bar is 62 - so the same button sat in a band two thirds the height
     * with its edges against both sides of it.
     */
    private fun buildListBar(): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(BAR_COLOUR)
            isClickable = true
        }
        bar.addView(circleButton(NEW_ICON) { newNote() },
            LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)))
        return bar
    }

    /**
     * The strip under a note: a picture from the camera, one from the phone, the bin, and
     * the rest behind the dots.
     *
     * Four slots is what the platform gave an app bar and it is the right number here -
     * the things a note is for, in the order you reach for them, with everything that is
     * about the note rather than its contents kept under the ellipsis.
     */
    private fun buildNoteBar(note: Note): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BAR_COLOUR)
            isClickable = true
        }

        val menu = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(6), 0, dp(6))
        }
        noteBarMenu = menu
        bar.addView(menu, LinearLayout.LayoutParams(MATCH, WRAP))

        // The commands sit together in the middle and the dots on the edge, which is the
        // shell's own strip - see WP81SecondaryBar, where the same rings are set out at the
        // same size and the same distance apart. Spread across the bar they read as
        // unrelated buttons; grouped they read as the things this note can be told to do,
        // and the dots stay out of the group because what is behind them is not one of them.
        val row = FrameLayout(context)
        val group = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        group.addView(circleButton(CAMERA_ICON) { takePicture() },
            LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)))
        group.addView(circleButton(PICTURE_ICON) { choosePicture() },
            LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)).apply {
                marginStart = dp(GAP_DP)
            })
        group.addView(circleButton(DELETE_ICON) { delete(note) },
            LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)).apply {
                marginStart = dp(GAP_DP)
            })
        row.addView(group, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        row.addView(
            buildEllipsis(note),
            FrameLayout.LayoutParams(
                dp(BUTTON_DP), dp(BUTTON_DP), Gravity.END or Gravity.CENTER_VERTICAL
            ).apply {
                // As far from the edge as the rings are from the top and bottom of the
                // strip, so the one thing that is not in the middle is still inset by the
                // bar's own measurement rather than by a number picked for it.
                marginEnd = dp((BAR_DP - BUTTON_DP) / 2)
            }
        )

        bar.addView(row, LinearLayout.LayoutParams(MATCH, dp(BAR_DP)))
        return bar
    }

    /**
     * A white ring with a white mark in it, open in the middle.
     *
     * The shape the Start screen puts on a tile in edit mode without its black fill: on the
     * app bar there is nothing behind the button but the bar, so the ring alone is the
     * button and the strip shows through it. See wp81_appbar_circle.
     */
    private fun circleButton(icon: String, onTap: () -> Unit): ImageView =
        ImageView(context).apply {
            setBackgroundResource(R.drawable.wp81_appbar_circle)
            setImageDrawable(SvgIcon.fromAsset(context, icon))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(GLYPH_INSET_DP), dp(GLYPH_INSET_DP), dp(GLYPH_INSET_DP), dp(GLYPH_INSET_DP))
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            isClickable = true
            setOnClickListener {
                Haptics.tap(it)
                closeBarMenu()
                onTap()
            }
            TiltEffect.apply(this)
        }

    /**
     * The three dots.
     *
     * Drawn rather than typed: an ellipsis character is a row of full stops sitting on the
     * baseline, and what the phone had was three round dots centred in the button.
     */
    private fun buildEllipsis(note: Note): View {
        val holder = FrameLayout(context).apply {
            isClickable = true
            setOnClickListener {
                Haptics.tap(it)
                if (noteBarMenu?.visibility == View.VISIBLE) closeBarMenu() else openBarMenu(note)
            }
        }
        val dots = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        repeat(3) { i ->
            dots.addView(View(context).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
            }, LinearLayout.LayoutParams(dp(DOT_DP), dp(DOT_DP)).apply {
                if (i > 0) marginStart = dp(4)
            })
        }
        holder.addView(dots, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        TiltEffect.apply(holder)
        return holder
    }

    private fun openBarMenu(note: Note) {
        val menu = noteBarMenu ?: return
        menu.removeAllViews()
        menu.addView(menuRow("rename") { rename(note) })
        menu.addView(menuRow(if (note.isArchived) "restore" else "archive") {
            setArchived(note, !note.isArchived)
        })
        menu.addView(menuRow("share") { share(note) })
        menu.visibility = View.VISIBLE
        // Each row swings down about its own top edge, on a stagger. Same as the shell's.
        for (i in 0 until menu.childCount) {
            val row = menu.getChildAt(i)
            row.cameraDistance = 8000f * context.resources.displayMetrics.density
            row.pivotX = 0f
            row.pivotY = 0f
            row.rotationX = -90f
            row.alpha = 0f
            row.animate().rotationX(0f).alpha(1f)
                .setStartDelay(i * STAGGER_MS)
                .setDuration(180)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun closeBarMenu() {
        val menu = noteBarMenu ?: return
        if (menu.visibility != View.VISIBLE) return
        menu.visibility = View.GONE
        menu.removeAllViews()
    }

    private fun menuRow(label: String, action: () -> Unit): View =
        TextView(context).apply {
            // Lowercase, like every command list in this shell.
            text = label.lowercase()
            typeface = font(R.font.segoeui_regular)
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(22), dp(12), dp(22), dp(12))
            isClickable = true
            setOnClickListener {
                Haptics.tap(it)
                closeBarMenu()
                action()
            }
            TiltEffect.apply(this)
        }

    // ------------------------------------------------------------------- commands

    /**
     * A new note, in the section it was asked for from.
     *
     * Made in the archive if that is the list on screen: the plus is on the strip under
     * whichever section is showing, and a note that jumped to the other one would be a
     * button that puts things where you were not looking.
     */
    private fun newNote() {
        commit()
        val archived = panorama.currentPage() == PAGE_ARCHIVE
        val note = Note(
            id = UUID.randomUUID().toString(),
            title = "Note ${notes.size + 1}",
            content = "",
            imageUris = mutableListOf(),
            isArchived = archived
        )
        notes.add(note)
        saveNotes()
        refreshLists()
        openNote(note)
    }

    private fun rename(note: Note) {
        renameDialog.show("rename", note.title) { typed ->
            val name = typed.trim()
            if (name.isEmpty()) return@show
            note.title = name
            saveNotes()
            noteHeader?.setName(name)
            if (current === note) onUpdateWindowTitle(name)
            refreshLists()
        }
    }

    /**
     * Moves a note between the two lists.
     *
     * The page closes with it when it is the one open: the note has just moved to the
     * section behind it, and a page left standing over a list it is no longer in is a
     * command that appears to have done nothing.
     */
    private fun setArchived(note: Note, archived: Boolean) {
        if (current === note) commit()
        note.isArchived = archived
        saveNotes()
        if (current === note) closeNote(save = false, animated = true) else refreshLists()
        onShowNotification("Notepad", if (archived) "Archived" else "Restored")
    }

    private fun delete(note: Note) {
        notes.remove(note)
        saveNotes()
        if (current === note) closeNote(save = false, animated = true) else refreshLists()
    }

    /** The note as text, to whatever the phone can send text with. */
    private fun share(note: Note) {
        commit()
        val text = buildString {
            append(note.title)
            if (note.content.isNotBlank()) {
                append("\n\n")
                append(note.content)
            }
        }
        try {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, note.title)
                        putExtra(Intent.EXTRA_TEXT, text)
                    },
                    null
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Nothing on this phone shares text", e)
            onShowNotification("Notepad", "Nothing here can share that")
        }
    }

    // ------------------------------------------------------------------- pictures

    private fun choosePicture() {
        commit()
        try {
            galleryPickerLauncher.launch("image/*")
        } catch (e: Exception) {
            Log.w(TAG, "No gallery to pick from", e)
            onShowNotification("Notepad", "No gallery app found")
        }
    }

    /**
     * Somewhere for the camera to put what it takes, and then the camera.
     *
     * A row in the phone's own picture library rather than a file of this app's, which is
     * the same place the desktop Notepad sends it: a photograph taken for a note is still
     * a photograph the user took, and it belongs in their pictures whether or not the note
     * survives.
     */
    private fun takePicture() {
        commit()
        val uri = try {
            context.contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                android.content.ContentValues().apply {
                    put(
                        android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                        "notepad_camera_${System.currentTimeMillis()}.jpg"
                    )
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not make a place for the picture", e)
            null
        }
        if (uri == null) {
            onShowNotification("Notepad", "Could not open the camera")
            return
        }
        onCameraCapture(uri)
    }

    /**
     * A picture the user just took or picked, onto the note that is open.
     *
     * Called by the launcher that the activity owns - a picker is an activity result, and
     * only the activity can be handed one.
     */
    fun onImageSelected(uri: Uri?) {
        val picked = uri ?: return
        val note = current ?: return
        try {
            context.contentResolver.takePersistableUriPermission(
                picked, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // A picture the camera just wrote is ours already and grants nothing to hold
            // on to; the gallery's needs the grant. Either way the note keeps the address.
            Log.d(TAG, "No persistable permission for $picked")
        }
        note.imageUris.add(picked.toString())
        saveNotes()
        bindPictures(note)
    }

    /**
     * The note's pictures, along the foot of the page.
     *
     * A strip rather than a grid: they sit between the writing and the app bar, where they
     * are a reminder of what is attached rather than the point of the page. Tapping one
     * opens it whole; holding one is how it is taken off the note.
     */
    private fun bindPictures(note: Note) {
        val row = pictureRow ?: return
        row.removeAllViews()
        pictureStrip?.visibility = if (note.imageUris.isEmpty()) View.GONE else View.VISIBLE
        for (uri in note.imageUris.toList()) {
            val thumb = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(palette.inactive)
                isClickable = true
                // Opening the picture, like opening a note, says nothing back.
                setOnClickListener { onShowFullscreenImage(Uri.parse(uri)) }
                setOnLongClickListener { view ->
                    // Claiming the hold is what buzzes. See the note list above.
                    contextMenu.show(
                        "picture",
                        listOf(WP81ContextMenu.Item("remove") {
                            note.imageUris.remove(uri)
                            saveNotes()
                            bindPictures(note)
                        }),
                        anchorY(view)
                    )
                    true
                }
            }
            TiltEffect.apply(thumb)
            row.addView(thumb, LinearLayout.LayoutParams(dp(THUMB_DP), dp(THUMB_DP)).apply {
                marginEnd = dp(8)
            })
            load(uri) { bitmap ->
                if (bitmap == null) thumb.setImageDrawable(null) else thumb.setImageBitmap(bitmap)
            }
        }
    }

    /**
     * Reads a picture small, off the main thread.
     *
     * Through ImageDecoder, which applies the rotation a camera writes into the file -
     * read with the raw decoder a portrait photograph arrives on its side.
     */
    private fun load(uri: String, onReady: (Bitmap?) -> Unit) {
        decoder.execute {
            val bitmap = try {
                val source = ImageDecoder.createSource(context.contentResolver, Uri.parse(uri))
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                    val longest = maxOf(info.size.width, info.size.height)
                    val target = dp(THUMB_DP) * 2
                    if (longest > target) {
                        val scale = target.toFloat() / longest
                        decoder.setTargetSize(
                            (info.size.width * scale).toInt().coerceAtLeast(1),
                            (info.size.height * scale).toInt().coerceAtLeast(1)
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not read a picture on a note", e)
                null
            }
            main.post { onReady(bitmap) }
        }
    }

    // ------------------------------------------------------------------- plumbing

    /**
     * Back, from the inside out.
     *
     * A press closes whatever is on top - the menu, the dialog, the app bar's own list,
     * the note - and only a press with nothing left to close leaves the app. The window
     * this lives in treats back as "put Notepad away", which is right at the top level and
     * wrong at every level below it.
     */
    fun handleBack(): Boolean {
        if (contextMenu.isShowing()) {
            contextMenu.dismiss()
            return true
        }
        if (renameDialog.isShowing()) {
            renameDialog.dismiss()
            return true
        }
        if (noteBarMenu?.visibility == View.VISIBLE) {
            closeBarMenu()
            return true
        }
        if (notePage != null) {
            closeNote(save = true, animated = true)
            return true
        }
        return false
    }

    fun cleanup() {
        commit()
        main.removeCallbacks(saveSoon)
        saveNotes()
    }

    private fun hideKeyboard() {
        val field = body ?: return
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        manager?.hideSoftInputFromWindow(field.windowToken, 0)
        field.clearFocus()
    }

    /** Where in the page a view sits, for a menu that has to come down beside it. */
    private fun anchorY(view: View): Float {
        val here = IntArray(2)
        val there = IntArray(2)
        root.getLocationOnScreen(here)
        view.getLocationOnScreen(there)
        return (there[1] - here[1] + view.height / 2f)
    }

    // ---------------------------------------------------------------------- storage

    /**
     * The desktop Notepad's own notes, in the desktop Notepad's own format.
     *
     * Deliberately the same key and the same encoding rather than a store of this app's
     * own: a note is a note whichever shell the launcher is wearing, and the one thing a
     * theme switch must never do is hide what somebody wrote.
     */
    private fun loadNotes(): MutableList<Note> {
        val json = prefs.getString(KEY_NOTES, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<Note>>() {}.type
            Gson().fromJson<MutableList<Note>>(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the notes", e)
            mutableListOf()
        }
    }

    private fun saveNotes() {
        prefs.edit { putString(KEY_NOTES, Gson().toJson(notes)) }
    }

    private fun font(res: Int) = ResourcesCompat.getFont(context, res)

    private fun wide() = LinearLayout.LayoutParams(MATCH, WRAP)

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "MetroNotepad"

        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT

        /** Where the desktop Notepad keeps its notes. See loadNotes. */
        private const val KEY_NOTES = "notepad_notes"

        /** The archive is the second section of the panorama. */
        private const val PAGE_ARCHIVE = 1

        private const val PAGE_MARGIN_DP = 22

        // The app bar, in the shell's own measurements: WP81SecondaryBar is the strip
        // that slides up over Start, and an app's strip is the same piece of furniture
        // doing the same job one level in. Its black, not the palette's - this surface
        // sits over a page rather than being part of it, and has to stay legible whatever
        // theme or accent is behind it.
        private const val BAR_COLOUR = 0xFF1F1F1F.toInt()
        private const val BAR_DP = WP81SecondaryBar.HEIGHT_DP
        private const val BUTTON_DP = 44

        /** Between the rings. Wide, so they read as a row of commands and not as a block. */
        private const val GAP_DP = 28

        /** How far the glyph sits inside its ring. */
        private const val GLYPH_INSET_DP = 5

        private const val DOT_DP = 5
        private const val STAGGER_MS = 30L

        /** How large a picture on the strip is drawn. */
        private const val THUMB_DP = 78

        /** How long the typing has to stop before the note is written down. */
        private const val SAVE_DELAY_MS = 600L

        // Modern UI Icons, the set the rest of the phone shell's marks come from.
        private const val ICON_DIR = "custom_icons_8"
        private const val NEW_ICON = "$ICON_DIR/appbar.add.svg"
        private const val CAMERA_ICON = "$ICON_DIR/appbar.camera.svg"
        private const val PICTURE_ICON = "$ICON_DIR/appbar.image.svg"
        private const val DELETE_ICON = "$ICON_DIR/appbar.delete.svg"
    }
}
