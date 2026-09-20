package rocks.gorjan.gokixp.apps.briefcase

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Briefcase itself: one folder on this phone kept in step with one folder on a winos
 * computer.
 *
 * There is one of these for the whole app. It owns the mirror on disk, the row for each file
 * ([BriefcaseStore]), and the only thread any of it happens on; the window above it does
 * nothing but ask and listen. Every public call returns at once and answers on the main
 * thread.
 *
 * The rhythm is section 2.3's: ask `/rev`, which is a few bytes, and only fetch the listing
 * when the number has moved. Nothing is asked of a server the person is not looking at -
 * [watch] is what says they are.
 */
class Briefcase private constructor(context: Context) {

    enum class Status {
        /** No computer has been connected, or the token has been thrown away. */
        NOT_CONNECTED,

        /** Everything here is what is there. */
        UP_TO_DATE,

        /** A round is running: something is going up, coming down, or being counted. */
        SYNCING,

        /** There is something to send, and the phone is on mobile data with leave to use it withheld. */
        WAITING_FOR_WIFI,

        /** The last round did not reach the computer. Keep what we have and try later. */
        OFFLINE
    }

    /** Everything the window draws, handed over in one piece so it can never be half-read. */
    data class Snapshot(
        val connected: Boolean,
        val site: String,
        val user: String,
        val status: Status,
        /** The last thing worth telling the person, or null. */
        val message: String?,
        val entries: List<BriefcaseEntry>,
        /** Names of the files a transfer is happening to right now. */
        val busy: Set<String>
    ) {
        fun stateOf(entry: BriefcaseEntry): BriefcaseFileState = entry.state(entry.name in busy)
    }

    fun interface Listener {
        fun onBriefcaseChanged(snapshot: Snapshot)
    }

    private val appContext = context.applicationContext
    val store = BriefcaseStore(appContext)

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "briefcase-sync").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val running = AtomicBoolean(false)

    @Volatile private var entries: List<BriefcaseEntry> = store.entries()
    @Volatile private var busy: Set<String> = emptySet()
    @Volatile private var status: Status =
        if (store.isConnected) Status.UP_TO_DATE else Status.NOT_CONNECTED
    @Volatile private var message: String? = null

    /**
     * The open Briefcase folders, each answering whether the person can actually see it.
     * Main thread only.
     */
    private val watchers = mutableListOf<() -> Boolean>()
    private var lastResumeSync = 0L

    /**
     * Told about files that arrived from the computer, so the shell can say so when nobody
     * has the folder open.
     */
    var onFilesArrived: ((List<String>) -> Unit)? = null

    // ---- What the window reads ---------------------------------------------------------------

    fun snapshot(): Snapshot = Snapshot(
        connected = store.isConnected,
        site = store.site,
        user = store.user,
        status = status,
        message = message,
        entries = entries.sortedBy { it.name.lowercase() },
        busy = busy
    )

    fun addListener(listener: Listener) {
        if (listener !in listeners) listeners.add(listener)
        listener.onBriefcaseChanged(snapshot())
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun folder(): File = store.folder()

    /** The copy on the phone, or null when there is not one yet. */
    fun localFile(entry: BriefcaseEntry): File? =
        entry.file(store.folder()).takeIf { it.isFile }

    // ---- Watching ------------------------------------------------------------------------

    /**
     * Says a Briefcase folder is open, and hands over the way to ask whether it is actually
     * in front of the person - a minimised window is not.
     *
     * Balance every call with [unwatch], passing the same [isVisible]. While at least one
     * watcher is registered the revision is polled, and when the last goes the polling stops:
     * section 2.3 is clear that a server nobody is looking at should be left alone.
     */
    fun watch(isVisible: () -> Boolean) {
        main.post {
            watchers.add(isVisible)
            if (watchers.size == 1) main.postDelayed(pollRunnable, POLL_MS)
            sync(force = false)
        }
    }

    fun unwatch(isVisible: () -> Boolean) {
        main.post {
            watchers.remove(isVisible)
            if (watchers.isEmpty()) main.removeCallbacks(pollRunnable)
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (watchers.isEmpty()) return
            if (watchers.any { it() }) sync(force = false)
            main.postDelayed(this, POLL_MS)
        }
    }

    /**
     * One catch-up round when the launcher comes back to the front, at most once a minute.
     * This is the only syncing that happens with the folder closed, and it is what lets the
     * shell say "a file arrived" without polling all day.
     */
    fun syncOnResume() {
        if (!store.isConnected) return
        val now = System.currentTimeMillis()
        if (now - lastResumeSync < RESUME_INTERVAL_MS) return
        lastResumeSync = now
        sync(force = false)
    }

    // ---- Connecting ------------------------------------------------------------------------

    /**
     * Section 1.2: the code the computer is showing, under **Briefcase ▸ Connect a Phone…**.
     *
     * [onDone] is handed the site's name on success, or the reason it did not work.
     */
    fun connectWithCode(host: String, code: String, onDone: (Result<String>) -> Unit) {
        connect(host, onDone) { client ->
            client.pair(code, deviceName(), platform())
        }
    }

    /** Section 1.3: the account's own name and password. The password is never written down. */
    fun connectWithPassword(host: String, user: String, password: String, onDone: (Result<String>) -> Unit) {
        connect(host, onDone) { client ->
            client.logon(user, password, deviceName(), platform())
        }
    }

    private fun connect(
        host: String,
        onDone: (Result<String>) -> Unit,
        pair: (BriefcaseClient) -> BriefcasePairing
    ) {
        val origin = BriefcaseClient.normalizeHost(host)
        worker.execute {
            try {
                if (origin.isEmpty()) {
                    throw BriefcaseException(
                        BriefcaseProtocol.CODE_BAD_REQUEST, 0, "Type the address the computer is showing."
                    )
                }
                if (!BriefcaseClient.isSecure(origin)) {
                    // Section 6: over plain HTTP the token and every file are in the open.
                    throw BriefcaseException(
                        BriefcaseProtocol.CODE_BAD_REQUEST, 0,
                        "The Briefcase will only connect over https, so nobody can read your files on the way."
                    )
                }

                val hello = BriefcaseClient(origin).hello()
                if (!hello.isWinos) {
                    throw BriefcaseException(
                        BriefcaseProtocol.CODE_NOT_WINOS, 0,
                        "That address answered, but it is not a winos computer."
                    )
                }
                if (hello.protocol != BriefcaseProtocol.VERSION) {
                    throw BriefcaseException(
                        BriefcaseProtocol.CODE_BAD_REQUEST, 0,
                        "That computer speaks version ${hello.protocol} of the Briefcase and this phone speaks " +
                            "version ${BriefcaseProtocol.VERSION}. One of them needs updating."
                    )
                }

                val pairing = pair(BriefcaseClient(origin))
                store.remember(origin, pairing)
                store.lastHost = origin
                store.site = pairing.site.ifEmpty { hello.site }
                message = null
                publish(Status.SYNCING)

                // Everything already in the folder belongs to this computer now: a Briefcase
                // is a folder for sharing, so what is in it goes up on the first round.
                entries = entries.map { if (it.id == null && it.isDownloaded) it.copy(pending = true) else it }
                store.saveEntries(entries)

                // Connected is connected: say so now, and let the first round - which may be
                // a folder full of files - happen behind the folder that is already open.
                main.post { onDone(Result.success(store.site)) }
                runRound(full = true)
            } catch (e: BriefcaseException) {
                main.post { onDone(Result.failure(e)) }
            } catch (e: Exception) {
                Log.e(TAG, "Could not connect to $origin", e)
                main.post {
                    onDone(Result.failure(BriefcaseException(BriefcaseProtocol.CODE_SERVER_ERROR, 0, "Could not connect.", cause = e)))
                }
            }
        }
    }

    /**
     * Section 1.6. Tells the computer to forget this phone, so its Connected Devices list
     * does not fill up with phones nobody has any more, and keeps the copies that are here.
     */
    fun disconnect(onDone: (Result<Unit>) -> Unit) {
        val client = client()
        worker.execute {
            try {
                client?.deleteSession()
            } catch (e: Exception) {
                // The token may already be dead at the other end; forgetting it here is the
                // point of the exercise either way.
                Log.w(TAG, "The computer did not answer the goodbye", e)
            }
            store.forget()
            entries = store.entries()
            busy = emptySet()
            message = null
            publish(Status.NOT_CONNECTED)
            main.post { onDone(Result.success(Unit)) }
        }
    }

    // ---- The files -------------------------------------------------------------------------

    /**
     * Puts a copy of [source] in the Briefcase and sends it up.
     *
     * [name] is what it should be called; a name already in the folder gets "Copy of" in
     * front of it, the same rule the server uses for a name already taken.
     */
    fun addFile(source: File, name: String = source.name, onDone: (Result<String>) -> Unit = {}) {
        worker.execute {
            try {
                val chosen = freeName(sanitize(name))
                val destination = File(store.folder(), chosen)
                source.copyTo(destination, overwrite = false)
                noteLocalFile(destination)
                main.post { onDone(Result.success(chosen)) }
                runRound(full = false)
            } catch (e: Exception) {
                Log.e(TAG, "Could not put ${source.name} in the Briefcase", e)
                main.post {
                    onDone(Result.failure(BriefcaseException(BriefcaseProtocol.CODE_BAD_REQUEST, 0, "Could not put that file in the Briefcase.", cause = e)))
                }
            }
        }
    }

    /** The same, for something shared into the launcher, which arrives as a stream. */
    fun addStream(open: () -> java.io.InputStream?, name: String, onDone: (Result<String>) -> Unit = {}) {
        worker.execute {
            try {
                val chosen = freeName(sanitize(name))
                val destination = File(store.folder(), chosen)
                val stream = open() ?: throw IllegalStateException("nothing to read")
                stream.use { input -> destination.outputStream().use { input.copyTo(it) } }
                noteLocalFile(destination)
                main.post { onDone(Result.success(chosen)) }
                runRound(full = false)
            } catch (e: Exception) {
                Log.e(TAG, "Could not put $name in the Briefcase", e)
                main.post {
                    onDone(Result.failure(BriefcaseException(BriefcaseProtocol.CODE_BAD_REQUEST, 0, "Could not put that file in the Briefcase.", cause = e)))
                }
            }
        }
    }

    /**
     * Makes sure there is a copy on the phone, fetching it if there is not, and hands back
     * the file to open.
     */
    fun fetch(entry: BriefcaseEntry, onDone: (Result<File>) -> Unit) {
        val local = entry.file(store.folder())
        if (local.isFile) {
            // There is already a copy here. Whatever has happened to it since, it is the
            // person's copy - opening a file is not an occasion to write over it.
            onDone(Result.success(local))
            return
        }
        val client = client() ?: run {
            onDone(Result.failure(notConnected()))
            return
        }
        val id = entry.id ?: run {
            onDone(Result.failure(notConnected()))
            return
        }

        markBusy(entry.name, true)
        worker.execute {
            try {
                client.download(id, local)
                replaceEntry(agree(entry, local))
                markBusy(entry.name, false)
                main.post { onDone(Result.success(local)) }
            } catch (e: BriefcaseException) {
                markBusy(entry.name, false)
                noteFailure(e)
                main.post { onDone(Result.failure(e)) }
            }
        }
    }

    /** Section 2.8. The computer decides the final name; the mirror takes what it is given. */
    fun rename(entry: BriefcaseEntry, newName: String, onDone: (Result<String>) -> Unit) {
        val wanted = sanitize(newName)
        if (wanted.isEmpty()) {
            onDone(Result.failure(BriefcaseException(BriefcaseProtocol.CODE_BAD_REQUEST, 0, "A file needs a name.")))
            return
        }
        if (wanted.length > store.limits.nameChars) {
            onDone(Result.failure(BriefcaseException(
                BriefcaseProtocol.CODE_BAD_REQUEST, 0,
                "That name is longer than the ${store.limits.nameChars} characters the Briefcase allows."
            )))
            return
        }

        worker.execute {
            try {
                val client = client()
                val landed = if (entry.id != null && client != null) {
                    client.rename(entry.id, wanted).name
                } else {
                    freeName(wanted)
                }

                val from = entry.file(store.folder())
                val to = File(store.folder(), landed)
                if (from.isFile) from.renameTo(to)

                val renamed = entry.copy(name = landed).let { if (to.isFile) measure(it, to) else it }
                replaceEntry(renamed, replacing = entry.name)
                main.post { onDone(Result.success(landed)) }
                runRound(full = false)
            } catch (e: BriefcaseException) {
                noteFailure(e)
                main.post { onDone(Result.failure(e)) }
            }
        }
    }

    /**
     * Section 2.9. It goes from the Briefcase on every device, including the computer, and
     * not into anybody's Recycle Bin - which is why the window asks first.
     */
    fun delete(entry: BriefcaseEntry, onDone: (Result<Unit>) -> Unit) {
        markBusy(entry.name, true)
        worker.execute {
            try {
                val client = client()
                if (entry.id != null && client != null) client.delete(entry.id)
                entry.file(store.folder()).delete()
                entries = entries.filterNot { it.name == entry.name }
                store.saveEntries(entries)
                markBusy(entry.name, false)
                publish(status)
                main.post { onDone(Result.success(Unit)) }
            } catch (e: BriefcaseException) {
                markBusy(entry.name, false)
                noteFailure(e)
                main.post { onDone(Result.failure(e)) }
            }
        }
    }

    // ---- Syncing -----------------------------------------------------------------------------

    /**
     * One round. [force] asks for the whole listing rather than what has changed since the
     * revision we hold, which is what "Update All" means and what section 3 asks for when a
     * stored revision may be too old to have tombstones for.
     */
    fun sync(force: Boolean, onDone: ((BriefcaseException?) -> Unit)? = null) {
        worker.execute {
            val failure = runRound(full = force)
            if (onDone != null) main.post { onDone(failure) }
        }
    }

    /**
     * The round itself, on the worker thread. Returns what went wrong, if anything.
     *
     * Order matters: what is on the disk here is counted first, then anything waiting goes
     * up, and only then is the computer asked what it has. An upload that collides with a
     * change made over there is settled before the listing arrives to confuse it.
     */
    private fun runRound(full: Boolean): BriefcaseException? {
        if (!running.compareAndSet(false, true)) return null
        try {
            scanFolder()

            if (!store.isConnected) {
                publish(Status.NOT_CONNECTED)
                return null
            }

            // Section 5.7: on mobile data with leave withheld, nothing is asked of the
            // computer at all - not a listing, and certainly not a hundred megabytes.
            if (!mayUseNetwork()) {
                publish(Status.WAITING_FOR_WIFI)
                return null
            }

            publish(Status.SYNCING)
            val client = client() ?: return null

            val changed = sendPending(client)

            val revision = try {
                client.rev()
            } catch (e: BriefcaseException) {
                return noteFailure(e)
            }

            if (full || changed || revision != store.rev) {
                val since = if (full || store.rev < 0) null else store.rev
                val listing = try {
                    client.files(since)
                } catch (e: BriefcaseException) {
                    return noteFailure(e)
                }
                applyListing(listing, full, client)
                store.rev = listing.rev
            }

            message = null
            publish(Status.UP_TO_DATE)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "The sync round fell over", e)
            return null
        } finally {
            running.set(false)
        }
    }

    /**
     * Brings the index into line with what is actually in the folder.
     *
     * A file dropped in from anywhere - a file manager, the share sheet, My Computer - is a
     * file the person meant to share, so it becomes a row waiting to go up. A file that has
     * been edited here is the same. A file that has disappeared is left as a row with no
     * copy, never as a deletion: taking something off the computer is something the person
     * asks for, not something a missing file decides.
     */
    private fun scanFolder() {
        val folder = store.folder()
        val onDisk = folder.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") }
            ?.associateBy { it.name }
            ?: emptyMap()

        val updated = mutableListOf<BriefcaseEntry>()
        val seen = mutableSetOf<String>()

        entries.forEach { entry ->
            val file = onDisk[entry.name]
            seen.add(entry.name)
            if (file == null) {
                updated.add(if (entry.isDownloaded) entry.notDownloaded() else entry)
                return@forEach
            }
            if (!entry.isMeasurementStale(file)) {
                updated.add(entry)
                return@forEach
            }
            val measured = measure(entry, file)
            updated.add(measured.copy(pending = measured.isModifiedHere || measured.id == null))

        }

        onDisk.keys.filterNot { it in seen }.forEach { name ->
            val file = onDisk[name] ?: return@forEach
            val entry = BriefcaseEntry(
                id = null, name = name, size = file.length(), serverSha = "",
                modified = file.lastModified() / 1000L
            )
            updated.add(measure(entry, file).copy(pending = true))
        }

        entries = updated
        store.saveEntries(entries)
    }

    /** Sends everything waiting. Returns whether anything actually went. */
    private fun sendPending(client: BriefcaseClient): Boolean {
        var sent = false
        entries.filter { it.pending && it.isDownloaded }.forEach { entry ->
            val file = entry.file(store.folder())
            if (!file.isFile) {
                replaceEntry(entry.copy(pending = false))
                return@forEach
            }
            if (file.length() > store.limits.fileBytes) {
                message = "'${entry.name}' is bigger than the ${readableSize(store.limits.fileBytes)} " +
                    "one file may be, so it stays on the phone."
                replaceEntry(entry.copy(pending = false))
                return@forEach
            }

            markBusy(entry.name, true)
            try {
                val sha = entry.localSha ?: BriefcaseClient.sha256(file)
                val saved = if (entry.id == null) {
                    // Section 2.6: contents are stored once per set of bytes, so bytes the
                    // computer already holds are claimed by hash rather than sent again.
                    val known = try {
                        client.have(listOf(sha)).contains(sha)
                    } catch (e: BriefcaseException) {
                        false
                    }
                    if (known) {
                        try {
                            client.uploadKnown(entry.name, sha)
                        } catch (e: BriefcaseException) {
                            if (e.code == BriefcaseProtocol.CODE_UNKNOWN_CONTENTS) client.upload(entry.name, file) else throw e
                        }
                    } else {
                        client.upload(entry.name, file)
                    }
                } else {
                    // Section 2.7: the version this copy was made from, so a change nobody
                    // here has seen is not written over.
                    client.replace(entry.id, file, ifMatch = entry.base)
                }

                val landed = File(store.folder(), saved.name)
                if (saved.name != entry.name && file.isFile) file.renameTo(landed)
                val current = if (landed.isFile) landed else file
                replaceEntry(
                    entry.copy(
                        id = saved.id, name = saved.name, size = saved.size,
                        serverSha = saved.sha256, modified = saved.modified
                    ).agreed(current, saved.sha256),
                    replacing = entry.name
                )
                sent = true
            } catch (e: BriefcaseException) {
                when (e.code) {
                    BriefcaseProtocol.CODE_CHANGED -> keepBothCopies(entry)
                    BriefcaseProtocol.CODE_NOT_FOUND -> {
                        // Somebody took it off the computer while this was waiting; what is
                        // here is now the phone's own copy, to be sent up again as new.
                        replaceEntry(entry.orphaned().copy(pending = true))
                    }
                    BriefcaseProtocol.CODE_NO_SPACE, BriefcaseProtocol.CODE_TOO_LARGE -> {
                        noteFailure(e)
                        replaceEntry(entry.copy(pending = false))
                    }
                    else -> noteFailure(e)
                }
            } finally {
                markBusy(entry.name, false)
            }
        }
        return sent
    }

    /**
     * What to do when both ends changed the same file.
     *
     * Nothing is thrown away and nobody is asked to decide in a hurry: the copy made here
     * becomes "Copy of …" - the computer's own word for a name already taken - and goes up as
     * a file of its own, leaving the computer's version to come down under the original name.
     */
    private fun keepBothCopies(entry: BriefcaseEntry) {
        val file = entry.file(store.folder())
        val copyName = freeName("Copy of ${entry.name}")
        val copy = File(store.folder(), copyName)
        if (!file.isFile || !file.renameTo(copy)) return

        val mine = measure(
            BriefcaseEntry(
                id = null, name = copyName, size = copy.length(), serverSha = "",
                modified = copy.lastModified() / 1000L
            ),
            copy
        ).copy(pending = true)

        entries = entries.map { if (it.name == entry.name) it.notDownloaded() else it } + mine
        store.saveEntries(entries)
        message = "'${entry.name}' changed on the computer as well, so your version is now '$copyName'."
        publish(status)
    }

    /** Applies a listing: what has gone first, then what is new or different. */
    private fun applyListing(listing: BriefcaseListing, full: Boolean, client: BriefcaseClient) {
        val byId = entries.filter { it.id != null }.associateBy { it.id!! }
        var updated = entries

        // Section 2.2: apply `deleted` first. A full listing says what is there, so anything
        // we hold an id for that is not in it has gone the same way.
        val gone = if (full) {
            val present = listing.files.map { it.id }.toSet()
            byId.keys.filterNot { it in present }
        } else {
            listing.deleted
        }
        gone.forEach { id ->
            val entry = byId[id] ?: return@forEach
            if (entry.isModifiedHere) {
                // Changed here since the two ends agreed: the copy stays, as the phone's own.
                updated = updated.map { if (it.name == entry.name) it.orphaned() else it }
            } else {
                entry.file(store.folder()).delete()
                updated = updated.filterNot { it.name == entry.name }
            }
        }

        // What to fetch is decided here, while the row still remembers the version the two
        // ends agreed on. Once `serverSha` has been written over, "the computer changed this"
        // and "I changed this" look exactly alike.
        val toFetch = mutableSetOf<String>()
        val arrived = mutableListOf<String>()

        listing.files.forEach { file ->
            val known = updated.firstOrNull { it.id == file.id }

            if (known == null) {
                // A name the phone is already using gives way: the computer's file owns it.
                val clash = updated.firstOrNull { it.name == file.name }
                if (clash != null) {
                    val moved = freeName("Copy of ${clash.name}")
                    val from = clash.file(store.folder())
                    if (from.isFile) from.renameTo(File(store.folder(), moved))
                    updated = updated.map { if (it.name == clash.name) it.copy(name = moved) else it }
                }

                updated = updated + BriefcaseEntry(
                    id = file.id, name = file.name, size = file.size,
                    serverSha = file.sha256, modified = file.modified
                )
                arrived.add(file.name)

                // Section 5.3: small files eagerly on wifi, the rest when they are opened.
                if (file.size <= EAGER_BYTES && !isMetered()) toFetch.add(file.name)
                return@forEach
            }

            val changedThere = known.serverSha != file.sha256 && file.sha256 != known.base
            val renamed = known.name != file.name

            var next = known.copy(
                size = file.size, serverSha = file.sha256, modified = file.modified
            )
            if (renamed) {
                val from = known.file(store.folder())
                if (from.isFile) from.renameTo(File(store.folder(), file.name))
                next = next.copy(name = file.name)
            }

            when {
                !changedThere -> Unit

                // Only the computer's copy moved on: refresh the copy here, and fetch one
                // the phone does not have yet if it is small enough not to be worth asking
                // about - the same rule a file arriving for the first time gets.
                !known.isModifiedHere ->
                    if (known.isDownloaded || (file.size <= EAGER_BYTES && !isMetered())) {
                        toFetch.add(next.name)
                    }

                // Both ends changed it. Nothing is thrown away and nobody has to decide in a
                // hurry: the copy made here becomes "Copy of ..." - the computer's own word
                // for a name already taken - and goes up as a file of its own, leaving the
                // computer's version to come down under the original name.
                else -> {
                    val mine = freeName("Copy of ${next.name}")
                    val from = next.file(store.folder())
                    if (from.isFile && from.renameTo(File(store.folder(), mine))) {
                        val copy = File(store.folder(), mine)
                        updated = updated + measure(
                            BriefcaseEntry(
                                id = null, name = mine, size = copy.length(), serverSha = "",
                                modified = copy.lastModified() / 1000L
                            ),
                            copy
                        ).copy(pending = true)
                        message = "'${next.name}' changed on the computer as well, so your version is now '$mine'."
                    }
                    next = next.notDownloaded()
                    toFetch.add(next.name)
                }
            }

            val was = known.name
            updated = updated.map { if (it.name == was) next else it }
        }

        entries = updated
        store.saveEntries(entries)

        toFetch.forEach { name ->
            val entry = entries.firstOrNull { it.name == name } ?: return@forEach
            val id = entry.id ?: return@forEach
            val file = entry.file(store.folder())

            markBusy(name, true)
            try {
                client.download(id, file)
                replaceEntry(agree(entry, file))
            } catch (e: BriefcaseException) {
                noteFailure(e)
            } finally {
                markBusy(name, false)
            }
        }

        if (arrived.isNotEmpty()) main.post { onFilesArrived?.invoke(arrived) }
    }

    // ---- Bookkeeping ---------------------------------------------------------------------

    private fun client(): BriefcaseClient? {
        val token = store.token ?: return null
        val host = store.host
        if (host.isEmpty()) return null
        return BriefcaseClient(host, token)
    }

    /** The row after a fetch: what is here is what is there, and that is the new base. */
    private fun agree(entry: BriefcaseEntry, file: File): BriefcaseEntry {
        val sha = BriefcaseClient.sha256(file)
        return entry.agreed(file, sha).copy(serverSha = sha)
    }

    private fun measure(entry: BriefcaseEntry, file: File): BriefcaseEntry =
        entry.measured(file, BriefcaseClient.sha256(file)).copy(
            size = if (entry.id == null) file.length() else entry.size
        )

    /** Records a file that has just appeared in the folder and starts it on its way up. */
    private fun noteLocalFile(file: File) {
        val entry = measure(
            BriefcaseEntry(
                id = null, name = file.name, size = file.length(), serverSha = "",
                modified = file.lastModified() / 1000L
            ),
            file
        ).copy(pending = true)
        entries = entries.filterNot { it.name == file.name } + entry
        store.saveEntries(entries)
        publish(status)
    }

    private fun replaceEntry(entry: BriefcaseEntry, replacing: String = entry.name) {
        entries = entries.map { if (it.name == replacing) entry else it }
        store.saveEntries(entries)
        publish(status)
    }

    private fun markBusy(name: String, active: Boolean) {
        busy = if (active) busy + name else busy - name
        publish(status)
    }

    /**
     * Remembers why a round stopped. A network failure is not an error worth a dialog -
     * section 4 - so it only changes what the status bar says.
     */
    private fun noteFailure(e: BriefcaseException): BriefcaseException {
        if (e.isAuthFailure) {
            // Section 1.4: the token is dead. Throw it away rather than retry with it.
            store.forget()
            entries = store.entries()
            message = "The computer no longer knows this phone. Connect it again."
            publish(Status.NOT_CONNECTED)
            return e
        }
        message = if (e.isNetworkFailure) null else e.message
        publish(Status.OFFLINE)
        return e
    }

    private fun publish(next: Status) {
        status = if (store.isConnected) next else Status.NOT_CONNECTED
        val snapshot = snapshot()
        main.post { listeners.forEach { it.onBriefcaseChanged(snapshot) } }
    }

    /** Section 5.7: not on mobile data unless the person has said so. */
    private fun mayUseNetwork(): Boolean = !isMetered() || store.syncOnMobileData

    private fun isMetered(): Boolean {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** A name nothing in the folder is using, with "Copy of" in front as many times as it takes. */
    private fun freeName(name: String): String {
        val folder = store.folder()
        var candidate = name
        var guard = 0
        while ((File(folder, candidate).exists() || entries.any { it.name == candidate }) && guard < 50) {
            candidate = "Copy of $candidate"
            guard++
        }
        return candidate.take(store.limits.nameChars)
    }

    /** The characters Windows will not have in a file name, the way section 2.8 replaces them. */
    private fun sanitize(name: String): String =
        name.map { if (it in ILLEGAL || it.code < 0x20) '_' else it }
            .joinToString("")
            .trim()
            .ifEmpty { "Untitled" }

    private fun notConnected() = BriefcaseException(
        BriefcaseProtocol.CODE_UNAUTHORIZED, 0, "This phone is not connected to a computer."
    )

    private fun deviceName(): String =
        store.deviceName.ifEmpty { "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim() }

    private fun platform(): String = "Android ${Build.VERSION.RELEASE}"

    companion object {
        private const val TAG = "Briefcase"

        /** Section 2.3, first row: the folder is open and in front of the person. */
        private const val POLL_MS = 5000L

        /** How often coming back to the launcher is allowed to cost a round trip. */
        private const val RESUME_INTERVAL_MS = 60_000L

        /** Section 5.3: fetch small files eagerly on wifi; leave the big ones until asked. */
        private const val EAGER_BYTES = 4L * 1024 * 1024

        private val ILLEGAL = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

        @Volatile private var instance: Briefcase? = null

        fun get(context: Context): Briefcase =
            instance ?: synchronized(this) {
                instance ?: Briefcase(context).also { instance = it }
            }

        /** "918 KB", for the sentences that have to name a size. */
        fun readableSize(bytes: Long): String = when {
            bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
            bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
            bytes >= 1024L -> "${bytes / 1024} KB"
            else -> "$bytes bytes"
        }
    }
}
