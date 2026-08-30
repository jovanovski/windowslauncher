package rocks.gorjan.gokixp.apps.zune

/**
 * The one thing in this process allowed to be making sound.
 *
 * There are two players here and there have to be. The Music app owns one, because it is a
 * page with a transport on it and it lives and dies with its window; the car service owns
 * another, because a head unit talks to a service and there may be no window at all when
 * it does. What there must never be is *both at once* - and there was: two media sessions
 * from one package, a tile sending "play" to whichever of them the system happened to list
 * first, and the service answering by starting the whole library from the top underneath a
 * song the app was already playing. Neither could stop the other, because neither knew the
 * other existed.
 *
 * So they know now. Each registers what it is and how to go quiet; whichever is about to
 * make sound says so first, and everything else is silenced before it does. It is a rule
 * about the speaker rather than about either player, which is why it lives outside both -
 * and why it is a list rather than a pair: a third way to play something would be a third
 * registration and nothing else.
 */
object ZuneAudio {

    /** What can currently make sound, and how to stop it. Insertion-ordered for clarity. */
    private val engines = LinkedHashMap<Any, () -> Unit>()

    /**
     * Adds an engine, or replaces what a previous instance of it left behind.
     *
     * Keyed on the object rather than on a name: a window closed and reopened is a new
     * player, and the old one has already gone through [unregister] on its way out.
     */
    @Synchronized
    fun register(owner: Any, silence: () -> Unit) {
        engines[owner] = silence
    }

    @Synchronized
    fun unregister(owner: Any) {
        engines.remove(owner)
    }

    /**
     * Says that [owner] is about to play, and quiets everything that is not it.
     *
     * Called before the sound starts rather than after, so there is no moment with two of
     * them running - and called on every start, not only the first, because the other
     * engine may have begun in between.
     *
     * The callbacks are taken out of the map before they are run: silencing an engine can
     * end with it unregistering itself, and a map cannot be walked while it is changing.
     */
    fun claim(owner: Any) {
        val others = synchronized(this) {
            engines.filterKeys { it !== owner }.values.toList()
        }
        for (silence in others) silence()
    }
}
