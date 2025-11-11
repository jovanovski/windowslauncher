package rocks.gorjan.gokixp.apps.notepad

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import android.text.Editable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
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
    private var isProgrammaticChange = false
    private var noteInMoveMode: Note? = null
    private var showingArchivedNotes = false  // Track if we're viewing archived notes

    // UI references
    private var notesEditText: EditText? = null
    private var notesListContainer: LinearLayout? = null
    private var notesList: RecyclerView? = null
    private var expandButton: TextView? = null
    private var addNoteButton: TextView? = null
    private var archiveButton: TextView? = null
    private var notesAdapter: NotesAdapter? = null
    private var itemTouchHelper: ItemTouchHelper? = null
    private var imageGallery: RecyclerView? = null
    private var imageGalleryAdapter: ImageGalleryAdapter? = null

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
        imageGallery = contentView.findViewById(R.id.image_gallery)

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
            isProgrammaticChange = true
            notesEditText?.setText(it.content)
            isProgrammaticChange = false
            loadNoteImages(it)
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
            isProgrammaticChange = true
            notesEditText?.setText("")
            isProgrammaticChange = false
            imageGallery?.visibility = View.GONE
            updateWindowTitle()
            refreshNotesList()
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
                isProgrammaticChange = true
                notesEditText?.setText(it.content)
                isProgrammaticChange = false
                loadNoteImages(it)
            } ?: run {
                // No notes in this view
                isProgrammaticChange = true
                notesEditText?.setText("")
                isProgrammaticChange = false
                imageGallery?.visibility = View.GONE
            }

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

    /**
     * Load images for a note and update the gallery
     */
    private fun loadNoteImages(note: Note) {
        // Ensure imageUris is initialized (for backward compatibility with old notes)
        if (note.imageUris == null) {
            note.imageUris = mutableListOf()
        }

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
                    if (note.imageUris == null) {
                        note.imageUris = mutableListOf()
                    }
                    note.imageUris.add(selectedUri.toString())
                    saveNotes()
                    loadNoteImages(note)
                }
            } catch (e: SecurityException) {
                Log.e("NotepadApp", "Could not take persistent permission", e)
                // Still add the image even if we can't take persistent permission
                currentNote?.let { note ->
                    // Ensure imageUris is initialized (for backward compatibility)
                    if (note.imageUris == null) {
                        note.imageUris = mutableListOf()
                    }
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
                        isProgrammaticChange = true
                        notesEditText?.setText(it.content)
                        isProgrammaticChange = false
                        loadNoteImages(it)
                    } ?: run {
                        // No notes left in this view
                        isProgrammaticChange = true
                        notesEditText?.setText("")
                        isProgrammaticChange = false
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
                    isProgrammaticChange = true
                    notesEditText?.setText(note.content)
                    isProgrammaticChange = false
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
