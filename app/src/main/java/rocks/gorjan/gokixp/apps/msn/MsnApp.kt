package rocks.gorjan.gokixp.apps.msn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import rocks.gorjan.gokixp.MainActivity
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.theme.AppTheme
import rocks.gorjan.gokixp.theme.ThemeManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MSN Messenger app logic and UI controller - SMS messaging interface
 */
class MsnApp(
    private val context: Context,
    private val onSoundPlay: () -> Unit,
    private val onCloseWindow: () -> Unit,
    private val onMoveWindow: (offsetY: Int) -> Unit,
    private val onShakeWindow: () -> Unit
) {
    companion object {
        private const val TAG = "MsnApp"
    }

    // UI references
    private var recipientText: TextView? = null
    private var messageThreadTable: TableLayout? = null
    private var composeMessageEdit: EditText? = null
    private var sendButton: View? = null
    private var nudgeButton: View? = null
    private var recentThreadsTable: TableLayout? = null
    private var recentThreadsScrollView: ScrollView? = null
    private var rootView: View? = null
    private var toggleThreadsButton: TextView? = null
    private var messageThreadScrollView: ScrollView? = null
    private var backgroundImage: ImageView? = null

    // Current state
    private var currentThreadAddress: String? = null
    private val smsThreads = mutableListOf<SmsThread>()
    private val currentMessages = mutableListOf<SmsMessage>()
    private var selectedThread: SmsThread? = null

    // Lazy loading state
    private var threadsLoaded = 0
    private var isLoadingThreads = false
    private var allThreadsLoaded = false
    private val THREADS_PER_PAGE = 20

    // Keyboard state
    private var originalWindowY = 0
    private var isKeyboardShown = false

    // SMS receiver
    private var smsReceiver: BroadcastReceiver? = null

    // Nudge tracking
    private val playedNudgeMessages = mutableSetOf<Long>() // Track message timestamps that have been nudged
    private var lastReceivedNudgeTime: Long = 0 // Track when we last received a nudge via broadcast

    // Toggle state
    private var isThreadsVisible = true

    // SharedPreferences for tracking read status
    private val prefs by lazy {
        context.getSharedPreferences("msn_thread_prefs", Context.MODE_PRIVATE)
    }

    /**
     * Initialize the app UI
     */
    fun setupApp(contentView: View): View {
        // Check permissions first
        if (!hasRequiredPermissions()) {
            // Close the window if permissions not granted
            onCloseWindow()
            return contentView
        }

        rootView = contentView

        // Get references to views
        recipientText = contentView.findViewById(R.id.msn_recipient)
        messageThreadTable = contentView.findViewById(R.id.msn_message_thread)
        composeMessageEdit = contentView.findViewById(R.id.msn_compose_message)
        sendButton = contentView.findViewById(R.id.msn_send_button)
        nudgeButton = contentView.findViewById(R.id.msn_nudge_button)
        recentThreadsTable = contentView.findViewById(R.id.msn_recent_message_threads)
        toggleThreadsButton = contentView.findViewById(R.id.msn_toggle_threads)
        messageThreadScrollView = contentView.findViewById(R.id.msn_message_thread_wrapper)
        backgroundImage = contentView.findViewById(R.id.msn_background)

        // Find the ScrollView parent
        recentThreadsScrollView = recentThreadsTable?.parent as? ScrollView

        // Load initial batch of SMS threads
        loadMoreThreads()

        // Set up scroll listener for lazy loading
        recentThreadsScrollView?.setOnScrollChangeListener { v, scrollX, scrollY, oldScrollX, oldScrollY ->
            val scrollView = v as ScrollView
            val child = scrollView.getChildAt(0)

            if (child != null) {
                val diff = (child.bottom - (scrollView.height + scrollView.scrollY))

                // If scrolled near bottom (within 50px), load more
                if (diff < 50 && !isLoadingThreads && !allThreadsLoaded) {
                    loadMoreThreads()
                }
            }
        }

        // Set up send button click listener
        sendButton?.setOnClickListener {
            sendMessage()
        }

        // Set up nudge button click listener
        nudgeButton?.setOnClickListener {
            composeMessageEdit?.setText("/nudge")
        }

        // Set up IME action listener for send on enter
        composeMessageEdit?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else {
                false
            }
        }

        // Set up toggle threads button
        toggleThreadsButton?.setOnClickListener {
            onSoundPlay()
            toggleThreadsVisibility()
        }

        // Set up keyboard detection
        setupKeyboardListener(contentView)

        // Set up SMS receiver
        setupSmsReceiver()

        return contentView
    }

    /**
     * Toggle threads list visibility and adjust layout
     */
    private fun toggleThreadsVisibility() {
        isThreadsVisible = !isThreadsVisible

        if (isThreadsVisible) {
            // Show threads
            recentThreadsScrollView?.visibility = View.VISIBLE
            backgroundImage?.setImageResource(R.drawable.msn_screen)
            toggleThreadsButton?.text = ">"

            // Restore original widths and margins
            messageThreadScrollView?.layoutParams?.width = 231.dpToPx()
            messageThreadTable?.layoutParams?.width = 213.dpToPx()
            recipientText?.layoutParams?.width = 200.dpToPx()
            composeMessageEdit?.layoutParams?.width = 188.dpToPx()

            // Restore original margins for buttons
            (nudgeButton?.layoutParams as? android.widget.RelativeLayout.LayoutParams)?.let {
                it.leftMargin = 200.dpToPx()
                nudgeButton?.layoutParams = it
            }
            (sendButton?.layoutParams as? android.widget.RelativeLayout.LayoutParams)?.let {
                it.leftMargin = 200.dpToPx()
                sendButton?.layoutParams = it
            }
        } else {
            // Hide threads
            recentThreadsScrollView?.visibility = View.GONE
            backgroundImage?.setImageResource(R.drawable.msn_screen_full)
            toggleThreadsButton?.text = "<"

            // Expand widths by 142dp
            messageThreadScrollView?.layoutParams?.width = (231 + 142).dpToPx()
            messageThreadTable?.layoutParams?.width = (213 + 142).dpToPx()
            recipientText?.layoutParams?.width = (200 + 142).dpToPx()
            composeMessageEdit?.layoutParams?.width = (188 + 142).dpToPx()

            // Increase margins by 142dp
            (nudgeButton?.layoutParams as? android.widget.RelativeLayout.LayoutParams)?.let {
                it.leftMargin = (200 + 142).dpToPx()
                nudgeButton?.layoutParams = it
            }
            (sendButton?.layoutParams as? android.widget.RelativeLayout.LayoutParams)?.let {
                it.leftMargin = (200 + 142).dpToPx()
                sendButton?.layoutParams = it
            }
        }

        // Request layout update
        rootView?.requestLayout()
    }

    /**
     * Setup keyboard visibility listener to move window up/down
     */
    private fun setupKeyboardListener(view: View) {
        view.viewTreeObserver.addOnGlobalLayoutListener {
            val rect = android.graphics.Rect()
            view.getWindowVisibleDisplayFrame(rect)
            val screenHeight = view.rootView.height
            val keypadHeight = screenHeight - rect.bottom

            // If keyboard height is more than 200px, consider it visible
            if (keypadHeight > 200) {
                // Keyboard is shown
                if (!isKeyboardShown) {
                    isKeyboardShown = true

                    // Calculate how much to move the window up
                    // We want to move it just enough so the compose field is visible above the keyboard
                    // Get the compose field's position on screen
                    val composeFieldLocation = IntArray(2)
                    composeMessageEdit?.getLocationOnScreen(composeFieldLocation)
                    val composeFieldBottom = composeFieldLocation[1] + (composeMessageEdit?.height ?: 0)

                    // Calculate the visible area bottom (where keyboard starts)
                    val visibleBottom = rect.bottom

                    // Only move if the compose field would be covered by the keyboard
                    val overlap = composeFieldBottom - visibleBottom
                    if (overlap > 0) {
                        // Move window up by the overlap plus some padding
                        onMoveWindow(-(overlap + 50))
                    }
                }
            } else {
                // Keyboard is hidden
                if (isKeyboardShown) {
                    isKeyboardShown = false
                    // Move window back to original position
                    onMoveWindow(0)
                }
            }
        }
    }

    /**
     * Setup SMS receiver to listen for incoming messages
     */
    private fun setupSmsReceiver() {
        smsReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
                    // Extract message body to check if it's a nudge
                    val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                    val messageBody = messages?.firstOrNull()?.messageBody

                    // Play appropriate sound (nudge or regular receive)
                    if (messageBody == "/nudge") {
                        playNudge()

                        // Mark when we received this nudge to prevent double-playing
                        lastReceivedNudgeTime = System.currentTimeMillis()
                    } else {
                        playSound(R.raw.msn_recieve_message)
                    }

                    // Delay to allow SMS to be written to database
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        handleIncomingSms()
                    }, 500)
                }
            }
        }

        val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        context.registerReceiver(smsReceiver, filter)
    }

    /**
     * Handle incoming SMS message
     */
    private fun handleIncomingSms() {
        // Save current thread address before reloading
        val currentAddress = currentThreadAddress

        // Reload threads to get the latest message
        resetAndReloadThreads {
            // After threads are reloaded, reload the current thread if one was open
            if (currentAddress != null) {
                val thread = smsThreads.find { it.address == currentAddress }
                if (thread != null) {
                    loadThread(thread)
                }
            }
        }
    }

    /**
     * Play a sound effect
     */
    private fun playSound(soundResId: Int) {
        try {
            val mediaPlayer = MediaPlayer.create(context, soundResId)
            mediaPlayer?.setOnCompletionListener { mp ->
                mp.release()
            }
            mediaPlayer?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing sound", e)
        }
    }

    /**
     * Play nudge sound and shake window
     */
    private fun playNudge() {
        playSound(R.raw.msn_nudge)
        onShakeWindow()
    }

    /**
     * Check if required permissions are granted
     */
    private fun hasRequiredPermissions(): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(android.Manifest.permission.SEND_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * Load more SMS threads with pagination
     */
    private fun loadMoreThreads(onComplete: (() -> Unit)? = null) {
        if (isLoadingThreads || allThreadsLoaded) {
            onComplete?.invoke()
            return
        }

        isLoadingThreads = true

        try {
            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE
                ),
                null,
                null,
                "${Telephony.Sms.DATE} DESC"
            )

            cursor?.use {
                val addressIndex = it.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIndex = it.getColumnIndex(Telephony.Sms.BODY)
                val dateIndex = it.getColumnIndex(Telephony.Sms.DATE)

                // Get all unique addresses first (we need this to properly paginate)
                val allAddresses = mutableListOf<String>()
                val seenAddresses = mutableSetOf<String>()
                val addressToMessageMap = mutableMapOf<String, Pair<String, Long>>()

                while (it.moveToNext()) {
                    val address = it.getString(addressIndex) ?: continue
                    if (!seenAddresses.contains(address)) {
                        seenAddresses.add(address)
                        allAddresses.add(address)
                        val body = it.getString(bodyIndex) ?: ""
                        val date = it.getLong(dateIndex)
                        addressToMessageMap[address] = Pair(body, date)
                    }
                }

                // Now paginate: get only the next THREADS_PER_PAGE items
                val startIndex = threadsLoaded
                val endIndex = minOf(startIndex + THREADS_PER_PAGE, allAddresses.size)

                if (startIndex >= allAddresses.size) {
                    allThreadsLoaded = true
                    isLoadingThreads = false
                    onComplete?.invoke()
                    return
                }

                val newThreads = mutableListOf<SmsThread>()
                for (i in startIndex until endIndex) {
                    val address = allAddresses[i]
                    val (body, date) = addressToMessageMap[address] ?: continue
                    val contactName = getContactName(address)

                    newThreads.add(
                        SmsThread(
                            address = address,
                            contactName = contactName,
                            lastMessage = body,
                            timestamp = date
                        )
                    )
                }

                smsThreads.addAll(newThreads)
                threadsLoaded = endIndex

                if (endIndex >= allAddresses.size) {
                    allThreadsLoaded = true
                }

                // Display the new threads
                displayNewThreads(newThreads)

                // Auto-open the first thread on initial load with a delay
                if (startIndex == 0 && newThreads.isNotEmpty() && selectedThread == null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        loadThread(newThreads.first())
                    }, 500)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading SMS threads", e)
        } finally {
            isLoadingThreads = false
            onComplete?.invoke()
        }
    }

    /**
     * Display only new threads (append to existing list)
     */
    private fun displayNewThreads(threads: List<SmsThread>) {
        threads.forEach { thread ->
            addThreadToList(thread)
        }
    }

    /**
     * Reload and display all threads from scratch
     */
    private fun reloadAllThreads() {
        recentThreadsTable?.removeAllViews()
        smsThreads.forEach { thread ->
            addThreadToList(thread)
        }
    }

    /**
     * Add a single thread to the list
     */
    private fun addThreadToList(thread: SmsThread) {
        val tableRow = TableRow(context).apply {
            layoutParams = TableLayout.LayoutParams(
                TableLayout.LayoutParams.MATCH_PARENT,
                TableLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Inflate the thread item layout
        val threadItem = LayoutInflater.from(context).inflate(
            R.layout.program_msn_thread_item,
            tableRow,
            false
        )

        val threadIcon = threadItem.findViewById<ImageView>(R.id.thread_icon)
        val threadName = threadItem.findViewById<TextView>(R.id.thread_name)
        val notificationDot = threadItem.findViewById<View>(R.id.notification_dot)

        // Set the contact name
        val displayName = if (thread.contactName != null) {
            thread.contactName
        } else {
            thread.address
        }
        threadName.text = displayName

        // Show or hide notification dot based on unread status
        notificationDot?.visibility = if (hasUnreadMessages(thread)) View.VISIBLE else View.GONE

        // Apply background styling based on selection
        val isSelected = selectedThread?.address == thread.address
        if (isSelected) {
            threadItem.alpha = 1f
            threadItem.setBackgroundColor(0xFFdae7f7.toInt())

        } else {
            threadItem.alpha = 0.6f
            threadItem.setBackgroundColor(0xFFFFFFFF.toInt())
        }

        // Set click listener to load this thread
        threadItem.setOnClickListener {
            onSoundPlay()
            loadThread(thread)
        }

        tableRow.addView(threadItem)
        recentThreadsTable?.addView(tableRow)
    }

    /**
     * Load a specific thread's messages
     */
    private fun loadThread(thread: SmsThread) {
        currentThreadAddress = thread.address
        currentMessages.clear()

        // Mark thread as read
        markThreadAsRead(thread.address)

        // Update recipient field
        val displayText = if (thread.contactName != null) {
            "${thread.contactName} (${thread.address})"
        } else {
            thread.address
        }
        recipientText?.setText(displayText)

        // Load all messages for this thread
        try {
            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE
                ),
                "${Telephony.Sms.ADDRESS} = ?",
                arrayOf(thread.address),
                "${Telephony.Sms.DATE} ASC"
            )

            cursor?.use {
                val addressIndex = it.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIndex = it.getColumnIndex(Telephony.Sms.BODY)
                val dateIndex = it.getColumnIndex(Telephony.Sms.DATE)
                val typeIndex = it.getColumnIndex(Telephony.Sms.TYPE)

                while (it.moveToNext()) {
                    val address = it.getString(addressIndex) ?: continue
                    val body = it.getString(bodyIndex) ?: ""
                    val date = it.getLong(dateIndex)
                    val type = it.getInt(typeIndex)

                    currentMessages.add(
                        SmsMessage(
                            address = address,
                            body = body,
                            timestamp = date,
                            isSent = type == Telephony.Sms.MESSAGE_TYPE_SENT
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading thread messages", e)
        }

        // Update selected thread
        selectedThread = thread

        // Refresh thread list to update highlighting
        refreshThreadListHighlighting()

        // Display the messages
        displayMessages()

        // Check if last message is an unread nudge and play sound
        checkAndPlayNudge()
    }

    /**
     * Check if the last message in the current thread is an unread nudge and play sound
     */
    private fun checkAndPlayNudge() {
        if (currentMessages.isEmpty()) return

        val lastMessage = currentMessages.last()
        val currentAddress = currentThreadAddress ?: return

        // Check if it's a nudge, not sent by us, and not already played
        if (lastMessage.body == "/nudge" &&
            !lastMessage.isSent &&
            !playedNudgeMessages.contains(lastMessage.timestamp)) {

            // Check if this nudge was just received (within last 2 seconds)
            // If so, skip playing because we already played it in the broadcast receiver
            val timeSinceLastReceived = System.currentTimeMillis() - lastReceivedNudgeTime
            if (timeSinceLastReceived < 2000) {
                // Just mark as played without playing sound
                playedNudgeMessages.add(lastMessage.timestamp)
                return
            }

            // Check if the nudge message is newer than the last time we opened this thread
            val lastReadTime = getLastReadTime(currentAddress)
            if (lastMessage.timestamp <= lastReadTime) {
                // This nudge was already seen before, don't play it
                playedNudgeMessages.add(lastMessage.timestamp)
                return
            }

            // Play nudge sound and shake (this is an unread nudge)
            playNudge()

            // Mark this nudge as played
            playedNudgeMessages.add(lastMessage.timestamp)
        }
    }

    /**
     * Refresh the thread list to update highlighting without reloading data
     */
    private fun refreshThreadListHighlighting() {
        recentThreadsTable?.removeAllViews()
        smsThreads.forEach { thread ->
            addThreadToList(thread)
        }
    }

    /**
     * Display messages in the thread
     */
    private fun displayMessages() {
        messageThreadTable?.removeAllViews()

        currentMessages.forEach { message ->
            // Create a container LinearLayout for proper alignment
            val messageContainer = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                layoutParams = TableLayout.LayoutParams(
                    TableLayout.LayoutParams.MATCH_PARENT,
                    TableLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(2.dpToPx(), 2.dpToPx(), 2.dpToPx(), 2.dpToPx())

                // Set gravity to align content
                gravity = if (message.isSent) {
                    android.view.Gravity.END
                } else {
                    android.view.Gravity.START
                }
            }

            val messageView = TextView(context).apply {
                // Check if it's a nudge and replace text
                text = if (message.body == "/nudge") {
                    "🫨 SENT A NUDGE 🫨"
                } else {
                    message.body
                }
                textSize = 12f
                setPadding(4.dpToPx(), 2.dpToPx(), 4.dpToPx(), 2.dpToPx())

                // Use theme's primary font
                typeface = MainActivity.getInstance()?.getThemePrimaryFont()

                // Set max width to ensure messages don't overflow (TableLayout is 233dp, leave some margin)
                maxWidth = 180.dpToPx()

                // Enable text wrapping
                maxLines = Int.MAX_VALUE

                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    // Add margins for spacing
                    if (message.isSent) {
                        setMargins(40.dpToPx(), 0, 2.dpToPx(), 0)
                    } else {
                        setMargins(2.dpToPx(), 0, 40.dpToPx(), 0)
                    }
                }

                // Different background colors for sent vs received with rounded corners
                val backgroundColor = if (message.isSent) {
                    0xFFDCF8C6.toInt() // Light green for sent
                } else {
                    0xFFEEEEEE.toInt() // Gray for received
                }

                // Create rounded corner background
                val drawable = GradientDrawable().apply {
                    setColor(backgroundColor)
                    cornerRadius = 3.dpToPx().toFloat()
                }

                background = drawable
                setTextColor(0xFF000000.toInt())
            }

            messageContainer.addView(messageView)
            messageThreadTable?.addView(messageContainer)
        }

        // Scroll to bottom to show latest messages
        messageThreadTable?.post {
            val scrollView = messageThreadTable?.parent as? ScrollView
            scrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    /**
     * Send a message
     */
    private fun sendMessage() {
        val messageText = composeMessageEdit?.text?.toString() ?: ""
        if (messageText.isEmpty()) {
            return
        }

        val address = currentThreadAddress
        if (address == null) {
            // Try to parse address from recipient field
            val recipientText = recipientText?.text?.toString() ?: ""
            // Extract phone number from "Name (number)" format
            val numberMatch = Regex("\\(([^)]+)\\)").find(recipientText)
            val extractedAddress = numberMatch?.groupValues?.get(1) ?: recipientText.trim()

            if (extractedAddress.isEmpty()) {
                Log.e(TAG, "No recipient specified")
                return
            }

            currentThreadAddress = extractedAddress
        }

        try {
            // Send SMS
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(currentThreadAddress, null, messageText, null, null)

            // Play appropriate sound (nudge or regular send)
            if (messageText == "/nudge") {
                playNudge()
            } else {
                playSound(R.raw.msn_recieve_message)
            }

            // Add message to current thread
            currentMessages.add(
                SmsMessage(
                    address = currentThreadAddress!!,
                    body = messageText,
                    timestamp = System.currentTimeMillis(),
                    isSent = true
                )
            )

            // Refresh display
            displayMessages()

            // Clear compose field
            composeMessageEdit?.setText("")

            // Reload threads to update the list (reset pagination) with a slight delay
            // to ensure the SMS is written to the database
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                resetAndReloadThreads()
            }, 300)

        } catch (e: Exception) {
            Log.e(TAG, "Error sending SMS", e)
        }
    }

    /**
     * Reset pagination and reload all threads
     */
    private fun resetAndReloadThreads(onComplete: (() -> Unit)? = null) {
        smsThreads.clear()
        threadsLoaded = 0
        allThreadsLoaded = false
        recentThreadsTable?.removeAllViews()
        loadMoreThreads(onComplete)
    }

    /**
     * Get contact name from phone number
     */
    private fun getContactName(phoneNumber: String): String? {
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    return it.getString(nameIndex)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting contact name", e)
        }
        return null
    }

    /**
     * Mark a thread as read by saving the current timestamp
     */
    private fun markThreadAsRead(address: String) {
        prefs.edit().putLong("last_read_$address", System.currentTimeMillis()).apply()
    }

    /**
     * Get the last time a thread was opened/read
     */
    private fun getLastReadTime(address: String): Long {
        return prefs.getLong("last_read_$address", 0L)
    }

    /**
     * Check if a thread has unread received messages
     */
    private fun hasUnreadMessages(thread: SmsThread): Boolean {
        val lastReadTime = getLastReadTime(thread.address)

        // If thread has never been opened (no timestamp saved), consider it as read
        if (lastReadTime == 0L) {
            return false
        }

        // Check if the last message is newer than the last read time and is a received message
        return thread.timestamp > lastReadTime && !isMessageSent(thread.address, thread.timestamp)
    }

    /**
     * Check if a message at a given timestamp was sent by us
     */
    private fun isMessageSent(address: String, timestamp: Long): Boolean {
        try {
            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.TYPE),
                "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.DATE} = ?",
                arrayOf(address, timestamp.toString()),
                null
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val typeIndex = it.getColumnIndex(Telephony.Sms.TYPE)
                    val type = it.getInt(typeIndex)
                    return type == Telephony.Sms.MESSAGE_TYPE_SENT
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking message type", e)
        }
        return false
    }

    /**
     * Save current state when window is minimized
     */
    fun onMinimize() {
        // Nothing to save for MSN
    }

    /**
     * Cleanup when app is closed
     */
    fun cleanup() {
        // Unregister SMS receiver
        try {
            smsReceiver?.let {
                context.unregisterReceiver(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering SMS receiver", e)
        }
    }

    /**
     * Extension function to convert dp to pixels
     */
    private fun Int.dpToPx(): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
}

/**
 * Data class for SMS thread
 */
data class SmsThread(
    val address: String,
    val contactName: String?,
    val lastMessage: String,
    val timestamp: Long
)

/**
 * Data class for SMS message
 */
data class SmsMessage(
    val address: String,
    val body: String,
    val timestamp: Long,
    val isSent: Boolean
)
