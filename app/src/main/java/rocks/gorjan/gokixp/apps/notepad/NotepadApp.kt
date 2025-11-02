package rocks.gorjan.gokixp.apps.notepad

import android.content.Context
import android.content.SharedPreferences
import android.text.Editable
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import rocks.gorjan.gokixp.ContextMenuItem
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
    private val onUpdateWindowTitle: (String) -> Unit
) {
    companion object {
        private const val PREFS_NAME = "taskbar_widget_prefs"
        private const val KEY_NOTES = "notepad_notes"
        private const val KEY_OLD_NOTE = "notepad_content"
        private const val KEY_LAST_NOTE_ID = "notepad_last_note_id"
    }

    // Game state
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val notes = mutableListOf<Note>()
    private var currentNote: Note? = null
    private var isListExpanded = false
    private var isProgrammaticChange = false

    // UI references
    private var notesEditText: EditText? = null
    private var notesListContainer: LinearLayout? = null
    private var notesListScrollView: android.widget.ScrollView? = null
    private var notesList: android.widget.TableLayout? = null
    private var expandButton: TextView? = null
    private var addNoteButton: TextView? = null

    /**
     * Initialize the app UI
     */
    fun setupApp(contentView: View): View {
        // Get references to views
        notesEditText = contentView.findViewById(R.id.notes)
        notesListContainer = contentView.findViewById(R.id.notes_list_container)
        notesListScrollView = contentView.findViewById(R.id.notes_list_scroll)
        notesList = contentView.findViewById(R.id.notes_list)
        expandButton = contentView.findViewById(R.id.expand_button)
        addNoteButton = contentView.findViewById(R.id.add_note_button)

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

        // Load the last opened note, or fall back to the first note
        val lastNoteId = prefs.getString(KEY_LAST_NOTE_ID, null)
        currentNote = if (lastNoteId != null) {
            notes.find { it.id == lastNoteId } ?: notes.firstOrNull()
        } else {
            notes.firstOrNull()
        }

        // Load current note content
        currentNote?.let {
            isProgrammaticChange = true
            notesEditText?.setText(it.content)
            isProgrammaticChange = false
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
            // Save current note before creating new one
            currentNote?.content = notesEditText?.text.toString()
            saveNotes()

            // Create new note
            val newNote = Note(
                id = java.util.UUID.randomUUID().toString(),
                title = "Note ${notes.size + 1}",
                content = ""
            )
            notes.add(newNote)
            saveNotes()

            // Switch to new note
            currentNote = newNote
            saveLastNoteId()
            isProgrammaticChange = true
            notesEditText?.setText("")
            isProgrammaticChange = false
            updateWindowTitle()
            refreshNotesList()
        }

        // Save notes as user types and handle automatic list continuation
        notesEditText?.addTextChangedListener(object : android.text.TextWatcher {
            private var isAutoInserting = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Skip automatic list logic if this is a programmatic change (loading a note)
                if (isProgrammaticChange) return

                // Check if a newline was just added
                if (!isAutoInserting && count > 0 && s != null && start + count > 0) {
                    val insertedText = s.subSequence(start, start + count).toString()
                    if (insertedText.contains('\n')) {
                        val editText = notesEditText ?: return
                        val text = s.toString()
                        val newlinePos = start + insertedText.indexOf('\n')

                        // Find the start of the previous line
                        val prevLineStart = text.lastIndexOf('\n', newlinePos - 1) + 1
                        val prevLine = text.substring(prevLineStart, newlinePos)


                        // Check if previous line starts with "- "
                        if (prevLine.startsWith("- ")) {
                            // Auto-insert "- " after the newline
                            isAutoInserting = true
                            val cursorPos = editText.selectionStart
                            editText.text.insert(cursorPos, "- ")
                            isAutoInserting = false
                        }
                    }
                }
            }

            override fun afterTextChanged(s: android.text.Editable?) {
                // Auto-save current note content
                currentNote?.content = s.toString()
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
            onUpdateWindowTitle("Notepad - ${it.title}")
        } ?: onUpdateWindowTitle("Notepad")
    }

    /**
     * Refresh the notes list UI
     */
    private fun refreshNotesList() {
        notesList?.removeAllViews()

        notes.forEachIndexed { index, note ->
            val tableRow = android.widget.TableRow(context).apply {
                layoutParams = android.widget.TableLayout.LayoutParams(
                    android.widget.TableLayout.LayoutParams.MATCH_PARENT,
                    android.widget.TableLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val noteItem = TextView(context).apply {
                text = note.title
                setTextColor(android.graphics.Color.BLACK)
                setPadding(8.dpToPx(), 4.dpToPx(), 8.dpToPx(), 4.dpToPx())
                layoutParams = android.widget.TableRow.LayoutParams(
                    android.widget.TableRow.LayoutParams.MATCH_PARENT,
                    android.widget.TableRow.LayoutParams.WRAP_CONTENT
                )

                // Use theme's primary font
                typeface = MainActivity.getInstance()?.getThemePrimaryFont()
                var currentTheme = ThemeManager(context).getSelectedTheme()
                if(currentTheme == AppTheme.WindowsClassic){
                    textSize = 12f
                }
                else {
                    textSize = 11f
                }

                // Highlight current note with bold font and larger size
                if (note.id == currentNote?.id) {
                    setBackgroundColor(0xFFFFFFFF.toInt()) // White background
                } else {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
                setPadding(8.dpToPx(), 6.dpToPx(), 8.dpToPx(), 6.dpToPx())


                // Click to select note
                setOnClickListener {
                    // Save current note before switching
                    currentNote?.content = notesEditText?.text.toString()
                    saveNotes()

                    // Switch to selected note
                    currentNote = note
                    saveLastNoteId()
                    isProgrammaticChange = true
                    notesEditText?.setText(note.content)
                    isProgrammaticChange = false
                    updateWindowTitle()
                    refreshNotesList()
                }

                // Long press for context menu
                setOnLongClickListener { view ->
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    showNoteContextMenu(note, location[0].toFloat(), location[1].toFloat())
                    true
                }
            }

            tableRow.addView(noteItem)
            notesList?.addView(tableRow)
        }
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
                            isProgrammaticChange = true
                            notesEditText?.setText(it.content)
                            isProgrammaticChange = false
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
    }

    /**
     * Extension function to convert dp to pixels
     */
    private fun Int.dpToPx(): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
}
