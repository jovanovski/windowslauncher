package rocks.gorjan.gokixp.wp81

import android.content.Context
import rocks.gorjan.gokixp.DesktopIcon
import rocks.gorjan.gokixp.IconType
import rocks.gorjan.gokixp.MainActivity
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.theme.AppTheme
import rocks.gorjan.gokixp.theme.ThemeManager

/**
 * What goes on a Start screen, and how each tile is painted.
 *
 * This used to sit in MainActivity, which was fine while the phone was the only thing
 * showing tiles. The car screen shows the same wall on a second display, and the one
 * thing it must not do is keep its own idea of what is pinned and what colour it is -
 * a tile added or recoloured on the phone has to be on the car screen too, without
 * anyone remembering to add it twice.
 *
 * So the wall's *contents* live here, reachable from anywhere with a Context, while what
 * a tap does about them stays with the caller: opening a window is the launcher's job on
 * the phone, and on the car screen most tiles have nowhere to open at all.
 *
 * The seams are the things only the launcher can answer - its live icon list, whether a
 * rename is in force, and which way up the arrangement is being read. A read-only surface
 * such as the car passes no writers, so nothing it does can disturb the phone's wall.
 */
class WP81TileHost(
    private val context: Context,
    /** The launcher's live icon list, or a copy loaded from preferences. */
    private val icons: () -> MutableList<DesktopIcon>,
    /** Writes the icon list back. Left out by a surface that only reads. */
    private val saveIcons: () -> Unit = {},
    /** Writes a freshly numbered arrangement back. Left out by a read-only surface. */
    private val persistTiles: (List<Tile>) -> Unit = {},
    /** A name the user typed for this app, if they typed one. */
    private val displayName: (packageName: String, original: String) -> String =
        { _, original -> original },
    /**
     * Which of the two arrangements to read.
     *
     * The phone keeps one for each way it is held. The car screen is wide, and so looks
     * like the sideways one, but it is not the same wall: reading the landscape
     * arrangement there would tie the car's layout to a phone orientation the user may
     * never have arranged. It reads the upright one, which is the one that is always kept.
     */
    private val landscape: () -> Boolean = { false },
    /**
     * Built-in tiles this surface does not want.
     *
     * Defaults to the set the user hid on the phone, which is right for the phone and
     * wrong for the car: hiding the clock on a phone that already shows one in its status
     * bar says nothing about wanting no clock in the car, where there is no status bar to
     * read. A surface that wants its own selection passes it.
     */
    private val hiddenTiles: (ThemeManager) -> Set<String> = { it.getWP81HiddenTiles() }
) {

    private val themeManager = ThemeManager(context)

    private val prefs =
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

    /** Read once per rebuild rather than per tile: it parses a string every time. */
    private var tileColors: Map<String, Int> = emptyMap()

    /** Whether those colours are currently held back so the wallpaper shows. */
    private var tileColorsHidden = false

    /** Picks up colour settings. Call before building or repainting a wall. */
    fun refreshColors() {
        tileColors = themeManager.getWP81TileColors()
        tileColorsHidden = themeManager.getWP81HideTileColors()
    }

    /**
     * Where this icon sits on the wall being read, and how big.
     *
     * Reading sideways falls back to the upright placement while there is none of its own,
     * so the first turn of the phone lands on the wall the user already knows rather than
     * on an alphabetical one. Writing never falls back.
     */
    private var DesktopIcon.wp81TileIndex: Int?
        get() = if (landscape()) tileIndexLandscape ?: tileIndex else tileIndex
        set(value) {
            if (landscape()) tileIndexLandscape = value else tileIndex = value
        }

    private var DesktopIcon.wp81TileSize: String?
        get() = if (landscape()) tileSizeLandscape ?: tileSize else tileSize
        set(value) {
            if (landscape()) tileSizeLandscape = value else tileSize = value
        }

    fun buildTiles(): List<Tile> {
        val placements = loadBuiltInPlacements()
        val widgets = builtInTiles(placements)
        var migrated = false
        // Only what sits at the top level: icons filed inside a folder belong to that
        // folder's page, not to Start. The Recycle Bin and My Computer are desktop
        // furniture with no phone counterpart, so they are left off entirely - the icons
        // themselves survive for when the user switches back to a desktop theme.
        val ordered = icons().filter {
            it.parentFolderId == null &&
                it.type != IconType.RECYCLE_BIN &&
                it.type != IconType.MY_COMPUTER
        }.sortedWith(
            compareBy(
                { it.wp81TileIndex ?: Int.MAX_VALUE },
                { it.portraitGridIndex ?: Int.MAX_VALUE },
                { it.name.lowercase() }
            )
        )
        val tiles = ordered.mapIndexed { position, icon ->
            if (icon.wp81TileIndex == null) {
                icon.wp81TileIndex = position
                icon.wp81TileSize = TileSize.MEDIUM.name
                migrated = true
            }
            Tile(
                id = icon.id,
                label = displayName(icon.packageName, icon.name)
                    .replace("\\n", " ").replace("\n", " "),
                packageName = icon.packageName,
                size = TileSize.fromName(icon.wp81TileSize),
                index = icon.wp81TileIndex ?: position,
                kind = kindFor(icon)
            )
        }
        if (migrated) saveIcons()

        // Built-ins and user tiles share one ordering, sorted by their stored positions,
        // then renumbered densely so the packer sees a clean sequence.
        val all = (widgets + tiles).sortedBy { it.index }
        all.forEachIndexed { i, tile -> tile.index = i }

        // A first run has just invented positions for the built-ins; write them down now
        // so they are not re-invented on the next refresh.
        if (placements.size < widgets.size) persistTiles(all)
        return all
    }

    /**
     * The tiles the shell always provides, pinned above the user's own.
     *
     * All are part of the shell rather than the user's arrangement: they always show
     * current content and cannot be unpinned, so they are rebuilt on every refresh
     * instead of being persisted as desktop icons.
     */
    fun builtInTiles(
        placements: MutableMap<String, Pair<TileSize, Int>>
    ): List<Tile> {
        // Defaults, used only for a tile that has never been placed. Seeded with negative
        // indices so a first run sorts them ahead of any tiles the user already had; the
        // dense renumber that follows turns those into ordinary positions.
        val defaults = listOf(
            Triple(WIDGET_CLOCK, "Clock", Tile.Kind.LIVE_CLOCK),
            Triple(WIDGET_AQI, "Air quality", Tile.Kind.LIVE_AQI),
            Triple(WIDGET_WEATHER, "Weather", Tile.Kind.LIVE_WEATHER),
            Triple(WIDGET_CALENDAR, "Calendar", Tile.Kind.LIVE_CALENDAR),
            Triple(WIDGET_NEWS, "News", Tile.Kind.LIVE_NEWS),
            Triple(WIDGET_PHOTOS, "Photos", Tile.Kind.LIVE_PHOTOS),
            Triple(WIDGET_PEOPLE, "People", Tile.Kind.LIVE_PEOPLE),
            // Its id is the package the shell knows it by, so the tile picks up the update
            // through the same lookup an app's tile uses for its notifications.
            Triple("system.welcome", "Welcome", Tile.Kind.WELCOME)
        )
        // Drop any stored placement for the Settings tile, which no longer exists.
        placements.remove(WIDGET_SETTINGS)

        val hidden = hiddenTiles(themeManager)
        return defaults.filterNot { (id, _, _) -> id in hidden }
            .mapIndexed { position, (id, label, kind) ->
                val stored = placements[id]
                // A headline needs a line to itself; the calendar wants room for what is
                // on; and the People tile is a wall of faces, which is the tile Windows
                // Phone shipped across the full width - eighteen of them rather than nine.
                val defaultSize = when (kind) {
                    Tile.Kind.LIVE_CALENDAR, Tile.Kind.LIVE_NEWS,
                    Tile.Kind.LIVE_PEOPLE -> TileSize.WIDE
                    else -> TileSize.MEDIUM
                }
                Tile(
                    id = id,
                    label = label,
                    packageName = id,
                    size = stored?.first ?: defaultSize,
                    index = stored?.second ?: (position - defaults.size),
                    kind = kind
                )
            }
    }

    fun kindFor(icon: DesktopIcon): Tile.Kind =
        when (icon.type) {
            IconType.FOLDER -> Tile.Kind.FOLDER
            IconType.MY_COMPUTER -> Tile.Kind.MY_COMPUTER
            IconType.RECYCLE_BIN -> Tile.Kind.RECYCLE_BIN
            IconType.URL_SHORTCUT -> Tile.Kind.URL_SHORTCUT
            IconType.APP ->
                if (MainActivity.isSystemApp(icon.packageName)) Tile.Kind.SYSTEM_APP
                else Tile.Kind.APP
        }

    /**
     * What a tile should actually be painted, which is nothing while colours are hidden.
     *
     * Null rather than the accent: a tile with no colour of its own is a window onto the
     * Start background, where one painted the accent would be a solid block of it - and
     * seeing the wallpaper is the entire point of the switch. See TileView.onDraw.
     */
    fun colorFor(tile: Tile): Int? {
        if (tileColorsHidden) return null
        tileColors[tile.id]?.let { return it }
        // Failing one of its own, a tile filed in a folder wears the folder's: a folder is
        // one thing on the wall and opens into one band, and a row of tiles inside it in
        // the plain accent read as having escaped from somewhere else. Painting one of
        // them still overrides this - what the user set by hand is never guessed over.
        val parent = icons().firstOrNull { it.id == tile.id }?.parentFolderId ?: return null
        return tileColors[parent]
    }

    /** Whether the user opted in to air quality. */
    fun showAqi(): Boolean = prefs.getBoolean("show_aqi", false)

    /** The last air quality reading that was fetched, if there is one. */
    fun cachedAqi(): Int? = try {
        prefs.getInt("aqi_data", -1).takeIf { it >= 0 }
    } catch (e: Exception) {
        null
    }

    fun aqiLabel(aqi: Int): String = when {
        aqi <= 26 -> "Good"
        aqi <= 33 -> "Fair"
        aqi <= 66 -> "Moderate"
        aqi <= 100 -> "Poor"
        else -> "Very poor"
    }

    /**
     * What a built-in live tile has to say right now.
     *
     * Weather and news are absent on purpose: they show a run of faces rather than one
     * reading, and arrive through setLiveWidgetRotation instead.
     */
    fun liveContent(
        tile: Tile,
        calendarSummary: (TileSize) -> TileView.Reading? = { null }
    ): TileView.Reading? {
        val now = java.util.Date()
        val locale = java.util.Locale.getDefault()
        return when (tile.kind) {
            Tile.Kind.LIVE_CLOCK -> {
                // No leading zero on the hour: a tile is read at a glance rather than
                // lined up in a column, and "9:05" is how the time is said.
                val timePattern =
                    if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h:mm"
                val small = tile.size == TileSize.SMALL
                val weekday = java.text.SimpleDateFormat("EEE", locale).format(now)
                    .lowercase(locale)
                // On the 1x1 there is no room beside a time for anything, so the day goes
                // over it instead of next to it.
                TileView.Reading(
                    number = java.text.SimpleDateFormat(timePattern, locale).format(now),
                    caption = weekday.takeIf { small },
                    aside = if (small) null else weekday + "\n" +
                        java.text.SimpleDateFormat("d MMM", locale).format(now).lowercase(locale)
                )
            }

            Tile.Kind.LIVE_CALENDAR -> calendarSummary(tile.size)

            Tile.Kind.LIVE_AQI -> {
                val aqi = if (showAqi()) cachedAqi() else null
                // The index is the reading, and the caption over it says both what the
                // number is and what it amounts to. Lower case: this shell shouts at nobody.
                if (aqi == null) TileView.Reading("--", "aqi", "tap to enable")
                else TileView.Reading(
                    number = aqi.toString(),
                    caption = "${aqiLabel(aqi).lowercase(locale)} aqi"
                )
            }

            else -> null
        }
    }

    fun loadBuiltInPlacements(): MutableMap<String, Pair<TileSize, Int>> {
        // The upright arrangement stands in until the screen has been arranged on its
        // side, exactly as an icon's own placement does.
        val raw = (if (landscape()) prefs.getString(KEY_BUILTIN_TILES_LANDSCAPE, null) else null)
            ?: prefs.getString(KEY_BUILTIN_TILES, null) ?: return mutableMapOf()
        val result = mutableMapOf<String, Pair<TileSize, Int>>()
        for (entry in raw.split(";")) {
            val parts = entry.split(":")
            if (parts.size != 3) continue
            val index = parts[2].toIntOrNull() ?: continue
            result[parts[0]] = TileSize.fromName(parts[1]) to index
        }
        return result
    }

    fun saveBuiltInPlacements(placements: Map<String, Pair<TileSize, Int>>) {
        val raw = placements.entries.joinToString(";") { (id, p) ->
            "$id:${p.first.name}:${p.second}"
        }
        val key = if (landscape()) KEY_BUILTIN_TILES_LANDSCAPE else KEY_BUILTIN_TILES
        prefs.edit().putString(key, raw).apply()
    }


    // ------------------------------------------------------------------ weather

    private fun cachedWeatherJson(): org.json.JSONObject? = try {
        prefs.getString("weather_data", null)?.let { org.json.JSONObject(it) }
    } catch (e: Exception) {
        null
    }

    fun weatherUnit(): String = prefs.getString("weather_unit", "C") ?: "C"

    fun weatherWord(code: Int): String? = when (code) {
        0, 1 -> "sunny"
        2 -> "cloudy"
        3 -> "overcast"
        45, 48 -> "fog"
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81 -> "rain"
        // Violent showers: rain hard enough to be its own kind of weather.
        82 -> "storm"
        71, 73, 75, 77, 85, 86 -> "snow"
        95 -> "t.storm"
        96, 99 -> "hail"
        // No reading yet, and a guess at the sky is worse than saying nothing about it.
        else -> null
    }

    /**
     * The same conditions as a mark rather than a word, for a face that has no room for
     * the word - which is the forecast panel, where three of them stand in a row and the
     * label under each is already spoken for by which day it is.
     *
     * Grouped exactly as [weatherWord] groups them, so the tile can never show a cloud
     * over the word "rain": one WMO reading, one condition, said either way.
     */
    fun weatherGlyph(code: Int): Int? = when (code) {
        0, 1 -> R.drawable.wp81_weather_sun
        2 -> R.drawable.wp81_weather_cloudy
        3 -> R.drawable.wp81_weather_overcast
        45, 48 -> R.drawable.wp81_weather_fog
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81 -> R.drawable.wp81_weather_rain
        82 -> R.drawable.wp81_weather_showers
        71, 73, 75, 77, 85, 86 -> R.drawable.wp81_weather_snow
        95 -> R.drawable.wp81_weather_thunder
        96, 99 -> R.drawable.wp81_weather_hail
        else -> null
    }

    /**
     * One of the readings the weather tile shows: the figure, the sky it was taken under,
     * and which of them it is.
     *
     * [name] is the plain word - "now", "today", "tomorrow" - and what each surface makes
     * of it is its own business: a turning face has a whole tile for it and says "max
     * today", a column in the panel has a column's width and says "today".
     */
    private data class WeatherReading(val temperature: Int, val code: Int, val name: String)

    /**
     * What there is to say about the weather, in the order the tile says it.
     *
     * Now, then today's high while the day can still reach it, then tomorrow's. Once the
     * afternoon peak is behind us today's figure is one the day has already spent - a
     * number that can only be higher than the reading beside it, for a reason that has
     * passed - so it comes out of the run entirely. See [todayHighAhead].
     *
     * Empty when there is no cached reading at all, and one long when the forecast has not
     * arrived with it.
     */
    private fun weatherReadings(): List<WeatherReading> {
        val cached = cachedWeatherJson() ?: return emptyList()
        return try {
            val current = cached.getJSONObject("current")
            val readings = mutableListOf(
                WeatherReading(
                    kotlin.math.round(current.getDouble("temperature_2m")).toInt(),
                    current.optInt("weather_code", -1),
                    "now"
                )
            )
            val daily = cached.optJSONObject("daily")
            val highs = daily?.optJSONArray("temperature_2m_max")
            val codes = daily?.optJSONArray("weather_code")
            if (highs != null && codes != null && highs.length() >= 2) {
                if (todayHighAhead(cached)) {
                    readings += WeatherReading(
                        kotlin.math.round(highs.getDouble(0)).toInt(),
                        codes.optInt(0, -1),
                        "today"
                    )
                }
                readings += WeatherReading(
                    kotlin.math.round(highs.getDouble(1)).toInt(),
                    codes.optInt(1, -1),
                    "tomorrow"
                )
            }
            readings
        } catch (e: Exception) {
            android.util.Log.w(TAG, "could not read the weather", e)
            emptyList()
        }
    }

    /**
     * Whether today's high is still to come.
     *
     * The daily block carries the figure but not the hour it falls on, so the hourly
     * temperatures are what separate an afternoon still ahead from one already over: find
     * the hour today reaches its highest - the last of them, if the peak is flat - and see
     * whether it is behind the current hour.
     *
     * Times come back in the location's own zone (timezone=auto), which is not necessarily
     * the phone's, so "now" is worked out against the offset the response states rather
     * than the device clock's.
     *
     * True whenever the answer cannot be read - a cache saved before the hourly figures
     * were asked for, or one left over from another day - which leaves the tile as it was
     * rather than hiding a face on a guess.
     */
    private fun todayHighAhead(cached: org.json.JSONObject): Boolean {
        return try {
            val hourly = cached.optJSONObject("hourly") ?: return true
            val times = hourly.optJSONArray("time") ?: return true
            val temperatures = hourly.optJSONArray("temperature_2m") ?: return true

            val offsetMillis = cached.optLong("utc_offset_seconds", 0L) * 1000L
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH", java.util.Locale.US)
            stamp.timeZone = java.util.TimeZone.getTimeZone("UTC")
            // The wall clock where the weather is: UTC formatting of a shifted instant.
            val now = stamp.format(java.util.Date(System.currentTimeMillis() + offsetMillis))
            val today = now.substring(0, 10)

            var peak = Double.NEGATIVE_INFINITY
            var peakHour: String? = null
            for (i in 0 until minOf(times.length(), temperatures.length())) {
                val time = times.optString(i)
                if (!time.startsWith(today)) continue
                val temperature = temperatures.optDouble(i, Double.NaN)
                if (temperature.isNaN()) continue
                // >= rather than >: a peak held over several hours has not passed until
                // the last hour holding it has.
                if (temperature >= peak) {
                    peak = temperature
                    peakHour = time
                }
            }

            // The peak landing on the current hour still counts as ahead - it is being
            // reached now, not spent.
            peakHour?.let { it.substring(0, minOf(it.length, 13)) >= now } ?: true
        } catch (e: Exception) {
            android.util.Log.w(TAG, "could not tell when today's high falls", e)
            true
        }
    }

    /**
     * The readings the weather tile turns over, one face each.
     *
     * What a tile falls back on rather than what it prefers: a tile with two cells or more
     * shows the lot of them at once and never asks for this - see [weatherPanel]. This is
     * the 1x1 and the strips, which have room for one reading at a time.
     *
     * A run of faces rather than one reading, which is why this does not come out of
     * [liveContent] - the caller hands it to setLiveWidgetRotation instead.
     *
     * Each is labelled. A temperature with no label is a number, and three of them in turn
     * without labels are numbers that appear to disagree; the 1x1 shortens the labels
     * rather than dropping them, because "max tomorrow" does not fit across it and
     * "tomorrow" says the necessary half.
     */
    fun weatherFaces(size: TileSize): List<TileView.LiveFace> {
        val small = size == TileSize.SMALL
        val unit = if (small) "" else weatherUnit()
        return weatherReadings().map { reading ->
            TileView.LiveFace(
                title = "${reading.temperature}°$unit",
                // What the sky is doing, and under it which reading this is. That order
                // because the weather is what the tile is about and the label is only
                // which of the run it is showing.
                detail = listOfNotNull(
                    weatherWord(reading.code),
                    when {
                        reading.name == "now" || small -> reading.name
                        else -> "max ${reading.name}"
                    }
                ).joinToString("\n")
            )
        }
    }

    /**
     * The same readings as columns, for a tile with the width to hold them side by side.
     *
     * Nothing is dropped to make them fit: the panel sets itself to the column it has, and
     * a tile too narrow for that is a tile that should be turning faces instead. The
     * labels are the plain words - a column is headed by the day, not by what the figure
     * is of, which the row of them says once by being a row.
     *
     * Takes no size, unlike [weatherFaces]: what a column can hold is a question the panel
     * answers for itself once it knows how wide the tile made it.
     */
    fun weatherPanel(): List<ForecastPanelView.Column> {
        val unit = weatherUnit()
        return weatherReadings().map { reading ->
            ForecastPanelView.Column(
                label = reading.name,
                glyph = weatherGlyph(reading.code),
                reading = "${reading.temperature}°$unit"
            )
        }
    }

    // ----------------------------------------------------------------- calendar

    /**
     * The date, and what is next on it.
     *
     * The date needs nothing but a clock, so a surface with no access to the event caches
     * still gets a real calendar tile rather than a label; [caption] is what the next
     * appointment would have filled in.
     */
    fun calendarSummary(size: TileSize, caption: String? = null): TileView.Reading {
        val locale = java.util.Locale.getDefault()
        val now = java.util.Date()
        val calendar = java.util.Calendar.getInstance()
        val small = size == TileSize.SMALL

        // The day of the month as the number with the weekday against it - "sun 30" -
        // which is how a date is read, the number being what is looked for and the
        // weekday what places it.
        val weekdayPattern = if (size == TileSize.WIDE) "EEEE" else "EEE"
        val weekday = java.text.SimpleDateFormat(weekdayPattern, locale).format(now)

        return TileView.Reading(
            number = calendar.get(java.util.Calendar.DAY_OF_MONTH).toString(),
            // The 1x1 has room for the date and nothing else.
            caption = if (small) null else caption ?: "no events",
            aside = weekday.lowercase(locale)
        )
    }

    // -------------------------------------------------------------------- art

    private val iconProvider by lazy { MonochromeIconProvider(context) }

    /**
     * The icons the user chose by hand, as packageName to the file they picked.
     *
     * Kept per theme, under the phone's own key - see AppTheme.customIconsKey. Read here
     * so the car's tiles wear the same art the phone's do; reading any other theme's key
     * would dress the car in a desktop's icons and miss every one picked on a tile.
     */
    private val customIcons: Map<String, String> by lazy {
        val raw = prefs.getString(KEY_CUSTOM_ICONS, "").orEmpty()
        if (raw.isEmpty()) emptyMap()
        else raw.split(";").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
    }

    /**
     * The mark on a tile.
     *
     * The provider prefers an app's themed monochrome layer, then its notification
     * silhouette, and falls back to the app's own icon - which is loaded here rather than
     * carried on the DesktopIcon, so that a wall of tiles costs one drawable per tile
     * actually shown instead of one per app installed.
     */
    fun glyphFor(tile: Tile): MonochromeIconProvider.Glyph? {
        // An icon the user chose outranks everything derived - the app's themed monochrome
        // layer, its notification silhouette, and the built-in glyphs for system tiles.
        // Full colour, because a picture someone picked is not a silhouette to be tinted.
        customIcons[tile.packageName]?.let { path ->
            runCatching { loadIconFromPath(context, path) }.getOrNull()?.let { drawable ->
                return MonochromeIconProvider.Glyph.FullColor(
                    drawable, iconProvider.ratioFor("custom:${tile.packageName}", drawable))
            }
        }

        val fixed = when (tile.kind) {
            Tile.Kind.FOLDER -> rocks.gorjan.gokixp.R.drawable.wp81_glyph_folder
            Tile.Kind.MY_COMPUTER -> rocks.gorjan.gokixp.R.drawable.wp81_glyph_computer
            Tile.Kind.URL_SHORTCUT -> rocks.gorjan.gokixp.R.drawable.wp81_glyph_ie
            // Live widgets draw their readings; there is no mark to resolve.
            else -> null
        }
        if (fixed != null) {
            val drawable = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, fixed)
            return drawable?.let { MonochromeIconProvider.Glyph.Monochrome(it, iconProvider.ratioFor("fixed:${tile.packageName}", it)) }
        }
        if (tile.kind != Tile.Kind.APP && tile.kind != Tile.Kind.SYSTEM_APP) return null
        val fallback = try {
            val pm = context.packageManager
            pm.getApplicationInfo(tile.packageName, 0).loadIcon(pm)
        } catch (e: Exception) {
            null
        }
        return iconProvider.glyphFor(tile.packageName, fallback)
    }

    companion object {

        /**
         * The launcher's icon list, read straight from preferences.
         *
         * The launcher keeps this list in memory and hands it over; anything else - the
         * car screen, in particular - has no MainActivity to ask and reads the same JSON
         * itself. Only what a tile needs is recovered: the art is left as a placeholder
         * because the wall resolves its own marks through the glyph provider, and loading
         * a drawable per installed app to throw them all away would cost seconds.
         */
        /**
         * Turns a stored icon path into something drawable.
         *
         * Three kinds of path end up in the mappings: a file the user imported, which
         * lives in the app's own storage; an SVG from the Windows Phone set, which nothing
         * in the platform will decode but whose path data it will draw; and an ordinary
         * image in the assets.
         */
        fun loadIconFromPath(context: Context, iconPath: String): android.graphics.drawable.Drawable? {
            return if (iconPath.endsWith(".svg", ignoreCase = true) &&
                    !iconPath.startsWith("$IMPORTED_ICONS_DIR/")
            ) {
                SvgIcon.fromAsset(context, iconPath)
            } else {
                val stream = if (iconPath.startsWith("$IMPORTED_ICONS_DIR/")) {
                    java.io.File(context.filesDir, iconPath).inputStream()
                } else {
                    context.assets.open(iconPath)
                }
                stream.use {
                    android.graphics.drawable.Drawable.createFromStream(it, iconPath)
                }
            }
        }

        const val IMPORTED_ICONS_DIR = "imported_icons"

        /** Where the Start screen's hand-picked icons are kept. */
        val KEY_CUSTOM_ICONS: String = AppTheme.WindowsPhone81.customIconsKey

        fun loadIcons(context: Context): MutableList<DesktopIcon> {
            val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString("desktop_icons", null) ?: return mutableListOf()
            val blank = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            return try {
                val type = object : com.google.gson.reflect.TypeToken<List<Map<String, Any>>>() {}.type
                val raw: List<Map<String, Any>> = com.google.gson.Gson().fromJson(json, type)
                raw.mapNotNull { data ->
                    try {
                        val packageName = data["packageName"] as String
                        // The player is called Music, as the phone called it.
                        val name = (data["name"] as String)
                            .let { if (packageName == "system.zune" && it == "Zune") "Music" else it }
                        val typeStr = data["type"] as? String
                        val iconType = try {
                            typeStr?.let { IconType.valueOf(it) } ?: IconType.APP
                        } catch (e: Exception) {
                            when (packageName) {
                                "recycle.bin" -> IconType.RECYCLE_BIN
                                "my.computer" -> IconType.MY_COMPUTER
                                else -> IconType.APP
                            }
                        }
                        DesktopIcon(
                            name = name,
                            packageName = packageName,
                            icon = blank,
                            x = 0f,
                            y = 0f,
                            id = data["id"] as String,
                            type = iconType,
                            parentFolderId = data["parentFolderId"] as? String,
                            portraitGridIndex = (data["portraitGridIndex"] as? Double)?.toInt(),
                            landscapeGridIndex = (data["landscapeGridIndex"] as? Double)?.toInt(),
                            targetUrl = data["targetUrl"] as? String,
                            tileSize = data["tileSize"] as? String,
                            tileIndex = (data["tileIndex"] as? Double)?.toInt(),
                            tileSizeLandscape = data["tileSizeLandscape"] as? String,
                            tileIndexLandscape = (data["tileIndexLandscape"] as? Double)?.toInt()
                        )
                    } catch (e: Exception) {
                        null
                    }
                }.toMutableList()
            } catch (e: Exception) {
                android.util.Log.w(TAG, "could not read the icon list", e)
                mutableListOf()
            }
        }

        private const val TAG = "WP81TileHost"

        const val WIDGET_CLOCK = "wp81.widget.clock"
        const val WIDGET_CALENDAR = "wp81.widget.calendar"
        const val WIDGET_NEWS = "wp81.widget.news"
        const val WIDGET_PHOTOS = "wp81.widget.photos"
        const val WIDGET_PEOPLE = "wp81.widget.people"
        const val WIDGET_AQI = "wp81.widget.aqi"
        const val WIDGET_WEATHER = "wp81.widget.weather"
        const val WIDGET_SETTINGS = "wp81.widget.settings"
        const val KEY_BUILTIN_TILES = "wp81_builtin_tiles"
        const val KEY_BUILTIN_TILES_LANDSCAPE = "wp81_builtin_tiles_landscape"
    }
}
