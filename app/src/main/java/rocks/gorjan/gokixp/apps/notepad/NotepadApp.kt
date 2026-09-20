package rocks.gorjan.gokixp.apps.notepad

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import rocks.gorjan.gokixp.ContextMenuItem
import rocks.gorjan.gokixp.Helpers
import rocks.gorjan.gokixp.MainActivity
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.theme.AppTheme
import rocks.gorjan.gokixp.theme.ThemeManager

/**
 * Notepad app logic and UI controller
 * This structure can be used as a template for other system apps
 */
class NotepadApp(
    private val context: Context,
    private val onSoundPlay: (String) -> Unit,
    private val onShowContextMenu: (List<ContextMenuItem>, Float, Float) -> Unit,
    private val onShowRenameDialog: (String, String, String, (String) -> Unit) -> Unit,
    private val onUpdateWindowTitle: (String) -> Unit,
    private val galleryPickerLauncher: ActivityResultLauncher<String>,
    private val onCameraCapture: (Uri) -> Unit,
    private val onShowFullscreenImage: (Uri) -> Unit,
    private val getCursorPosition: () -> Pair<Float, Float>
) {
    companion object {
        private const val KEY_NOTES = "notepad_notes"
        private const val KEY_OLD_NOTE = "notepad_content"
        private const val KEY_LAST_NOTE_ID = "notepad_last_note_id"
    }

    // Game state
    private val prefs: SharedPreferences = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
    private val notes = mutableListOf<Note>()
    private var currentNote: Note? = null
    private var isListExpanded = false
    private var noteInMoveMode: Note? = null
    private var showingArchivedNotes = false  // Track if we're viewing archived notes

    // UI references
    private var notesEditText: NoteEditor? = null
    private var formatButton: TextView? = null
    private var notesListContainer: LinearLayout? = null
    private var notesList: RecyclerView? = null
    private var expandButton: TextView? = null
    private var addNoteButton: TextView? = null
    private var archiveButton: TextView? = null
    private var notesAdapter: NotesAdapter? = null
    private var itemTouchHelper: ItemTouchHelper? = null
    private var imageGallery: RecyclerView? = null
    private var imageGalleryAdapter: ImageGalleryAdapter? = null

    /** What the open note's markdown is drawn in. See [NoteMarkdown]. */
    private var noteLook: NoteMarkdown.Look? = null
    private val main = Handler(Looper.getMainLooper())
    private val restyle = Runnable { restyleNow() }

    /**
     * Initialize the app UI
     */
    fun setupApp(contentView: View): View {
        // Get references to views
        notesEditText = contentView.findViewById(R.id.notes)
        notesListContainer = contentView.findViewById(R.id.notes_list_container)
        notesList = contentView.findViewById(R.id.notes_list)
        expandButton = contentView.findViewById(R.id.expand_button)
        addNoteButton = contentView.findViewById(R.id.add_note_button)
        archiveButton = contentView.findViewById(R.id.notepad_archive_button)
        formatButton = contentView.findViewById(R.id.notepad_format_button)
        imageGallery = contentView.findViewById(R.id.image_gallery)

        // Markdown, drawn on the note as it is written, with its marks hidden until the
        // caret comes to one. The text itself is untouched - see NoteMarkdown. The look is
        // built once per window, since the shell's theme cannot change while one is open.
        noteLook = markdownLook()
        notesEditText?.apply {
            onSelection = { restyleSoon() }
            // A note nobody is writing in has no caret, so it shows none of its marks.
            setOnFocusChangeListener { _, _ -> restyleSoon() }
            // Return in a list carries the list on, and on an empty item ends it.
            filters = arrayOf(ListReturn(::endList))
        }

        // Setup Notes RecyclerView
        notesAdapter = NotesAdapter()
        notesList?.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = notesAdapter
        }

        // Setup Image Gallery RecyclerView
        imageGallery?.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        }

        // Setup ItemTouchHelper for drag-and-drop
        val callback = object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                // Only allow dragging if in move mode
                val dragFlags = if (noteInMoveMode != null) ItemTouchHelper.UP or ItemTouchHelper.DOWN else 0
                return makeMovementFlags(dragFlags, 0)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPosition = viewHolder.adapterPosition
                val toPosition = target.adapterPosition
                notesAdapter?.onItemMove(fromPosition, toPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // Not used
            }

            override fun isLongPressDragEnabled(): Boolean = false

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // Called when dragging ends
                notesAdapter?.onItemDropped()

                // Exit move mode
                noteInMoveMode = null
                refreshNotesList()
                onSoundPlay("click")
            }
        }

        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper?.attachToRecyclerView(notesList)

        // Load notes from SharedPreferences
        notes.clear()
        notes.addAll(loadNotes())

        // Migrate old single note if exists
        val oldNote = prefs.getString(KEY_OLD_NOTE, null)
        if (!oldNote.isNullOrEmpty() && notes.isEmpty()) {
            val migratedNote = Note(
                id = java.util.UUID.randomUUID().toString(),
                title = "Note 1",
                content = oldNote
            )
            notes.add(migratedNote)
            saveNotes()
            prefs.edit { remove(KEY_OLD_NOTE) }
        }

        // Ensure at least one note exists
        if (notes.isEmpty()) {
            notes.add(Note(
                id = java.util.UUID.randomUUID().toString(),
                title = "Note 1",
                content = ""
            ))
            saveNotes()
        }

        // Always start with active (non-archived) notes view
        showingArchivedNotes = false
        archiveButton?.text = "Archived Notes"

        // Load the last opened note if it's active, otherwise fall back to first active note
        val lastNoteId = prefs.getString(KEY_LAST_NOTE_ID, null)
        currentNote = if (lastNoteId != null) {
            val lastNote = notes.find { it.id == lastNoteId }
            // Only use last note if it's not archived
            if (lastNote != null && !lastNote.isArchived) {
                lastNote
            } else {
                getFilteredNotes().firstOrNull()
            }
        } else {
            getFilteredNotes().firstOrNull()
        }

        // Load current note content and images
        currentNote?.let {
            notesEditText?.setText(it.content)
            loadNoteImages(it)
            // The watcher that styles every later change is not on yet, so this first note
            // is styled by hand.
            restyleNow()
        }

        // Set initial title
        updateWindowTitle()

        // Initial list refresh
        refreshNotesList()

        // Expand/collapse button
        expandButton?.setOnClickListener {
            onSoundPlay("click")
            isListExpanded = !isListExpanded
            if (isListExpanded) {
                notesListContainer?.visibility = View.VISIBLE
                expandButton?.text = "<"
            } else {
                notesListContainer?.visibility = View.GONE
                expandButton?.text = ">"
            }
        }

        // Add note button
        addNoteButton?.setOnClickListener {
            onSoundPlay("click")
            Helpers.performHapticFeedback(context)

            // Save current note before creating new one
            currentNote?.content = notesEditText?.text.toString()
            saveNotes()

            // Create new note with current archive status matching the view
            val newNote = Note(
                id = java.util.UUID.randomUUID().toString(),
                title = "Note ${notes.size + 1}",
                content = "",
                imageUris = mutableListOf(),
                isArchived = showingArchivedNotes  // Match current view
            )
            notes.add(newNote)
            saveNotes()

            // Switch to new note
            currentNote = newNote
            saveLastNoteId()
            notesEditText?.setText("")
            imageGallery?.visibility = View.GONE
            updateWindowTitle()
            refreshNotesList()
        }

        // Format menu - the markdown commands, on the selection or on what is typed next
        formatButton?.setOnClickListener { view ->
            onSoundPlay("click")
            showFormatMenu(view)
        }

        // Add Image button
        val addImageButton = contentView.findViewById<TextView>(R.id.notepad_add_image)
        addImageButton?.setOnClickListener { view ->
            onSoundPlay("click")
            showImageSourcePicker(view)
        }

        // Archive button - toggle between active and archived notes
        archiveButton?.setOnClickListener {
            onSoundPlay("click")
            Helpers.performHapticFeedback(context)

            // Save current note before switching views
            currentNote?.content = notesEditText?.text.toString()
            saveNotes()

            // Toggle archive view
            showingArchivedNotes = !showingArchivedNotes

            // Update button text
            archiveButton?.text = if (showingArchivedNotes) "Active Notes" else "Archived Notes"

            // Switch to first note in the new view (archived or active)
            val filteredNotes = getFilteredNotes()
            currentNote = filteredNotes.firstOrNull()
            saveLastNoteId()

            // Load the new current note
            currentNote?.let {
                notesEditText?.setText(it.content)
                loadNoteImages(it)
            } ?: run {
                // No notes in this view
                notesEditText?.setText("")
                imageGallery?.visibility = View.GONE
            }

            updateWindowTitle()
            refreshNotesList()
        }

        // Save notes as user types, and draw the markdown on them as they are written.
        // Carrying a list on past a return is the input filter's job now - see ListReturn -
        // which knows about numbers and to-do boxes as well as bullets.
        notesEditText?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val text = s ?: return
                // Restyled after every change rather than parsed once: a note is short, and
                // a code fence opened ten lines up changes how the line being typed is set.
                noteLook?.let { NoteMarkdown.style(text, it, caret()) }

                // Auto-save current note content
                currentNote?.content = text.toString()
                saveNotes()
            }
        })

        return contentView
    }

    /**
     * Save current state when window is minimized
     */
    fun onMinimize() {
        currentNote?.content = notesEditText?.text.toString()
        saveNotes()
        saveLastNoteId()
    }

    /**
     * Update window title with current note name
     */
    private fun updateWindowTitle() {
        currentNote?.let {
            onUpdateWindowTitle("${it.title} - Notepad")
        } ?: onUpdateWindowTitle("Notepad")
    }

    /**
     * Get filtered notes based on current view (active or archived)
     */
    private fun getFilteredNotes(): List<Note> {
        return notes.filter { it.isArchived == showingArchivedNotes }
    }

    /**
     * Refresh the notes list UI
     */
    private fun refreshNotesList() {
        notesAdapter?.notifyDataSetChanged()
    }

    // ============================== Markdown ==============================

    /**
     * What the open note's markdown is drawn in.
     *
     * The page is white under every one of the shells - see program_notepad.xml - so only
     * the accent moves with the theme; the rest are the greys Windows has always drawn its
     * chrome in.
     */
    private fun markdownLook(): NoteMarkdown.Look {
        val themes = ThemeManager(context)
        val theme = themes.getSelectedTheme()
        val bold = ResourcesCompat.getFont(context, themes.getBoldFontRes(theme))?.let { face ->
            // Tahoma - XP's face and Vista's - is carried without a bold of its own, so
            // there the platform emboldens it. Micross and Segoe have a real bold beside them.
            if (theme is AppTheme.WindowsXP || theme is AppTheme.WindowsVista) {
                Typeface.create(face, Typeface.BOLD)
            } else {
                face
            }
        } ?: Typeface.DEFAULT_BOLD

        return NoteMarkdown.Look(
            // Links, bullets and the bar down a quotation, in the shell's own blue.
            accent = when (theme) {
                AppTheme.WindowsClassic -> 0xFF000080.toInt()
                AppTheme.WindowsXP -> 0xFF316AC5.toInt()
                else -> 0xFF0066CC.toInt()
            },
            ink = Color.BLACK,
            subtle = 0xFF808080.toInt(),
            // The ground behind code: 9x greys it in the silver it greyed everything in,
            // the later shells more lightly.
            shade = if (theme is AppTheme.WindowsClassic) 0xFFC0C0C0.toInt() else 0xFFE8E8E8.toInt(),
            bold = bold,
            density = context.resources.displayMetrics.density
        )
    }

    /**
     * Restyles the open note once the event that moved its caret is over.
     *
     * Not there and then: the caret is moved from inside the text's own bookkeeping, and
     * adding and taking off spans while the text is still telling its watchers about the
     * last change is how an Editable ends up notifying a span twice or not at all.
     * Coalesced, since a tap moves the caret and the focus together.
     */
    private fun restyleSoon() {
        main.removeCallbacks(restyle)
        main.post(restyle)
    }

    private fun restyleNow() {
        val field = notesEditText ?: return
        val look = noteLook ?: return
        NoteMarkdown.style(field.text ?: return, look, caret())
    }

    /** The caret, for the marks it uncovers: none while the note is only being read. */
    private fun caret(): IntRange? {
        val field = notesEditText ?: return null
        if (!field.isFocused) return null
        val start = field.selectionStart
        val end = field.selectionEnd
        if (start < 0 || end < 0) return null
        return minOf(start, end)..maxOf(start, end)
    }

    /**
     * The Format menu: the markdown commands, ticked where they are in force at the caret.
     *
     * The note's text and selection are taken now and handed to whichever command is
     * picked. The menu takes the focus while it is up, so by the time a command runs the
     * caret is no longer the note's to read - and nothing can have been typed meanwhile,
     * which is why taking it here is safe.
     */
    private fun showFormatMenu(anchorView: View) {
        val field = notesEditText ?: return
        val src = field.text?.toString() ?: return
        val s = minOf(field.selectionStart, field.selectionEnd).coerceIn(0, src.length)
        val e = maxOf(field.selectionStart, field.selectionEnd).coerceIn(0, src.length)
        val active = NoteFormat.active(src, s, e)

        fun command(title: String, tool: NoteFormat.Tool) = ContextMenuItem(
            title = title,
            isEnabled = true,
            hasCheckbox = true,
            isChecked = tool in active,
            action = {
                onSoundPlay("click")
                applyFormat(tool, src, s, e)
            }
        )

        val divider = ContextMenuItem("", isEnabled = false)
        val menuItems = listOf(
            command("Bold", NoteFormat.Tool.BOLD),
            command("Italic", NoteFormat.Tool.ITALIC),
            command("Underline", NoteFormat.Tool.UNDERLINE),
            divider,
            command("Heading 1", NoteFormat.Tool.H1),
            command("Heading 2", NoteFormat.Tool.H2),
            command("Heading 3", NoteFormat.Tool.H3),
            ContextMenuItem(
                title = "Normal",
                isEnabled = true,
                action = {
                    onSoundPlay("click")
                    applyFormat(NoteFormat.Tool.NORMAL, src, s, e)
                }
            ),
            divider,
            command("Bulleted List", NoteFormat.Tool.BULLETS),
            command("Numbered List", NoteFormat.Tool.NUMBERS),
            divider,
            ContextMenuItem(
                title = if (NoteFormat.Tool.LINK in active) "Edit Link..." else "Add Link...",
                isEnabled = true,
                action = {
                    onSoundPlay("click")
                    askForLink(src, s, e)
                }
            )
        )

        // Below the menu bar's own "Format", the way a menu drops in Windows.
        val location = IntArray(2)
        anchorView.getLocationOnScreen(location)
        onShowContextMenu(
            menuItems,
            location[0].toFloat(),
            location[1].toFloat() + anchorView.height
        )
    }

    /** One of the Format menu's commands, on the selection - or, with none, on what is typed next. */
    private fun applyFormat(tool: NoteFormat.Tool, src: String, s: Int, e: Int) {
        val field = notesEditText ?: return
        if (field.text?.toString() != src) return
        NoteFormat.apply(tool, src, s, e)?.let { carryOut(field, it) }
    }

    /**
     * Makes a command's changes and puts the selection where it says.
     *
     * As edits to the text like typing is, so the note saves and restyles the way it would
     * if the marks had been typed by hand; batched, so the keyboard hears about them once
     * rather than once each.
     */
    private fun carryOut(field: NoteEditor, result: NoteFormat.Result) {
        val text = field.text ?: return
        field.beginBatchEdit()
        for (c in result.changes) text.replace(c.start, c.end, c.text)
        field.endBatchEdit()
        field.setSelection(
            result.selStart.coerceIn(0, text.length),
            result.selEnd.coerceIn(0, text.length)
        )
        // Back to the note, so the caret is the note's again and shows the marks the
        // command just put down.
        field.requestFocus()
    }

    /**
     * Asks for the address to link the selection to - or, inside a link already, for its
     * new one, with an empty answer taking the link off.
     *
     * The selection is taken before the prompt goes up and used when the answer comes back:
     * nothing can be typed into the note meanwhile, and a note that has changed anyway -
     * switched out from under the prompt - is left alone.
     */
    private fun askForLink(src: String, s: Int, e: Int) {
        val existing = NoteFormat.linkAt(src, s, e)
        onShowRenameDialog(
            if (existing == null) "Add Link" else "Edit Link",
            existing?.url.orEmpty(),
            "Web address"
        ) { typed ->
            val field = notesEditText ?: return@onShowRenameDialog
            if (field.text?.toString() != src) return@onShowRenameDialog
            NoteFormat.link(src, s, e, typed)?.let { carryOut(field, it) }
        }
    }

    /**
     * Takes an empty list item's mark off, after the return that ended the list - see
     * ListReturn - once it is certain the mark is still there to take.
     */
    private fun endList(start: Int, mark: String) {
        main.post {
            val text = notesEditText?.text ?: return@post
            val end = start + mark.length
            if (end <= text.length && text.subSequence(start, end).toString() == mark) {
                text.delete(start, end)
            }
        }
    }

    /**
     * Load images for a note and update the gallery
     */
    private fun loadNoteImages(note: Note) {
        // Ensure imageUris is initialized (for backward compatibility with old notes)

        if (note.imageUris.isEmpty()) {
            // No images - hide gallery
            imageGallery?.visibility = View.GONE
        } else {
            // Has images - show gallery and load them
            imageGallery?.visibility = View.VISIBLE
            imageGalleryAdapter = ImageGalleryAdapter(note.imageUris)
            imageGallery?.adapter = imageGalleryAdapter
        }
    }

    /**
     * Show image source picker dialog (Gallery or Camera)
     */
    private fun showImageSourcePicker(anchorView: View) {
        val menuItems = listOf(
            ContextMenuItem(
                title = "From Gallery",
                isEnabled = true,
                action = {
                    onSoundPlay("click")
                    // Launch gallery picker
                    galleryPickerLauncher.launch("image/*")
                }
            ),
            ContextMenuItem(
                title = "From Camera",
                isEnabled = true,
                action = {
                    onSoundPlay("click")
                    // Create temporary URI for camera
                    val tempUri = createTempImageUri()
                    if (tempUri != null) {
                        onCameraCapture(tempUri)
                    }
                }
            )
        )

        // Show context menu right below the Add Image button
        val location = IntArray(2)
        anchorView.getLocationOnScreen(location)
        val x = location[0].toFloat()
        val y = location[1].toFloat() + anchorView.height
        onShowContextMenu(menuItems, x, y)
    }

    /**
     * Create a temporary URI for camera capture
     */
    private fun createTempImageUri(): Uri? {
        return try {
            val fileName = "notepad_camera_${System.currentTimeMillis()}.jpg"
            val contentValues = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            }
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        } catch (e: Exception) {
            Log.e("NotepadApp", "Error creating temp URI", e)
            null
        }
    }

    /**
     * Handle image selected from gallery or camera
     */
    fun onImageSelected(uri: Uri?) {
        uri?.let { selectedUri ->
            try {
                // Take persistent permission for this URI
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(selectedUri, takeFlags)

                // Add to current note
                currentNote?.let { note ->
                    // Ensure imageUris is initialized (for backward compatibility)
                    note.imageUris.add(selectedUri.toString())
                    saveNotes()
                    loadNoteImages(note)
                }
            } catch (e: SecurityException) {
                Log.e("NotepadApp", "Could not take persistent permission", e)
                // Still add the image even if we can't take persistent permission
                currentNote?.let { note ->
                    // Ensure imageUris is initialized (for backward compatibility)
                    note.imageUris.add(selectedUri.toString())
                    saveNotes()
                    loadNoteImages(note)
                }
            } catch (e: Exception) {
                Log.e("NotepadApp", "Error adding image", e)
            }
        }
    }

    /**
     * Show context menu for an image
     */
    private fun showImageContextMenu(uriString: String) {
        val menuItems = listOf(
            ContextMenuItem(
                title = "Delete",
                isEnabled = true,
                action = {
                    onSoundPlay("click")
                    // Remove image from current note
                    currentNote?.let { note ->
                        note.imageUris?.remove(uriString)
                        saveNotes()
                        loadNoteImages(note)
                    }
                }
            )
        )

        val (x, y) = getCursorPosition()
        onShowContextMenu(menuItems, x, y)
    }

    /**
     * Show context menu for a note
     */
    private fun showNoteContextMenu(note: Note, x: Float, y: Float) {
        // Create context menu items
        val menuItems = mutableListOf<ContextMenuItem>()

        // Rename option
        menuItems.add(ContextMenuItem(
            title = "Rename",
            isEnabled = true,
            action = {
                onShowRenameDialog("Rename Note", note.title, "Note name") { newTitle ->
                    if (newTitle.isNotBlank()) {
                        note.title = newTitle
                        saveNotes()
                        updateWindowTitle()
                        refreshNotesList()
                    }
                }
            }
        ))

        // Move option (only if more than one note exists in current view)
        val filteredNotes = getFilteredNotes()
        if (filteredNotes.size > 1) {
            menuItems.add(ContextMenuItem(
                title = "Move",
                isEnabled = true,
                action = {
                    // Enter move mode for this note
                    noteInMoveMode = note
                    refreshNotesList()

                    // Start dragging for this note
                    val noteIndex = filteredNotes.indexOfFirst { it.id == note.id }
                    notesList?.post {
                        val viewHolder = notesList?.findViewHolderForAdapterPosition(noteIndex)
                        viewHolder?.let {
                            itemTouchHelper?.startDrag(it)
                        }
                    }

                    onSoundPlay("click")
                }
            ))
        }

        // Archive/Unarchive option
        menuItems.add(ContextMenuItem(
            title = if (note.isArchived) "Unarchive" else "Archive",
            isEnabled = true,
            action = {
                onSoundPlay("click")

                // Toggle archive status
                note.isArchived = !note.isArchived
                saveNotes()

                // If we archived/unarchived the current note, switch to first available note in current view
                if (note.id == currentNote?.id) {
                    val remainingNotes = getFilteredNotes()
                    currentNote = remainingNotes.firstOrNull()
                    saveLastNoteId()

                    currentNote?.let {
                        notesEditText?.setText(it.content)
                        loadNoteImages(it)
                    } ?: run {
                        // No notes left in this view
                        notesEditText?.setText("")
                        imageGallery?.visibility = View.GONE
                    }

                    updateWindowTitle()
                }

                refreshNotesList()
            }
        ))

        // Delete option (only if more than one note exists)
        if (notes.size > 1) {
            menuItems.add(ContextMenuItem(
                title = "Delete",
                isEnabled = true,
                action = {
                    // Find index of note to delete
                    val index = notes.indexOfFirst { it.id == note.id }
                    if (index != -1) {
                        notes.removeAt(index)
                        saveNotes()

                        // Switch to first remaining note
                        val newCurrentNote = notes.firstOrNull()
                        currentNote = newCurrentNote
                        saveLastNoteId()
                        newCurrentNote?.let {
                            notesEditText?.setText(it.content)
                        }

                        updateWindowTitle()
                        refreshNotesList()
                    }
                }
            ))
        }

        // Show context menu
        onShowContextMenu(menuItems, x, y)
    }

    /**
     * Load notes from SharedPreferences
     */
    private fun loadNotes(): MutableList<Note> {
        val notesJson = prefs.getString(KEY_NOTES, null)
        return if (notesJson != null) {
            val type = object : TypeToken<MutableList<Note>>() {}.type
            Gson().fromJson(notesJson, type) ?: mutableListOf()
        } else {
            mutableListOf()
        }
    }

    /**
     * Save notes to SharedPreferences
     */
    private fun saveNotes() {
        val notesJson = Gson().toJson(notes)
        prefs.edit {
            putString(KEY_NOTES, notesJson)
        }
    }

    /**
     * Save the current note ID to SharedPreferences
     */
    private fun saveLastNoteId() {
        currentNote?.let { note ->
            prefs.edit {
                putString(KEY_LAST_NOTE_ID, note.id)
            }
        }
    }

    /**
     * Cleanup when app is closed
     */
    fun cleanup() {
        // Save final state
        currentNote?.content = notesEditText?.text.toString()
        saveNotes()
        saveLastNoteId()
        main.removeCallbacks(restyle)
    }

    /**
     * Extension function to convert dp to pixels
     */
    private fun Int.dpToPx(): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }

    /**
     * RecyclerView Adapter for notes list
     */
    inner class NotesAdapter : RecyclerView.Adapter<NotesAdapter.NoteViewHolder>() {

        inner class NoteViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
            val textView = TextView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setTextColor(Color.BLACK)
                setPadding(8.dpToPx(), 6.dpToPx(), 8.dpToPx(), 6.dpToPx())

                // Use theme's primary font
                typeface = MainActivity.getInstance()?.getThemePrimaryFont()
                val currentTheme = ThemeManager(context).getSelectedTheme()
                textSize = if (currentTheme == AppTheme.WindowsClassic) 12f else 11f
            }
            return NoteViewHolder(textView)
        }

        override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
            val filteredNotes = getFilteredNotes()
            val note = filteredNotes[position]
            val textView = holder.textView

            // Set text and appearance based on state
            if (note.id == noteInMoveMode?.id) {
                // Note in move mode - highlight with yellow/gold
                textView.setBackgroundColor(0xFFFFFF99.toInt())
                textView.text = "↕ ${note.title}"
            } else if (note.id == currentNote?.id) {
                // Current note - white background
                textView.setBackgroundColor(0xFFFFFFFF.toInt())
                textView.text = note.title
            } else {
                // Normal note
                textView.setBackgroundColor(Color.TRANSPARENT)
                textView.text = note.title
            }

            // Click listener (only if not in move mode)
            if (note.id != noteInMoveMode?.id) {
                textView.setOnClickListener {
                    // Save current note before switching
                    currentNote?.content = notesEditText?.text.toString()
                    saveNotes()

                    // Switch to selected note
                    currentNote = note
                    saveLastNoteId()
                    notesEditText?.setText(note.content)
                    updateWindowTitle()
                    loadNoteImages(note)
                    notifyDataSetChanged()
                }

                // Long press for context menu
                textView.setOnLongClickListener { view ->
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    showNoteContextMenu(note, location[0].toFloat(), location[1].toFloat())
                    true
                }
            } else {
                // In move mode - clear listeners
                textView.setOnClickListener(null)
                textView.setOnLongClickListener(null)
            }
        }

        override fun getItemCount(): Int = getFilteredNotes().size

        fun onItemMove(fromPosition: Int, toPosition: Int) {
            val filteredNotes = getFilteredNotes().toMutableList()

            // Move within filtered list
            val movedNote = filteredNotes[fromPosition]
            filteredNotes.removeAt(fromPosition)
            filteredNotes.add(toPosition, movedNote)

            // Update positions in main notes list
            // Remove all filtered notes from main list, then re-add in new order
            val otherNotes = notes.filter { it.isArchived != showingArchivedNotes }
            notes.clear()
            notes.addAll(otherNotes)
            notes.addAll(filteredNotes)

            notifyItemMoved(fromPosition, toPosition)
        }

        fun onItemDropped() {
            // Save the new order when dragging is complete
            saveNotes()
        }
    }

    /**
     * Image Gallery Adapter for displaying images in horizontal scroll
     */
    inner class ImageGalleryAdapter(
        private val imageUris: MutableList<String>
    ) : RecyclerView.Adapter<ImageGalleryAdapter.ImageViewHolder>() {

        inner class ImageViewHolder(val imageView: android.widget.ImageView) : RecyclerView.ViewHolder(imageView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
            val imageView = android.widget.ImageView(context).apply {
                layoutParams = ViewGroup.MarginLayoutParams(72.dpToPx(), 72.dpToPx()).apply {
                    marginEnd = 4.dpToPx()
                }
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.LTGRAY)
            }
            return ImageViewHolder(imageView)
        }

        override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
            val uriString = imageUris[position]
            val imageView = holder.imageView
            var isValidImage = false

            try {
                val uri = android.net.Uri.parse(uriString)

                // Try to load the image with proper orientation
                val bitmap = loadBitmapWithOrientation(uri)

                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                    imageView.setBackgroundColor(Color.TRANSPARENT)
                    isValidImage = true
                } else {
                    // Image couldn't be decoded - show placeholder
                    showPlaceholder(imageView)
                }

                // Set up click listener to view full-screen (only for valid images)
                if (isValidImage) {
                    imageView.setOnClickListener {
                        onSoundPlay("click")
                        onShowFullscreenImage(uri)
                    }
                } else {
                    imageView.setOnClickListener(null)
                }

            } catch (e: Exception) {
                // Error loading image - show placeholder
                Log.e("NotepadApp", "Error loading image: $uriString", e)
                showPlaceholder(imageView)
                imageView.setOnClickListener(null)
            }

            // Set up long press listener to show delete menu (always available)
            imageView.setOnLongClickListener {
                showImageContextMenu(uriString)
                true
            }
        }

        private fun loadBitmapWithOrientation(uri: Uri): android.graphics.Bitmap? {
            try {
                // First, decode the bitmap
                val inputStream = context.contentResolver.openInputStream(uri)
                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (bitmap == null) return null

                // Read EXIF orientation
                val exifInputStream = context.contentResolver.openInputStream(uri)
                val exif = exifInputStream?.use {
                    androidx.exifinterface.media.ExifInterface(it)
                }

                val orientation = exif?.getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                ) ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL

                // Calculate rotation angle
                val rotationAngle = when (orientation) {
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }

                // If no rotation needed, return original bitmap
                if (rotationAngle == 0f) return bitmap

                // Rotate the bitmap
                val matrix = android.graphics.Matrix()
                matrix.postRotate(rotationAngle)
                val rotatedBitmap = android.graphics.Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                )

                // Recycle original if different from rotated
                if (rotatedBitmap != bitmap) {
                    bitmap.recycle()
                }

                return rotatedBitmap
            } catch (e: Exception) {
                Log.e("NotepadApp", "Error loading bitmap with orientation", e)
                return null
            }
        }

        private fun showPlaceholder(imageView: android.widget.ImageView) {
            // Create a simple placeholder: gray background with "?" icon
            imageView.setBackgroundColor(0xFFE0E0E0.toInt())
            imageView.setImageDrawable(null)

            // Create a simple text drawable as placeholder
            val textDrawable = android.graphics.drawable.ShapeDrawable().apply {
                paint.color = Color.GRAY
                paint.textSize = 32f
                paint.textAlign = android.graphics.Paint.Align.CENTER
            }

            // Set a broken image indicator (we'll use a simple colored square for now)
            imageView.setColorFilter(0xFF999999.toInt())
        }

        override fun getItemCount(): Int = imageUris.size

        fun updateImages(newImageUris: List<String>) {
            imageUris.clear()
            imageUris.addAll(newImageUris)
            notifyDataSetChanged()
        }
    }
}
