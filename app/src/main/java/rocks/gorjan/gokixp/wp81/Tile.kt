package rocks.gorjan.gokixp.wp81

/**
 * The three tile footprints Windows Phone 8.1 offered, measured in small-tile cells.
 *
 * The Start screen is a four-column grid of small cells, so a medium tile is a 2x2
 * block and a wide tile spans the full row. Long-pressing a tile and tapping the
 * resize chevron cycles through them in this order.
 */
enum class TileSize(val cols: Int, val rows: Int) {
    SMALL(1, 1),

    // One-row strips, two to four cells across. Wide enough for a line of text and a
    // control beside it, without giving up a whole band of the grid to one tile.
    SMALL_WIDE(2, 1),
    SMALL_WIDE_3(3, 1),
    SMALL_WIDE_4(4, 1),

    MEDIUM(2, 2),

    // Two columns run down the page, three and four rows deep. The counterpart to the
    // strips: those spend a whole band of the grid on one line, these spend half the
    // width on a column of it, which is what a run of pictures or a list wants.
    MEDIUM_TALL_3(2, 3),
    MEDIUM_TALL_4(2, 4),

    WIDE(4, 2);

    fun next(): TileSize = when (this) {
        SMALL -> SMALL_WIDE
        SMALL_WIDE -> SMALL_WIDE_3
        SMALL_WIDE_3 -> SMALL_WIDE_4
        SMALL_WIDE_4 -> MEDIUM
        MEDIUM -> MEDIUM_TALL_3
        MEDIUM_TALL_3 -> MEDIUM_TALL_4
        MEDIUM_TALL_4 -> WIDE
        WIDE -> SMALL
    }

    /**
     * Whether the tile has room for words as well as a glyph.
     *
     * Only the 1x1 tile does not: everything else can carry at least a label, and a
     * notification or a track title cropped to one line.
     */
    val canShowText: Boolean
        get() = this != SMALL

    /**
     * Whether there is room for a subtitle under the title.
     *
     * True of everything but the 1x1 tile: a one-row strip is short, but two compact lines
     * still fit once the app-name label steps aside for them - see [isStrip].
     */
    val hasTwoTextLines: Boolean
        get() = this != SMALL

    /**
     * A one-row tile.
     *
     * Its height is spoken for by the content, so the app-name label along the bottom
     * gives way whenever a notification or a track is being shown.
     */
    val isStrip: Boolean
        get() = rows == 1 && this != SMALL

    companion object {
        fun fromName(name: String?): TileSize =
            entries.firstOrNull { it.name == name } ?: MEDIUM

        /**
         * The size a resize drag of this many cells is asking for.
         *
         * Width alone does not name a size - the banner and the medium tile are both two
         * cells across - so the drag reads both. One row gives the strips, which differ
         * only in width; two columns gives the square tile and the two tall ones, which
         * differ only in height; anything wider is the wide tile.
         */
        fun forSpan(cols: Int, rows: Int): TileSize = when {
            rows <= 1 -> when {
                cols <= 1 -> SMALL
                cols <= 2 -> SMALL_WIDE
                cols <= 3 -> SMALL_WIDE_3
                else -> SMALL_WIDE_4
            }
            cols <= 2 -> when {
                rows <= 2 -> MEDIUM
                rows <= 3 -> MEDIUM_TALL_3
                else -> MEDIUM_TALL_4
            }
            else -> WIDE
        }
    }
}

/**
 * What a tile launches when tapped.
 *
 * Tiles are backed by the launcher's existing [rocks.gorjan.gokixp.DesktopIcon] list -
 * the desktop icons and folders the user already had become the Start screen - plus
 * anything they pin from the app list.
 */
data class Tile(
    /** Matches DesktopIcon.id for migrated icons, or the package name for pinned apps. */
    val id: String,
    val label: String,
    val packageName: String,
    var size: TileSize,
    /** Position in the packing order. Lower comes first. */
    var index: Int,
    val kind: Kind
) {
    enum class Kind {
        APP, SYSTEM_APP, FOLDER, MY_COMPUTER, RECYCLE_BIN, URL_SHORTCUT,

        /**
         * Built-in live widgets pinned to the top of Start.
         *
         * Unlike app tiles these render their content directly rather than an icon, are
         * always live, and cannot be unpinned - they are part of the shell, not the user's
         * arrangement. Fed from the same data the Quick Glance widget uses.
         */
        LIVE_CLOCK, LIVE_CALENDAR, LIVE_AQI, LIVE_WEATHER, LIVE_NEWS,

        /**
         * The camera roll, turning over one picture at a time.
         *
         * The one live tile whose content the phone already has - and the one that shows
         * a picture rather than a reading, so its faces carry no words and no wash. See
         * PhotoFeed.
         */
        LIVE_PHOTOS,

        /**
         * Welcome, which the shell provides rather than the user pinning.
         *
         * Not a live widget - it has an icon like any program and only speaks up when
         * there is an update - but part of the shell all the same: it is where the release
         * notes are, and a launcher that had just updated itself and left no way to find
         * out what changed would be hiding the one thing worth reading.
         */
        WELCOME,

        /**
         * The launcher's own settings (Display Properties).
         *
         * Also a built-in, for a blunt reason: the desktop themes reach settings by
         * right-clicking the wallpaper or via the Start menu, and the phone shell has
         * neither. Without a tile there is no way in at all.
         */
        SETTINGS;

        val isLiveWidget: Boolean
            get() = this == LIVE_CLOCK || this == LIVE_CALENDAR ||
                this == LIVE_AQI || this == LIVE_WEATHER || this == LIVE_NEWS ||
                this == LIVE_PHOTOS

        /**
         * Tiles the shell provides rather than the user pinning them.
         *
         * They can be moved and resized but not removed: they are rebuilt on every
         * refresh, so unpinning one would only make it reappear.
         */
        val isBuiltIn: Boolean
            get() = isLiveWidget || this == WELCOME || this == SETTINGS
    }
}
