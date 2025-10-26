package rocks.gorjan.gokixp.apps.regedit

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.core.content.edit
import rocks.gorjan.gokixp.R
import java.util.concurrent.TimeUnit

class RegistryEditorApp(
    private val context: Context,
    private val onSoundPlay: () -> Unit,
    private val onShowNotification: (String, String) -> Unit,
    private val onShowAddKeyDialog: (SharedPreferences, () -> Unit) -> Unit,
    private val onExportToLocalFile: (SharedPreferences) -> Unit,
    private val onExportToGoogleDrive: (SharedPreferences) -> Unit,
    private val onImportFromLocalFile: () -> Unit,
    private val onImportFromGoogleDrive: () -> Unit,
    private val onAutoSyncChanged: (Boolean) -> Unit,
    private val getLastSyncTime: () -> Long
) {
    private var selectedRow: TableRow? = null
    private var selectedKey: String? = null
    private var lastSyncTextView: TextView? = null

    fun setupApp(contentView: View, prefs: SharedPreferences) {
        // Get references to views
        val preferencesTable = contentView.findViewById<TableLayout>(R.id.preferences_table)
        val exportButton = contentView.findViewById<TextView>(R.id.export_button)
        val importButton = contentView.findViewById<TextView>(R.id.import_button)
        val addButton = contentView.findViewById<TextView>(R.id.add_button)
        val editButton = contentView.findViewById<TextView>(R.id.edit_button)
        val deleteButton = contentView.findViewById<TextView>(R.id.delete_button)
        val autoSyncCheckbox = contentView.findViewById<CheckBox>(R.id.auto_sync_checkbox)
        lastSyncTextView = contentView.findViewById(R.id.last_sync_text)

        // Disable Edit and Delete by default
        editButton.alpha = 0.5f
        editButton.isEnabled = false
        deleteButton.alpha = 0.5f
        deleteButton.isEnabled = false

        // Load auto-sync state
        val autoSyncEnabled = prefs.getBoolean("auto_sync_google_drive", false)
        autoSyncCheckbox.isChecked = autoSyncEnabled

        // Update last sync text
        updateLastSyncText()

        // Handle auto-sync checkbox changes
        autoSyncCheckbox.setOnCheckedChangeListener { _, isChecked ->
            onSoundPlay()
            prefs.edit { putBoolean("auto_sync_google_drive", isChecked) }

            // If checking the box, show "Syncing..." and trigger sync
            if (isChecked) {
                lastSyncTextView?.text = "Syncing..."
            }

            onAutoSyncChanged(isChecked)
        }

        // Function to display all preferences
        fun displayAllPreferences() {
            val allPrefs = prefs.all

            // Clear existing table rows
            preferencesTable.removeAllViews()

            // Clear selection
            selectedRow = null
            selectedKey = null
            editButton.alpha = 0.5f
            editButton.isEnabled = false
            deleteButton.alpha = 0.5f
            deleteButton.isEnabled = false

            // Sort keys alphabetically
            val sortedPrefs = allPrefs.entries.sortedBy { it.key }

            // Add each preference as a table row
            for (entry in sortedPrefs) {
                val tableRow = TableRow(context).apply {
                    layoutParams = TableLayout.LayoutParams(
                        TableLayout.LayoutParams.MATCH_PARENT,
                        TableLayout.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(2, 2, 2, 2)
                    isClickable = true
                    isFocusable = true
                    setBackgroundColor(Color.TRANSPARENT)
                }

                // Key column
                val keyTextView = TextView(context).apply {
                    text = entry.key
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10f)
                    setTextColor(Color.BLACK)
                    typeface = android.graphics.Typeface.MONOSPACE
                    setPadding(4, 2, 8, 2)
                    layoutParams = TableRow.LayoutParams(
                        0,
                        TableRow.LayoutParams.WRAP_CONTENT,
                        0.45f
                    )
                }

                // Value column
                val valueTextView = TextView(context).apply {
                    text = entry.value?.toString() ?: "null"
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10f)
                    setTextColor(Color.BLACK)
                    typeface = android.graphics.Typeface.MONOSPACE
                    setPadding(4, 2, 4, 2)
                    layoutParams = TableRow.LayoutParams(
                        0,
                        TableRow.LayoutParams.WRAP_CONTENT,
                        0.55f
                    )
                }

                tableRow.addView(keyTextView)
                tableRow.addView(valueTextView)

                // Handle row click for selection
                tableRow.setOnClickListener {
                    onSoundPlay()

                    // Deselect previous row
                    if (selectedRow != null) {
                        selectedRow?.setBackgroundColor(Color.TRANSPARENT)
                        for (i in 0 until (selectedRow?.childCount ?: 0)) {
                            (selectedRow?.getChildAt(i) as? TextView)?.setTextColor(Color.BLACK)
                        }
                    }

                    // Select this row
                    selectedRow = tableRow
                    selectedKey = entry.key
                    tableRow.setBackgroundColor(Color.parseColor("#0A246A"))
                    keyTextView.setTextColor(Color.WHITE)
                    valueTextView.setTextColor(Color.WHITE)

                    // Enable Edit and Delete buttons
                    editButton.alpha = 1.0f
                    editButton.isEnabled = true
                    deleteButton.alpha = 1.0f
                    deleteButton.isEnabled = true
                }

                preferencesTable.addView(tableRow)

                // Add a thin divider line
                val divider = View(context).apply {
                    layoutParams = TableLayout.LayoutParams(
                        TableLayout.LayoutParams.MATCH_PARENT,
                        1
                    )
                    setBackgroundColor(Color.parseColor("#d3cec7"))
                }
                preferencesTable.addView(divider)
            }
        }

        // Display preferences on open
        displayAllPreferences()

        // Export button - show choice dialog
        exportButton.setOnClickListener {
            onSoundPlay()
            showExportChoiceDialog(prefs)
        }

        // Import button - show choice dialog
        importButton.setOnClickListener {
            onSoundPlay()
            showImportChoiceDialog(prefs)
        }

        // Add button - add new key/value pair
        addButton.setOnClickListener {
            onSoundPlay()
            onShowAddKeyDialog(prefs, ::displayAllPreferences)
        }

        // Edit button - edit selected key
        editButton.setOnClickListener {
            onSoundPlay()
            val key = selectedKey
            if (key != null) {
                showEditKeyDialog(prefs, key, ::displayAllPreferences)
            }
        }

        // Delete button - delete selected key
        deleteButton.setOnClickListener {
            onSoundPlay()
            val key = selectedKey
            if (key != null) {
                showDeleteKeyDialog(prefs, key, ::displayAllPreferences)
            }
        }
    }

    private fun showEditKeyDialog(prefs: SharedPreferences, key: String, refreshCallback: () -> Unit) {
        val currentValue = prefs.all[key]

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        container.addView(TextView(context).apply {
            text = "Key: $key"
            setTextColor(Color.BLACK)
            setPadding(0, 8, 0, 4)
        })

        val valueInput = EditText(context).apply {
            hint = "New value"
            setText(currentValue?.toString() ?: "")
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        val typeSpinner = Spinner(context)
        val typeOptions = arrayOf("String", "Boolean", "Integer", "Float", "Long")
        val currentTypeIndex = when (currentValue) {
            is String -> 0
            is Boolean -> 1
            is Int -> 2
            is Float -> 3
            is Long -> 4
            else -> 0
        }
        val spinnerAdapter = object : android.widget.ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, typeOptions) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as? TextView)?.setTextColor(Color.BLACK)
                return view
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent)
                (view as? TextView)?.setTextColor(Color.BLACK)
                return view
            }
        }
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        typeSpinner.adapter = spinnerAdapter
        typeSpinner.setSelection(currentTypeIndex)

        container.addView(TextView(context).apply {
            text = "New Value:"
            setTextColor(Color.BLACK)
            setPadding(0, 16, 0, 4)
        })
        container.addView(valueInput)
        container.addView(TextView(context).apply {
            text = "Type:"
            setTextColor(Color.BLACK)
            setPadding(0, 16, 0, 4)
        })
        container.addView(typeSpinner)

        android.app.AlertDialog.Builder(context, R.style.LightAlertDialog)
            .setTitle("Edit Key: $key")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newValue = valueInput.text.toString().trim()
                val type = typeSpinner.selectedItem.toString()

                try {
                    prefs.edit().apply {
                        when (type) {
                            "String" -> putString(key, newValue)
                            "Boolean" -> putBoolean(key, newValue.toBoolean())
                            "Integer" -> putInt(key, newValue.toInt())
                            "Float" -> putFloat(key, newValue.toFloat())
                            "Long" -> putLong(key, newValue.toLong())
                        }
                        apply()
                    }
                    onShowNotification("Registry Editor", "Key updated successfully")
                    refreshCallback()
                } catch (e: Exception) {
                    onShowNotification("Registry Editor", "Error updating key: ${e.message}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteKeyDialog(prefs: SharedPreferences, key: String, refreshCallback: () -> Unit) {
        android.app.AlertDialog.Builder(context, R.style.LightAlertDialog)
            .setTitle("Confirm Delete")
            .setMessage("Are you sure you want to delete the key:\n\n$key")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    prefs.edit { remove(key) }
                    onShowNotification("Registry Editor", "Key deleted successfully")
                    refreshCallback()
                } catch (e: Exception) {
                    onShowNotification("Registry Editor", "Error deleting key: ${e.message}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateLastSyncText() {
        val lastSync = getLastSyncTime()
        val text = if (lastSync == 0L) {
            "Never synced"
        } else {
            val now = System.currentTimeMillis()
            val diffMillis = now - lastSync
            val minutes = TimeUnit.MILLISECONDS.toMinutes(diffMillis)
            val hours = TimeUnit.MILLISECONDS.toHours(diffMillis)
            val days = TimeUnit.MILLISECONDS.toDays(diffMillis)

            when {
                minutes < 1 -> "Synced just now"
                minutes < 60 -> "Synced $minutes min ago"
                hours < 24 -> "Synced $hours hr ago"
                days == 1L -> "Synced yesterday"
                else -> "Synced $days days ago"
            }
        }
        lastSyncTextView?.text = text
    }

    fun onSyncCompleted() {
        updateLastSyncText()
    }

    private fun showExportChoiceDialog(prefs: SharedPreferences) {
        android.app.AlertDialog.Builder(context, R.style.LightAlertDialog)
            .setTitle("Export Settings")
            .setMessage("Where would you like to export your settings?")
            .setPositiveButton("Local File") { _, _ ->
                onExportToLocalFile(prefs)
            }
            .setNegativeButton("Google Drive") { _, _ ->
                onExportToGoogleDrive(prefs)
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun showImportChoiceDialog(prefs: SharedPreferences) {
        android.app.AlertDialog.Builder(context, R.style.LightAlertDialog)
            .setTitle("Import Settings")
            .setMessage("Where would you like to import your settings from?")
            .setPositiveButton("Local File") { _, _ ->
                onImportFromLocalFile()
            }
            .setNegativeButton("Google Drive") { _, _ ->
                onImportFromGoogleDrive()
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    fun cleanup() {
        // No cleanup needed for Registry Editor
    }
}
