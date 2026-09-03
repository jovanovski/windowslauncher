package rocks.gorjan.gokixp.wp81

import android.content.Context
import android.location.Geocoder
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import rocks.gorjan.gokixp.MainActivity
import rocks.gorjan.gokixp.R
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A place the weather is wanted for.
 *
 * [HERE] is the one the phone works out for itself, and it is not a place so much as a
 * question asked afresh each time - which is why its coordinates are remembered rather
 * than chosen: the last fix is what a forecast is drawn against while the radio is finding
 * the next one, and a phone indoors may never find one at all.
 *
 * The reading is carried on the place rather than looked up beside it. The places list
 * wants a temperature against every line and nothing else about any of them, and a
 * full forecast per place would be several hundred kilobytes of preferences for the sake
 * of eight numbers.
 */
data class WeatherPlace(
    val id: String,
    val name: String,
    val region: String,
    val country: String,
    val latitude: Double,
    val longitude: Double,
    /** The last temperature seen here, in Celsius, or null while there has been none. */
    val temperature: Double? = null,
    val code: Int = -1,
    val readAt: Long = 0L
) {
    val isHere: Boolean get() = id == HERE

    /** The line under the name: where in the world this is, as much of it as there is. */
    fun subtitle(): String =
        listOf(region, country).filter { it.isNotBlank() }.joinToString(", ")

    companion object {
        const val HERE = "here"
    }
}

/** The reading as it stands, at the place it was taken. */
data class WeatherNow(
    val temperature: Double,
    val apparent: Double,
    val code: Int,
    val humidity: Int,
    val wind: Double,
    val windFrom: Int,
    val gusts: Double,
    val pressure: Double,
    val cloud: Int,
    val precipitation: Double,
    val isDay: Boolean
)

/**
 * One hour of the forecast.
 *
 * [time] is the hour as the API stated it - "2026-09-02T14:00", in the *place's* own
 * clock, not the phone's. Which hour is current is therefore a question about that clock,
 * and [WeatherReport.hourNow] is what answers it; comparing against the device would put
 * a forecast for Tokyo eight hours out.
 */
data class WeatherHour(
    val time: String,
    val temperature: Double,
    val apparent: Double,
    val code: Int,
    val chance: Int,
    val humidity: Int,
    val wind: Double,
    val isDay: Boolean
) {
    /** The hour of the day, 0-23. */
    val hour: Int get() = time.substring(11, 13).toIntOrNull() ?: 0

    /** The day this hour falls on, as "yyyy-MM-dd". */
    val day: String get() = time.substring(0, 10)
}

/** One day of it. */
data class WeatherDay(
    val date: String,
    val code: Int,
    val high: Double,
    val low: Double,
    val sunrise: String,
    val sunset: String,
    val rainfall: Double,
    val chance: Int,
    val wind: Double,
    val uv: Double
)

/**
 * A whole forecast, parsed once out of the cached response.
 *
 * Everything in it is metric, whatever the user has asked to be shown: one cache serves
 * the tile, the taskbar, the Quick Glance widget and this app, and a cache written in
 * whatever unit was selected when the fetch happened is a cache that lies the moment the
 * setting changes. Conversion is [WeatherStore.temperature]'s job, at the point of
 * display.
 */
data class WeatherReport(
    val now: WeatherNow?,
    val hours: List<WeatherHour>,
    val days: List<WeatherDay>,
    /** The place's offset from UTC, which is how its own wall clock is worked out. */
    val offsetSeconds: Long,
    val fetchedAt: Long
) {

    /** The current hour where the weather is, as an [WeatherHour.time] to compare against. */
    fun hourNow(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH':00'", Locale.US)
        stamp.timeZone = TimeZone.getTimeZone("UTC")
        return stamp.format(Date(System.currentTimeMillis() + offsetSeconds * 1000L))
    }

    /** The forecast from this hour on, which is the only part of it still to happen. */
    fun hoursAhead(): List<WeatherHour> {
        val now = hourNow()
        return hours.filter { it.time >= now }
    }

    /** Today's entry, which is the first the API returns. */
    fun today(): WeatherDay? = days.firstOrNull()
}

/**
 * What WMO weather codes mean, in each of the lengths this shell says them in.
 *
 * A hundred codes, and three vocabularies over them: a mark for a tile column, one word
 * for a tile face, and a short phrase for a page with room to be read. They are grouped
 * identically on purpose - one reading is one condition however it is being said, so the
 * tile can never show a cloud over the word "rain".
 *
 * The grouping is coarse where the distinction does not change what anybody does about
 * it: drizzle, rain and showers are all rain to somebody deciding whether to take a coat.
 * What earns a category of its own is what changes the answer - thunder, hail, ice.
 */
object WeatherCodes {

    /** The one word a tile face has room for, or null for a reading there is no word for. */
    fun word(code: Int): String? = when (code) {
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
     * The same conditions as a mark, for a face with no room for the word.
     *
     * [night] swaps the sun for a moon, and only for a sky clear enough to see one
     * through: a cloud is a cloud at either hour, and a set with a night version of every
     * condition would be eight more files to say what one of them says.
     */
    fun glyph(code: Int, night: Boolean = false): Int? = when (code) {
        0, 1 -> if (night) R.drawable.wp81_weather_moon else R.drawable.wp81_weather_sun
        2 -> R.drawable.wp81_weather_sun_cloud
        3 -> R.drawable.wp81_weather_cloud
        45, 48 -> R.drawable.wp81_weather_fog
        // Rain is rain at every strength the codes distinguish. Freezing rain gets the
        // same mark and a different word, which is the right way round: the word is what
        // says to expect ice, and a second raincloud with something extra on it would not.
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> R.drawable.wp81_weather_rain
        71, 73, 75, 77, 85, 86 -> R.drawable.wp81_weather_snow
        95 -> R.drawable.wp81_weather_lightning
        96, 99 -> R.drawable.wp81_weather_storm
        else -> null
    }

    /**
     * The phrase a page has room for, which is where the distinctions the tile drops go.
     *
     * Freezing rain and rain are the same coat and a very different drive, and a reader
     * who has opened the weather has asked for the difference.
     */
    fun condition(code: Int): String = when (code) {
        // One word for both. The WMO pair is "clear" and "mainly clear", and the
        // difference between them is a tenth of the sky - not something anybody can see
        // out of a window, and not something worth a second word and a second mark that
        // would then have to differ from each other.
        0, 1 -> "clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45 -> "fog"
        48 -> "freezing fog"
        51, 53, 55 -> "drizzle"
        56, 57 -> "freezing drizzle"
        61 -> "light rain"
        63 -> "rain"
        65 -> "heavy rain"
        66, 67 -> "freezing rain"
        71 -> "light snow"
        73 -> "snow"
        75 -> "heavy snow"
        77 -> "snow grains"
        80 -> "light showers"
        81 -> "showers"
        82 -> "violent showers"
        85 -> "light snow showers"
        86 -> "snow showers"
        95 -> "thunderstorm"
        96, 99 -> "thunderstorm with hail"
        else -> "no reading"
    }
}

/**
 * The phone's weather: where it is wanted, what it says, and how to ask again.
 *
 * One fetch serves everything. The taskbar's temperature, the Start screen's weather tile,
 * the Quick Glance widget and the Weather app all read the response cached here, so there
 * is one answer on the phone rather than four that disagree by however long apart they
 * were fetched. The app is simply the surface with room to show all of it.
 *
 * Open-Meteo, for the same reason the news is public RSS: no key to obtain, hold or leak,
 * no quota to run out at the worst moment, and the request says nothing about the phone
 * beyond the coordinates it is asking about.
 *
 * Only the selected place's forecast is kept in full. The others keep a temperature and a
 * condition, which is all a list of places shows, and are fetched in full the moment one
 * of them is selected - a dozen forecasts in a preferences file that the launcher loads at
 * startup is a real cost for eight numbers nobody has looked at yet.
 */
object WeatherStore {

    private const val TAG = "WeatherStore"

    /** The forecast itself, and when it landed. Shared with everything else on the phone. */
    private const val KEY_DATA = "weather_data"
    private const val KEY_TIMESTAMP = "weather_timestamp"

    /** The saved places, and which of them is the one being shown. */
    private const val KEY_PLACES = "weather_places"
    private const val KEY_SELECTED = "weather_place"

    /** Degrees, and the two ways of saying how hard the wind is blowing. */
    private const val KEY_UNIT = "weather_unit"
    private const val KEY_WIND_UNIT = "weather_wind_unit"

    /** Whether rain on the way is worth a word in the shade. */
    private const val KEY_RAIN_ALERTS = "weather_rain_alerts"

    const val CELSIUS = "C"
    const val FAHRENHEIT = "F"

    const val KMH = "kmh"
    const val MPH = "mph"
    const val MS = "ms"

    /**
     * How much is asked for.
     *
     * Everything on this list is shown somewhere, and nothing is asked for on the chance
     * it might be: the response is held in preferences, which the launcher reads at
     * startup, so an unused hourly field is a few kilobytes read on every cold start
     * forever. Visibility and dew point were dropped for exactly that reason - a line each
     * in the details, and a hundred and sixty-eight numbers apiece to carry them.
     */
    private const val CURRENT_FIELDS =
        "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code," +
            "wind_speed_10m,wind_direction_10m,wind_gusts_10m,surface_pressure," +
            "cloud_cover,precipitation,is_day"

    private const val HOURLY_FIELDS =
        "temperature_2m,apparent_temperature,relative_humidity_2m," +
            "precipitation_probability,weather_code,wind_speed_10m,is_day"

    private const val DAILY_FIELDS =
        "weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset," +
            "precipitation_sum,precipitation_probability_max,wind_speed_10m_max,uv_index_max"

    /** A week, which is as far ahead as an hourly forecast is worth anything. */
    private const val FORECAST_DAYS = 7

    /** How old a forecast may be before opening the app asks for another. */
    private const val STALE_MS = 30L * 60L * 1000L

    /** How many places may be saved. A list nobody scrolls, and a preferences file that stays small. */
    private const val MAX_PLACES = 10

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var fetching = false

    /** Whether a name is already being looked up, so a run of fixes asks once. */
    @Volatile
    private var naming = false

    /** Whether a forecast is on its way, so a page can say so rather than look empty. */
    fun isFetching(): Boolean = fetching

    // ------------------------------------------------------------------ the request

    /**
     * The one forecast request, in one place.
     *
     * MainActivity's hourly update and this app both build their URL here, so the cache
     * they share can never end up holding less than one of them needs.
     */
    fun forecastUrl(latitude: Double, longitude: Double): String =
        "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$latitude&longitude=$longitude" +
            "&current=$CURRENT_FIELDS" +
            "&hourly=$HOURLY_FIELDS" +
            "&daily=$DAILY_FIELDS" +
            "&forecast_days=$FORECAST_DAYS&timezone=auto"

    /** The same, cut down to what a line in the places list shows. */
    private fun summaryUrl(latitude: Double, longitude: Double): String =
        "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$latitude&longitude=$longitude" +
            "&current=temperature_2m,weather_code&timezone=auto"

    // ------------------------------------------------------------------ the forecast

    private fun prefs(context: Context) =
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

    /** The cached forecast, parsed, or null when there has never been one. */
    fun report(context: Context): WeatherReport? {
        val raw = prefs(context).getString(KEY_DATA, null) ?: return null
        return try {
            parse(JSONObject(raw), prefs(context).getLong(KEY_TIMESTAMP, 0L))
        } catch (e: Exception) {
            Log.w(TAG, "could not read the cached forecast", e)
            null
        }
    }

    /** When the cached forecast landed, or 0. */
    fun fetchedAt(context: Context): Long = try {
        prefs(context).getLong(KEY_TIMESTAMP, 0L)
    } catch (e: Exception) {
        0L
    }

    /** Whether it is old enough that opening the app should ask for another. */
    fun isStale(context: Context): Boolean =
        System.currentTimeMillis() - fetchedAt(context) > STALE_MS

    /**
     * Writes a response through as the phone's current forecast.
     *
     * The one door into the shared cache. MainActivity's own fetch comes through here too,
     * so the selected place's summary is updated by whichever of the two fetched last
     * rather than by whichever remembered to.
     */
    fun save(context: Context, response: String) {
        prefs(context).edit()
            .putString(KEY_DATA, response)
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .apply()
        try {
            val current = JSONObject(response).optJSONObject("current") ?: return
            noteReading(
                context,
                selectedId(context),
                current.optDouble("temperature_2m", Double.NaN),
                current.optInt("weather_code", -1)
            )
        } catch (e: Exception) {
            Log.w(TAG, "saved a forecast that could not be summarised", e)
        }
    }

    /**
     * Fetches the selected place's forecast, unless a fresh one is already held.
     *
     * [onDone] is called on the main thread either way, and is told nothing about what
     * happened: a page redraws itself from the cache afterwards, and whether that cache
     * was just replaced or merely re-read is not a difference it can show.
     */
    fun refresh(context: Context, force: Boolean = false, onDone: () -> Unit = {}) {
        if (fetching) return
        if (!force && !isStale(context) && report(context) != null) {
            onDone()
            return
        }
        val place = selected(context)
        val point = coordinatesFor(context, place)
        if (point == null) {
            onDone()
            return
        }
        fetching = true
        Thread {
            val response = read(forecastUrl(point.first, point.second))
            if (response != null) {
                // The place is written back with the fix the forecast was actually drawn
                // against: "here" is wherever the phone last knew it was, and a list that
                // says otherwise is a list that will fetch the wrong town next time.
                if (place != null && place.isHere) {
                    rememberHere(context, point.first, point.second)
                    // Blocking, and deliberately before the save: this thread ends in the
                    // redraw that puts the place's name at the top of the app, and a name
                    // resolved a moment after that would not appear until the next one.
                    nameHere(context, point.first, point.second)
                }
                save(context, response)
            }
            fetching = false
            main.post { onDone() }
        }.start()
    }

    /**
     * Reads a whole forecast out of the response.
     *
     * Every field is optional at this level even though the request asks for all of them:
     * a cache written by an older build asked for less, and a forecast that refuses to
     * parse because it has no gust figure is a blank page where most of a forecast was.
     */
    private fun parse(json: JSONObject, fetchedAt: Long): WeatherReport {
        val current = json.optJSONObject("current")
        val now = current?.let {
            WeatherNow(
                temperature = it.optDouble("temperature_2m", Double.NaN),
                apparent = it.optDouble("apparent_temperature", Double.NaN),
                code = it.optInt("weather_code", -1),
                humidity = it.optInt("relative_humidity_2m", -1),
                wind = it.optDouble("wind_speed_10m", Double.NaN),
                windFrom = it.optInt("wind_direction_10m", -1),
                gusts = it.optDouble("wind_gusts_10m", Double.NaN),
                pressure = it.optDouble("surface_pressure", Double.NaN),
                cloud = it.optInt("cloud_cover", -1),
                precipitation = it.optDouble("precipitation", Double.NaN),
                isDay = it.optInt("is_day", 1) == 1
            )
        }?.takeIf { !it.temperature.isNaN() }

        val hours = mutableListOf<WeatherHour>()
        json.optJSONObject("hourly")?.let { hourly ->
            val times = hourly.optJSONArray("time")
            val temperatures = hourly.optJSONArray("temperature_2m")
            if (times != null && temperatures != null) {
                val apparent = hourly.optJSONArray("apparent_temperature")
                val codes = hourly.optJSONArray("weather_code")
                val chances = hourly.optJSONArray("precipitation_probability")
                val humidity = hourly.optJSONArray("relative_humidity_2m")
                val wind = hourly.optJSONArray("wind_speed_10m")
                val day = hourly.optJSONArray("is_day")
                for (i in 0 until minOf(times.length(), temperatures.length())) {
                    val temperature = temperatures.optDouble(i, Double.NaN)
                    if (temperature.isNaN()) continue
                    hours += WeatherHour(
                        time = times.optString(i),
                        temperature = temperature,
                        apparent = apparent?.optDouble(i, Double.NaN) ?: Double.NaN,
                        code = codes?.optInt(i, -1) ?: -1,
                        chance = chances?.optInt(i, -1) ?: -1,
                        humidity = humidity?.optInt(i, -1) ?: -1,
                        wind = wind?.optDouble(i, Double.NaN) ?: Double.NaN,
                        isDay = (day?.optInt(i, 1) ?: 1) == 1
                    )
                }
            }
        }

        val days = mutableListOf<WeatherDay>()
        json.optJSONObject("daily")?.let { daily ->
            val dates = daily.optJSONArray("time")
            val highs = daily.optJSONArray("temperature_2m_max")
            val lows = daily.optJSONArray("temperature_2m_min")
            if (dates != null && highs != null && lows != null) {
                val codes = daily.optJSONArray("weather_code")
                val sunrise = daily.optJSONArray("sunrise")
                val sunset = daily.optJSONArray("sunset")
                val rainfall = daily.optJSONArray("precipitation_sum")
                val chances = daily.optJSONArray("precipitation_probability_max")
                val wind = daily.optJSONArray("wind_speed_10m_max")
                val uv = daily.optJSONArray("uv_index_max")
                for (i in 0 until minOf(dates.length(), highs.length())) {
                    val high = highs.optDouble(i, Double.NaN)
                    if (high.isNaN()) continue
                    days += WeatherDay(
                        date = dates.optString(i),
                        code = codes?.optInt(i, -1) ?: -1,
                        high = high,
                        low = lows.optDouble(i, Double.NaN),
                        sunrise = sunrise?.optString(i).orEmpty(),
                        sunset = sunset?.optString(i).orEmpty(),
                        rainfall = rainfall?.optDouble(i, Double.NaN) ?: Double.NaN,
                        chance = chances?.optInt(i, -1) ?: -1,
                        wind = wind?.optDouble(i, Double.NaN) ?: Double.NaN,
                        uv = uv?.optDouble(i, Double.NaN) ?: Double.NaN
                    )
                }
            }
        }

        return WeatherReport(
            now = now,
            hours = hours,
            days = days,
            offsetSeconds = json.optLong("utc_offset_seconds", 0L),
            fetchedAt = fetchedAt
        )
    }

    // ------------------------------------------------------------------ places

    /**
     * Everywhere the weather is wanted, the phone's own position first.
     *
     * "Here" is always in the list and cannot be taken out of it. A weather app whose only
     * places are ones the user typed is one that has to be told where its owner lives.
     */
    fun places(context: Context): List<WeatherPlace> {
        val saved = readPlaces(context)
        val here = saved.firstOrNull { it.isHere } ?: WeatherPlace(
            id = WeatherPlace.HERE,
            name = "my location",
            region = "",
            country = "",
            latitude = Double.NaN,
            longitude = Double.NaN
        )
        return listOf(here) + saved.filterNot { it.isHere }
    }

    fun selectedId(context: Context): String =
        prefs(context).getString(KEY_SELECTED, WeatherPlace.HERE) ?: WeatherPlace.HERE

    /** The place being shown, or null in the one case where there is none to show. */
    fun selected(context: Context): WeatherPlace? {
        val id = selectedId(context)
        return places(context).firstOrNull { it.id == id } ?: places(context).firstOrNull()
    }

    /**
     * Shows a different place.
     *
     * The forecast held is the old place's, and there is no honest way to convert one into
     * the other, so it is cleared rather than left up: a page that keeps showing Berlin
     * under the heading "Lisbon" until the fetch lands is worse than one that says it is
     * fetching.
     */
    fun select(context: Context, id: String) {
        if (id == selectedId(context)) return
        prefs(context).edit()
            .putString(KEY_SELECTED, id)
            .remove(KEY_DATA)
            .remove(KEY_TIMESTAMP)
            .apply()
    }

    /**
     * Adds a place, and shows it.
     *
     * Adding a place is asking about it - nobody searches for a town to leave it at the
     * bottom of a list - so this selects as well. An id already in the list is simply
     * selected: the same town added twice is one town.
     */
    fun add(context: Context, place: WeatherPlace): Boolean {
        val saved = readPlaces(context)
        if (saved.none { it.id == place.id }) {
            if (saved.count { !it.isHere } >= MAX_PLACES) return false
            writePlaces(context, saved + place)
        }
        select(context, place.id)
        return true
    }

    /**
     * Removes a place, and falls back to the phone's own position if it was the one shown.
     *
     * "Here" is not removable - see [places] - so there is always something to fall back
     * to.
     */
    fun remove(context: Context, id: String) {
        if (id == WeatherPlace.HERE) return
        writePlaces(context, readPlaces(context).filterNot { it.id == id })
        if (selectedId(context) == id) select(context, WeatherPlace.HERE)
    }

    /**
     * Where the phone last knew it was.
     *
     * Written by whoever gets a fix - this app's own refresh, or MainActivity's hourly
     * update, which is the one that actually waits on the radio. Held so that a forecast
     * can be asked for indoors, where a fresh fix may never arrive.
     */
    fun rememberHere(context: Context, latitude: Double, longitude: Double) {
        val saved = readPlaces(context)
        val here = saved.firstOrNull { it.isHere }
        val updated = (here ?: WeatherPlace(
            id = WeatherPlace.HERE, name = "my location", region = "", country = "",
            latitude = latitude, longitude = longitude
        )).copy(latitude = latitude, longitude = longitude)
        writePlaces(context, listOf(updated) + saved.filterNot { it.isHere })
    }

    /**
     * Puts the name of the town the phone is standing in at the top of the app.
     *
     * "My location" is what the phone calls the question, not an answer to it. Somewhere
     * with a name is somewhere you can recognise at a glance - and it is the one line that
     * says whether the forecast on screen is about where you are or about where you were
     * this morning.
     *
     * The locality first, then the district, then the region: a fix in open country has no
     * town to be in, and the nearest thing with a name beats a heading that gave up.
     *
     * Blocking, and on the platform's own geocoder: nothing about the phone leaves it that
     * has not already gone out with the forecast request. Where there is no geocoder to
     * ask - a build with no backend for it - the name is simply left as it was, which is
     * the question again, and no worse than it has always been.
     */
    fun nameHere(context: Context, latitude: Double, longitude: Double) {
        if (naming || !Geocoder.isPresent()) return
        naming = true
        try {
            @Suppress("DEPRECATION")
            val found = Geocoder(context, Locale.getDefault())
                .getFromLocation(latitude, longitude, 1)
                ?.firstOrNull() ?: return
            val name = found.locality ?: found.subAdminArea ?: found.adminArea ?: return
            val saved = readPlaces(context)
            if (saved.none { it.isHere }) return
            writePlaces(context, saved.map {
                if (it.isHere) it.copy(
                    name = name,
                    // The district is dropped where it only repeats the town, which is
                    // most cities: "Skopje, Skopje, North Macedonia" is one place said
                    // three times.
                    region = found.adminArea.orEmpty().takeIf { area -> area != name }.orEmpty(),
                    country = found.countryName.orEmpty()
                ) else it
            })
        } catch (e: Exception) {
            // A geocoder with no network behind it throws rather than returning nothing.
            Log.w(TAG, "could not put a name to where the phone is", e)
        } finally {
            naming = false
        }
    }

    /** The same, off whatever thread this was called on. For callers on the main one. */
    fun nameHereLater(context: Context, latitude: Double, longitude: Double) {
        if (naming) return
        Thread { nameHere(context, latitude, longitude) }.start()
    }

    /** Records what a place last read, for the line about it in the places list. */
    private fun noteReading(context: Context, id: String, temperature: Double, code: Int) {
        if (temperature.isNaN()) return
        val saved = readPlaces(context)
        // "Here" may not be in the list yet - nothing has had a fix to write - and a
        // reading is not reason enough to invent a position for it.
        if (saved.none { it.id == id }) return
        writePlaces(context, saved.map {
            if (it.id == id) it.copy(
                temperature = temperature, code = code, readAt = System.currentTimeMillis()
            ) else it
        })
    }

    /**
     * Brings the places list's own temperatures up to date.
     *
     * One small request per place, and only for the places whose reading has gone stale -
     * the selected one is left alone, because its full forecast is fetched anyway and
     * writing its summary twice would be two requests for one number.
     */
    fun refreshSummaries(context: Context, onDone: () -> Unit = {}) {
        val stale = places(context).filter {
            it.id != selectedId(context) &&
                !it.latitude.isNaN() &&
                System.currentTimeMillis() - it.readAt > STALE_MS
        }
        if (stale.isEmpty()) {
            onDone()
            return
        }
        Thread {
            for (place in stale) {
                val response = read(summaryUrl(place.latitude, place.longitude)) ?: continue
                try {
                    val current = JSONObject(response).optJSONObject("current") ?: continue
                    val temperature = current.optDouble("temperature_2m", Double.NaN)
                    val code = current.optInt("weather_code", -1)
                    main.post { noteReading(context, place.id, temperature, code) }
                } catch (e: Exception) {
                    Log.w(TAG, "could not read ${place.name}", e)
                }
            }
            main.post { onDone() }
        }.start()
    }

    private fun readPlaces(context: Context): List<WeatherPlace> {
        val raw = prefs(context).getString(KEY_PLACES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                WeatherPlace(
                    id = item.optString("id"),
                    name = item.optString("name"),
                    region = item.optString("region"),
                    country = item.optString("country"),
                    latitude = item.optDouble("lat", Double.NaN),
                    longitude = item.optDouble("lon", Double.NaN),
                    temperature = item.optDouble("t", Double.NaN).takeIf { !it.isNaN() },
                    code = item.optInt("c", -1),
                    readAt = item.optLong("at", 0L)
                ).takeIf { it.id.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not read the places list", e)
            emptyList()
        }
    }

    private fun writePlaces(context: Context, places: List<WeatherPlace>) {
        val array = JSONArray()
        for (place in places) {
            array.put(JSONObject().apply {
                put("id", place.id)
                put("name", place.name)
                put("region", place.region)
                put("country", place.country)
                put("lat", place.latitude)
                put("lon", place.longitude)
                place.temperature?.let { put("t", it) }
                put("c", place.code)
                put("at", place.readAt)
            })
        }
        prefs(context).edit().putString(KEY_PLACES, array.toString()).apply()
    }

    /**
     * Where to point the request for a place.
     *
     * A saved place carries its own coordinates. "Here" asks the system for the last fix
     * it has - which costs nothing and needs no listener - and falls back to the one
     * remembered from the last successful fetch. Null when the phone has never known where
     * it is and nothing has been saved, which is the only case with nothing to ask about.
     */
    private fun coordinatesFor(context: Context, place: WeatherPlace?): Pair<Double, Double>? {
        if (place == null) return null
        if (!place.isHere) {
            return if (place.latitude.isNaN()) null else place.latitude to place.longitude
        }
        lastKnownLocation(context)?.let { return it }
        return if (place.latitude.isNaN()) null else place.latitude to place.longitude
    }

    /**
     * The last fix the system happens to be holding, if it will part with one.
     *
     * Deliberately not a request for a fresh one: this is called to draw a page, and a
     * page that waits on the radio is a page that is blank for ten seconds. MainActivity's
     * hourly update is what actually goes and looks; this reads whatever that left behind.
     */
    private fun lastKnownLocation(context: Context): Pair<Double, Double>? {
        return try {
            val manager =
                context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                    ?: return null
            val providers = listOf(
                LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER
            )
            for (provider in providers) {
                if (!manager.isProviderEnabled(provider)) continue
                manager.getLastKnownLocation(provider)?.let {
                    return it.latitude to it.longitude
                }
            }
            null
        } catch (e: SecurityException) {
            // No location permission. The app says so on the page rather than here.
            null
        } catch (e: Exception) {
            Log.w(TAG, "could not read the last known position", e)
            null
        }
    }

    // ------------------------------------------------------------------ searching

    /**
     * Looks a place up by name, on Open-Meteo's own geocoder.
     *
     * [onResult] is called on the main thread, with an empty list for both "nothing
     * matched" and "the search failed" - the distinction is not one the page can act on,
     * and a search box that distinguishes them says "no results" either way.
     */
    fun search(query: String, onResult: (List<WeatherPlace>) -> Unit) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            onResult(emptyList())
            return
        }
        Thread {
            val found = try {
                val name = URLEncoder.encode(trimmed, "UTF-8")
                val language = Locale.getDefault().language.ifBlank { "en" }
                val url = "https://geocoding-api.open-meteo.com/v1/search" +
                    "?name=$name&count=8&language=$language&format=json"
                val response = read(url)
                if (response == null) emptyList() else {
                    val results = JSONObject(response).optJSONArray("results")
                    (0 until (results?.length() ?: 0)).mapNotNull { i ->
                        val item = results?.optJSONObject(i) ?: return@mapNotNull null
                        val latitude = item.optDouble("latitude", Double.NaN)
                        val longitude = item.optDouble("longitude", Double.NaN)
                        if (latitude.isNaN() || longitude.isNaN()) return@mapNotNull null
                        WeatherPlace(
                            id = idFor(latitude, longitude),
                            name = item.optString("name"),
                            region = item.optString("admin1"),
                            country = item.optString("country"),
                            latitude = latitude,
                            longitude = longitude
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "could not search for \"$trimmed\"", e)
                emptyList()
            }
            main.post { onResult(found) }
        }.start()
    }

    /**
     * A place's identity, which is where it is rather than what it is called.
     *
     * Two decimal places, which is about a kilometre: fine enough that neighbouring towns
     * are different places, coarse enough that the same town found by two different
     * searches is the same entry rather than a duplicate a few metres away.
     */
    private fun idFor(latitude: Double, longitude: Double): String =
        String.format(Locale.US, "%.2f,%.2f", latitude, longitude)

    // ------------------------------------------------------------------ units

    fun unit(context: Context): String =
        prefs(context).getString(KEY_UNIT, CELSIUS) ?: CELSIUS

    fun setUnit(context: Context, unit: String) {
        prefs(context).edit().putString(KEY_UNIT, unit).apply()
    }

    /**
     * Whether to say anything when rain is on the way.
     *
     * On unless it has been turned off: a weather app that has to be asked before it warns
     * anybody about rain is one that will be looked at after they are already wet. What
     * stops it being a nuisance is [rocks.gorjan.gokixp.apps.weather.RainNotifier]'s rules
     * about when to keep quiet, not a setting nobody found.
     */
    fun rainAlerts(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RAIN_ALERTS, true)

    fun setRainAlerts(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_RAIN_ALERTS, on).apply()
    }

    fun windUnit(context: Context): String =
        prefs(context).getString(KEY_WIND_UNIT, KMH) ?: KMH

    fun setWindUnit(context: Context, unit: String) {
        prefs(context).edit().putString(KEY_WIND_UNIT, unit).apply()
    }

    /** A Celsius reading in whatever degrees the user asked for, rounded to the whole. */
    fun temperature(celsius: Double, unit: String): Int =
        if (unit == FAHRENHEIT) Math.round(celsius * 9.0 / 5.0 + 32.0).toInt()
        else Math.round(celsius).toInt()

    /** The same, ready to be read: "18°". The letter is left off where the page says it once. */
    fun degrees(context: Context, celsius: Double, withLetter: Boolean = false): String {
        if (celsius.isNaN()) return "--°"
        val unit = unit(context)
        return "${temperature(celsius, unit)}°" + if (withLetter) unit else ""
    }

    /** A km/h reading in whatever the user asked for, with its unit written out. */
    fun wind(context: Context, kmh: Double): String {
        if (kmh.isNaN()) return "--"
        return when (windUnit(context)) {
            MPH -> "${Math.round(kmh * 0.621371)} mph"
            MS -> "${Math.round(kmh / 3.6)} m/s"
            else -> "${Math.round(kmh)} km/h"
        }
    }

    /**
     * Which way the wind is coming from, as a point of the compass.
     *
     * The direction a forecast states is the one the wind blows *from*, which is the
     * convention every weather service uses and the opposite of what an arrow would
     * suggest - so it is written as a name rather than drawn as one.
     */
    fun windPoint(degrees: Int): String {
        if (degrees < 0) return ""
        val points = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return points[(Math.round(degrees / 45.0).toInt()) % 8]
    }

    // ------------------------------------------------------------------ plumbing

    /** One request, with the retry left out: the caller is a background thread either way. */
    private fun read(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            if (connection.responseCode != 200) {
                Log.w(TAG, "weather request answered ${connection.responseCode}")
                null
            } else {
                connection.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "weather request failed", e)
            null
        } finally {
            connection?.disconnect()
        }
    }
}
