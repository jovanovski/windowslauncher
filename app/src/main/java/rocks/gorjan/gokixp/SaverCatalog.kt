package rocks.gorjan.gokixp

/**
 * The screen savers the Screen Saver list offers, in the order Windows 98 SE showed them:
 * the twelve .scr files it installed, then the two this launcher adds of its own.
 *
 * Everything from 3D Flower Box to Scrolling Marquee is drawn live in a WebView by the
 * savers ported from winos - see app/src/main/assets/screensavers/. Underwater is the
 * bundled aquarium video, and Custom... plays a video the user picked.
 */
object SaverCatalog {

    /** How [ScreensaverManager] puts a saver on the screen. */
    enum class Kind { NONE, WEB, VIDEO, CUSTOM_VIDEO }

    data class Saver(val id: String, val label: String, val kind: Kind)

    const val NONE = "none"

    /** What a fresh install gets, and what an unknown id falls back to. */
    const val DEFAULT = "pipes"

    val all: List<Saver> = listOf(
        Saver(NONE, "(None)", Kind.NONE),
        Saver("flowerbox", "3D Flower Box", Kind.WEB),
        Saver("flyingobjects", "3D Flying Objects", Kind.WEB),
        Saver("maze", "3D Maze", Kind.WEB),
        Saver("pipes", "3D Pipes", Kind.WEB),
        Saver("text3d", "3D Text", Kind.WEB),
        Saver("blank", "Blank Screen", Kind.WEB),
        Saver("curves", "Curves and Colors", Kind.WEB),
        Saver("starfield", "Flying Through Space", Kind.WEB),
        Saver("flying", "Flying Windows", Kind.WEB),
        Saver("mystify", "Mystify Your Mind", Kind.WEB),
        Saver("marquee", "Scrolling Marquee", Kind.WEB),
        Saver("underwater", "Underwater", Kind.VIDEO),
        Saver("custom", "Custom...", Kind.CUSTOM_VIDEO)
    )

    /** The labels the Screen Saver spinner shows, in list order. */
    val labels: Array<String> = all.map { it.label }.toTypedArray()

    fun at(position: Int): Saver = all.getOrElse(position) { all.first() }

    fun byId(id: String?): Saver = all.firstOrNull { it.id == id }
        ?: all.first { it.id == DEFAULT }

    fun positionOf(id: String?): Int = all.indexOfFirst { it.id == id }
        .takeIf { it >= 0 } ?: all.indexOfFirst { it.id == DEFAULT }

    /**
     * What the int this setting used to be stored as means now. Before the winos savers
     * arrived the list was only (None), 3D Pipes and Underwater, and the spinner's position
     * went straight into SharedPreferences.
     */
    fun fromLegacyPosition(position: Int): String = when (position) {
        1 -> "pipes"
        2 -> "underwater"
        else -> NONE
    }
}
