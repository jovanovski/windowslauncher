package rocks.gorjan.gokixp.apps.weather

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.Haptics
import rocks.gorjan.gokixp.wp81.MetroAppBar
import rocks.gorjan.gokixp.wp81.MetroChoiceRow
import rocks.gorjan.gokixp.wp81.MetroPageHeader
import rocks.gorjan.gokixp.wp81.MetroPageTransition
import rocks.gorjan.gokixp.wp81.MetroPanorama
import rocks.gorjan.gokixp.wp81.TiltEffect
import rocks.gorjan.gokixp.wp81.WP81ContextMenu
import rocks.gorjan.gokixp.wp81.WP81Palette
import rocks.gorjan.gokixp.wp81.WeatherCodes
import rocks.gorjan.gokixp.wp81.WeatherDay
import rocks.gorjan.gokixp.wp81.WeatherHour
import rocks.gorjan.gokixp.wp81.WeatherPlace
import rocks.gorjan.gokixp.wp81.WeatherReport
import rocks.gorjan.gokixp.wp81.WeatherStore
import rocks.gorjan.gokixp.wp81.applyToField
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Weather, as Windows Phone had it.
 *
 * The Start screen's weather tile shows one reading at a time and a wide one shows three,
 * which is the right amount for a wall and nowhere near enough to decide anything with.
 * This is the same forecast opened out: what it is doing now, what the day is shaped like,
 * what the week is, and the same questions asked about somewhere else.
 *
 * Four sections on one panorama, which is the arrangement the platform used for an app
 * whose parts are all about one subject - the sections here are not different tools, they
 * are the same forecast at four lengths, and burying any of them a level down would be
 * hiding half a forecast behind a tap.
 *
 * Nothing is fetched here. [WeatherStore] holds the phone's one forecast, and the taskbar
 * temperature, the tile, the Quick Glance widget and this app all read it - so opening
 * this cannot produce a second answer that disagrees with the one on Start. What this app
 * adds is the places, the units, and the room to show what was already being fetched.
 */
class WeatherApp(
    private val context: Context,
    private val palette: WP81Palette,
    /** The shell's own toast, for the few things that happen off-screen. */
    private val onNotify: (String, String) -> Unit,
    /** Asks for location permission, which only the launcher's Activity can do. */
    private val onAskForLocation: () -> Unit,
    /** Told after a fetch lands, so the Start screen's weather tile keeps up with it. */
    private val onWeatherChanged: () -> Unit
) {

    private lateinit var root: FrameLayout
    private lateinit var panorama: MetroPanorama
    private lateinit var contextMenu: WP81ContextMenu

    /** One strip per section: only the places section has anything of its own to command. */
    private val bars = mutableListOf<MetroAppBar>()

    /**
     * Every section's refresh ring, each with the strip it belongs to.
     *
     * Kept in pairs because dimming one is the strip's business, not the button's: a ring
     * handed to another strip's [MetroAppBar.setCommandEnabled] happens to work today and
     * is a lie about which strip owns it.
     */
    private val refreshButtons = mutableListOf<Pair<MetroAppBar, ImageView>>()

    private lateinit var todayColumn: LinearLayout
    private lateinit var daysColumn: LinearLayout
    private lateinit var placesColumn: LinearLayout

    /** Pages stacked over the panorama, newest last. See [handleBack]. */
    private val overlays = mutableListOf<View>()

    /** Whether the readings behind "more info" are showing. Survives a rebind. */
    private var moreInfoOpen = false

    /** The search box's own delay, so a name is looked up once rather than per letter. */
    private val typing = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null

    // ---------------------------------------------------------------- construction

    fun createView(): View {
        root = FrameLayout(context).apply { setBackgroundColor(palette.background) }

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        panorama = MetroPanorama(context, palette).apply {
            setPadding(dp(PAGE_MARGIN_DP), 0, 0, 0)
            clipToPadding = false
            clipChildren = false
        }
        // The place, not the program. A weather app's title bar has one useful thing to
        // say and it is which town is being shown - the app names itself on the tile that
        // opened it and on the strip along the bottom, and saying it a third time in the
        // largest type on the screen would be the one heading that cannot change.
        panorama.onTitle = { panorama.goTo(PAGE_PLACES, animated = true) }

        todayColumn = sectionColumn()
        daysColumn = sectionColumn()
        placesColumn = sectionColumn()

        panorama.addPage("today", scroller(todayColumn))
        panorama.addPage("this week", scroller(daysColumn))
        panorama.addPage("places", scroller(placesColumn))

        column.addView(panorama, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        repeat(PAGE_COUNT) { page ->
            val bar = buildBar(withAdd = page == PAGE_PLACES)
            bars.add(bar)
            root.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        }
        // The sections stop above the strip rather than running under it, so the last line
        // of a long forecast is reachable instead of sitting behind the buttons.
        column.setPadding(0, 0, 0, dp(MetroAppBar.HEIGHT_DP))

        panorama.onPageSettled = { index ->
            showBarFor(index)
            // The other places' temperatures are only worth a request when somebody is
            // looking at the list of them, and only then if they have gone stale.
            if (index == PAGE_PLACES) {
                WeatherStore.refreshSummaries(context) { bindPlaces() }
            }
        }
        showBarFor(PAGE_TODAY)

        contextMenu = WP81ContextMenu(context, palette)
        root.addView(contextMenu, FrameLayout.LayoutParams(MATCH, MATCH))

        bind()
        // Opening a weather app is a request for what it is doing now. Asked for on the
        // way in, but not forced: the tile may have fetched two minutes ago, and a fresh
        // request for an answer that cannot have changed is a fetch nobody asked for.
        refresh(force = false)
        return root
    }

    /** Rebinds every section from what is cached. Cheap, and safe to call at any time. */
    fun bind() {
        // Before the sections, and outside bindToday, which returns early when there is no
        // forecast - the heading says where, and where is known before anything about it is.
        panorama.setTitle(WeatherStore.selected(context)?.name ?: "weather")
        bindToday()
        bindDays()
        bindPlaces()
        syncCommands()
    }

    fun cleanup() {
        pendingSearch?.let { typing.removeCallbacks(it) }
    }

    // ---------------------------------------------------------------- the strip

    /**
     * The strip: what to do here, and nothing behind an ellipsis.
     *
     * Every command this app has is a ring on the row. The dots exist for the tail of a
     * long list, and a program with three commands has no tail - a switch of units sitting
     * behind them was a setting filed under "more", one tap from the settings page that
     * already holds it and says what it is set to.
     */
    private fun buildBar(withAdd: Boolean): MetroAppBar {
        val bar = MetroAppBar(context, palette)
        if (withAdd) bar.addCommand(ADD_ICON) { showSearch() }
        refreshButtons.add(bar to bar.addCommand(REFRESH_ICON) { refresh(force = true) })
        bar.addCommand(SETTINGS_ICON) { showSettings() }
        return bar
    }

    private fun showBarFor(page: Int) {
        for ((index, bar) in bars.withIndex()) {
            bar.visibility = if (index == page) View.VISIBLE else View.GONE
        }
    }

    /** Dims the refresh rings while a fetch is out, so the strip says one is. */
    private fun syncCommands() {
        val idle = !WeatherStore.isFetching()
        for ((bar, button) in refreshButtons) bar.setCommandEnabled(button, idle)
    }

    /**
     * Asks for the forecast again, and redraws when it lands.
     *
     * The redraw happens whether or not anything arrived: a failed fetch leaves the cache
     * as it was, and re-binding it costs nothing and puts the strip's rings back.
     */
    private fun refresh(force: Boolean) {
        if (WeatherStore.isFetching()) return
        WeatherStore.refresh(context, force) {
            bind()
            onWeatherChanged()
        }
        // After, not before: the store decides whether there is anything worth fetching,
        // and only once it has decided is there anything for the rings to say.
        syncCommands()
    }

    // ---------------------------------------------------------------- today

    /**
     * The reading as it stands, the day's shape, and the numbers behind both.
     *
     * The temperature is set enormous and everything else is small, which is the one
     * decision this page really makes: somebody opening a weather app has one question
     * nine times out of ten, and it should be answered before they have focused on
     * anything else.
     */
    private fun bindToday() {
        todayColumn.removeAllViews()
        val report = WeatherStore.report(context)
        val now = report?.now
        if (report == null || now == null) {
            todayColumn.addView(emptyNote(), wide())
            return
        }

        // The reading, and the three figures that qualify it, on one line. They belong
        // beside the temperature rather than under it: "34°" on its own is the answer to
        // half a question, and how it feels and how far the day moves are the other half -
        // not a footnote to be read after it.
        val hero = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, dp(14), 0, 0)
        }

        val temperature = TextView(context).apply {
            text = WeatherStore.degrees(context, now.temperature)
            typeface = font(R.font.segoeui_light)
            textSize = 92f
            includeFontPadding = false
            setTextColor(palette.foreground)
        }
        hero.addView(temperature, LinearLayout.LayoutParams(WRAP, WRAP))

        val figures = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            // Raised off the foot of the row by the numeral's own descent, which is empty
            // space - "34°" has nothing below its baseline - so the last of the three sits
            // on the temperature's baseline rather than under the hole beneath it.
            setPadding(dp(12), 0, 0, descentOf(temperature))
        }
        if (!now.apparent.isNaN()) {
            figures.addView(
                figure("feels like ${WeatherStore.degrees(context, now.apparent)}"), wide())
        }
        report.today()?.let { today ->
            figures.addView(figure("high ${WeatherStore.degrees(context, today.high)}"), wide())
            figures.addView(figure("low ${WeatherStore.degrees(context, today.low)}"), wide())
        }
        hero.addView(figures, LinearLayout.LayoutParams(0, WRAP, 1f))
        todayColumn.addView(hero, wide())

        // The word for the reading, set against the bottom of the numeral rather than
        // under its line box.
        //
        // At 92sp a font reserves something like twenty device-independent pixels below
        // the baseline for descenders, and "34°" has none - so laid out honestly the word
        // starts a finger's width beneath the temperature it belongs to, with nothing in
        // between. Its own line box adds a second gap above its letters for the same
        // reason. Both are measured off rather than guessed at with a padding, because
        // both depend on the font and on the two sizes, and a number tuned by eye here
        // would be wrong on the next screen that shipped a different Segoe.
        val condition = TextView(context).apply {
            text = WeatherCodes.condition(now.code)
            typeface = font(R.font.segoeui_semilight)
            textSize = 22f
            includeFontPadding = false
            setTextColor(palette.foreground)
        }
        todayColumn.addView(condition, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dp(CONDITION_GAP_DP) - descentOf(temperature) - capGapOf(condition)
        })

        // The next day, as a line, and with no heading over it: a row of hours under a
        // temperature is self-evidently the hours after it, and a label would be a line of
        // the screen spent saying so.
        val ahead = report.hoursAhead().take(CURVE_HOURS)
        if (ahead.size > 1) {
            todayColumn.addView(
                curveFor(report, ahead),
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(CURVE_GAP_DP) }
            )
        }

        todayColumn.addView(
            detailGrid(report, now),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) }
        )
    }

    /** The empty space a line of type reserves below its baseline, in pixels. */
    private fun descentOf(view: TextView): Int = view.paint.fontMetrics.descent.toInt()

    /**
     * How far below the top of its line box a font's capitals actually start, in pixels.
     *
     * Turning off font padding takes away the padding, not the ascent: the box still
     * begins where the tallest possible letter would, and most letters are not that tall.
     */
    private fun capGapOf(view: TextView): Int {
        val metrics = view.paint.fontMetrics
        val caps = android.graphics.Rect()
        view.paint.getTextBounds("H", 0, 1, caps)
        return ((-metrics.ascent) - caps.height()).toInt().coerceAtLeast(0)
    }

    /** One of the figures standing beside the temperature. */
    private fun figure(text: String) = TextView(context).apply {
        this.text = text
        typeface = font(R.font.segoeui_regular)
        textSize = 17f
        gravity = Gravity.END
        setTextColor(palette.foreground)
        setPadding(0, dp(2), 0, 0)
    }

    /** The line of hours, in a scroller of its own so the day can be pushed past. */
    private fun curveFor(report: WeatherReport, hours: List<WeatherHour>): View {
        val curve = WeatherCurveView(context, palette)
        val nowHour = report.hourNow()
        curve.setPoints(hours.map { hour ->
            WeatherCurveView.Point(
                label = hourLabel(hour.hour),
                value = hour.temperature,
                reading = WeatherStore.degrees(context, hour.temperature),
                glyph = WeatherCodes.glyph(hour.code, night = !hour.isDay),
                chance = hour.chance,
                now = hour.time == nowHour
            )
        })
        return HourStrip(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isHorizontalScrollBarEnabled = false
            addView(curve, FrameLayout.LayoutParams(WRAP, WRAP))
        }
    }

    /**
     * A sideways scroller living inside a panorama, which is two sideways gestures in the
     * same place.
     *
     * The panorama takes any drag that is clearly horizontal, and it is an ancestor, so it
     * gets first refusal on every event and the strip underneath never saw one: the hours
     * were drawn past the edge of the screen with no way to reach them.
     *
     * So the strip claims the gesture on the way down, and hands it back only once the
     * drag has proved to be vertical - which is the page being scrolled, and not this.
     *
     * "Proved" is the whole of it. The first move of any drag is a pixel or two of jitter,
     * and a version of this that read a direction out of that let go before the finger had
     * gone anywhere: dx comes back 0 on the first move about as often as not, which read
     * as "rightwards", which at scroll position zero read as "off the end", which handed
     * the panorama a gesture meant for the hours. Nothing under the slop is a direction.
     */
    private inner class HourStrip(context: Context) : HorizontalScrollView(context) {

        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) claim(ev)
            return super.onInterceptTouchEvent(ev)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> claim(event)
                MotionEvent.ACTION_MOVE -> {
                    val dx = kotlin.math.abs(event.x - downX)
                    val dy = kotlin.math.abs(event.y - downY)
                    if (dy > touchSlop && dy > dx) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
            }
            return super.onTouchEvent(event)
        }

        private fun claim(event: MotionEvent) {
            downX = event.x
            downY = event.y
            parent?.requestDisallowInterceptTouchEvent(true)
        }
    }

    /**
     * The numbers behind the reading, two to a row.
     *
     * Four of them are up where they can be read without asking: whether it is going to
     * rain, whether the sun will burn, how close the air is and what the wind is doing.
     * Those are the ones that change what somebody puts on or whether they go now.
     *
     * The rest are real readings that almost nobody opens a forecast for - pressure is for
     * people who already know what to do with it - so they are behind a heading that opens.
     * A screenful of figures with the useful four somewhere in it is a screenful nobody
     * reads; four is a glance.
     *
     * Everything here is optional. A cache written before a field was asked for simply has
     * no line about it, which is better than a grid of dashes explaining what the phone
     * failed to fetch.
     */
    private fun detailGrid(report: WeatherReport, now: rocks.gorjan.gokixp.wp81.WeatherNow): View {
        val today = report.today()
        val wind = if (now.wind.isNaN()) null else {
            val point = WeatherStore.windPoint(now.windFrom)
            WeatherStore.wind(context, now.wind) + if (point.isNotBlank()) " $point" else ""
        }

        val asked = buildList {
            today?.let { day ->
                if (day.chance >= 0) add("chance of rain" to "${day.chance}%")
                if (!day.uv.isNaN()) add("uv index" to uvReading(day.uv))
            }
            if (now.humidity >= 0) add("humidity" to "${now.humidity}%")
            wind?.let { add("wind" to it) }
        }
        val rest = buildList {
            if (!now.gusts.isNaN()) add("gusts" to WeatherStore.wind(context, now.gusts))
            if (now.cloud >= 0) add("cloud cover" to "${now.cloud}%")
            if (!now.pressure.isNaN()) add("pressure" to "${Math.round(now.pressure)} hPa")
            today?.let { day ->
                if (!day.rainfall.isNaN()) {
                    add("rain today" to String.format(Locale.getDefault(), "%.1f mm", day.rainfall))
                }
                if (day.sunrise.isNotBlank()) add("sunrise" to clock(day.sunrise))
                if (day.sunset.isNotBlank()) add("sunset" to clock(day.sunset))
            }
        }

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(grid(asked), wide())
        if (rest.isNotEmpty()) {
            val more = grid(rest).apply {
                // Whatever it was last left as. Rebinding happens on every refresh and on
                // every change of unit, and a panel that shut itself each time would be
                // one the reader has to open again to finish reading it.
                visibility = if (moreInfoOpen) View.VISIBLE else View.GONE
            }
            column.addView(expander("more info", more), wide())
            column.addView(more, wide())
        }
        return column
    }

    /** Readings laid out two to a row, each under its own name. */
    private fun grid(items: List<Pair<String, String>>): View {
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (pair in items.chunked(2)) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (item in pair) {
                row.addView(detailCell(item.first, item.second),
                    LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            // A single item on the last row keeps its column rather than spreading across
            // both, so the grid stays a grid all the way down.
            if (pair.size == 1) row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            grid.addView(row, wide())
        }
        return grid
    }

    /**
     * A heading that opens what is under it.
     *
     * Set like the other headings on the page, so the section it opens reads as one of
     * them rather than as a control - the chevron is the only thing saying there is
     * anything to do here, and it turns over rather than swapping for a second mark.
     */
    private fun expander(title: String, panel: View): View {
        val chevron = ImageView(context).apply {
            setImageDrawable(rocks.gorjan.gokixp.wp81.SvgIcon.fromAsset(context, MORE_ICON))
            imageTintList = android.content.res.ColorStateList.valueOf(palette.accent)
            scaleType = ImageView.ScaleType.FIT_CENTER
            rotation = if (moreInfoOpen) 180f else 0f
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(20), 0, dp(6))
            isClickable = true
            setOnClickListener {
                Haptics.tap(it)
                moreInfoOpen = !moreInfoOpen
                panel.visibility = if (moreInfoOpen) View.VISIBLE else View.GONE
                chevron.animate().rotation(if (moreInfoOpen) 180f else 0f)
                    .setDuration(EXPAND_MS).start()
            }
            TiltEffect.apply(this)
        }
        row.addView(TextView(context).apply {
            text = title
            typeface = font(R.font.segoeui_semibold)
            textSize = 12f
            letterSpacing = 0.06f
            setTextColor(palette.accent)
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        row.addView(chevron, LinearLayout.LayoutParams(dp(13), dp(13)).apply {
            marginStart = dp(8)
        })
        return row
    }

    private fun detailCell(name: String, value: String): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), dp(14), dp(10))
            addView(TextView(context).apply {
                text = name
                typeface = font(R.font.segoeui_regular)
                textSize = 14f
                setTextColor(palette.foregroundSubtle)
            }, wide())
            addView(TextView(context).apply {
                text = value
                typeface = font(R.font.segoeui_semilight)
                textSize = 23f
                setTextColor(palette.foreground)
                setPadding(0, dp(1), 0, 0)
            }, wide())
        }

    /**
     * One hour, as a row.
     *
     * There is no hourly section any more - the line of hours on "today" says the same
     * thing in one look, and a page that listed them again underneath it was the same
     * forecast twice. This is still how a day opened out of the week lists its own hours,
     * which is the one place a column of them is the right shape.
     */
    private fun hourRow(hour: WeatherHour, isNow: Boolean): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), dp(4), dp(9))
        }
        row.addView(TextView(context).apply {
            text = hourLabel(hour.hour)
            typeface = font(if (isNow) R.font.segoeui_semibold else R.font.segoeui_regular)
            textSize = 15f
            setTextColor(if (isNow) palette.accent else palette.foregroundSubtle)
        }, LinearLayout.LayoutParams(dp(HOUR_LABEL_DP), WRAP))

        row.addView(mark(WeatherCodes.glyph(hour.code, night = !hour.isDay)),
            LinearLayout.LayoutParams(dp(ROW_GLYPH_DP), dp(ROW_GLYPH_DP)).apply {
                marginEnd = dp(12)
            })

        row.addView(TextView(context).apply {
            text = WeatherCodes.condition(hour.code)
            typeface = font(R.font.segoeui_semilight)
            textSize = 16f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.foreground)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))

        // Rain only when there is a chance of it, for the same reason the line of hours
        // leaves it out: a column of noughts down a clear day is data, not information.
        if (hour.chance >= CHANCE_FLOOR) {
            row.addView(TextView(context).apply {
                text = "${hour.chance}%"
                typeface = font(R.font.segoeui_regular)
                textSize = 15f
                setTextColor(palette.accent)
                setPadding(0, 0, dp(12), 0)
            }, LinearLayout.LayoutParams(WRAP, WRAP))
        }

        row.addView(TextView(context).apply {
            text = WeatherStore.degrees(context, hour.temperature)
            typeface = font(R.font.segoeui_semilight)
            textSize = 20f
            gravity = Gravity.END
            setTextColor(palette.foreground)
        }, LinearLayout.LayoutParams(dp(READING_DP), WRAP))
        return row
    }

    // ---------------------------------------------------------------- the week

    /**
     * The week, one row a day.
     *
     * This used to draw each day's range as a bar on the week's own scale - the idea being
     * that seven pairs of numbers is a table nobody reads across, and seven bars on one
     * scale is a week you can see a cold snap in. It was not readable: a coloured band
     * between two temperatures, on a scale stated nowhere, is a thing to be worked out
     * rather than glanced at, and the first question it drew was what it meant.
     *
     * So the row says it in the words the hourly rows use instead, and the reading is left
     * to do its own comparing.
     */
    private fun bindDays() {
        daysColumn.removeAllViews()
        val report = WeatherStore.report(context)
        if (report == null || report.days.isEmpty()) {
            daysColumn.addView(emptyNote(), wide())
            return
        }
        for (day in report.days) daysColumn.addView(dayRow(day, report), wide())
    }

    private fun dayRow(day: WeatherDay, report: WeatherReport): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(13), dp(4), dp(13))
            isClickable = true
            setOnClickListener { showDay(day, report) }
            TiltEffect.apply(this)
        }

        row.addView(TextView(context).apply {
            text = dayName(day.date, report)
            typeface = font(R.font.segoeui_semilight)
            textSize = 17f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.foreground)
        }, LinearLayout.LayoutParams(dp(DAY_NAME_DP), WRAP))

        row.addView(mark(WeatherCodes.glyph(day.code)),
            LinearLayout.LayoutParams(dp(ROW_GLYPH_DP), dp(ROW_GLYPH_DP)).apply {
                marginEnd = dp(12)
            })

        // The same shape as an hourly row: what the sky is doing, then the chance of rain
        // where there is one, then the reading. A week and a day read the same way down
        // the page, and the eye only has to learn one column.
        row.addView(TextView(context).apply {
            text = WeatherCodes.condition(day.code)
            typeface = font(R.font.segoeui_semilight)
            textSize = 16f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.foreground)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))

        // The low first, the high after it. The two are the same size and the same face,
        // and only the colour separates them - a row of a week is read down its columns,
        // and a low set smaller than the high made two ragged columns out of what should
        // be two straight ones.
        //
        // No chance of rain here. The week is where the shape of it is read, and a figure
        // that appears on three rows out of seven is a column that is mostly empty; the
        // hours are where somebody who wants to know when goes.
        row.addView(TextView(context).apply {
            text = WeatherStore.degrees(context, day.low)
            typeface = font(R.font.segoeui_semilight)
            textSize = 20f
            gravity = Gravity.END
            setTextColor(palette.foregroundSubtle)
        }, LinearLayout.LayoutParams(dp(EDGE_READING_DP), WRAP))

        row.addView(TextView(context).apply {
            text = WeatherStore.degrees(context, day.high)
            typeface = font(R.font.segoeui_semilight)
            textSize = 20f
            gravity = Gravity.END
            setTextColor(palette.foreground)
        }, LinearLayout.LayoutParams(dp(READING_DP), WRAP))
        return row
    }

    // ---------------------------------------------------------------- places

    /**
     * Everywhere the weather is being kept an eye on.
     *
     * The phone's own position is always first and cannot be removed, so there is always
     * something to fall back to when a place is deleted - and so that a weather app never
     * has to be told where its owner lives.
     */
    private fun bindPlaces() {
        placesColumn.removeAllViews()
        val selected = WeatherStore.selectedId(context)
        for (place in WeatherStore.places(context)) {
            placesColumn.addView(placeRow(place, place.id == selected), wide())
        }
        placesColumn.addView(note("use the + to add a place"), wide())
        if (!hasLocationPermission() ) {
            placesColumn.addView(actionNote(
                "the phone has not been allowed to say where it is, so \"my location\" " +
                    "has nothing to look up.  tap to allow it"
            ) { onAskForLocation() }, wide())
        }
    }

    private fun placeRow(place: WeatherPlace, isSelected: Boolean): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), dp(4), dp(12))
            isClickable = true
            setOnClickListener { choose(place) }
            setOnLongClickListener {
                Haptics.tap(it)
                showPlaceMenu(place, it)
                true
            }
            TiltEffect.apply(this)
        }

        val name = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        name.addView(TextView(context).apply {
            text = place.name
            typeface = font(
                if (isSelected) R.font.segoeui_semibold else R.font.segoeui_semilight)
            textSize = 19f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            // The one being shown is named in the accent, which is how this shell says
            // "this one" everywhere else.
            setTextColor(if (isSelected) palette.accent else palette.foreground)
        }, wide())
        val subtitle = place.subtitle()
        if (subtitle.isNotBlank()) {
            name.addView(TextView(context).apply {
                text = subtitle
                typeface = font(R.font.segoeui_regular)
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(palette.foregroundSubtle)
            }, wide())
        }
        row.addView(name, LinearLayout.LayoutParams(0, WRAP, 1f))

        // The selected place's reading comes from the forecast rather than from the
        // summary saved on the place: the forecast is newer by definition, and the two
        // disagreeing on the same screen is the worst way to find that out.
        val reading = if (isSelected) WeatherStore.report(context)?.now?.temperature
            else place.temperature
        val code = if (isSelected) WeatherStore.report(context)?.now?.code ?: -1
            else place.code

        row.addView(mark(WeatherCodes.glyph(code)),
            LinearLayout.LayoutParams(dp(ROW_GLYPH_DP), dp(ROW_GLYPH_DP)).apply {
                marginEnd = dp(12)
            })
        row.addView(TextView(context).apply {
            text = if (reading == null) "--" else WeatherStore.degrees(context, reading)
            typeface = font(R.font.segoeui_semilight)
            textSize = 22f
            gravity = Gravity.END
            setTextColor(palette.foreground)
        }, LinearLayout.LayoutParams(dp(READING_DP), WRAP))
        return row
    }

    /**
     * Shows a different place, and goes to look at it.
     *
     * Selecting clears the held forecast - it was the other place's - so the page would
     * otherwise be blank while the fetch is out. Going to "today" is deliberate: choosing
     * a place from a list is asking what the weather is there, and staying on the list
     * would answer it with a two-digit number at the end of a row.
     */
    private fun choose(place: WeatherPlace) {
        if (place.id != WeatherStore.selectedId(context)) {
            WeatherStore.select(context, place.id)
            bind()
            refresh(force = true)
        }
        panorama.goTo(PAGE_TODAY, animated = true)
    }

    private fun showPlaceMenu(place: WeatherPlace, anchor: View) {
        // The phone's own position is not a place somebody added, so there is nothing to
        // remove and no list worth opening for it.
        if (place.isHere) return
        val position = IntArray(2)
        anchor.getLocationInWindow(position)
        contextMenu.bringToFront()
        contextMenu.show(place.name, listOf(
            WP81ContextMenu.Item("remove") {
                WeatherStore.remove(context, place.id)
                bind()
                // Removing the place that was being shown leaves nothing cached, so the
                // one it fell back to has to be asked about.
                refresh(force = true)
            }
        ), position[1].toFloat())
    }

    // ---------------------------------------------------------------- a day, opened

    /**
     * One day, opened out: what it is shaped like and what is behind the figures.
     *
     * A row in the week says a high, a low and a mark, which is enough to plan around and
     * not enough to plan with - "18 and rain" is a different afternoon depending on
     * whether the rain is at nine or at six, and this is where that is answered.
     */
    private fun showDay(day: WeatherDay, report: WeatherReport) {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            isClickable = true
        }
        page.addView(MetroPageHeader(context, palette).apply {
            setTitle(dayName(day.date, report))
            onBack = { handleBack() }
        }, wide())

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAGE_MARGIN_DP), 0, dp(PAGE_MARGIN_DP), dp(24))
        }

        column.addView(TextView(context).apply {
            text = WeatherStore.degrees(context, day.high)
            typeface = font(R.font.segoeui_light)
            textSize = 72f
            includeFontPadding = false
            setTextColor(palette.foreground)
        }, wide())
        column.addView(TextView(context).apply {
            text = "${WeatherCodes.condition(day.code)}" +
                "     low ${WeatherStore.degrees(context, day.low)}"
            typeface = font(R.font.segoeui_semilight)
            textSize = 18f
            setTextColor(palette.foreground)
            setPadding(0, dp(6), 0, 0)
        }, wide())

        val facts = buildList {
            if (day.chance >= 0) add("chance of rain" to "${day.chance}%")
            if (!day.rainfall.isNaN()) {
                add("rainfall" to String.format(Locale.getDefault(), "%.1f mm", day.rainfall))
            }
            if (!day.wind.isNaN()) add("wind up to" to WeatherStore.wind(context, day.wind))
            if (!day.uv.isNaN()) add("uv index" to uvReading(day.uv))
            if (day.sunrise.isNotBlank()) add("sunrise" to clock(day.sunrise))
            if (day.sunset.isNotBlank()) add("sunset" to clock(day.sunset))
        }
        column.addView(grid(facts), wide())

        // Only the hours still to come, and only on the day being looked at. A page about
        // Thursday that opens on Thursday shows the rest of Thursday, not the small hours
        // of it that are already over.
        val hours = report.hours.filter { it.day == day.date && it.time >= report.hourNow() }
        if (hours.isNotEmpty()) {
            column.addView(label("hour by hour"), wide())
            val nowHour = report.hourNow()
            for (hour in hours) {
                column.addView(hourRow(hour, isNow = hour.time == nowHour), wide())
            }
        }

        page.addView(scroller(column), LinearLayout.LayoutParams(MATCH, 0, 1f))
        pushOverlay(page)
    }

    // ---------------------------------------------------------------- searching

    /**
     * Adding a place: a box, and whatever the geocoder makes of what is in it.
     *
     * Looked up as the name is typed rather than on a key, which is what every search on
     * this platform did - but on a delay, so a six-letter town is one request and not six.
     */
    private fun showSearch() {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            isClickable = true
        }
        page.addView(MetroPageHeader(context, palette).apply {
            setTitle("add a place")
            onBack = { handleBack() }
        }, wide())

        val results = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAGE_MARGIN_DP), dp(6), dp(PAGE_MARGIN_DP), dp(24))
        }

        val field = EditText(context).apply {
            hint = "town or city"
            typeface = font(R.font.segoeui_regular)
            textSize = 18f
            maxLines = 1
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(10), dp(10), dp(10), dp(10))
            palette.applyToField(this)
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString().orEmpty()
                pendingSearch?.let { typing.removeCallbacks(it) }
                if (query.trim().length < MIN_QUERY) {
                    results.removeAllViews()
                    return
                }
                val run = Runnable {
                    WeatherStore.search(query) { found ->
                        // The box may have moved on while the request was out, and results
                        // for a name nobody is looking at any more are worse than none.
                        if (field.text.toString() != query) return@search
                        showResults(results, found)
                    }
                }
                pendingSearch = run
                typing.postDelayed(run, SEARCH_DELAY_MS)
            }
        })

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAGE_MARGIN_DP), 0, dp(PAGE_MARGIN_DP), 0)
        }
        body.addView(field, wide())
        page.addView(body, wide())
        page.addView(scroller(results), LinearLayout.LayoutParams(MATCH, 0, 1f))

        pushOverlay(page)
        field.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        imm?.showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun showResults(into: LinearLayout, found: List<WeatherPlace>) {
        into.removeAllViews()
        if (found.isEmpty()) {
            into.addView(note("nothing found by that name"), wide())
            return
        }
        for (place in found) {
            into.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(12), 0, dp(12))
                isClickable = true
                setOnClickListener { addPlace(place) }
                TiltEffect.apply(this)
                addView(TextView(context).apply {
                    text = place.name
                    typeface = font(R.font.segoeui_semilight)
                    textSize = 19f
                    setTextColor(palette.foreground)
                }, wide())
                val subtitle = place.subtitle()
                if (subtitle.isNotBlank()) {
                    addView(TextView(context).apply {
                        text = subtitle
                        typeface = font(R.font.segoeui_regular)
                        textSize = 12f
                        setTextColor(palette.foregroundSubtle)
                    }, wide())
                }
            }, wide())
        }
    }

    private fun addPlace(place: WeatherPlace) {
        if (!WeatherStore.add(context, place)) {
            onNotify("Weather", "That is as many places as this will hold")
            return
        }
        hideKeyboard()
        overlays.lastOrNull()?.let { dismissOverlay(it) }
        bind()
        refresh(force = true)
        panorama.goTo(PAGE_TODAY, animated = true)
    }

    // ---------------------------------------------------------------- settings

    /**
     * The app's own settings, which are the units it is read in.
     *
     * Here rather than on the shell's settings page: they are this app's business, and
     * degrees are the one thing a weather app must let the reader change without going
     * looking for where the phone keeps its settings.
     */
    private fun showSettings() {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            isClickable = true
        }
        page.addView(MetroPageHeader(context, palette).apply {
            setTitle("settings")
            onBack = { handleBack() }
        }, wide())

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAGE_MARGIN_DP), 0, dp(PAGE_MARGIN_DP), dp(24))
        }

        column.addView(label("temperature"), wide())
        column.addView(choices(
            listOf(
                WeatherStore.CELSIUS to "celsius  (°C)",
                WeatherStore.FAHRENHEIT to "fahrenheit  (°F)"
            ),
            chosen = WeatherStore.unit(context),
            onPick = {
                WeatherStore.setUnit(context, it)
                bind()
                // The tile reads the same setting and would otherwise carry the old
                // degrees until something else happened to refresh it.
                onWeatherChanged()
            }
        ), wide())

        column.addView(label("wind speed"), wide())
        column.addView(choices(
            listOf(
                WeatherStore.KMH to "kilometres an hour",
                WeatherStore.MPH to "miles an hour",
                WeatherStore.MS to "metres a second"
            ),
            chosen = WeatherStore.windUnit(context),
            onPick = {
                WeatherStore.setWindUnit(context, it)
                bind()
            }
        ), wide())

        column.addView(label("notifications"), wide())
        column.addView(
            MetroChoiceRow(context, palette, "rain notifications", round = false).apply {
                set(WeatherStore.rainAlerts(context))
                onPicked = { on ->
                    WeatherStore.setRainAlerts(context, on)
                    // Switched off with one still in the shade, the one in the shade is
                    // the setting still being on as far as anybody reading it can tell.
                    if (!on) RainNotifier.clear(context)
                }
            }, wide())
        column.addView(note(
            "readings come from Open-Meteo, which is asked for the place shown and told " +
                "nothing else about this phone."
        ), wide())

        page.addView(scroller(column), LinearLayout.LayoutParams(MATCH, 0, 1f))
        pushOverlay(page)
    }

    /**
     * One setting with several answers, only one of which can be in force.
     *
     * Round marks, which is what says so - see [rocks.gorjan.gokixp.wp81.MetroMarker].
     * Built as a group rather than a row at a time because choosing one has to unchoose
     * its neighbours, and something has to be holding all of them to do that.
     */
    private fun <T> choices(
        options: List<Pair<T, String>>,
        chosen: T,
        onPick: (T) -> Unit
    ): View {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val rows = mutableListOf<Pair<T, MetroChoiceRow>>()
        for ((value, name) in options) {
            val row = MetroChoiceRow(context, palette, name, round = true)
            row.set(value == chosen)
            row.onPicked = {
                onPick(value)
                for ((other, view) in rows) view.set(other == value)
            }
            rows += value to row
            column.addView(row, wide())
        }
        return column
    }

    // ---------------------------------------------------------------- pages

    /**
     * Closes whatever is open over the panorama, and says whether there was anything.
     *
     * The host asks before it closes the window: back means "out of this page" while one
     * is open, and "out of the app" only once it is not.
     */
    fun handleBack(): Boolean {
        if (contextMenu.isShowing()) {
            contextMenu.dismiss()
            return true
        }
        overlays.lastOrNull()?.let { top ->
            hideKeyboard()
            dismissOverlay(top)
            return true
        }
        return false
    }

    private fun pushOverlay(view: View) {
        overlays.add(view)
        root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        MetroPageTransition(view).playIn()
    }

    private fun dismissOverlay(view: View) {
        overlays.remove(view)
        MetroPageTransition(view).playOut { root.removeView(view) }
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(root.windowToken, 0)
    }

    // ---------------------------------------------------------------- words

    /**
     * An hour of the day, in whichever clock the phone is set to.
     *
     * Written as a whole time - "15:00", not "15". A bare number in a column of
     * temperatures is one more number, and the reader has to work out which of the two
     * kinds it is before they can use either.
     */
    private fun hourLabel(hour: Int): String {
        if (android.text.format.DateFormat.is24HourFormat(context)) {
            return "${if (hour < 10) "0$hour" else hour}:00"
        }
        val twelve = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return "$twelve:00 ${if (hour < 12) "am" else "pm"}"
    }

    /** A time out of the forecast - "2026-09-02T06:12" - as a clock reading. */
    private fun clock(iso: String): String {
        if (iso.length < 16) return iso
        val hour = iso.substring(11, 13).toIntOrNull() ?: return iso.substring(11, 16)
        val minute = iso.substring(14, 16)
        if (android.text.format.DateFormat.is24HourFormat(context)) {
            return "${if (hour < 10) "0$hour" else hour}:$minute"
        }
        val twelve = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return "$twelve:$minute ${if (hour < 12) "am" else "pm"}"
    }

    /**
     * A date out of the forecast as the name a reader would call it.
     *
     * "Today" is the place's today, not the phone's: it is half past one on Wednesday in
     * Auckland while it is still Tuesday afternoon here, and a forecast headed "tomorrow"
     * for the day somebody there is living through is simply wrong.
     */
    private fun dayName(date: String, report: WeatherReport): String {
        val today = report.hourNow().substring(0, 10)
        if (date == today) return "today"
        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        stamp.timeZone = TimeZone.getTimeZone("UTC")
        return try {
            val at = stamp.parse(date) ?: return date
            val here = stamp.parse(today) ?: return date
            val days = Math.round((at.time - here.time) / DAY_MS.toDouble()).toInt()
            if (days == 1) return "tomorrow"
            val weekday = SimpleDateFormat("EEEE", Locale.getDefault())
            weekday.timeZone = TimeZone.getTimeZone("UTC")
            weekday.format(at).lowercase(Locale.getDefault())
        } catch (e: Exception) {
            date
        }
    }

    /**
     * The UV index with the word that says what to do about it.
     *
     * The number alone is a scale nobody has memorised - the bands are the whole reason
     * the index exists, and "7" without "high" beside it is a fact with no advice in it.
     */
    private fun uvReading(uv: Double): String {
        val value = Math.round(uv).toInt()
        val band = when {
            value <= 2 -> "low"
            value <= 5 -> "moderate"
            value <= 7 -> "high"
            value <= 10 -> "very high"
            else -> "extreme"
        }
        return "$value  $band"
    }

    private fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- furniture

    /**
     * What a section says when there is no forecast to show.
     *
     * Three different reasons and three different sentences, because the thing to do about
     * them differs: wait, allow the phone to say where it is, or add somewhere to look at.
     */
    private fun emptyNote(): View = when {
        WeatherStore.isFetching() -> note("reading the forecast…")
        !hasLocationPermission() && WeatherStore.selected(context)?.isHere == true ->
            actionNote(
                "the phone has not been allowed to say where it is.  tap to allow it, " +
                    "or add a place by name from the places section"
            ) { onAskForLocation() }
        else -> note("no reading yet.  pull the refresh from the strip below")
    }

    private fun sectionColumn() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), dp(PAGE_MARGIN_DP), dp(24))
    }

    private fun scroller(content: View): ScrollView = ScrollView(context).apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    private fun label(text: String) = TextView(context).apply {
        this.text = text
        typeface = font(R.font.segoeui_semibold)
        textSize = 12f
        letterSpacing = 0.06f
        setTextColor(palette.accent)
        setPadding(0, dp(22), 0, dp(4))
    }

    private fun note(message: String) = TextView(context).apply {
        text = message
        typeface = font(R.font.segoeui_regular)
        textSize = 14f
        setTextColor(palette.foregroundSubtle)
        setPadding(0, dp(18), dp(8), dp(6))
        setLineSpacing(0f, 1.1f)
    }

    /** The same, where there is something to do about what it says. */
    private fun actionNote(message: String, onTap: () -> Unit) = note(message).apply {
        setTextColor(palette.accent)
        isClickable = true
        setOnClickListener { onTap() }
        TiltEffect.apply(this)
    }

    /** A condition's mark, tinted to the page rather than to a tile. */
    private fun mark(res: Int?): View = ImageView(context).apply {
        if (res != null) {
            setImageResource(res)
            imageTintList = android.content.res.ColorStateList.valueOf(palette.foreground)
        }
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private fun font(res: Int): Typeface? = ResourcesCompat.getFont(context, res)

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun wide() = LinearLayout.LayoutParams(MATCH, WRAP)

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        const val PAGE_TODAY = 0
        const val PAGE_PLACES = 2
        const val PAGE_COUNT = 3

        const val PAGE_MARGIN_DP = 22

        /** How many hours the line on the "today" page runs to. A day, and a look at the next. */
        const val CURVE_HOURS = 30

        /** Under this, rain is not worth saying anything about. */
        const val CHANCE_FLOOR = 20

        const val HOUR_LABEL_DP = 76
        const val DAY_NAME_DP = 96
        const val ROW_GLYPH_DP = 26
        const val READING_DP = 64
        const val EDGE_READING_DP = 60

        /**
         * Air between the temperature and the word for it.
         *
         * Measured from the foot of the numeral to the top of the letters, which is what
         * the two measurements either side of it are for - a plain margin here would be
         * this plus about thirty pixels of a 92sp font's unused descent.
         */
        const val CONDITION_GAP_DP = 15

        /**
         * Air between the word and the line of hours under it.
         *
         * Wider than the gap above, and deliberately: the two lines above it are one
         * statement about now, and the line of hours is the next thing being said.
         */
        const val CURVE_GAP_DP = 28

        /** Shorter than this and a search is a letter, not a name. */
        const val MIN_QUERY = 2

        /** How long the box waits after the last keystroke before it looks anything up. */
        const val SEARCH_DELAY_MS = 400L

        const val DAY_MS = 24L * 60L * 60L * 1000L

        const val ICON_DIR = "custom_icons_8"
        const val ADD_ICON = "$ICON_DIR/appbar.add.svg"
        const val REFRESH_ICON = "$ICON_DIR/appbar.refresh.svg"
        const val SETTINGS_ICON = "$ICON_DIR/appbar.cog.svg"
        const val MORE_ICON = "$ICON_DIR/appbar.chevron.down.svg"

        /** How long the chevron takes to turn over. */
        const val EXPAND_MS = 160L
    }
}
