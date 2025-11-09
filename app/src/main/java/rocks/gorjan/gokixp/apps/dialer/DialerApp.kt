package rocks.gorjan.gokixp.apps.dialer

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.core.content.edit
import rocks.gorjan.gokixp.ContextMenuItem
import rocks.gorjan.gokixp.MainActivity
import rocks.gorjan.gokixp.R

/**
 * Phone Dialer app logic and UI controller
 */
class DialerApp(
    private val context: Context,
    private val onSoundPlay: (Int) -> Unit,
    private val onShowContextMenu: (List<ContextMenuItem>, Float, Float) -> Unit
) {
    companion object {
        private const val SPEED_DIAL_COUNT = 8
    }

    // App state
    private val prefs: SharedPreferences = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
    private var currentContacts = listOf<ContactInfo>()
    private var isPickingSlot = false
    private var contactToSave: ContactInfo? = null

    // UI references
    private var numberEdit: TextView? = null
    private val speedDialViews = mutableListOf<TextView>()
    private lateinit var updateSpeedDials: () -> Unit

    /**
     * Initialize the app UI
     */
    fun setupApp(contentView: View): View {
        // Get references to views
        numberEdit = contentView.findViewById(R.id.number_edit)
        val button1 = contentView.findViewById<View>(R.id.button_1)
        val button2 = contentView.findViewById<View>(R.id.button_2)
        val button3 = contentView.findViewById<View>(R.id.button_3)
        val button4 = contentView.findViewById<View>(R.id.button_4)
        val button5 = contentView.findViewById<View>(R.id.button_5)
        val button6 = contentView.findViewById<View>(R.id.button_6)
        val button7 = contentView.findViewById<View>(R.id.button_7)
        val button8 = contentView.findViewById<View>(R.id.button_8)
        val button9 = contentView.findViewById<View>(R.id.button_9)
        val button0 = contentView.findViewById<View>(R.id.button_0)
        val buttonHash = contentView.findViewById<View>(R.id.button_hash)
        val buttonDelete = contentView.findViewById<View>(R.id.button_delete)
        val buttonDial = contentView.findViewById<View>(R.id.button_dial)

        // Get references to speed dial TextViews
        speedDialViews.clear()
        speedDialViews.add(contentView.findViewById(R.id.speed_dial_1))
        speedDialViews.add(contentView.findViewById(R.id.speed_dial_2))
        speedDialViews.add(contentView.findViewById(R.id.speed_dial_3))
        speedDialViews.add(contentView.findViewById(R.id.speed_dial_4))
        speedDialViews.add(contentView.findViewById(R.id.speed_dial_5))
        speedDialViews.add(contentView.findViewById(R.id.speed_dial_6))
        speedDialViews.add(contentView.findViewById(R.id.speed_dial_7))
        speedDialViews.add(contentView.findViewById(R.id.speed_dial_8))

        // Function to update speed dial list
        updateSpeedDials = {
            when {
                isPickingSlot -> {
                    // Show "Pick slot" in all slots
                    speedDialViews.forEachIndexed { index, textView ->
                        textView.text = "Pick slot"
                        textView.visibility = View.VISIBLE
                        textView.setOnClickListener {
                            // Save contact to this slot
                            contactToSave?.let { contact ->
                                saveSpeedDial(index + 1, contact)
                                isPickingSlot = false
                                contactToSave = null
                                // Clear the number input to show saved speed dial contacts
                                numberEdit?.text = ""
                                updateSpeedDials()
                                onSoundPlay(R.raw.click)
                            }
                        }
                        textView.setOnLongClickListener(null)
                    }
                }
                numberEdit?.text.toString().isNotEmpty() -> {
                    // Search mode: show search results
                    val query = numberEdit?.text.toString()
                    currentContacts = searchContacts(query)

                    speedDialViews.forEachIndexed { index, textView ->
                        if (index < currentContacts.size) {
                            val contact = currentContacts[index]
                            textView.text = contact.name
                            textView.visibility = View.VISIBLE

                            // Set click listener to call this contact
                            textView.setOnClickListener {
                                callContact(contact.phoneNumber)
                            }

                            // Set long click listener to show context menu
                            textView.setOnLongClickListener { view ->
                                val location = IntArray(2)
                                view.getLocationOnScreen(location)
                                showContactContextMenu(contact, location[0].toFloat(), location[1].toFloat())
                                true
                            }
                        } else {
                            textView.text = ""
                            textView.visibility = View.INVISIBLE
                            textView.setOnClickListener(null)
                            textView.setOnLongClickListener(null)
                        }
                    }
                }
                else -> {
                    // No search query: show saved speed dial contacts
                    speedDialViews.forEachIndexed { index, textView ->
                        val savedContact = getSpeedDial(index + 1)
                        if (savedContact != null) {
                            textView.text = savedContact.name
                            textView.visibility = View.VISIBLE

                            // Set click listener to call this contact
                            textView.setOnClickListener {
                                callContact(savedContact.phoneNumber)
                            }

                            // Set long click listener to show context menu
                            textView.setOnLongClickListener { view ->
                                val location = IntArray(2)
                                view.getLocationOnScreen(location)
                                showContactContextMenu(savedContact, location[0].toFloat(), location[1].toFloat())
                                true
                            }
                        } else {
                            textView.text = ""
                            textView.visibility = View.INVISIBLE
                            textView.setOnClickListener(null)
                            textView.setOnLongClickListener(null)
                        }
                    }
                }
            }
        }

        // Function to add digit to number
        fun addDigit(digit: String) {
            val currentNumber = numberEdit?.text.toString()
            numberEdit?.text = currentNumber + digit

            // Play appropriate sound based on digit
            val soundResource = when (digit) {
                "1" -> R.raw.num_1
                "2" -> R.raw.num_2
                "3" -> R.raw.num_3
                "4" -> R.raw.num_4
                "5" -> R.raw.num_5
                "6" -> R.raw.num_6
                "7" -> R.raw.num_7
                "8" -> R.raw.num_8
                "9" -> R.raw.num_9
                else -> R.raw.num_other // For 0 and #
            }
            onSoundPlay(soundResource)

            updateSpeedDials()
        }

        // Set up number button click listeners
        button1.setOnClickListener { addDigit("1") }
        button2.setOnClickListener { addDigit("2") }
        button3.setOnClickListener { addDigit("3") }
        button4.setOnClickListener { addDigit("4") }
        button5.setOnClickListener { addDigit("5") }
        button6.setOnClickListener { addDigit("6") }
        button7.setOnClickListener { addDigit("7") }
        button8.setOnClickListener { addDigit("8") }
        button9.setOnClickListener { addDigit("9") }
        button0.setOnClickListener { addDigit("0") }
        buttonHash.setOnClickListener { addDigit("#") }

        // Set up delete button - tap to remove last digit, long press to clear all
        buttonDelete.setOnClickListener {
            val currentNumber = numberEdit?.text.toString()
            if (currentNumber.isNotEmpty()) {
                numberEdit?.text = currentNumber.dropLast(1)
                onSoundPlay(R.raw.click)
                updateSpeedDials()
            }
        }

        buttonDelete.setOnLongClickListener {
            numberEdit?.text = ""
            onSoundPlay(R.raw.click)
            updateSpeedDials()
            true
        }

        // Initialize speed dial list on open
        updateSpeedDials()

        // Set up dial button to initiate phone calls
        buttonDial.setOnClickListener {
            val phoneNumber = numberEdit?.text.toString()
            if (phoneNumber.isNotEmpty()) {
                callContact(phoneNumber)
            }
        }

        return contentView
    }

    /**
     * Call a contact
     */
    private fun callContact(phoneNumber: String) {
        onSoundPlay(R.raw.click)

        // Check if we have CALL_PHONE permission
        if (context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                val intent = Intent(Intent.ACTION_CALL)
                intent.data = Uri.parse("tel:$phoneNumber")
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e("DialerApp", "Error initiating call", e)
                // Fallback to ACTION_DIAL which doesn't require permission
                try {
                    val intent = Intent(Intent.ACTION_DIAL)
                    intent.data = Uri.parse("tel:$phoneNumber")
                    context.startActivity(intent)
                } catch (e2: Exception) {
                    Log.e("DialerApp", "Error opening dialer", e2)
                }
            }
        } else {
            // Use ACTION_DIAL as fallback which opens the dialer without making the call
            try {
                val intent = Intent(Intent.ACTION_DIAL)
                intent.data = Uri.parse("tel:$phoneNumber")
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e("DialerApp", "Error opening dialer", e)
            }
        }
    }

    /**
     * Send SMS to a contact
     */
    private fun sendMessage(phoneNumber: String) {
        onSoundPlay(R.raw.click)
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse("sms:$phoneNumber")
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("DialerApp", "Error opening SMS app", e)
        }
    }

    /**
     * Open contact in contacts app
     */
    private fun openContactProperties(phoneNumber: String) {
        onSoundPlay(R.raw.click)
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse("tel:$phoneNumber")
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("DialerApp", "Error opening contacts app", e)
        }
    }

    /**
     * Show context menu for a contact
     */
    private fun showContactContextMenu(contact: ContactInfo, x: Float, y: Float) {
        onSoundPlay(R.raw.click)

        // Check if contact is already in speed dial
        val existingSlot = getSpeedDialSlot(contact)

        // Build menu items
        val menuItems = mutableListOf(
            ContextMenuItem("Call", isEnabled = true, action = {
                callContact(contact.phoneNumber)
            }),
            ContextMenuItem("Send Message", isEnabled = true, action = {
                sendMessage(contact.phoneNumber)
            }),
            ContextMenuItem("", isEnabled = false) // Divider
        )

        if (existingSlot != null) {
            menuItems.add(ContextMenuItem("Remove from Speed Dial", isEnabled = true, action = {
                removeSpeedDial(existingSlot)
                updateSpeedDials()
            }))
        } else {
            menuItems.add(ContextMenuItem("Save to Speed Dial", isEnabled = true, action = {
                // Enter slot picking mode
                isPickingSlot = true
                contactToSave = contact
                updateSpeedDials()
            }))
        }

        menuItems.add(ContextMenuItem("", isEnabled = false)) // Divider
        menuItems.add(ContextMenuItem("Properties", isEnabled = true, action = {
            openContactProperties(contact.phoneNumber)
        }))

        // Show the context menu
        onShowContextMenu(menuItems, x, y)
    }

    /**
     * Speed dial storage functions
     */
    private fun saveSpeedDial(slot: Int, contact: ContactInfo) {
        prefs.edit {
            putString("speed_dial_${slot}_name", contact.name)
            putString("speed_dial_${slot}_number", contact.phoneNumber)
        }
    }

    private fun getSpeedDial(slot: Int): ContactInfo? {
        val name = prefs.getString("speed_dial_${slot}_name", null)
        val number = prefs.getString("speed_dial_${slot}_number", null)
        return if (name != null && number != null) {
            ContactInfo(name, number)
        } else {
            null
        }
    }

    private fun removeSpeedDial(slot: Int) {
        prefs.edit {
            remove("speed_dial_${slot}_name")
            remove("speed_dial_${slot}_number")
        }
    }

    private fun getSpeedDialSlot(contact: ContactInfo): Int? {
        for (slot in 1..SPEED_DIAL_COUNT) {
            val saved = getSpeedDial(slot)
            if (saved != null && saved.name == contact.name && saved.phoneNumber == contact.phoneNumber) {
                return slot
            }
        }
        return null
    }

    /**
     * T9 keypad mapping
     */
    private fun getT9Mapping(digit: Char): String {
        return when (digit) {
            '2' -> "abc"
            '3' -> "def"
            '4' -> "ghi"
            '5' -> "jkl"
            '6' -> "mno"
            '7' -> "pqrs"
            '8' -> "tuv"
            '9' -> "wxyz"
            '0' -> " "
            else -> ""
        }
    }

    /**
     * Check if a name matches the T9 pattern
     */
    private fun matchesT9Pattern(name: String, pattern: String): Boolean {
        if (pattern.isEmpty()) return true
        if (name.isEmpty()) return false

        val nameLower = name.lowercase()

        // Try to match from the beginning of the name
        if (matchesT9FromPosition(nameLower, pattern, 0)) {
            return true
        }

        // Also try to match from the beginning of each word (after spaces)
        for (i in nameLower.indices) {
            if (nameLower[i] == ' ' && i + 1 < nameLower.length) {
                if (matchesT9FromPosition(nameLower, pattern, i + 1)) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Helper function to check if pattern matches starting from a specific position
     */
    private fun matchesT9FromPosition(name: String, pattern: String, startPos: Int): Boolean {
        if (startPos + pattern.length > name.length) return false

        for (i in pattern.indices) {
            val digit = pattern[i]
            val validChars = getT9Mapping(digit)

            if (validChars.isEmpty()) continue

            val nameChar = name[startPos + i]
            if (!validChars.contains(nameChar)) {
                return false
            }
        }

        return true
    }

    /**
     * Search contacts by number and T9 name pattern
     */
    private fun searchContacts(query: String): List<ContactInfo> {
        val contacts = mutableListOf<ContactInfo>()

        // Check if we have READ_CONTACTS permission
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return contacts
        }

        try {
            val cursor = context.contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )

            cursor?.use {
                val nameIndex = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (it.moveToNext()) {
                    val name = it.getString(nameIndex) ?: continue
                    val number = it.getString(numberIndex) ?: continue

                    // Clean the phone number (remove spaces, dashes, etc.)
                    val cleanNumber = number.replace(Regex("[^0-9+]"), "")

                    // Match by phone number (contains query)
                    var numberMatches = cleanNumber.contains(query)

                    // If query starts with 0, also search without the leading 0
                    // This handles local dialing (e.g., 071545369 matches +38971545369)
                    if (!numberMatches && query.startsWith("0") && query.length > 1) {
                        val queryWithoutZero = query.substring(1)
                        numberMatches = cleanNumber.contains(queryWithoutZero)
                    }

                    if (numberMatches) {
                        contacts.add(ContactInfo(name, cleanNumber))
                    }
                    // Match by T9 name pattern
                    else if (matchesT9Pattern(name, query)) {
                        contacts.add(ContactInfo(name, cleanNumber))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DialerApp", "Error searching contacts", e)
        }

        // Remove duplicates by name and return top 8 results
        return contacts.distinctBy { it.name }.take(SPEED_DIAL_COUNT)
    }

    /**
     * Cleanup when app is closed
     */
    fun cleanup() {
        // Nothing to clean up for now
    }
}
