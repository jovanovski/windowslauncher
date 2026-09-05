package rocks.gorjan.gokixp.apps.people

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.ContactFeed
import rocks.gorjan.gokixp.wp81.Haptics
import rocks.gorjan.gokixp.wp81.MessageStore
import rocks.gorjan.gokixp.wp81.MetroAppBar
import rocks.gorjan.gokixp.wp81.MetroPageHeader
import rocks.gorjan.gokixp.wp81.MetroPanorama
import rocks.gorjan.gokixp.wp81.PeopleStore
import rocks.gorjan.gokixp.wp81.PhoneHistory
import rocks.gorjan.gokixp.wp81.TiltEffect
import rocks.gorjan.gokixp.wp81.WP81ContextMenu
import rocks.gorjan.gokixp.wp81.WP81InputDialog
import rocks.gorjan.gokixp.wp81.WP81Palette
import rocks.gorjan.gokixp.wp81.applyToField
import rocks.gorjan.gokixp.wp81.applyToPageText

/**
 * People, as Windows Phone 8.1 had it - and the Phone app it shared a job with.
 *
 * On the phone these were two programs that kept looking at each other: the People hub
 * held everybody and the Phone app held the history, and both of them listed the same
 * contacts because the thing you do with a person is ring them. Here they are one
 * panorama, which is what the two of them always were - favourites, history, all - and
 * the same surface Music and News are laid out on.
 *
 * Everything the app knows comes from the phone's own address book and call log through
 * [PeopleStore] and [PhoneHistory], and everything it changes is written back there: a
 * contact created here is a contact, in an account, that every other app on the phone can
 * see. That is the point of it. A launcher that kept its own private list of people would
 * have made an address book that goes away when it does.
 *
 * The People tile opens this rather than the phone's dialler, which is the other half of
 * the same idea: the tile is a wall of the faces in *this* book, and tapping it should
 * land inside the app that wall belongs to.
 */
class PeopleApp(
    private val context: Context,
    private val palette: WP81Palette,
    /**
     * Asks the host for permissions. Contacts, the call log and calling are asked for
     * where they are first needed rather than in a heap on first run - an address book is
     * not something to demand from somebody who has opened the app to look at it.
     */
    private val onRequestPermissions: (Array<String>) -> Unit,
    /** The gallery, for a contact's picture. Its result comes back to [onPhotoPicked]. */
    private val photoPicker: ActivityResultLauncher<String>,
    /**
     * Asks the system to make this the phone.
     *
     * The host does the asking because the answer arrives as an activity result, and this
     * is not an activity. See [isThePhone] for what holding it buys.
     */
    private val onBecomeDialer: () -> Unit,
    /**
     * Asks the system to make this the messaging app. The same arrangement, and the same
     * reason the host does the asking: see [onBecomeDialer].
     */
    private val onBecomeMessenger: () -> Unit,
    private val onNotify: (String, String) -> Unit
) {

    private lateinit var root: FrameLayout
    private lateinit var panorama: MetroPanorama
    private lateinit var appBar: MetroAppBar
    private lateinit var contextMenu: WP81ContextMenu
    private lateinit var dialog: WP81InputDialog

    /** The three sections that are columns of rows built by hand. */
    private lateinit var favouritesColumn: LinearLayout
    private lateinit var historyColumn: LinearLayout
    private lateinit var historyScroll: GrowingScroll
    private lateinit var messagesColumn: LinearLayout
    private lateinit var messagesScroll: GrowingScroll

    /**
     * Everybody, on the app list's own page.
     *
     * A [ContactList] rather than a column of rows like the other two sections: this is the
     * one that can run to four thousand people, and it is the one the phone gave letter
     * squares, a jump grid and a search band. All of which the app list already has - see
     * [rocks.gorjan.gokixp.wp81.MetroIndexList], which the two of them share.
     */
    private lateinit var allList: ContactList

    /**
     * The calls the history holds but has not built rows for yet.
     *
     * Nobody scrolls to the end of a call log before deciding whether to, so the section
     * builds a screenful and the next as the reader reaches the bottom of the last. The
     * contact list needs no such thing: a RecyclerView recycles, which is the same idea
     * done properly and is why that section is one.
     */
    private val pendingCalls = mutableListOf<PhoneHistory.Entry>()

    /** The same, for conversations. A phone that texts has as many of these as calls. */
    private val pendingThreads = mutableListOf<MessageStore.Conversation>()

    private var contacts: List<PeopleStore.Contact> = emptyList()
    private var history: List<PhoneHistory.Entry> = emptyList()
    private var conversations: List<MessageStore.Conversation> = emptyList()

    /** Whether the last read of the log threw rather than coming back empty. */
    private var historyFailed = false

    /** The same question about the message store. */
    private var messagesFailed = false

    /**
     * What tells this app a message has arrived.
     *
     * Held only while the app is on screen - see [createView] - because the store is
     * written to by whichever app on the phone delivers messages, and a launcher that
     * re-read it every time that happened while nobody was looking would be doing the
     * work of a page that is not open.
     */
    private var messagesWatch: android.database.ContentObserver? = null

    /** The conversation that is open, if one is. It re-reads when the app does. */
    private var openThread: MessageThread? = null

    /** The one history row that is open, if any. See [toggleHistoryActions]. */
    private var openHistoryActions: OpenRow? = null

    /**
     * Which day the calls built into the history so far belong to.
     *
     * Held across the chunks the list is built in rather than worked out from them: the
     * rows arrive a screenful at a time and a heading has to be written when the day
     * changes *between* two of them, which the chunk that starts the new day cannot see on
     * its own. [NO_DAY] until the first row, so that day gets a heading like every other.
     */
    private var lastHistoryDay = NO_DAY

    /**
     * Pages stacked over the panorama, newest last.
     *
     * Tracked rather than counted off the root, for the reason Zune tracks its own: the
     * panorama, the strip, the grid and the prompt all live in the same parent, and "the
     * last child" is only sometimes a page somebody navigated into.
     */
    private val overlays = mutableListOf<View>()

    /** The editor waiting for a picture, while the gallery is open over the app. */
    private var awaitingPhoto: Editor? = null

    /** People a directory answered with, shown under the book while a search is on. */
    private var directoryHits: List<PeopleStore.Contact> = emptyList()

    /** The directory search waiting for the typing to settle. See [searchDirectory]. */
    private var directorySearch: Runnable? = null


    /** Whether this session has already asked to be allowed to place calls. See [place]. */
    private var askedToCall = false

    /**
     * The two things this app remembers that the phone's address book does not: how the
     * favourites are arranged, and which account new contacts go to.
     *
     * Neither is a fact about a contact, which is why neither can be written where the
     * contacts are. Being starred is in the book; being third on the wall is a fact about
     * this wall. And the account is a fact about the person doing the saving.
     */
    private val prefs = context.getSharedPreferences(
        rocks.gorjan.gokixp.MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- construction

    fun createView(): View {
        root = FrameLayout(context).apply { setBackgroundColor(palette.background) }

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        panorama = MetroPanorama(context, palette).apply {
            setPadding(dp(PAGE_MARGIN_DP), 0, 0, 0)
            clipToPadding = false
            clipChildren = false
        }
        // The wordmark belongs to the panorama rather than sitting above it, so it drifts
        // as the sections are pulled past underneath. Lowercase, as everything on this
        // platform is.
        panorama.setTitle("people")

        favouritesColumn = sectionColumn()
        historyColumn = sectionColumn()
        messagesColumn = sectionColumn()

        panorama.addPage("favourites", page(favouritesColumn) {})
        historyScroll = page(historyColumn) { extendHistory() }
        panorama.addPage("history", historyScroll)
        // Between the calls and the book, which is where it belongs: the two pages either
        // side of it are the two things a conversation is a way of starting, and the one
        // before it is the other half of the same question - who has been in touch.
        messagesScroll = page(messagesColumn) { extendMessages() }
        panorama.addPage("messages", messagesScroll)

        allList = ContactList(context, palette).apply {
            onPick = { person ->
                // Somebody found in a directory has no card to open - they are not on this
                // phone - so tapping them offers to put them on it, with what the directory
                // knew already filled in. See PeopleStore.Contact.directory.
                if (person.directory) {
                    showEditor(
                        null,
                        prefillNumber = person.number,
                        prefillName = person.name
                    )
                } else {
                    showProfile(person.id)
                }
            }
            onLongPress = { person, anchor -> showContactMenu(person, anchor) }
            // The keyboard's search key opens whoever is at the top of what is left, which
            // is the person somebody typing three letters was typing towards.
            onSearchSubmit = { _, found ->
                found.firstOrNull()?.let { person ->
                    if (person.directory) {
                        showEditor(null, prefillNumber = person.number, prefillName = person.name)
                    } else {
                        showProfile(person.id)
                    }
                }
            }
            // Every account's directory, asked as the letters arrive. A colleague in a work
            // account's company directory is in none of the queries that read this phone -
            // there is nothing here to read - so a search that only filtered what was
            // already loaded could never find them. See searchDirectories.
            onQueryChanged = { typed -> searchDirectory(typed) }
            // Leaving search puts the directory's people away with it. They were answers
            // to a question that is no longer being asked, and a list of everybody with
            // four colleagues stuck on the end of it is not the address book.
            onSearchChanged = { on -> if (!on) forgetDirectoryHits() }
        }
        panorama.addPage("contacts", allList)

        // Leaving the section puts its search away. The field belongs to that page, and a
        // search still standing on a page nobody is looking at is a filter that will
        // surprise whoever comes back to it.
        panorama.onPageSettled = { index ->
            if (index != PAGE_CONTACTS) allList.endSearch(animated = false)
        }

        column.addView(panorama, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        appBar = buildAppBar()
        root.addView(appBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        // The sections stop above the strip rather than running under it, so the last row
        // of a list is reachable instead of sitting behind the buttons.
        column.setPadding(0, 0, 0, dp(MetroAppBar.HEIGHT_DP))

        // The jump grid measures itself against the whole display, because that is what it
        // covers - so it is put up over the app rather than inside the section, which is
        // only as tall as the panorama leaves it.
        allList.setJumpListHost(root)

        contextMenu = WP81ContextMenu(context, palette)
        root.addView(contextMenu, FrameLayout.LayoutParams(MATCH, MATCH))

        dialog = WP81InputDialog(context, palette)
        root.addView(dialog, FrameLayout.LayoutParams(MATCH, MATCH))

        // The message store, watched while the app is on screen. A text arriving is
        // written down by whichever app on this phone delivers messages, and this is how
        // that reaches the section showing it - there is no broadcast to listen for.
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                messagesWatch = MessageStore.watch(context) { readMessages() }
            }

            override fun onViewDetachedFromWindow(v: View) {
                MessageStore.unwatch(context, messagesWatch)
                messagesWatch = null
            }
        })

        // Favourites first, which is the whole argument for having the section: the people
        // you actually ring are four or five, and every other way of reaching them starts
        // with scrolling past everybody you do not.
        panorama.goTo(PAGE_FAVOURITES, animated = false)

        refresh()
        return root
    }

    /** A section's column: the rows themselves, padded off both edges of the page. */
    private fun sectionColumn() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), dp(PAGE_MARGIN_DP), dp(24))
        // A favourite being carried leaves its slot, and the column's own right-hand
        // margin is the first thing it crosses. Without this it is cut off at the edge of
        // the wall it is being dragged across.
        clipChildren = false
    }

    private fun page(column: LinearLayout, onNearEnd: () -> Unit): GrowingScroll =
        GrowingScroll(onNearEnd).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
        }

    /**
     * A page that says when the reader is getting near the end of what has been built.
     *
     * Measured against the height of the content rather than a row count, so it holds
     * however tall the rows come out - a section of contacts and a section of calls have
     * different rows and both use this.
     */
    private inner class GrowingScroll(
        private val onNearEnd: () -> Unit
    ) : ScrollView(context) {
        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            val content = getChildAt(0) ?: return
            if (content.height - (t + height) < height) onNearEnd()
        }
    }

    /**
     * The strip: the two things this app is for, and the rest behind the dots.
     *
     * A keypad and a new contact are the only commands that belong to the app rather than
     * to somebody in it - everything you can do *to* a person is on their own page or
     * behind a long press, which is where the phone put them.
     */
    private fun buildAppBar(): MetroAppBar {
        val bar = MetroAppBar(context, palette)
        // Search rather than new-contact on the ring, and first: looking somebody up is
        // what this app is opened for, and adding somebody is what it is opened for once
        // in a while. The keypad is second because it is the answer when the search has
        // failed - the book first, the bare number after it.
        bar.addCommand(SEARCH_ICON) {
            // To the page the search is of, and then into it. Searching contacts while
            // standing on the call history would be a field with nothing under it.
            panorama.goTo(PAGE_CONTACTS, animated = true)
            allList.beginSearch()
        }
        bar.addCommand(KEYPAD_ICON) { showDialer() }
        bar.menu = {
            buildList {
                add(MetroAppBar.Item("new contact") { showEditor(null, prefillNumber = null) })
                add(MetroAppBar.Item("refresh") { refresh() })
                if (!isThePhone()) {
                    add(MetroAppBar.Item("default phone app") { onBecomeDialer() })
                } else if (needsBluetoothName()) {
                    // Only while it is missing, and only to somebody who already holds the
                    // phone role - which is the awkward case the request with the role does
                    // not reach: it is asked as the role is taken, and anybody who took it
                    // before this existed was never asked. Rather than a dialog on the way
                    // past, or one over a live call, it is a command that says what it is.
                    add(MetroAppBar.Item("name bluetooth devices") {
                        onRequestPermissions(
                            arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT))
                    })
                }
                if (panorama.currentPage() == PAGE_MESSAGES) {
                    add(MetroAppBar.Item("new message") { showNewMessage() })
                    if (!MessageStore.isTheMessenger(context)) {
                        add(MetroAppBar.Item("default messaging app") { onBecomeMessenger() })
                    }
                }
                if (panorama.currentPage() == PAGE_HISTORY && history.isNotEmpty()) {
                    add(MetroAppBar.Item("delete all") { confirmClearHistory() })
                }
            }
        }
        return bar
    }

    // ---------------------------------------------------------------- reading

    /**
     * Re-reads the book and the log, and fills the sections from them.
     *
     * Called on the way in, after a permission is answered, and after anything is written
     * - a contact saved, starred or deleted. It is two short queries; there is no state
     * worth keeping in sync by hand when re-asking the phone is this cheap.
     */
    fun refresh() {
        // Whatever is open over the app is looking at the same phone, and a permission
        // just granted or a contact just saved is a thing it may now be able to show.
        openThread?.reload()
        if (!PeopleStore.canRead(context)) {
            contacts = emptyList()
            bindContacts()
        } else {
            PeopleStore.all(context) { people ->
                contacts = people
                bindContacts()
            }
        }
        // Each asks the system for itself. The call log and the messages are permissions
        // of their own, and a phone that has said yes to one of the three should not have
        // the other two sections go blank over it.
        readHistory()
        readMessages()
    }

    private fun readHistory() {
        if (!PhoneHistory.hasAccess(context)) {
            history = emptyList()
            historyFailed = false
            bindHistory()
            return
        }
        PhoneHistory.recent(context) { result ->
            history = result.entries
            historyFailed = result.failed
            bindHistory()
        }
    }

    private fun bindContacts() {
        bindFavourites()
        allList.setItems(contacts + directoryHits)
    }

    /** Drops the directory's answers and puts the list back to the book alone. */
    private fun forgetDirectoryHits() {
        directorySearch?.let { root.removeCallbacks(it) }
        directorySearch = null
        if (directoryHits.isEmpty()) return
        directoryHits = emptyList()
        allList.setItems(contacts)
    }

    /**
     * Asks the directories about what is being typed, once the typing has settled.
     *
     * Debounced, because each of these is a network round trip and somebody typing a name
     * would otherwise send one per letter. Short queries are not asked at all - see
     * [PeopleStore.searchDirectories] - and the answer is dropped if the field has moved on
     * since, so a slow directory cannot put stale people under a query they do not match.
     */
    private fun searchDirectory(typed: String) {
        directorySearch?.let { root.removeCallbacks(it) }
        val wanted = typed.trim()
        if (wanted.isEmpty()) {
            if (directoryHits.isNotEmpty()) {
                directoryHits = emptyList()
                allList.setItems(contacts)
            }
            return
        }
        val run = Runnable {
            PeopleStore.searchDirectories(context, wanted) { found ->
                // The field has moved on while the network was thinking.
                if (allList.searchText().trim() != wanted) return@searchDirectories
                // Anybody already in the book is already in the list, and a colleague who
                // is both would otherwise appear twice.
                directoryHits = found.filter { person ->
                    contacts.none { it.name.equals(person.name, ignoreCase = true) }
                }
                allList.setItems(contacts + directoryHits)
            }
        }
        directorySearch = run
        root.postDelayed(run, DIRECTORY_DEBOUNCE_MS)
    }

    // ---------------------------------------------------------------- favourites

    /**
     * The starred people, as a wall of faces rather than another list.
     *
     * The same argument the People tile makes, one level in: these are four or five people
     * and the app already has two sections that are lists. A grid of pictures is quicker
     * to hit and it is what the phone's own group pages looked like - and where somebody
     * has no picture the square is the accent with their initials on it, which is exactly
     * what the tile does with the same person.
     */
    private fun bindFavourites() {
        favouritesColumn.removeAllViews()
        if (!PeopleStore.canRead(context)) {
            favouritesColumn.addView(
                note("no access to your contacts.  tap to allow") { askForContacts() }, wide())
            return
        }
        val favourites = contacts.filter { it.starred }
        if (favourites.isEmpty()) {
            favouritesColumn.addView(
                note("nobody starred yet.  open somebody and tap the star"), wide())
            return
        }
        // In the order they were last arranged into, with anybody starred since put at the
        // end. Alphabetical between people the arrangement says nothing about, so a wall
        // that has never been touched is in an order that has a reason.
        val arrangement = savedArrangement()
        val ordered = favourites.sortedWith(
            compareBy(
                { arrangement.indexOf(keyOf(it)).let { at -> if (at < 0) Int.MAX_VALUE else at } },
                { it.name.lowercase() }
            )
        )
        val grid = FavouritesGrid(
            context,
            FAVOURITE_COLUMNS,
            dp(FAVOURITE_GAP_DP)
        ) { keys -> saveArrangement(keys) }
        for (person in ordered) grid.addTile(favouriteTile(person))
        favouritesColumn.addView(grid, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dp(FAVOURITE_GAP_DP)
        })
    }

    /**
     * What a favourite is filed under in the saved arrangement.
     *
     * The lookup key, which survives the provider re-merging a contact and changing its
     * id - which is exactly what editing one does. An id is the fallback for the rare row
     * that has no key, and it is at least stable until that happens.
     */
    private fun keyOf(person: PeopleStore.Contact): String =
        person.lookupKey ?: person.id.toString()

    private fun savedArrangement(): List<String> =
        prefs.getString(KEY_FAVOURITE_ORDER, "").orEmpty()
            .split(SEPARATOR).filter { it.isNotBlank() }

    private fun saveArrangement(keys: List<String>) {
        prefs.edit().putString(KEY_FAVOURITE_ORDER, keys.joinToString(SEPARATOR)).apply()
    }

    /** One square: their face, their name across the foot of it, the way a tile is set. */
    private fun favouriteTile(person: PeopleStore.Contact): FavouritesGrid.Tile {
        val tile = FavouritesGrid.Tile(context)
        tile.key = keyOf(person)
        tile.isClickable = true
        tile.setOnClickListener { showProfile(person.id) }
        // No long-click listener: the hold on this wall is the grid's, because a hold is
        // where a drag starts. What a hold that goes nowhere does is this, and the grid
        // fires it once it knows the finger stayed put.
        tile.onHeld = { showContactMenu(person, tile) }
        TiltEffect.apply(tile)

        tile.addView(faceView(person, big = true), FrameLayout.LayoutParams(MATCH, MATCH))

        // The name sits on a band rather than straight on the picture: a photograph is
        // whatever colour it happens to be, and white type over the light half of one is
        // not type.
        val name = TextView(context).apply {
            text = person.name
            typeface = font(R.font.segoeui_regular)
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(android.graphics.Color.WHITE)
            includeFontPadding = false
            setPadding(dp(8), dp(6), dp(8), dp(7))
            setBackgroundColor(NAME_BAND)
        }
        tile.addView(name, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        return tile
    }

    /** Somebody's face at the size a page wants it. See [ContactFace]. */
    private fun faceView(person: PeopleStore.Contact, big: Boolean): View =
        ContactFace(context, palette).apply {
            setLetterSize(if (big) 40f else 20f)
            show(person)
        }

    // ---------------------------------------------------------------- history

    /**
     * The call log, folded the way the phone folded it.
     *
     * Tapping a row opens what you can do about it underneath, rather than ringing them
     * back on the spot. A history is a list of people you have already called once, so the
     * odds of a stray tap dialling somebody are high enough that the call is worth putting
     * behind a word - and the other two things you would want, their calls and their card,
     * had nowhere to be until the row could open.
     */
    private fun bindHistory() {
        historyColumn.removeAllViews()
        openHistoryActions = null
        lastHistoryDay = NO_DAY
        if (!PhoneHistory.hasAccess(context)) {
            historyColumn.addView(
                note("no access to your call history.  tap to allow") {
                    onRequestPermissions(PhoneHistory.permissions())
                }, wide())
            return
        }
        // The offer to take the phone over, at the top of the section it is about. Only
        // while somebody else has it: once People is the phone there is nothing to say.
        if (!isThePhone()) {
            historyColumn.addView(
                note("another app is handling calls.  tap to make People your phone") {
                    onBecomeDialer()
                }, wide())
        }
        if (historyFailed) {
            historyColumn.addView(note("the call log could not be read"), wide())
            return
        }
        if (history.isEmpty()) {
            historyColumn.addView(note("no calls yet"), wide())
            return
        }
        pendingCalls.clear()
        pendingCalls.addAll(history)
        extendHistory()
    }

    /** Adds the next screenful of calls, if there are any left. */
    private fun extendHistory() {
        var built = 0
        while (pendingCalls.isNotEmpty() && built < CHUNK) {
            val entry = pendingCalls.removeAt(0)
            // A heading each time the day changes, which - the log being newest first - is
            // the only direction it can change in. The first call of all gets one too: the
            // top of the list is the start of a day as much as any line further down is.
            val day = dayOf(entry.at)
            if (day != lastHistoryDay) {
                val heading = dayHeadingOf(entry.at)
                if (heading.isNotBlank()) {
                    historyColumn.addView(dayHeading(heading, first = lastHistoryDay == NO_DAY), wide())
                }
                lastHistoryDay = day
            }
            historyColumn.addView(historyRow(entry), wide())
            built++
        }
    }

    /**
     * The line that says which day the calls under it happened on.
     *
     * In the accent, which is what the phone put its list headings in and what makes them
     * findable at a scroll - the eye goes down the coloured line and stops at the day it
     * wants. Set smaller and in the lighter face than the names it stands over, because it
     * is still not one of the things in the list; it is the shelf they are on.
     */
    private fun dayHeading(label: String, first: Boolean) = TextView(context).apply {
        text = label
        typeface = font(R.font.segoeui_semilight)
        textSize = 15f
        setTextColor(palette.accent)
        includeFontPadding = false
        // Air above, so a heading belongs to what follows it rather than floating between
        // two days - except at the very top, where there is nothing above to be parted from.
        setPadding(0, dp(if (first) 6 else 22), dp(16), dp(6))
    }

    private fun historyRow(entry: PhoneHistory.Entry): View {
        val holder = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
            isClickable = true
            setOnLongClickListener {
                showHistoryMenu(entry, this)
                true
            }
            TiltEffect.apply(this)
        }
        row.addView(directionArrow(entry), LinearLayout.LayoutParams(dp(ARROW_DP), dp(ARROW_DP))
            .apply { marginEnd = dp(10) })

        val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(context).apply {
            this.text = entry.title + if (entry.count > 1) "  (${entry.count})" else ""
            typeface = font(R.font.segoeui_regular)
            textSize = 19f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(
                if (entry.direction == PhoneHistory.Direction.MISSED) palette.accent
                else palette.foreground
            )
            includeFontPadding = false
        }, wide())
        text.addView(TextView(context).apply {
            // The clock, and how long it lasted. Which day it was is written once over the
            // day's rows instead of on each of them - see [dayHeading]. The number is not
            // repeated here either: it is either the title of the row already, or it is one
            // tap away on their card.
            this.text = listOfNotNull(
                timeOf(context, entry.at).takeIf { it.isNotBlank() },
                lasted(entry.seconds)
            ).joinToString("  ·  ")
            typeface = font(R.font.segoeui_regular)
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.foregroundSubtle)
            setPadding(0, dp(3), 0, 0)
        }, wide())
        // Squared off with the top of the title rather than centred on the whole block,
        // so that the rings beside it line up with the name and not with the gap under it.
        row.addView(text, LinearLayout.LayoutParams(0, WRAP, 1f).apply { gravity = Gravity.TOP })

        // Ringing back and texting, on the name's own line: they are about the call the
        // title names, and set below it they would read as two more of the words in the
        // block underneath rather than as the two commands the row is mostly opened for.
        val rings = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        rings.addView(reachKey(CALL_ICON) { place(entry.number) }, ring(first = true))
        // The other thing you do about a call you have just looked at, and now that
        // messages are a page in this app it goes to the conversation rather than out to
        // somebody else's program. See message().
        rings.addView(reachKey(MESSAGE_ICON) { message(entry.number) }, ring(first = false))
        row.addView(rings, LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.TOP })
        holder.addView(row, wide())

        // Built once and hidden, rather than made on each tap: a row that has been opened
        // and shut is the common case, and rebuilding four buttons every time is work
        // done in the middle of an animation.
        val actions = historyActions(entry)
        actions.visibility = View.GONE
        holder.addView(actions, wide())

        val open = OpenRow(rings, actions)
        row.setOnClickListener {
            Haptics.tap(it)
            toggleHistoryActions(open)
        }
        return holder
    }

    /**
     * What a call's row shows once it has been opened.
     *
     * Two views rather than one, and in different parents: the rings belong on the title's
     * line and the words belong under it, and they still have to come and go together.
     */
    private class OpenRow(private val rings: View, private val words: View) {
        val isOpen: Boolean get() = words.visibility == View.VISIBLE

        fun setOpen(open: Boolean) {
            val how = if (open) View.VISIBLE else View.GONE
            rings.visibility = how
            words.visibility = how
        }
    }

    /**
     * The other two things a call in the list is a way to.
     *
     * Ringing back and texting are rings on the title's line - see [historyRow] - because
     * they are the two the row is usually opened for and they are the two a profile draws
     * that way. What is left has no mark of its own and would not be read as one: the
     * whole of what has passed between you, and their card, or for a number the phone does
     * not know the offer to make one. Those stay words, under the title, indented to it.
     */
    private fun historyActions(entry: PhoneHistory.Entry): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(ARROW_DP + 10), 0, 0, dp(10))
        }
        row.addView(actionWord("call history") { showNumberHistory(entry) })
        val id = entry.contactId
        if (id != null) row.addView(actionWord("view contact") { showProfile(id) })
        else row.addView(actionWord("add contact") {
            showEditor(null, prefillNumber = entry.number)
        })
        return row
    }

    /** How one of those rings stands off what comes before it. See [reachRow], which spaces
     *  its own the same way. */
    private fun ring(first: Boolean) =
        LinearLayout.LayoutParams(dp(REACH_KEY_DP), dp(REACH_KEY_DP)).apply {
            marginStart = dp(if (first) REACH_KEY_INSET_DP else REACH_KEY_GAP_DP)
        }

    private fun actionWord(label: String, onTap: () -> Unit): View = TextView(context).apply {
        text = label
        typeface = font(R.font.segoeui_regular)
        textSize = 14f
        // One line always: these are labels, and a squeezed "view contact" folded in two
        // is a taller row rather than a more readable one.
        maxLines = 1
        setTextColor(palette.accent)
        // Tightened when the row gained a fourth word: four commands and their gaps have
        // to fit across the narrowest page this app is shown on.
        setPadding(0, dp(6), dp(14), dp(6))
        isClickable = true
        setOnClickListener {
            Haptics.tap(it)
            onTap()
        }
        TiltEffect.apply(this)
    }

    /** One open row at a time: two sets of buttons on screen is two rows asking to be read. */
    private fun toggleHistoryActions(row: OpenRow) {
        val already = row.isOpen
        openHistoryActions?.setOpen(false)
        openHistoryActions = if (already) null else row.also { it.setOpen(true) }
    }

    /**
     * Which way a call went, as an arrow.
     *
     * A missed one is the same arrow in the accent: the direction is the same fact about
     * it, and what is different is that it wants answering.
     */
    private fun directionArrow(entry: PhoneHistory.Entry): ImageView = ImageView(context).apply {
        setImageDrawable(
            rocks.gorjan.gokixp.wp81.SvgIcon.fromAsset(
                context,
                if (entry.direction == PhoneHistory.Direction.OUTGOING) OUTGOING_ICON
                else INCOMING_ICON
            )
        )
        scaleType = ImageView.ScaleType.FIT_CENTER
        imageTintList = android.content.res.ColorStateList.valueOf(
            if (entry.direction == PhoneHistory.Direction.MISSED) palette.accent
            else palette.foregroundSubtle
        )
    }

    /**
     * Everything that has passed between this phone and one number.
     *
     * The history page folds a run of calls into one line, which is the right summary and
     * exactly the wrong thing when the question is when you spoke and for how long. So this
     * is the same log unfolded and narrowed to one person - every call, in order, with its
     * own length.
     */
    private fun showNumberHistory(entry: PhoneHistory.Entry) {
        val page = overlayPage()
        val header = MetroPageHeader(context, palette).apply {
            setName(entry.title)
            onBack = { dismissOverlay(page) }
        }
        page.addView(header, wide())

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAGE_MARGIN_DP), 0, dp(PAGE_MARGIN_DP), dp(24))
        }
        page.addView(
            ScrollView(context).apply {
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
            },
            LinearLayout.LayoutParams(MATCH, 0, 1f)
        )
        column.addView(note("reading the call log…"), wide())

        val bar = MetroAppBar(context, palette)
        bar.addCommand(CALL_ICON) { place(entry.number) }
        page.addView(bar, wide())
        pushOverlay(page)

        PhoneHistory.forNumber(context, entry.number) { calls ->
            column.removeAllViews()
            if (calls.isEmpty()) {
                column.addView(note("no calls with this number"), wide())
                return@forNumber
            }
            for (call in calls) column.addView(callRow(call), wide())
        }
    }

    /** One call on one person's page: which way it went, when, and how long it lasted. */
    private fun callRow(entry: PhoneHistory.Entry): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
        }
        row.addView(directionArrow(entry), LinearLayout.LayoutParams(dp(ARROW_DP), dp(ARROW_DP))
            .apply { marginEnd = dp(12) })

        val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(context).apply {
            this.text = when (entry.direction) {
                PhoneHistory.Direction.OUTGOING -> "outgoing"
                PhoneHistory.Direction.INCOMING -> "incoming"
                PhoneHistory.Direction.MISSED -> "missed"
            }
            typeface = font(R.font.segoeui_regular)
            textSize = 16f
            setTextColor(
                if (entry.direction == PhoneHistory.Direction.MISSED) palette.accent
                else palette.foreground
            )
            includeFontPadding = false
        }, wide())
        text.addView(TextView(context).apply {
            this.text = listOfNotNull(
                whenOf(entry.at).takeIf { it.isNotBlank() },
                lasted(entry.seconds)
            ).joinToString("  ·  ")
            typeface = font(R.font.segoeui_regular)
            textSize = 12f
            setTextColor(palette.foregroundSubtle)
            setPadding(0, dp(3), 0, 0)
        }, wide())
        row.addView(text, LinearLayout.LayoutParams(0, WRAP, 1f))
        return row
    }

    /**
     * How long a call lasted, or nothing at all.
     *
     * A missed call has no length, and "0:00" beside it is a measurement of something that
     * never happened. Minutes and seconds up to an hour, then hours - the same shape the
     * timer on the call screen uses, so a call reads the same in the log as it did while
     * it was happening.
     */
    private fun lasted(seconds: Long): String? {
        if (seconds <= 0L) return null
        val minutes = seconds / 60
        return if (minutes >= 60) {
            String.format("%d:%02d:%02d", minutes / 60, minutes % 60, seconds % 60)
        } else {
            String.format("%d:%02d", minutes, seconds % 60)
        }
    }

    /**
     * When a call happened, said the way somebody reading a list of them would.
     *
     * Shared with the messages beside it - see [momentOf] - because a call and a text that
     * arrived in the same minute have to say the same minute in the same words.
     */
    private fun whenOf(at: Long): String = momentOf(context, at)

    private fun confirmClearHistory() {
        ask(
            "delete all",
            "This clears every call from the phone's history, not just the ones shown here.",
            "delete"
        ) {
            PhoneHistory.clear(context) { readHistory() }
        }
    }

    // ---------------------------------------------------------------- messages

    private fun readMessages() {
        if (!MessageStore.canRead(context)) {
            conversations = emptyList()
            messagesFailed = false
            bindMessages()
            return
        }
        MessageStore.conversations(context) { result ->
            conversations = result.conversations
            messagesFailed = result.failed
            bindMessages()
        }
    }

    /**
     * Who has been in touch, most recently first.
     *
     * One row per person rather than per message, which is what a conversation is - and
     * the reason the store's own threads are not used as they stand: see
     * [MessageStore.keyOf]. Tapping a row opens the whole of it, because unlike a call
     * there is nothing a message row can do on its own that is worth putting behind a
     * word - reading it *is* the thing.
     */
    private fun bindMessages() {
        messagesColumn.removeAllViews()
        if (!MessageStore.canRead(context)) {
            messagesColumn.addView(
                note("no access to your messages.  tap to allow") {
                    onRequestPermissions(MessageStore.readPermissions())
                }, wide())
            return
        }
        // The offer to take the role, at the top of the section it is about - the same
        // place and the same words the history page offers the phone one. Only while
        // somebody else holds it: once People is the messaging app there is nothing to say.
        if (!MessageStore.isTheMessenger(context)) {
            messagesColumn.addView(
                note("another app is handling messages.  tap to make People your " +
                    "messaging app") { onBecomeMessenger() }, wide())
        }
        if (messagesFailed) {
            messagesColumn.addView(note("your messages could not be read"), wide())
            return
        }
        if (conversations.isEmpty()) {
            messagesColumn.addView(
                note("no messages yet.  tap ··· to start one") { showNewMessage() }, wide())
            return
        }
        pendingThreads.clear()
        pendingThreads.addAll(conversations)
        extendMessages()
    }

    /** Adds the next screenful of conversations, if there are any left. */
    private fun extendMessages() {
        var built = 0
        while (pendingThreads.isNotEmpty() && built < CHUNK) {
            messagesColumn.addView(threadRow(pendingThreads.removeAt(0)), wide())
            built++
        }
    }

    /**
     * One conversation on the list: who, the last thing said, and when.
     *
     * A face at the head of it like the contact list, because this is a page about people
     * and the number it is really keyed on is the least interesting thing on the row. An
     * unread one is in the accent with the count beside it - the same way the history says
     * a call was missed, which is the same fact about the same person.
     */
    private fun threadRow(thread: MessageStore.Conversation): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
            isClickable = true
            setOnClickListener {
                Haptics.tap(it)
                showThread(thread.address, thread.contact)
            }
            setOnLongClickListener {
                showThreadMenu(thread, this)
                true
            }
            TiltEffect.apply(this)
        }
        row.addView(
            ContactFace(context, palette).apply {
                setLetterSize(15f)
                // The title rather than the contact, so a sender the book has never heard
                // of - a bank, a network - still gets its letters instead of a blank
                // square. A bare number gets neither; see PeopleStore.initialsOf.
                show(thread.title, thread.contact?.photoUri)
            },
            LinearLayout.LayoutParams(dp(THREAD_FACE_DP), dp(THREAD_FACE_DP))
        )

        val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(context).apply {
            this.text = thread.title +
                if (thread.unread > 0) "  (${thread.unread})" else ""
            typeface = font(R.font.segoeui_regular)
            textSize = 19f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(if (thread.unread > 0) palette.accent else palette.foreground)
            includeFontPadding = false
        }, wide())
        text.addView(TextView(context).apply {
            // Said by whom, as well as what: half a conversation is your own half, and a
            // list that showed only the words would keep answering a question nobody asked.
            this.text = if (thread.outgoing) "you:  ${thread.snippet}" else thread.snippet
            typeface = font(R.font.segoeui_regular)
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.foregroundSubtle)
            setPadding(0, dp(3), 0, 0)
        }, wide())
        row.addView(text, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
            marginStart = dp(14)
            marginEnd = dp(10)
        })

        row.addView(TextView(context).apply {
            this.text = briefMomentOf(context, thread.at)
            typeface = font(R.font.segoeui_regular)
            textSize = 11f
            maxLines = 1
            setTextColor(palette.foregroundSubtle)
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        return row
    }

    private fun showThreadMenu(thread: MessageStore.Conversation, anchor: View) {
        showMenu(
            thread.title,
            buildList {
                add(WP81ContextMenu.Item("call") { place(thread.address) })
                val person = thread.contact
                if (person != null) add(WP81ContextMenu.Item("profile") { showProfile(person.id) })
                else add(WP81ContextMenu.Item("save to contacts") {
                    showEditor(null, prefillNumber = thread.address)
                })
            },
            anchor
        )
    }

    /**
     * Opens the conversation with one number.
     *
     * Everywhere in this app that offers to text somebody comes here rather than handing
     * the number to another program: a shell with its own phone that sent you out to
     * somebody else's messaging app to say a sentence would be two apps for one address
     * book. See [MessageThread] for what happens once you are in it.
     */
    private fun showThread(
        address: String,
        person: PeopleStore.Contact?,
        draft: String? = null
    ) {
        if (address.isBlank()) return
        lateinit var page: MessageThread
        page = MessageThread(
            context = context,
            palette = palette,
            address = address,
            contact = person,
            draft = draft,
            onBack = { dismissOverlay(page) },
            onProfile = { showProfile(it) },
            onAddContact = { showEditor(null, prefillNumber = it) },
            onRequestPermissions = onRequestPermissions,
            onMenu = { title, items, anchor -> showMenu(title, items, anchor) },
            onNotify = onNotify
        )
        openThread = page
        pushOverlay(page)
    }

    /**
     * Starting one with somebody the list has no row for yet.
     *
     * The field at the top is both halves of the question: type a name and it narrows the
     * book, type a number and the first row offers to text it. Which is how the phone did
     * it, and it is the only sensible answer to a screen that has to serve "text my
     * brother" and "text the number on this delivery slip" with one control.
     */
    private fun showNewMessage() {
        val page = overlayPage()
        val header = MetroPageHeader(context, palette).apply {
            setTitle("new message")
            onBack = { dismissOverlay(page) }
        }
        page.addView(header, wide())

        val to = EditText(context).apply {
            hint = "name or number"
            typeface = font(R.font.segoeui_regular)
            textSize = 17f
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setPadding(dp(10), dp(9), dp(10), dp(9))
            palette.applyToField(this)
        }
        page.addView(to, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            marginStart = dp(PAGE_MARGIN_DP)
            marginEnd = dp(PAGE_MARGIN_DP)
            bottomMargin = dp(10)
        })

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAGE_MARGIN_DP), 0, dp(PAGE_MARGIN_DP), dp(24))
        }
        page.addView(
            ScrollView(context).apply {
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
            },
            LinearLayout.LayoutParams(MATCH, 0, 1f)
        )

        fun repaint() {
            val typed = to.text?.toString()?.trim().orEmpty()
            val lower = typed.lowercase()
            column.removeAllViews()
            // A number typed out in full is somebody who is not in the book, which is half
            // of what this page is for. Offered as the first row rather than as a button
            // somewhere else, because at that point it is what the typing was for.
            if (typed.none { it.isLetter() } && typed.count { it.isDigit() } >= MATCH_FROM) {
                column.addView(pickRow(null, "text  $typed") {
                    hideKeyboard()
                    dismissOverlay(page)
                    showThread(typed, null)
                }, wide())
            }
            val found = contacts.filter { person ->
                lower.isEmpty() || person.name.lowercase().let { name ->
                    name.startsWith(lower) ||
                        name.split(' ', '-', '.').any { it.startsWith(lower) }
                }
            }.take(PICK_ROWS)
            if (found.isEmpty() && column.childCount == 0) {
                column.addView(note("nobody by that name"), wide())
            }
            for (person in found) {
                column.addView(pickRow(person, person.name) {
                    chooseNumber(person) { number ->
                        hideKeyboard()
                        dismissOverlay(page)
                        showThread(number, person)
                    }
                }, wide())
            }
        }
        to.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = repaint()
        })
        repaint()
        pushOverlay(page)
        to.requestFocus()
        to.post {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager)
                ?.showSoftInput(to, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** A face and a line of type, for picking somebody out of a short list. */
    private fun pickRow(
        person: PeopleStore.Contact?,
        label: String,
        onTap: () -> Unit
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
            isClickable = true
            setOnClickListener {
                Haptics.tap(it)
                onTap()
            }
            TiltEffect.apply(this)
        }
        row.addView(
            ContactFace(context, palette).apply {
                setLetterSize(15f)
                show(person)
            },
            LinearLayout.LayoutParams(dp(THREAD_FACE_DP), dp(THREAD_FACE_DP))
        )
        row.addView(TextView(context).apply {
            text = label
            typeface = font(R.font.segoeui_regular)
            textSize = 19f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.foreground)
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(14) })
        return row
    }

    /**
     * Which of somebody's numbers to use.
     *
     * Asked only when there is a question: one number is not a choice, and putting a list
     * of one in front of somebody is a dialog that exists to be dismissed.
     */
    private fun chooseNumber(person: PeopleStore.Contact, onPicked: (String) -> Unit) {
        PeopleStore.detail(context, person.id) { detail ->
            val numbers = detail?.phones.orEmpty()
            when {
                numbers.isEmpty() ->
                    onNotify("People", "${person.name} has no number to message")
                numbers.size == 1 -> onPicked(numbers.first().value)
                else -> showMenu(
                    person.name,
                    numbers.map { phone ->
                        WP81ContextMenu.Item("${phone.label}  ·  ${phone.value}") {
                            onPicked(phone.value)
                        }
                    },
                    root.height / 3f
                )
            }
        }
    }

    // ---------------------------------------------------------------- profile

    /**
     * One person's page: their face, their name, and every way of reaching them.
     *
     * The action rows are written as the phone wrote them - the verb and what it applies
     * to on one line, the number or address under it - because "call mobile" is a command
     * and "+38970…" is a fact about it, and a page that led with the fact would be a list
     * of numbers you have to work out what to do with.
     */
    private fun showProfile(contactId: Long) {
        PeopleStore.detail(context, contactId) { detail ->
            if (detail == null) {
                onNotify("People", "That contact is no longer on this phone")
                refresh()
                return@detail
            }
            buildProfile(detail)
        }
    }

    private fun buildProfile(detail: PeopleStore.Detail) {
        val person = detail.contact
        val page = overlayPage()
        val header = MetroPageHeader(context, palette).apply {
            setName(person.name)
            onBack = { dismissOverlay(page) }
        }
        page.addView(header, wide())

        val scroll = ScrollView(context).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAGE_MARGIN_DP), 0, dp(PAGE_MARGIN_DP), dp(24))
        }
        scroll.addView(column, FrameLayout.LayoutParams(MATCH, WRAP))

        // The picture, large, at the head of the page. This is the one place the app gives
        // a face a line of its own rather than a row's worth of it.
        column.addView(
            faceView(person, big = true),
            LinearLayout.LayoutParams(dp(PROFILE_FACE_DP), dp(PROFILE_FACE_DP)).apply {
                topMargin = dp(4)
                bottomMargin = dp(16)
            }
        )

        if (detail.phones.isEmpty() && detail.emails.isEmpty()) {
            column.addView(note("no numbers or addresses for this contact"), wide())
        }
        for (phone in detail.phones) {
            column.addView(
                reachRow(phone, listOf(
                    reachKey(CALL_ICON) { place(phone.value) },
                    reachKey(MESSAGE_ICON) { showThread(phone.value, person) }
                )),
                wide()
            )
        }
        for (address in detail.emails) {
            column.addView(
                reachRow(address, listOf(
                    reachKey(EMAIL_ICON) { email(address.value) }
                )),
                wide()
            )
        }

        // Which account this person is filed under, in the small type a footnote gets. It
        // matters exactly once - when an edit does not stick because the row belongs to an
        // account that overwrites it - and this is where somebody would look.
        detail.account?.let {
            column.addView(TextView(context).apply {
                text = it.name
                typeface = font(R.font.segoeui_regular)
                textSize = 12f
                setTextColor(palette.foregroundSubtle)
                setPadding(0, dp(22), 0, 0)
            }, wide())
        }

        page.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // The page's own strip. Every page in this shell that can be acted on has one, and
        // a profile is the page where most of the acting happens.
        val bar = MetroAppBar(context, palette)
        // Held here rather than read back off the contact, because the contact is a
        // snapshot taken when the page was built and starring somebody does not rebuild it.
        var starred = person.starred
        lateinit var star: android.widget.ImageView
        star = bar.addCommand(STAR_ICON) {
            starred = !starred
            // Painted before the write rather than after it. Nothing else on this page
            // changes when somebody is starred, so there is nothing to re-read and no
            // reason to close the page and open it again - which is what this used to do,
            // and it read as the profile flinching every time the star was tapped.
            bar.setCommandOn(star, starred)
            PeopleStore.setStarred(context, person.id, starred) {
                // The sections behind are the ones that actually change: the favourites
                // wall gains or loses a face, and the list's own star goes on or off.
                refresh()
            }
        }
        // Filled where they are a favourite, outlined where they are not - the same way
        // the strip says any mode is in force.
        bar.setCommandOn(star, starred)
        bar.addCommand(EDIT_ICON) {
            dismissOverlay(page)
            showEditor(detail, prefillNumber = null)
        }
        bar.menu = {
            listOf(MetroAppBar.Item("delete contact") { confirmDelete(person, page) })
        }
        page.addView(bar, wide())

        pushOverlay(page)
    }

    /**
     * A way of reaching somebody, and the keys for the things done with it.
     *
     * The verbs used to be the rows: "call mobile" over the number, and then "text mobile"
     * over the very same number again - which is the number said twice to offer two
     * things, and a person with three of them a page of six rows that reads as a list of
     * near-duplicates. So the number is the row now and the verbs are keys beside it,
     * which is also the only arrangement in which the two are plainly about the same
     * number rather than about two that happen to match.
     *
     * An address takes the same shape for one key rather than two, so that a page of
     * numbers and addresses is one column of rows and one column of keys, instead of two
     * kinds of row that happen to be about the same person.
     *
     * The words are left inert on purpose. Everything that can be done here is under a
     * key, and a row that quietly did one of them when tapped would be a further thing to
     * know about with nothing on screen to say which it was.
     */
    private fun reachRow(entry: PeopleStore.Entry, keys: List<View>): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(11), 0, dp(11))
        }

        // The number or address large and its kind underneath, which is the other way up
        // from the rows this replaced - the verb was the command and the value a fact
        // about it, and with the verbs gone the value is what the row is for.
        val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        words.addView(TextView(context).apply {
            text = entry.value
            typeface = font(R.font.segoeui_regular)
            textSize = 19f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.foreground)
            includeFontPadding = false
        }, wide())
        words.addView(TextView(context).apply {
            text = entry.label
            typeface = font(R.font.segoeui_regular)
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.foregroundSubtle)
            setPadding(0, dp(3), 0, 0)
        }, wide())
        row.addView(words, LinearLayout.LayoutParams(0, WRAP, 1f))

        // Rings, which is what a command looks like in this shell - the app bar is a row
        // of them, and these are the same command in the same clothes, put next to the
        // thing it applies to instead of at the foot of the page. Added last and against
        // the end of the row, so a row with one ring keeps it under the last ring of the
        // rows with two.
        for ((index, key) in keys.withIndex()) {
            row.addView(
                key,
                LinearLayout.LayoutParams(dp(REACH_KEY_DP), dp(REACH_KEY_DP)).apply {
                    marginStart = dp(if (index == 0) REACH_KEY_INSET_DP else REACH_KEY_GAP_DP)
                }
            )
        }
        return row
    }

    /**
     * One of the rings on a [reachRow].
     *
     * The app bar's own button, built here rather than borrowed from [MetroAppBar]: that
     * one is nailed to a strip, and it draws itself white because the strip it sits on is
     * always near-black. A profile is the palette's own background, which is white under
     * the Light theme, so the ring and the glyph are both tinted with the foreground - the
     * ring shape is drawn white precisely so a tint can decide what colour it really is,
     * which is how the bar marks a command that is in force.
     *
     * Not built out of [footKey] either, for the tick: the foot of the keypad is part of
     * the keypad and takes the light keystroke one, where these are commands on a page and
     * take the shell's own. See Haptics, which draws exactly that line.
     */
    private fun reachKey(icon: String, onTap: () -> Unit): View =
        ImageView(context).apply {
            setBackgroundResource(R.drawable.wp81_appbar_circle)
            backgroundTintList =
                android.content.res.ColorStateList.valueOf(palette.foreground)
            setImageDrawable(rocks.gorjan.gokixp.wp81.SvgIcon.fromAsset(context, icon))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(REACH_GLYPH_INSET_DP), dp(REACH_GLYPH_INSET_DP),
                dp(REACH_GLYPH_INSET_DP), dp(REACH_GLYPH_INSET_DP))
            imageTintList =
                android.content.res.ColorStateList.valueOf(palette.foreground)
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            isClickable = true
            setOnClickListener {
                Haptics.tap(it)
                onTap()
            }
            TiltEffect.apply(this)
        }

    private fun confirmDelete(person: PeopleStore.Contact, page: View) {
        ask(
            "delete contact",
            "${person.name} will be removed from this phone and from any account they sync with.",
            "delete"
        ) {
            PeopleStore.delete(context, person) { gone ->
                if (!gone) onNotify("People", "That contact could not be deleted")
                dismissOverlay(page)
                refresh()
            }
        }
    }

    // ---------------------------------------------------------------- editor

    /**
     * The page that writes somebody down, whether they exist yet or not.
     *
     * Held as an object rather than built by a function because it has to be reachable
     * from outside: choosing a picture leaves the app entirely, and what comes back is a
     * URI that has to find its way to the editor that asked for it.
     */
    private inner class Editor(
        private val existing: PeopleStore.Detail?,
        prefillNumber: String?,
        prefillName: String?
    ) {
        val page: LinearLayout = overlayPage()
        private val face = ImageView(context)
        private val faceInitials = TextView(context)
        private val given = field("first name", InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        private val family = field("last name", InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        private val phoneRows = mutableListOf<Pair<EditText, TextView>>()
        private val emailRows = mutableListOf<Pair<EditText, TextView>>()
        private val phoneColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        private val emailColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        /** Chosen this time round, and written on save. Null leaves any existing one alone. */
        private var photo: Bitmap? = null

        private var account: Account? = null
        private var accounts: List<Account?> = listOf(null)
        private val accountLabel = TextView(context)

        init {
            val header = MetroPageHeader(context, palette).apply {
                setTitle(if (existing == null) "new contact" else "edit")
                onBack = { close() }
            }
            page.addView(header, wide())

            val scroll = ScrollView(context).apply { overScrollMode = View.OVER_SCROLL_NEVER }
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(PAGE_MARGIN_DP), 0, dp(PAGE_MARGIN_DP), dp(24))
            }
            scroll.addView(column, FrameLayout.LayoutParams(MATCH, WRAP))

            column.addView(photoSquare(), LinearLayout.LayoutParams(
                dp(EDITOR_FACE_DP), dp(EDITOR_FACE_DP)).apply {
                topMargin = dp(4)
                bottomMargin = dp(14)
            })

            // Only for somebody who does not exist yet. Moving an existing contact between
            // accounts is a different operation from editing one, and offering it here
            // would be offering to make a copy without saying so.
            if (existing == null) {
                column.addView(label("save to"), wide())
                column.addView(accountRow(), wide())
                // Asked for rather than assumed: working out which accounts sync contacts
                // is a walk of every raw contact on the phone, and the editor should be on
                // screen before that finishes. It opens saying "phone", which is the right
                // answer for a device with no accounts and the honest one until the read
                // comes back.
                PeopleStore.accounts(context) { choices ->
                    accounts = choices
                    account = rememberedAccount(choices)
                    accountLabel.text = PeopleStore.labelOf(account)
                }
            }

            column.addView(label("name"), wide())
            column.addView(given)
            column.addView(family)

            column.addView(label("phone"), wide())
            column.addView(phoneColumn, wide())
            column.addView(adder("add phone") { addPhone("", "mobile") }, wide())

            column.addView(label("email"), wide())
            column.addView(emailColumn, wide())
            column.addView(adder("add email") { addEmail("", "personal") }, wide())

            page.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

            val bar = MetroAppBar(context, palette)
            bar.addCommand(SAVE_ICON) { save() }
            bar.addCommand(CANCEL_ICON) { close() }
            page.addView(bar, wide())

            // Filled last, so every field it writes into is already in the page.
            existing?.let {
                given.setText(it.givenName.ifBlank {
                    // A contact synced without a structured name still has a display name,
                    // and losing it on the way into the editor is how an edit of a phone
                    // number ends up deleting somebody's name.
                    if (it.familyName.isBlank()) it.contact.name else ""
                })
                family.setText(it.familyName)
                for (phone in it.phones) {
                    addPhone(phone.value, PeopleStore.nearestPhoneLabel(phone.label))
                }
                for (address in it.emails) {
                    addEmail(address.value, PeopleStore.nearestEmailLabel(address.label))
                }
                showFace(it.contact)
            }
            if (!prefillNumber.isNullOrBlank()) addPhone(prefillNumber, "mobile")
            // What a directory knew about somebody being saved off it. Split on the first
            // space, which is the same guess the phone's own editor makes and the same one
            // this editor's two fields ask the user to make.
            if (!prefillName.isNullOrBlank()) {
                given.setText(prefillName.substringBefore(' ').trim())
                family.setText(prefillName.substringAfter(' ', "").trim())
            }
            if (phoneRows.isEmpty()) addPhone("", "mobile")
        }

        /** The picture, and the whole square as the way to change it. */
        private fun photoSquare(): View {
            val holder = FrameLayout(context)
            holder.background = ContactFace.placeholder(context, palette.accent)
            faceInitials.apply {
                text = "photo"
                typeface = font(R.font.segoeui_regular)
                textSize = 15f
                setTextColor(android.graphics.Color.WHITE)
                gravity = Gravity.CENTER
            }
            holder.addView(faceInitials, FrameLayout.LayoutParams(MATCH, MATCH))
            face.scaleType = ImageView.ScaleType.CENTER_CROP
            face.visibility = View.GONE
            holder.addView(face, FrameLayout.LayoutParams(MATCH, MATCH))
            holder.isClickable = true
            holder.setOnClickListener {
                Haptics.tap(it)
                choosePhoto()
            }
            TiltEffect.apply(holder)
            return holder
        }

        private fun showFace(person: PeopleStore.Contact) {
            faceInitials.text = person.initials.ifBlank { "photo" }
            val uri = person.photoUri ?: return
            ContactFeed.load(context, uri) { bitmap ->
                if (bitmap != null) showBitmap(bitmap)
            }
        }

        private fun showBitmap(bitmap: Bitmap) {
            face.setImageBitmap(bitmap)
            face.visibility = View.VISIBLE
            faceInitials.visibility = View.GONE
        }

        private fun choosePhoto() {
            awaitingPhoto = this
            try {
                photoPicker.launch("image/*")
            } catch (e: Exception) {
                Log.w(TAG, "No gallery to pick a contact picture from", e)
                awaitingPhoto = null
                onNotify("People", "There is nothing on this phone to pick a picture with")
            }
        }

        /** The picture the gallery handed back, decoded and shown before it is written. */
        fun onPhotoPicked(uri: Uri) {
            val bitmap = try {
                android.graphics.ImageDecoder.decodeBitmap(
                    android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
                ) { decoder, _, _ ->
                    // Software, and mutable-free: this is scaled and compressed on the way
                    // into the provider, and a hardware bitmap cannot be read back.
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not read the chosen picture", e)
                onNotify("People", "That picture could not be read")
                return
            }
            photo = bitmap
            showBitmap(bitmap)
        }

        private fun accountRow(): View {
            accountLabel.apply {
                text = PeopleStore.labelOf(account)
                typeface = font(R.font.segoeui_regular)
                textSize = 17f
                setTextColor(palette.foreground)
                setPadding(0, dp(8), 0, dp(12))
                isClickable = true
                setOnClickListener { pickAccount(this) }
                TiltEffect.apply(this)
            }
            return accountLabel
        }

        private fun pickAccount(anchor: View) {
            val choices = accounts
            if (choices.size <= 1) return
            showMenu(
                "save to",
                choices.map { choice ->
                    WP81ContextMenu.Item(PeopleStore.labelOf(choice)) {
                        account = choice
                        accountLabel.text = PeopleStore.labelOf(choice)
                        // Written down as it is picked rather than as the contact is
                        // saved: choosing where contacts go is a decision about all of
                        // them, and somebody who changes it and then abandons the contact
                        // has still made that decision.
                        rememberAccount(choice)
                    }
                },
                anchor
            )
        }

        private fun addPhone(value: String, label: String) =
            addEntry(phoneColumn, phoneRows, value, label, PeopleStore.PHONE_LABELS,
                InputType.TYPE_CLASS_PHONE)

        private fun addEmail(value: String, label: String) =
            addEntry(emailColumn, emailRows, value, label, PeopleStore.EMAIL_LABELS,
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)

        /**
         * One value and the word for what kind it is.
         *
         * The kind is a tap rather than a dropdown: WP8.1 had no combo boxes, and there are
         * four choices. Tapping it walks round them, which is quicker than any list of four
         * would be and needs no second surface.
         */
        private fun addEntry(
            column: LinearLayout,
            rows: MutableList<Pair<EditText, TextView>>,
            value: String,
            label: String,
            labels: List<String>,
            inputType: Int
        ) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val entry = EditText(context).apply {
                setText(value)
                setSingleLine()
                textSize = 17f
                typeface = font(R.font.segoeui_regular)
                this.inputType = inputType
                setPadding(dp(10), dp(9), dp(10), dp(9))
                palette.applyToField(this)
            }
            val kind = TextView(context).apply {
                text = label
                typeface = font(R.font.segoeui_regular)
                textSize = 13f
                setTextColor(palette.accent)
                setPadding(dp(10), dp(9), dp(2), dp(9))
                isClickable = true
                setOnClickListener {
                    val next = labels[(labels.indexOf(text.toString()) + 1).mod(labels.size)]
                    text = next
                }
                TiltEffect.apply(this)
            }
            row.addView(entry, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(kind, LinearLayout.LayoutParams(dp(KIND_DP), WRAP))
            column.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dp(4)
            })
            rows.add(entry to kind)
        }

        private fun adder(text: String, onTap: () -> Unit): View = TextView(context).apply {
            this.text = "+  $text"
            typeface = font(R.font.segoeui_regular)
            textSize = 15f
            setTextColor(palette.accent)
            setPadding(0, dp(10), 0, dp(6))
            isClickable = true
            setOnClickListener {
                Haptics.tap(it)
                onTap()
            }
            TiltEffect.apply(this)
        }

        private fun label(text: String): View = TextView(context).apply {
            this.text = text
            typeface = font(R.font.segoeui_semibold)
            textSize = 12f
            letterSpacing = 0.06f
            setTextColor(palette.accent)
            setPadding(0, dp(18), 0, dp(4))
        }

        private fun field(hint: String, flags: Int): EditText = EditText(context).apply {
            this.hint = hint
            setSingleLine()
            textSize = 17f
            typeface = font(R.font.segoeui_regular)
            inputType = InputType.TYPE_CLASS_TEXT or flags
            setPadding(dp(10), dp(9), dp(10), dp(9))
            palette.applyToField(this)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) }
        }

        private fun draft(): PeopleStore.Draft = PeopleStore.Draft(
            givenName = given.text.toString(),
            familyName = family.text.toString(),
            phones = phoneRows.map {
                PeopleStore.Entry(it.first.text.toString(), it.second.text.toString())
            }.filter { it.value.isNotBlank() },
            emails = emailRows.map {
                PeopleStore.Entry(it.first.text.toString(), it.second.text.toString())
            }.filter { it.value.isNotBlank() },
            photo = photo
        )

        private fun save() {
            if (!PeopleStore.canWrite(context)) {
                onRequestPermissions(PeopleStore.permissions())
                return
            }
            val draft = draft()
            if (draft.isEmpty) {
                onNotify("People", "Nothing to save yet")
                return
            }
            if (existing == null) {
                PeopleStore.create(context, account, draft) { id ->
                    PeopleStore.forgetAccounts()
                    close()
                    refresh()
                    if (id == null) onNotify("People", "That contact could not be saved")
                    else showProfile(id)
                }
                return
            }
            val rawId = existing.rawId
            if (rawId == null) {
                onNotify("People", "This contact is read-only on this phone")
                return
            }
            PeopleStore.update(context, rawId, draft) { ok ->
                close()
                refresh()
                if (!ok) onNotify("People", "That contact could not be saved")
                else showProfile(existing.contact.id)
            }
        }

        fun close() {
            hideKeyboard()
            if (awaitingPhoto === this) awaitingPhoto = null
            dismissOverlay(page)
        }
    }

    /**
     * Which account a new contact opens on.
     *
     * Whatever was picked last, if that account is still on the phone; the first of them
     * otherwise, which puts Google at the top - see [PeopleStore.accounts]. A stored
     * choice of the phone itself is remembered as such and is not the same as never having
     * chosen, which is why this reads the key's presence rather than its value.
     */
    private fun rememberedAccount(choices: List<Account?>): Account? {
        val stored = prefs.getString(KEY_NEW_CONTACT_ACCOUNT, null)
            ?: return choices.firstOrNull()
        if (stored.isEmpty()) return null
        val type = stored.substringBefore(SEPARATOR)
        val name = stored.substringAfter(SEPARATOR, "")
        return choices.filterNotNull().firstOrNull { it.type == type && it.name == name }
            ?: choices.firstOrNull()
    }

    private fun rememberAccount(account: Account?) {
        prefs.edit().putString(
            KEY_NEW_CONTACT_ACCOUNT,
            if (account == null) "" else account.type + SEPARATOR + account.name
        ).apply()
    }

    private fun showEditor(
        existing: PeopleStore.Detail?,
        prefillNumber: String?,
        prefillName: String? = null
    ) {
        if (!PeopleStore.canWrite(context)) {
            onRequestPermissions(PeopleStore.permissions())
            return
        }
        pushOverlay(Editor(existing, prefillNumber, prefillName).page)
    }

    /** The gallery's answer, on its way back to whichever editor asked for it. */
    fun onPhotoPicked(uri: Uri?) {
        val editor = awaitingPhoto ?: return
        awaitingPhoto = null
        if (uri == null) return
        editor.onPhotoPicked(uri)
    }

    // ---------------------------------------------------------------- keypad

    /**
     * The dialpad, for a number that is nobody yet.
     *
     * The phone kept this behind a button on the Phone app's strip rather than giving it a
     * section, and it is right: a keypad is what you reach for when the address book has
     * failed you, which is not often enough to spend a swipe of the panorama on.
     *
     * The keys are the calculator's - a field of flat rectangles sized off the width, with
     * the letters under the digits the way every phone has drawn them since the rotary
     * dial was replaced.
     */
    /** Puts the panorama on the call history, for something that arrived asking for it. */
    /**
     * The messages page, and one conversation on it if the caller named a number.
     *
     * The way in from outside the app: a notification tapped, an `sms:` link followed, a
     * picture message this app cannot show. The section is shown underneath either way, so
     * backing out of a conversation opened this way lands somewhere that makes sense
     * rather than closing the app.
     */
    fun showMessages(address: String? = null, draft: String? = null) {
        panorama.goTo(PAGE_MESSAGES, animated = false)
        if (!address.isNullOrBlank()) showThread(address, null, draft)
    }

    fun showHistory() {
        if (!::panorama.isInitialized) return
        panorama.goTo(PAGE_HISTORY, animated = false)
    }

    fun showDialer(prefill: String? = null) {
        val page = overlayPage()
        val header = MetroPageHeader(context, palette).apply {
            // The pad is the keypad; the page is the dialer. What is on screen is a number
            // being put together and the people it might be, and only one part of that is
            // keys - naming the whole page after them named it after its furniture.
            setTitle("dialer")
            onBack = { dismissOverlay(page) }
        }
        page.addView(header, wide())

        // Who that could be, as it is typed. The one thing a modern keypad can do that the
        // phone's could not, and the answer to the commonest reason for opening one: a
        // number half-remembered that turns out to be somebody already in the book - or a
        // name being spelled out on the keys, which is the other half of what a keypad has
        // always been for. See PeopleStore.keypadMatches.
        //
        // In a scroller of its own, taking whatever height is left between the number and
        // the keys: five people is more than fits on a short phone under a keypad, and a
        // list that pushed the keys off the bottom of the screen would have answered the
        // question by taking away the thing that asked it.
        val matches = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val matchScroller = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            addView(matches, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        page.addView(matchScroller, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // One grid, held at the foot of the page: the number, the digits under it, and
        // under those the three keys that are about the number rather than about a digit.
        // They used to be three separate things with the page's spare height shared out
        // between them, which drew lines across the keypad where there are none - the
        // number is what the pad is filling in, and the call key is on the pad rather
        // than beside it.
        val keys = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Only the last row's own margin below it, because the rows carry the rest.
            setPadding(dp(KEYPAD_MARGIN_DP), dp(6), dp(KEYPAD_MARGIN_DP), dp(10))
        }

        // The number, in a key of its own at the head of the pad. It used to sit on the
        // black under the title, where it read as a caption about the page rather than as
        // the thing the page fills in; in the keys' own grey, directly over them, it is
        // the top of the pad.
        //
        // A field rather than a label, because a number is got wrong in the middle far
        // more often than at the end - a digit dropped from a dialling code was otherwise
        // six presses of delete and the whole thing typed again. A tap puts the caret
        // where the finger is and the keys write there. The soft keyboard never comes up:
        // this pad *is* the keyboard, and a second one would cover what it types into.
        val typed = EditText(context).apply {
            typeface = font(R.font.segoeui_light)
            textSize = 32f
            gravity = Gravity.CENTER
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            showSoftInputOnFocus = false
            isCursorVisible = true
            // Set before the padding: a view takes its padding from whatever background
            // it is handed, and this one has insets to report. See [keyFace].
            background = keyFace(keyFill())
            setPadding(dp(12), dp(10), dp(12), dp(12))
            setText(prefill.orEmpty())
            setSelection(text.length)
        }
        // Not applyToField, which is the white box a form has. This one is a key, drawn in
        // the page's own colours, so only the caret and the band behind a selection need
        // saying.
        palette.applyToPageText(typed)
        keys.addView(typed, wideCell())

        // The rows on screen, by who is on them.
        //
        // Kept so that a keystroke can change the list without rebuilding it. Emptying the
        // column and filling it again is the obvious way and the wrong one: most of an
        // answer is usually the same people as the answer before it, and tearing their
        // rows down only to build the same rows again throws away every face that had
        // finished decoding - which is a list that flashes on every key pressed.
        val rows = LinkedHashMap<String, View>()

        fun showMatches(found: List<PeopleStore.Reachable>) {
            val wanted = found.map { "${it.contact.id}:${it.number}" }
            // The people who are no longer an answer. Taken out first, so what is left is
            // already in the right order more often than not.
            for (gone in rows.keys - wanted.toSet()) {
                matches.removeView(rows.remove(gone))
            }
            for ((index, person) in found.withIndex()) {
                val id = wanted[index]
                val standing = rows[id]
                if (standing == null) {
                    val row = matchRow(person)
                    rows[id] = row
                    matches.addView(row, index, wide())
                } else if (matches.indexOfChild(standing) != index) {
                    // Moved rather than remade: a row that has slid up the list is the
                    // same row, and its face has already been decoded once.
                    matches.removeView(standing)
                    matches.addView(standing, index)
                }
            }
        }

        // Which search the list above the number is waiting for. The book is read on its
        // own thread, and a slow answer to "07" must not land on top of the right answer
        // to "0712345" that was asked for after it.
        var pending = 0
        fun repaint() {
            val typedNow = typed.text.toString()
            pending++
            val token = pending
            // What is standing is left standing until the book answers. It belongs to the
            // digits of a keystroke ago, which is a moment out of date rather than wrong -
            // and a list that empties itself between every pair of answers is a list that
            // blinks at the user for the whole time they are typing.
            if (typedNow.count { it.isDigit() } < MATCH_FROM) {
                showMatches(emptyList())
                return
            }
            PeopleStore.keypadMatches(context, typedNow) { found ->
                if (token != pending) return@keypadMatches
                showMatches(found)
            }
        }
        typed.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = repaint()
        })

        for (row in KEYS) {
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (key in row) line.addView(
                keyView(
                    key,
                    onTap = { write(typed, key.digit) },
                    onHold = { second -> write(typed, second) }
                ),
                keyCell(dp(KEY_DP))
            )
            keys.addView(line, wide())
        }

        // Call in the middle of the last row: it is the one thing this page is for, and it
        // sits between the two keys that are about the number rather than the call -
        // saving it on one side, taking it back a digit on the other. Under the 0, where
        // the thumb already is.
        val foot = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        foot.addView(footKey(ADD_ICON, keyFill()) {
            val number = typed.text.toString()
            if (number.isNotEmpty()) {
                dismissOverlay(page)
                showEditor(null, prefillNumber = number)
            }
        }, keyCell(dp(FOOT_DP)))
        // Green rather than the accent, and the same green the call screen answers in -
        // this is the other end of the same act. See CALL_GREEN, which says why the one
        // colour in this shell that is not the user's choice is not the user's choice.
        foot.addView(footKey(CALL_ICON, CALL_GREEN, android.graphics.Color.WHITE) {
            val number = typed.text.toString()
            if (number.isNotEmpty()) place(number)
        }, keyCell(dp(FOOT_DP)))
        val back = footKey(BACKSPACE_ICON, keyFill()) { erase(typed) }
        back.setOnLongClickListener {
            typed.setText("")
            true
        }
        foot.addView(back, keyCell(dp(FOOT_DP)))
        keys.addView(foot, wide())

        page.addView(keys, wide())

        repaint()
        pushOverlay(page)
    }

    /**
     * Writes one character where the caret is, which is where the last tap put it.
     *
     * Through the field's own text rather than by keeping the number here and setting it
     * back afterwards: the caret belongs to the field, and a number rewritten from outside
     * puts it back at the end on every key pressed - which is the whole of what tapping
     * into the middle was for.
     */
    private fun write(field: EditText, digit: Char) {
        val from = field.selectionStart.coerceAtLeast(0)
        val to = field.selectionEnd.coerceAtLeast(0)
        field.text.replace(minOf(from, to), maxOf(from, to), digit.toString())
    }

    /** Takes out what is selected, or the character before the caret when nothing is. */
    private fun erase(field: EditText) {
        val from = field.selectionStart.coerceAtLeast(0)
        val to = field.selectionEnd.coerceAtLeast(0)
        when {
            from != to -> field.text.delete(minOf(from, to), maxOf(from, to))
            from > 0 -> field.text.delete(from - 1, from)
        }
    }

    /**
     * One key of the pad, a third of a row wide.
     *
     * [height] is the key as it is drawn; the cell is that plus a whole gap, because the
     * gap lives inside the cell rather than between the cells - see [keyFace]. The columns
     * still line up with the columns above them and the space between any two rows is the
     * same space; the difference is that all of it can be pressed.
     */
    private fun keyCell(height: Int) =
        LinearLayout.LayoutParams(0, height + dp(KEY_GAP_DP), 1f)

    /** A key that is the whole row: the number at the head of the pad. */
    private fun wideCell() = LinearLayout.LayoutParams(MATCH, WRAP)

    /**
     * One person the keys pressed could mean, and the two things you would do about them.
     *
     * The same pair of rings a profile puts beside a number, because it is the same offer
     * in the same clothes - somebody found on the keypad is somebody to ring or to write
     * to, and having found them there is no reason to make the user open their card first
     * to do either. The card is still a tap away on the name, which is what the line the
     * list replaced used to do and the only thing it could do.
     */
    private fun matchRow(found: PeopleStore.Reachable): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(KEYPAD_MARGIN_DP), dp(MATCH_ROW_PAD_DP), dp(KEYPAD_MARGIN_DP),
                dp(MATCH_ROW_PAD_DP))
        }

        // The same square the contact list puts at the head of a row, at the same size:
        // somebody found on the keypad is the same person the list shows, and a face that
        // was a different size here would say they were a different kind of thing.
        row.addView(
            ContactFace(context, palette).apply {
                setLetterSize(MATCH_FACE_SP)
                show(found.contact)
            },
            LinearLayout.LayoutParams(dp(MATCH_FACE_DP), dp(MATCH_FACE_DP))
        )

        val words = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setOnClickListener {
                Haptics.tap(it)
                showProfile(found.contact.id)
            }
            TiltEffect.apply(this)
        }
        words.addView(TextView(context).apply {
            text = found.contact.name
            typeface = font(R.font.segoeui_regular)
            textSize = 19f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.foreground)
            includeFontPadding = false
        }, wide())
        // The number they were found by, and not only for show: somebody with a work and a
        // home number matched on one of them, and the rings act on that one.
        words.addView(TextView(context).apply {
            text = found.number
            typeface = font(R.font.segoeui_regular)
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.foregroundSubtle)
            setPadding(0, dp(3), 0, 0)
        }, wide())
        row.addView(words, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
            marginStart = dp(16)
        })

        // The ring, and only the one. A keypad is somebody about to make a call - that is
        // what they opened it to do - and a second ring beside it for writing to them
        // instead would be answering a question nobody asked here. Their card is a tap on
        // the name away, and everything else about them is on it.
        row.addView(reachKey(CALL_ICON) { place(found.number) }, ring(first = true))
        return row
    }

    /**
     * One key: what it types, what is written under it, and what holding it types instead.
     *
     * [held] is the plus on the zero and nothing anywhere else. It is the one thing on a
     * telephone keypad that has never had a key of its own - there are twelve keys and
     * thirteen things to type - and every phone since has put it under a long press on the
     * nought, which is why the plus is printed there in the first place.
     */
    private data class Key(val digit: Char, val letters: String, val held: Char? = null)

    private fun keyView(key: Key, onTap: () -> Unit, onHold: (Char) -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            // Before the padding, which a view otherwise takes from whatever background it
            // is handed - and this one has insets to report.
            background = keyFace(keyFill())
            setPadding(0, dp(KEY_PAD_DP), 0, dp(KEY_PAD_DP))
            isClickable = true
            setOnClickListener {
                Haptics.key(it)
                // A telephone key is felt and heard. The tick alone is what a calculator
                // does, and this is not one.
                DialTones.press(key.digit)
                onTap()
            }
            key.held?.let { second ->
                setOnLongClickListener {
                    // No tone, and no buzz of its own: there is no tone for a plus - it is
                    // not something the line can hear, it is how a country code is written
                    // down - and the framework gives the shell's tick as it claims a hold.
                    onHold(second)
                    true
                }
            }
            TiltEffect.apply(this)

            addView(TextView(context).apply {
                text = key.digit.toString()
                typeface = font(R.font.segoeui_semilight)
                textSize = KEY_DIGIT_SP
                includeFontPadding = false
                setTextColor(palette.foreground)
                gravity = Gravity.CENTER
                // Centred by the ink rather than by the line it sits on.
                //
                // A line box is the same height whatever is written in it, and centring
                // that centres the space rather than the mark. It makes no difference to
                // the digits, which all fill their line the same way - and all the
                // difference to the star, which is drawn up at the cap height with
                // nothing below the middle of the em, so a centred line box left it
                // sitting visibly above the middle of its own key.
                val ink = android.graphics.Rect()
                paint.getTextBounds(key.digit.toString(), 0, 1, ink)
                val metrics = paint.fontMetrics
                translationY =
                    (metrics.ascent + metrics.descent) / 2f - (ink.top + ink.bottom) / 2f
            }, wide())
            // The letters are what tells a keypad from a calculator, and they are set
            // small and quiet because nobody reads them - they are recognised.
            //
            // Gone rather than invisible on the keys that have none: the key is a fixed
            // height now - see KEY_DP - so a line held open for nothing no longer keeps
            // the rows level, it only pushes the star and the hash up off the middle of
            // their own keys.
            addView(TextView(context).apply {
                text = key.letters
                typeface = font(R.font.segoeui_regular)
                textSize = KEY_LETTERS_SP
                letterSpacing = 0.12f
                includeFontPadding = false
                setTextColor(palette.foregroundSubtle)
                gravity = Gravity.CENTER
                setPadding(0, dp(3), 0, 0)
                visibility = if (key.letters.isBlank()) View.GONE else View.VISIBLE
            }, wide())
        }

    /**
     * A key's own paint, held half a gap in from the edge of the cell it is drawn in.
     *
     * What makes a keypad with no dead ground on it. The gaps used to be margins - space
     * between the keys that belonged to neither of them, so a thumb landing a millimetre
     * wide of a key hit nothing at all and the number stayed as it was. The gap is now
     * inside the key that owns it: the same picture, and every pixel of the pad answers.
     */
    private fun keyFace(colour: Int): Drawable =
        InsetDrawable(ColorDrawable(colour), dp(KEY_GAP_DP) / 2)

    /**
     * One of the three across the foot of the keypad.
     *
     * [ink] is said rather than worked out from [fill], because the one key that is not
     * drawn in the page's own colours is not drawn in the palette's either: green is a
     * colour this shell chose, and what reads on it is a decision that belongs with the
     * green rather than with a rule about fills.
     */
    private fun footKey(
        icon: String, fill: Int, ink: Int = palette.foreground, onTap: () -> Unit
    ): View =
        ImageView(context).apply {
            setImageDrawable(rocks.gorjan.gokixp.wp81.SvgIcon.fromAsset(context, icon))
            scaleType = ImageView.ScaleType.FIT_CENTER
            // The background first: it carries insets, and a view takes its padding from
            // whatever background it is handed. See [keyFace].
            background = keyFace(fill)
            // Measured against the key rather than the cell: the cell grew by a gap when
            // the gap moved inside it, and the same numbers would have grown the mark.
            setPadding(dp(16), dp(16), dp(16), dp(16))
            imageTintList = android.content.res.ColorStateList.valueOf(ink)
            isClickable = true
            setOnClickListener {
                Haptics.key(it)
                onTap()
            }
            TiltEffect.apply(this)
        }

    /** The key grey: the foreground a little way over the background, as the calculator's. */
    private fun keyFill(): Int = androidx.core.graphics.ColorUtils.blendARGB(
        palette.background, palette.foreground, KEY_FILL_ALPHA)

    // ---------------------------------------------------------------- commands

    /**
     * Rings a number.
     *
     * Placed by the launcher itself where it has been allowed to, and handed to the phone's
     * own dialler where it has not - which is also what happens on a device with no
     * telephony at all. The permission is asked for at the moment somebody actually tries
     * to make a call, because that is the moment the request explains itself.
     */
    private fun place(number: String) {
        if (number.isBlank()) return
        val uri = Uri.parse("tel:" + Uri.encode(number))
        val allowed = context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

        // Placed through Telecom rather than thrown at whatever answers a tel: intent,
        // once this app is the phone. It is the same call either way - the difference is
        // that this one is asked for directly, so there is no moment where the request is
        // out in the system looking for somebody to take it.
        if (allowed && isThePhone()) {
            try {
                telecom()?.placeCall(uri, android.os.Bundle())
                return
            } catch (e: Exception) {
                Log.w(TAG, "Telecom would not place the call; falling back to an intent", e)
            }
        }
        // Asked for once, at the moment somebody actually tries to make a call - which is
        // the moment the request explains itself. Asking and dialling in the same breath
        // would put the system's prompt over the phone's dialler, so the first refusal is
        // just the prompt; from then on the number goes to the dialler with the call one
        // tap away, which is what somebody who said no to this asked for.
        if (!allowed && !askedToCall) {
            askedToCall = true
            onRequestPermissions(arrayOf(android.Manifest.permission.CALL_PHONE))
            return
        }
        try {
            context.startActivity(
                Intent(if (allowed) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri))
        } catch (e: Exception) {
            Log.w(TAG, "Could not place a call", e)
            try {
                context.startActivity(Intent(Intent.ACTION_DIAL, uri))
            } catch (e2: Exception) {
                Log.w(TAG, "Nothing on this phone dials", e2)
                onNotify("People", "There is nothing on this phone to call with")
            }
        }
    }

    /**
     * Texting somebody, which is now a page in this app rather than a way out of it.
     *
     * It used to throw an `sms:` intent at whatever the phone had. That was right while
     * People could only read an address book; it is wrong now that it has the conversation
     * itself, and a "text" command that left the app to say one sentence was the last
     * thing here that treated somebody else's program as part of this one.
     */
    private fun message(number: String) = showThread(number, null)

    /**
     * Puts a number on the clipboard.
     *
     * Silent when it works. Android has shown what was copied itself since 13, and a word
     * of our own over the top of that is the same news twice - see MessageThread.copy,
     * which takes a message the same way and for the same reason.
     */
    private fun copyNumber(number: String) {
        if (number.isBlank()) return
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager
            if (clipboard == null) {
                onNotify("People", "This phone has nowhere to copy to")
                return
            }
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("number", number))
        } catch (e: Exception) {
            Log.w(TAG, "Could not copy a number", e)
            onNotify("People", "That could not be copied")
        }
    }

    private fun email(address: String) = open(Intent(
        Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(address))), "email")

    private fun open(intent: Intent, what: String) {
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Nothing on this phone handles $what", e)
            onNotify("People", "There is nothing on this phone to $what with")
        }
    }

    /**
     * Whether the in-call list of outputs would have to say "bluetooth" rather than a name.
     *
     * Before Android 12 there is nothing to ask for - the old permission is granted at
     * install - so there is nothing to offer either. See CallCentre.bluetoothName.
     */
    private fun needsBluetoothName(): Boolean =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun askForContacts() = onRequestPermissions(PeopleStore.permissions())

    /**
     * Whether this app is the phone.
     *
     * Android hands every call on the device to one app, chosen by this role, and there is
     * no smaller version of it - an app cannot show its own calls and leave the rest to
     * somebody else. So holding it is what makes the call screen exist at all, and until
     * it is held a call placed from here is handed on to whoever does hold it.
     */
    fun isThePhone(): Boolean = try {
        val roles = context.getSystemService(android.app.role.RoleManager::class.java)
        roles?.isRoleHeld(android.app.role.RoleManager.ROLE_DIALER) == true
    } catch (e: Exception) {
        Log.w(TAG, "Could not ask whether this app is the phone", e)
        false
    }

    private fun telecom(): android.telecom.TelecomManager? =
        context.getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager

    /**
     * The commands for somebody in a list.
     *
     * Short, and every one of them a thing you would want without opening them first -
     * anything that needs their page is on their page. Starring is here because it is the
     * one change worth making from a list: it moves somebody into the section you actually
     * use, and having to open a profile to do it is what stops people using favourites.
     */
    private fun showContactMenu(person: PeopleStore.Contact, anchor: View) =
        showContactMenu(person, anchorYOf(anchor))

    /** The same, for a list that reports where its row ended rather than handing it over. */
    private fun showContactMenu(person: PeopleStore.Contact, anchorY: Float) {
        showMenu(
            person.name,
            listOf(
                WP81ContextMenu.Item(
                    if (person.starred) "remove from favourites" else "add to favourites"
                ) {
                    PeopleStore.setStarred(context, person.id, !person.starred) { refresh() }
                },
                WP81ContextMenu.Item("delete") { confirmDeleteFromList(person) }
            ),
            anchorY
        )
    }

    private fun confirmDeleteFromList(person: PeopleStore.Contact) {
        ask(
            "delete contact",
            "${person.name} will be removed from this phone and from any account they sync with.",
            "delete"
        ) {
            PeopleStore.delete(context, person) { gone ->
                if (!gone) onNotify("People", "That contact could not be deleted")
                refresh()
            }
        }
    }

    private fun showHistoryMenu(entry: PhoneHistory.Entry, anchor: View) {
        showMenu(
            entry.title,
            buildList {
                add(WP81ContextMenu.Item("call") { place(entry.number) })
                add(WP81ContextMenu.Item("text") { message(entry.number) })
                val id = entry.contactId
                if (id != null) add(WP81ContextMenu.Item("profile") { showProfile(id) })
                else add(WP81ContextMenu.Item("save to contacts") {
                    showEditor(null, prefillNumber = entry.number)
                })
                // Last, under the things the row is usually held for. It is the one command
                // here that is about the number as a piece of text rather than about the
                // person - pasting it into a message, into a form, reading it out to
                // somebody - and a withheld caller never reaches this list, so there is
                // always a number there to take.
                add(WP81ContextMenu.Item("copy number") { copyNumber(entry.number) })
            },
            anchor
        )
    }

    // ---------------------------------------------------------------- navigation

    /**
     * The command list and the prompt, put up over whatever is on screen.
     *
     * Always through these two rather than through the views themselves. Both are built
     * with the app and every page is added over them, so a menu opened from inside a
     * profile - or the prompt that asks before a contact is deleted - would otherwise go
     * up behind the page that asked for it. Brought to the front as they are shown, which
     * is the only moment their depth matters.
     */
    private fun showMenu(title: String?, items: List<WP81ContextMenu.Item>, anchor: View) =
        showMenu(title, items, anchorYOf(anchor))

    private fun showMenu(title: String?, items: List<WP81ContextMenu.Item>, anchorY: Float) {
        contextMenu.bringToFront()
        contextMenu.show(title, items, anchorY)
    }

    /** Where a view sits in the app's own coordinates, for anything anchored to it. */
    private fun anchorYOf(anchor: View): Float {
        val position = IntArray(2)
        anchor.getLocationInWindow(position)
        return position[1].toFloat()
    }

    private fun ask(title: String, question: String, accept: String, onAccept: () -> Unit) {
        dialog.bringToFront()
        dialog.confirm(title, question, accept, onAccept)
    }

    /** A page over the app: full height, its own background, and it swallows stray taps. */
    private fun overlayPage(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(palette.background)
        // Anything that falls past the rows stops here, so the panorama underneath does not
        // page sideways while a page is open on top of it.
        isClickable = true
    }

    private fun pushOverlay(view: View) {
        overlays.add(view)
        root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        rocks.gorjan.gokixp.wp81.MetroPageTransition(view).playIn()
    }

    private fun dismissOverlay(view: View) {
        overlays.remove(view)
        if (view === openThread) openThread = null
        rocks.gorjan.gokixp.wp81.MetroPageTransition(view).playOut { root.removeView(view) }
    }

    /**
     * Back, from the inside out.
     *
     * The window this app lives in reads back as "put People away", which is right at the
     * top of the panorama and wrong everywhere below it. So everything opened over the app
     * is closed first, one press at a time, and only a press with nothing left to close
     * leaves.
     */
    fun handleBack(): Boolean {
        if (dialog.isShowing()) {
            dialog.dismiss()
            return true
        }
        if (contextMenu.isShowing()) {
            contextMenu.dismiss()
            return true
        }
        if (appBar.isMenuOpen()) {
            appBar.closeMenu()
            return true
        }
        // The contact list answers for its own grid and its own search band, both of which
        // are steps inside that page rather than ways out of the app.
        if (::allList.isInitialized && allList.handleBack()) return true
        overlays.lastOrNull()?.let { top ->
            // A page carries its own strip, and a command list open on that strip is the
            // innermost thing on screen - so it is what back closes, before the page it
            // belongs to.
            if (barOf(top)?.closeMenu() == true) return true
            hideKeyboard()
            dismissOverlay(top)
            return true
        }
        return false
    }

    /** A page's own command strip, if it has one. See [handleBack]. */
    private fun barOf(page: View): MetroAppBar? {
        val group = page as? android.view.ViewGroup ?: return null
        for (i in 0 until group.childCount) {
            (group.getChildAt(i) as? MetroAppBar)?.let { return it }
        }
        return null
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(root.windowToken, 0)
    }

    // ---------------------------------------------------------------- helpers

    private fun note(message: String, onTap: (() -> Unit)? = null) = TextView(context).apply {
        text = message
        typeface = font(R.font.segoeui_regular)
        textSize = 15f
        setTextColor(palette.foregroundSubtle)
        setPadding(0, dp(18), dp(16), dp(18))
        if (onTap != null) {
            isClickable = true
            setOnClickListener { onTap() }
            TiltEffect.apply(this)
        }
    }

    private fun font(res: Int): Typeface? = ResourcesCompat.getFont(context, res)

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun wide() = LinearLayout.LayoutParams(MATCH, WRAP)

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        const val PAGE_FAVOURITES = 0
        const val PAGE_HISTORY = 1
        const val PAGE_MESSAGES = 2
        const val PAGE_CONTACTS = 3

        /** Rows built at a time, and added again as the reader nears the end of them. */
        const val CHUNK = 30

        /** No day yet, for [lastHistoryDay]. Not zero: that is what an undated call gives. */
        const val NO_DAY = Long.MIN_VALUE

        const val PAGE_MARGIN_DP = 22

        const val ICON_DIR = "custom_icons_8"
        const val KEYPAD_ICON = "$ICON_DIR/appbar.dial.svg"
        const val ADD_ICON = "$ICON_DIR/appbar.add.svg"
        const val SEARCH_ICON = "$ICON_DIR/appbar.magnify.svg"
        const val EDIT_ICON = "$ICON_DIR/appbar.edit.svg"
        const val STAR_ICON = "$ICON_DIR/appbar.star.svg"
        const val SAVE_ICON = "$ICON_DIR/appbar.check.svg"
        const val CANCEL_ICON = "$ICON_DIR/appbar.cancel.svg"
        const val CALL_ICON = "$ICON_DIR/appbar.phone.svg"
        /** Texting, drawn as the call screen draws it - see CallScreen's own MESSAGE_ICON. */
        const val MESSAGE_ICON = "$ICON_DIR/appbar.message.svg"
        /** Mail. The hard-edged envelope, which is the one drawn as this shell draws. */
        const val EMAIL_ICON = "$ICON_DIR/appbar.email.hardedge.svg"
        /**
         * The delete key's mark: a cross backed into a shoulder, pointing left.
         *
         * A plain left arrow is the same mark the back key wears, and on a keypad whose
         * other two foot keys add and call, an arrow reads as "go back" rather than as
         * "take one off". This is the shape every telephone keypad has drawn there.
         */
        const val BACKSPACE_ICON = "$ICON_DIR/appbar.clear.inverse.reflect.horizontal.svg"

        /**
         * Which way a call went.
         *
         * Left for one this phone made and right for one it received - the log read as an
         * account of what arrived and what left, rather than of who was pointing where.
         */
        const val OUTGOING_ICON = "$ICON_DIR/appbar.arrow.left.svg"
        const val INCOMING_ICON = "$ICON_DIR/appbar.arrow.right.svg"

        /** The star beside a favourite in the list. Drawn as type, like Zune's heart. */
        const val STAR = "★"

        /** The band a favourite's name sits on, over whatever their picture happens to be. */
        const val NAME_BAND = 0x99000000.toInt()

        const val FACE_DP = 62

        /** The face on a conversation, at the size the contact list sets one. */
        const val THREAD_FACE_DP = 42

        /** How many people a new message offers to pick from before you type. */
        const val PICK_ROWS = 40

        /** How long the typing has to settle before a directory is asked about it. */
        const val DIRECTORY_DEBOUNCE_MS = 400L


        const val PROFILE_FACE_DP = 176
        const val EDITOR_FACE_DP = 132
        const val ARROW_DP = 18

        /** How wide the kind of a number or address is, beside the field for it. */
        const val KIND_DP = 66

        const val FAVOURITE_COLUMNS = 3
        const val FAVOURITE_GAP_DP = 10

        /** How the favourites are arranged, and where new contacts go. Both this app's. */
        const val KEY_FAVOURITE_ORDER = "wp81_people_favourite_order"
        const val KEY_NEW_CONTACT_ACCOUNT = "wp81_people_new_contact_account"

        /**
         * What separates the parts of both of those.
         *
         * A unit separator, because the things being joined are a contact lookup key and
         * an account name, and there is no printable character neither of them can hold.
         */
        const val SEPARATOR = "\u001f"

        const val KEYPAD_MARGIN_DP = 16
        const val KEY_GAP_DP = 8
        const val FOOT_DP = 62

        /**
         * The rings beside a number or an address on a profile, at the app bar's own
         * size and with its glyph sitting the same way inside them. See reachRow.
         */
        const val REACH_KEY_DP = 44
        const val REACH_GLYPH_INSET_DP = 5

        /** Between the words and the first ring, and between the rings. */
        const val REACH_KEY_INSET_DP = 14
        const val REACH_KEY_GAP_DP = 14
        const val KEY_FILL_ALPHA = 0.122f

        /** How many digits are typed before the keypad starts guessing who they belong to. */
        const val MATCH_FROM = 3

        /**
         * A digit key, at three quarters of the size it was drawn at first.
         *
         * The pad was a hand's worth of screen and the list of people it finds had what
         * was left, which on a full name and number is not enough. Everything about the
         * key comes down together - the air above and below the digit and both sizes of
         * type - because a key with the same type on it in a shorter box is not a smaller
         * key, it is a cramped one.
         */
        const val KEY_PAD_DP = 9
        const val KEY_DIGIT_SP = 22.5f
        const val KEY_LETTERS_SP = 10.5f

        /**
         * How tall a digit key is drawn.
         *
         * Said rather than left to the words on it, because the star and the hash have no
         * words: a key that took its height from its contents was a key that stood shorter
         * than its neighbours the moment the line of letters under the digit went away, so
         * the line was kept open and empty and their glyphs sat above the middle of their
         * own keys ever after. With the height fixed, the letters can simply go, and every
         * key centres what is actually on it.
         *
         * The number is what the four rows come to at these sizes, with the air above and
         * below the digit that [KEY_PAD_DP] asks for.
         */
        const val KEY_DP = 60

        /**
         * A face beside somebody the keypad found, and the row it sits in.
         *
         * ContactList's own numbers - see ContactList.FACE_DP and its ROW_DP, which is
         * this padding either side of this square. Two lists in one shell whose rows are
         * a different height with their faces at different sizes are two lists that
         * merely resemble each other.
         */
        const val MATCH_FACE_DP = 42
        const val MATCH_FACE_SP = 15f
        const val MATCH_ROW_PAD_DP = 10

        val KEYS = listOf(
            listOf(Key('1', ""), Key('2', "ABC"), Key('3', "DEF")),
            listOf(Key('4', "GHI"), Key('5', "JKL"), Key('6', "MNO")),
            listOf(Key('7', "PQRS"), Key('8', "TUV"), Key('9', "WXYZ")),
            listOf(Key('*', ""), Key('0', "+", held = '+'), Key('#', ""))
        )

        const val TAG = "WP81People"
    }
}
