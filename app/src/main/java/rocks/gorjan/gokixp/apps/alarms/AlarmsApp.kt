package rocks.gorjan.gokixp.apps.alarms

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableString
import android.text.TextUtils
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.Haptics
import rocks.gorjan.gokixp.wp81.MetroAppBar
import rocks.gorjan.gokixp.wp81.MetroLoopSelector
import rocks.gorjan.gokixp.wp81.MetroPageHeader
import rocks.gorjan.gokixp.wp81.MetroPageTransition
import rocks.gorjan.gokixp.wp81.MetroPanorama
import rocks.gorjan.gokixp.wp81.MetroToggle
import rocks.gorjan.gokixp.wp81.SvgIcon
import rocks.gorjan.gokixp.wp81.TiltEffect
import rocks.gorjan.gokixp.wp81.WP81ContextMenu
import rocks.gorjan.gokixp.wp81.WP81InputDialog
import rocks.gorjan.gokixp.wp81.WP81Palette
import rocks.gorjan.gokixp.wp81.applyToField
import java.util.Calendar

/**
 * Alarms, as Windows Phone had them.
 *
 * Three sections on one panorama, which is the arrangement the platform settled on for
 * this: alarms, the stopwatch, and the countdown. They are three different things that all
 * answer to a clock, and the panorama is what lets them be one app without any of them
 * being buried a level down.
 *
 * Almost nothing here is this app's own furniture. The panorama, the command strip, the
 * page header, the prompt, the command list and the press-and-tilt are the shell's, and
 * they arrived built. What this app did add is the two controls the shell had never needed
 * before and now has for good: [MetroToggle], the switch that goes beside anything simply
 * on or off, and [MetroLoopSelector], the column of values you flick - which the alarm's
 * time, the countdown's length and anything after them all set a number with.
 *
 * The counting is not here either. An alarm is [AlarmScheduler]'s and [AlarmRingService]'s
 * business, and the stopwatch and countdown keep their state in [Stopwatch] and
 * [Countdown] so that closing this window does not stop them. What is left in this file is
 * what the user sees, which is the right amount for a file about a panorama.
 */
class AlarmsApp(
    private val context: Context,
    private val palette: WP81Palette,
    /** The shell's own toast: what it says after an alarm is set, and when one cannot be. */
    private val onNotify: (String, String) -> Unit
) {

    private lateinit var root: FrameLayout
    private lateinit var panorama: MetroPanorama
    private lateinit var contextMenu: WP81ContextMenu
    private lateinit var dialog: WP81InputDialog

    /** One strip per section: the three sections have nothing in common to command. */
    private val bars = mutableListOf<MetroAppBar>()

    private lateinit var alarmsColumn: LinearLayout
    private lateinit var tasksColumn: LinearLayout
    private lateinit var stopwatchReadout: TextView
    private lateinit var stopwatchNote: TextView
    private lateinit var lapsColumn: LinearLayout
    private lateinit var countdownReadout: TextView
    private lateinit var countdownNote: TextView

    /** The strip buttons that change what they say as the thing they command changes. */
    private var stopwatchRun: ImageView? = null
    private var stopwatchLap: ImageView? = null
    private var stopwatchReset: ImageView? = null
    private var countdownRun: ImageView? = null
    private var countdownReset: ImageView? = null

    /** Pages stacked over the panorama, newest last. See [handleBack]. */
    private val overlays = mutableListOf<View>()

    /**
     * The one thing in this app that has to be redrawn rather than merely shown.
     *
     * A running stopwatch is a number changing a hundred times a second, and there is no
     * event to hang that on - so this is the exception to the rule that a view is bound
     * when something happens to it. It runs only while a running thing is on screen; see
     * [syncTicker], which is called after everything that could change either.
     */
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            showStopwatch()
            showCountdown()
            // Asked rather than assumed: showCountdown() above may have found the timer
            // finished and turned the ticker off, and a runnable that re-posts itself
            // regardless would keep redrawing a page with nothing moving on it.
            if (ticking) ticker.postDelayed(this, TICK_MS)
        }
    }
    private var ticking = false

    // ---------------------------------------------------------------- construction

    fun createView(): View {
        root = FrameLayout(context).apply { setBackgroundColor(palette.background) }

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        panorama = MetroPanorama(context, palette).apply {
            setPadding(dp(PAGE_MARGIN_DP), 0, 0, 0)
            clipToPadding = false
            clipChildren = false
        }
        panorama.setTitle("alarms")

        panorama.addPage("alarms", alarmsPage())
        panorama.addPage("tasks", tasksPage())
        panorama.addPage("stopwatch", stopwatchPage())
        panorama.addPage("countdown", countdownPage())

        column.addView(panorama, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        bars.add(alarmsBar())
        bars.add(tasksBar())
        bars.add(stopwatchBar())
        bars.add(countdownBar())
        for (bar in bars) {
            root.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        }
        // The sections stop above the strip rather than running under it, so the last
        // alarm in a long list is reachable instead of sitting behind the buttons.
        column.setPadding(0, 0, 0, dp(MetroAppBar.HEIGHT_DP))

        panorama.onPageSettled = { index ->
            showBarFor(index)
            syncTicker()
        }
        showBarFor(PAGE_ALARMS)

        contextMenu = WP81ContextMenu(context, palette)
        root.addView(contextMenu, FrameLayout.LayoutParams(MATCH, MATCH))

        dialog = WP81InputDialog(context, palette)
        root.addView(dialog, FrameLayout.LayoutParams(MATCH, MATCH))

        refresh()
        return root
    }

    /** Rebinds everything from what is actually saved. Cheap, and safe to call at any time. */
    fun refresh() {
        bindAlarms()
        bindTasks()
        showStopwatch()
        showCountdown()
        syncCommands()
        syncTicker()
    }

    /**
     * Called as the window closes.
     *
     * Only the things that would outlive it: the redraw, which has no reason to run with
     * nothing on screen, and a sound preview, which would otherwise carry on playing into
     * an app that is no longer there. The alarms themselves are deliberately untouched -
     * they are the whole point, and they are not this window's to end.
     */
    fun cleanup() {
        ticker.removeCallbacks(tick)
        ticking = false
        AlarmSounds.stopPreview()
    }

    private fun showBarFor(page: Int) {
        for ((index, bar) in bars.withIndex()) {
            if (index != page) bar.closeMenu()
            bar.visibility = if (index == page) View.VISIBLE else View.GONE
        }
    }

    // ---------------------------------------------------------------- alarms

    private fun alarmsPage(): View {
        alarmsColumn = sectionColumn()
        return scroller(alarmsColumn)
    }

    private fun alarmsBar(): MetroAppBar {
        val bar = MetroAppBar(context, palette)
        bar.addCommand(ADD_ICON) { showEditor(null) }
        bar.menu = {
            buildList {
                val alarms = AlarmStore.all(context)
                if (alarms.any { !it.enabled }) {
                    add(MetroAppBar.Item("turn all on") { setAll(true) })
                }
                if (alarms.any { it.enabled }) {
                    add(MetroAppBar.Item("turn all off") { setAll(false) })
                }
                if (alarms.isNotEmpty()) {
                    add(MetroAppBar.Item("delete all") { confirmDeleteAll() })
                }
            }
        }
        return bar
    }

    private fun setAll(on: Boolean) {
        for (alarm in AlarmStore.all(context)) AlarmStore.setEnabled(context, alarm.id, on)
        bindAlarms()
    }

    private fun bindAlarms() {
        if (!::alarmsColumn.isInitialized) return
        alarmsColumn.removeAllViews()

        // An alarm that is going off right now, at the top of its own list.
        //
        // Normally the ring screen is in front of everything and this is never seen. It is
        // here for the two cases where it is not: the screen was left with the back key,
        // which deliberately does not stop the alarm, and the phone has refused this app
        // notifications, which takes the full-screen intent with it. In both, the alarm is
        // audibly going off and the app that owns it should not be the one place with no
        // way to stop it.
        AlarmRingService.current?.let { alarmsColumn.addView(ringingBanner(it), wide()) }

        val alarms = AlarmStore.all(context)
        if (alarms.isEmpty()) {
            alarmsColumn.addView(note("no alarms are set.\ntap + to add one."), wide())
            return
        }
        // Said once, at the top, rather than on every row: on a phone where exact alarms
        // have been refused, every alarm in the list is approximate and the user should
        // hear that from the app rather than from an alarm that goes off late.
        if (!AlarmScheduler.exactAlarmsAllowed(context)) {
            alarmsColumn.addView(
                note("this phone has not allowed exact alarms, so these may ring late."),
                wide()
            )
        }
        for (alarm in alarms) alarmsColumn.addView(alarmRow(alarm), wide())
    }

    /**
     * One alarm in the list.
     *
     * The time large and light, what it repeats under it, and the switch on the right with
     * the word for its state under that. The switch is the row's own control rather than
     * something the row opens: turning an alarm off for one morning is the thing this list
     * is used for most, and it should not cost a page.
     */
    private fun alarmRow(alarm: Alarm): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            setOnClickListener { showEditor(alarm) }
            setOnLongClickListener {
                showMenu(
                    alarm.name.ifBlank { timeOf(alarm.hour, alarm.minute).toString() },
                    listOf(
                        WP81ContextMenu.Item("edit") { showEditor(alarm) },
                        WP81ContextMenu.Item("delete") { confirmDelete(alarm) }
                    ),
                    it
                )
                true
            }
            TiltEffect.apply(this)
        }

        val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val time = TextView(context).apply {
            text = timeOf(alarm.hour, alarm.minute)
            typeface = font(R.font.segoeui_light)
            textSize = 40f
            includeFontPadding = false
            setTextColor(if (alarm.enabled) palette.foreground else palette.foregroundSubtle)
        }
        words.addView(time, wide())

        val detail = TextView(context).apply {
            // The name is what the user called it and comes first; the repeat is what the
            // alarm will actually do, and there is always one of those.
            text = listOf(alarm.name, alarm.repeatText())
                .filter { it.isNotBlank() }
                .joinToString("  ·  ")
            typeface = font(R.font.segoeui_regular)
            textSize = 14f
            setTextColor(palette.foregroundSubtle)
            setPadding(0, dp(2), 0, 0)
        }
        words.addView(detail, wide())

        // A snooze and a dismissed morning are both facts about this alarm that the list
        // would otherwise hide - the switch says "on" either way, and the time still says
        // six o'clock.
        val aside = when {
            alarm.snoozedUntil > System.currentTimeMillis() ->
                "snoozed until ${timeOf(alarm.snoozedUntil)}"
            alarm.dismissedFor > System.currentTimeMillis() ->
                "next one dismissed · rings again ${AlarmScheduler.timeUntil(alarm, System.currentTimeMillis())} from now"
            else -> null
        }
        if (aside != null) {
            words.addView(TextView(context).apply {
                text = aside
                typeface = font(R.font.segoeui_regular)
                textSize = 13f
                setTextColor(palette.accent)
                setPadding(0, dp(3), 0, 0)
            }, wide())
        }

        row.addView(words, LinearLayout.LayoutParams(0, WRAP, 1f))

        // The switch on its own. Its colour is the state - filled with the accent when on,
        // an outline when off - and a word under it repeating that was the row saying the
        // same thing twice in two alphabets.
        val toggle = MetroToggle(context, palette).apply {
            set(alarm.enabled, animated = false)
            onChanged = { on ->
                AlarmStore.setEnabled(context, alarm.id, on)
                time.setTextColor(if (on) palette.foreground else palette.foregroundSubtle)
                if (on) announce(AlarmStore.byId(context, alarm.id))
            }
        }
        row.addView(toggle, LinearLayout.LayoutParams(
            dp(MetroToggle.TRACK_W_DP), dp(MetroToggle.TRACK_H_DP)).apply {
            marginStart = dp(12)
        })

        return row
    }

    /** The alarm that is sounding, and the two things that can be done about it. */
    private fun ringingBanner(ringing: AlarmRingService.Ringing): View {
        val banner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.accent)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        banner.addView(TextView(context).apply {
            text = if (ringing.isCountdown) "countdown finished" else ringing.label
            typeface = font(R.font.segoeui_light)
            textSize = 26f
            setTextColor(palette.onAccent())
        }, wide())

        val commands = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        fun word(text: String, action: String) = TextView(context).apply {
            this.text = text
            typeface = font(R.font.segoeui_regular)
            textSize = 16f
            setTextColor(palette.onAccent())
            setPadding(0, dp(4), dp(24), dp(4))
            isClickable = true
            setOnClickListener {
                Haptics.tap(it)
                AlarmRingService.send(context, action)
                // The service takes a moment to stop and clear what it is holding; the
                // list is rebuilt after it, so the banner goes with the noise.
                ticker.postDelayed({ refresh() }, SETTLE_MS)
            }
            TiltEffect.apply(this)
        }
        // Stop then snooze, as on the ring screen and in the shade.
        commands.addView(word("stop", AlarmRingService.ACTION_DISMISS))
        if (!ringing.isCountdown) {
            commands.addView(word("snooze", AlarmRingService.ACTION_SNOOZE))
        }
        banner.addView(commands, wide())
        return banner
    }

    /** Says when the alarm the user just switched on or saved will actually go off. */
    private fun announce(alarm: Alarm?) {
        if (alarm == null || !alarm.enabled) return
        val away = AlarmScheduler.timeUntil(alarm, System.currentTimeMillis())
        if (away.isNotBlank()) onNotify("Alarm", "Will go off in $away")
    }

    private fun confirmDelete(alarm: Alarm) = ask(
        "delete alarm?",
        "${timeOf(alarm.hour, alarm.minute)} will not go off.",
        "delete"
    ) {
        AlarmStore.delete(context, alarm.id)
        bindAlarms()
    }

    private fun confirmDeleteAll() = ask(
        "delete all?", "Every alarm will be removed.", "delete"
    ) {
        for (alarm in AlarmStore.all(context)) AlarmStore.delete(context, alarm.id)
        bindAlarms()
    }

    // ---------------------------------------------------------------- the editor

    /**
     * One alarm, opened up.
     *
     * A page over the panorama with its own strip, which is how every page in this shell
     * that can be saved or abandoned is built: the tick and the cross are commands, and
     * commands live on the strip.
     */
    private fun showEditor(existing: Alarm?) {
        val page = overlayPage()
        val header = MetroPageHeader(context, palette).apply {
            setTitle(if (existing == null) "new alarm" else "alarm")
            onBack = { handleBack() }
        }
        page.addView(header, wide())

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAGE_MARGIN_DP), 0, dp(PAGE_MARGIN_DP), dp(24))
        }
        page.addView(scroller(body), LinearLayout.LayoutParams(MATCH, 0, 1f))

        // What is being edited, held here and written back only when the tick is pressed.
        val hour = existing?.hour ?: DEFAULT_HOUR
        val minute = existing?.minute ?: 0
        val days = (existing?.days ?: emptySet()).toMutableSet()
        var sound = existing?.sound ?: AlarmSounds.DEFAULT
        var vibrate = existing?.vibrate ?: true

        val clock = TimeColumns(hour, minute)
        body.addView(clock.view, LinearLayout.LayoutParams(MATCH, dp(PICKER_DP)).apply {
            topMargin = dp(4)
        })

        body.addView(label("repeats"), wide())
        body.addView(dayKeyRow(days), wide())
        body.addView(note("no days chosen means it goes off once and then turns itself off."), wide())

        body.addView(label("sound"), wide())
        lateinit var soundRow: TextView
        soundRow = valueRow(AlarmSounds.byId(sound).name) {
            showSoundPicker(sound) { picked ->
                sound = picked
                soundRow.text = AlarmSounds.byId(picked).name
            }
        }
        body.addView(soundRow, wide())

        body.addView(label("name"), wide())
        val nameField = EditText(context).apply {
            setSingleLine()
            textSize = 17f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            typeface = font(R.font.segoeui_regular)
            hint = "alarm"
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setText(existing?.name.orEmpty())
            palette.applyToField(this)
        }
        body.addView(nameField, wide())

        val vibrateRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(20), 0, dp(8))
        }
        vibrateRow.addView(TextView(context).apply {
            text = "vibrate as well"
            typeface = font(R.font.segoeui_regular)
            textSize = 16f
            setTextColor(palette.foreground)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        vibrateRow.addView(
            MetroToggle(context, palette).apply {
                set(vibrate, animated = false)
                onChanged = { vibrate = it }
            },
            LinearLayout.LayoutParams(dp(MetroToggle.TRACK_W_DP), dp(MetroToggle.TRACK_H_DP))
        )
        body.addView(vibrateRow, wide())

        // Two commands and no list behind them. Saving and deleting are the only things
        // that can be done to an alarm from here, and cancelling is what the back arrow at
        // the top of the page and the key at the foot of the screen both already are.
        val bar = MetroAppBar(context, palette)
        bar.addCommand(SAVE_ICON) {
            val saved = AlarmStore.save(context, Alarm(
                id = existing?.id ?: 0L,
                hour = clock.hour(),
                minute = clock.minute(),
                days = days.toSet(),
                sound = sound,
                name = nameField.text.toString().trim(),
                enabled = true,
                vibrate = vibrate,
                // Editing an alarm ends whatever snooze it was under, whichever morning it
                // was sitting out, and whatever it had already given notice of: none of the
                // three is about the alarm this now is.
                snoozedUntil = 0L,
                dismissedFor = 0L,
                warnedFor = 0L
            ))
            hideKeyboard()
            dismissOverlay(page)
            bindAlarms()
            announce(saved)
        }
        // Only when there is something to delete: an alarm that has not been saved yet
        // is thrown away by leaving the page.
        if (existing != null) {
            bar.addCommand(DELETE_ICON) {
                hideKeyboard()
                dismissOverlay(page)
                confirmDelete(existing)
            }
        }
        page.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))

        pushOverlay(page)
        header.playEntrance()
    }

    /**
     * The three columns a time is set on.
     *
     * Twelve-hour or twenty-four, following the phone rather than this app's own opinion -
     * somebody whose phone shows 18:30 everywhere else should not have to work out which
     * six o'clock they mean here. The value stored is always on the 24-hour clock; only the
     * columns differ.
     */
    private inner class TimeColumns(hour: Int, minute: Int) {

        private val is24 = android.text.format.DateFormat.is24HourFormat(context)

        private val hours = MetroLoopSelector(context, palette)
        private val minutes = MetroLoopSelector(context, palette)
        private val meridiem = MetroLoopSelector(context, palette)

        val view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        init {
            hours.setValues(
                if (is24) (0..23).map { two(it) } else (1..12).map { it.toString() },
                if (is24) hour else ((hour + 11) % 12)
            )
            minutes.setValues((0..59).map { two(it) }, minute)
            meridiem.setValues(listOf("am", "pm"), if (hour < 12) 0 else 1)

            view.addView(hours, LinearLayout.LayoutParams(0, MATCH, 1f))
            view.addView(minutes, LinearLayout.LayoutParams(0, MATCH, 1f))
            if (!is24) view.addView(meridiem, LinearLayout.LayoutParams(0, MATCH, 1f))
        }

        fun hour(): Int {
            if (is24) return hours.selectedIndex()
            val twelve = hours.selectedIndex() + 1
            val pm = meridiem.selectedIndex() == 1
            return when {
                twelve == 12 && !pm -> 0
                twelve == 12 -> 12
                pm -> twelve + 12
                else -> twelve
            }
        }

        fun minute(): Int = minutes.selectedIndex()
    }

    // ---------------------------------------------------------------- sounds

    /**
     * The five alarms, to be chosen by ear.
     *
     * Tapping one picks it *and* plays it, which is the only sensible way round: a picker
     * that made you choose first and listen afterwards would be a list of five names that
     * mean nothing. Playing at alarm volume rather than media volume, so what is heard here
     * is what will be heard at six in the morning.
     */
    private fun showSoundPicker(current: String, onPicked: (String) -> Unit) {
        val page = overlayPage()
        val header = MetroPageHeader(context, palette).apply {
            setTitle("sound")
            onBack = { handleBack() }
        }
        page.addView(header, wide())

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAGE_MARGIN_DP), 0, dp(PAGE_MARGIN_DP), dp(24))
        }
        page.addView(scroller(body), LinearLayout.LayoutParams(MATCH, 0, 1f))

        var chosen = current
        val markers = mutableMapOf<String, View>()
        for (sound in AlarmSounds.ALL) {
            val marker = View(context)
            markers[sound.id] = marker
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(13), 0, dp(13))
                isClickable = true
                setOnClickListener {
                    Haptics.tap(it)
                    chosen = sound.id
                    for ((id, dot) in markers) dot.background = markerFor(id == chosen)
                    AlarmSounds.preview(context, sound.id)
                    onPicked(sound.id)
                }
                TiltEffect.apply(this)
            }
            marker.background = markerFor(sound.id == chosen)
            row.addView(marker, LinearLayout.LayoutParams(dp(MARKER_DP), dp(MARKER_DP)))
            row.addView(TextView(context).apply {
                text = sound.name
                typeface = font(R.font.segoeui_regular)
                textSize = 17f
                setTextColor(palette.foreground)
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(14) })
            body.addView(row, wide())
        }
        body.addView(note("tap a sound to hear it. the preview follows the media volume; the alarm itself rings at alarm volume."), wide())

        pushOverlay(page)
        header.playEntrance()
    }

    /** A round mark, filled when chosen: one of a set, so round rather than a ticked square. */
    private fun markerFor(on: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (on) palette.accent else Color.TRANSPARENT)
        setStroke(dp(2), if (on) palette.accent else palette.foregroundSubtle)
    }

    // ---------------------------------------------------------------- tasks

    private fun tasksPage(): View {
        tasksColumn = sectionColumn()
        return scroller(tasksColumn)
    }

    private fun tasksBar(): MetroAppBar {
        val bar = MetroAppBar(context, palette)
        bar.addCommand(ADD_ICON) { showTaskEditor(null) }
        bar.menu = {
            buildList {
                val tasks = TaskStore.all(context)
                if (tasks.any { !it.enabled }) {
                    add(MetroAppBar.Item("turn all on") { setAllTasks(true) })
                }
                if (tasks.any { it.enabled }) {
                    add(MetroAppBar.Item("turn all off") { setAllTasks(false) })
                }
                if (tasks.isNotEmpty()) {
                    add(MetroAppBar.Item("delete all") { confirmDeleteAllTasks() })
                }
            }
        }
        return bar
    }

    private fun setAllTasks(on: Boolean) {
        for (task in TaskStore.all(context)) TaskStore.setEnabled(context, task.id, on)
        bindTasks()
    }

    private fun bindTasks() {
        if (!::tasksColumn.isInitialized) return
        tasksColumn.removeAllViews()
        val tasks = TaskStore.all(context)
        if (tasks.isEmpty()) {
            tasksColumn.addView(
                note("no tasks are set.\ntap + to have something said at a time."), wide())
            return
        }
        for (task in tasks) tasksColumn.addView(taskRow(task), wide())
    }

    /**
     * One task in the list.
     *
     * The other way up from an alarm's row. There, the time is the fact and what it is
     * called is the detail; here the words are the whole point and the time is when they
     * happen, so the words are set large and the clock goes underneath them.
     */
    private fun taskRow(task: Task): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            setOnClickListener { showTaskEditor(task) }
            setOnLongClickListener {
                showMenu(
                    task.text.ifBlank { "task" },
                    listOf(
                        WP81ContextMenu.Item("edit") { showTaskEditor(task) },
                        WP81ContextMenu.Item("delete") { deleteTask(task) }
                    ),
                    it
                )
                true
            }
            TiltEffect.apply(this)
        }

        val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val what = TextView(context).apply {
            text = task.text.ifBlank { "task" }
            typeface = font(R.font.segoeui_regular)
            textSize = 19f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(if (task.enabled) palette.foreground else palette.foregroundSubtle)
        }
        words.addView(what, wide())

        val detail = TextView(context).apply {
            text = TextUtils.concat(timeOf(task.hour, task.minute), "  ·  ", task.repeatText())
            typeface = font(R.font.segoeui_regular)
            textSize = 14f
            setTextColor(palette.foregroundSubtle)
            setPadding(0, dp(3), 0, 0)
        }
        words.addView(detail, wide())

        row.addView(words, LinearLayout.LayoutParams(0, WRAP, 1f))

        // The switch on its own. Its colour is the state - filled with the accent when on,
        // an outline when off - and a word under it repeating that was the row saying the
        // same thing twice in two alphabets.
        val toggle = MetroToggle(context, palette).apply {
            set(task.enabled, animated = false)
            onChanged = { on ->
                TaskStore.setEnabled(context, task.id, on)
                what.setTextColor(if (on) palette.foreground else palette.foregroundSubtle)
                if (on) announceTask(TaskStore.byId(context, task.id))
            }
        }
        row.addView(toggle, LinearLayout.LayoutParams(
            dp(MetroToggle.TRACK_W_DP), dp(MetroToggle.TRACK_H_DP)).apply {
            marginStart = dp(12)
        })

        return row
    }

    private fun announceTask(task: Task?) {
        if (task == null || !task.enabled) return
        val away = TaskScheduler.timeUntil(task, System.currentTimeMillis())
        if (away.isNotBlank()) onNotify("Task", "Will show in $away")
    }

    /**
     * Deletes a task, without asking.
     *
     * No prompt, unlike an alarm's. Deleting an alarm throws away a time somebody set for
     * a morning they have to be up; deleting a task throws away a line of text they can
     * retype in five seconds, and a confirmation for that is a keystroke charged for
     * nothing. "delete all" still asks, because that one is not five seconds.
     */
    private fun deleteTask(task: Task) {
        TaskStore.delete(context, task.id)
        TaskNotifier.clear(context, task.id)
        bindTasks()
    }

    private fun confirmDeleteAllTasks() = ask(
        "delete all?", "Every task will be removed.", "delete"
    ) {
        for (task in TaskStore.all(context)) {
            TaskStore.delete(context, task.id)
            TaskNotifier.clear(context, task.id)
        }
        bindTasks()
    }

    /**
     * One task, opened up.
     *
     * The words first, because they are what the user came here to write; the clock and the
     * days after them. The alarm editor is the other way round for the same reason its row
     * is - there, the time is the thing being set.
     */
    private fun showTaskEditor(existing: Task?) {
        val page = overlayPage()
        val header = MetroPageHeader(context, palette).apply {
            setTitle(if (existing == null) "new task" else "task")
            onBack = { handleBack() }
        }
        page.addView(header, wide())

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAGE_MARGIN_DP), 0, dp(PAGE_MARGIN_DP), dp(24))
        }
        page.addView(scroller(body), LinearLayout.LayoutParams(MATCH, 0, 1f))

        val days = (existing?.days ?: emptySet()).toMutableSet()

        body.addView(label("say"), wide())
        val textField = EditText(context).apply {
            textSize = 17f
            // Several lines rather than one: a task is whatever the user needs to be told,
            // and a field that scrolled sideways past its own beginning would make writing
            // anything longer than four words unpleasant.
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 3
            typeface = font(R.font.segoeui_regular)
            hint = "put the bins out"
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setText(existing?.text.orEmpty())
            palette.applyToField(this)
        }
        body.addView(textField, wide())

        body.addView(label("at"), wide())
        val clock = TimeColumns(existing?.hour ?: DEFAULT_TASK_HOUR, existing?.minute ?: 0)
        body.addView(clock.view, LinearLayout.LayoutParams(MATCH, dp(PICKER_DP)))

        body.addView(label("repeats"), wide())
        body.addView(dayKeyRow(days), wide())
        body.addView(note("no days chosen means it is shown once and then turns itself off."), wide())

        val bar = MetroAppBar(context, palette)
        bar.addCommand(SAVE_ICON) {
            val words = textField.text.toString().trim()
            if (words.isBlank()) {
                // A task with nothing to say is a notification that arrives blank. Said
                // here rather than saved and puzzled over at nine in the morning.
                onNotify("Task", "Write what the task should say")
            } else {
                val saved = TaskStore.save(context, Task(
                    id = existing?.id ?: 0L,
                    text = words,
                    hour = clock.hour(),
                    minute = clock.minute(),
                    days = days.toSet(),
                    enabled = true
                ))
                hideKeyboard()
                dismissOverlay(page)
                bindTasks()
                announceTask(saved)
            }
        }
        if (existing != null) {
            bar.addCommand(DELETE_ICON) {
                hideKeyboard()
                dismissOverlay(page)
                deleteTask(existing)
            }
        }
        page.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))

        pushOverlay(page)
        header.playEntrance()
    }

    // ---------------------------------------------------------------- stopwatch

    private fun stopwatchPage(): View {
        val column = sectionColumn()

        stopwatchReadout = TextView(context).apply {
            typeface = font(R.font.segoeui_light)
            textSize = 56f
            includeFontPadding = false
            setTextColor(palette.foreground)
            setPadding(0, dp(10), 0, 0)
        }
        column.addView(stopwatchReadout, wide())

        stopwatchNote = TextView(context).apply {
            typeface = font(R.font.segoeui_regular)
            textSize = 14f
            setTextColor(palette.foregroundSubtle)
            setPadding(0, dp(4), 0, dp(16))
        }
        column.addView(stopwatchNote, wide())

        lapsColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(lapsColumn, wide())

        return scroller(column)
    }

    private fun stopwatchBar(): MetroAppBar {
        val bar = MetroAppBar(context, palette)
        stopwatchRun = bar.addCommand(PLAY_ICON) {
            if (Stopwatch.state(context).running) Stopwatch.pause(context)
            else Stopwatch.start(context)
            showStopwatch()
            syncCommands()
            syncTicker()
        }
        stopwatchLap = bar.addCommand(LAP_ICON) {
            Stopwatch.lap(context)
            showStopwatch()
            syncCommands()
        }
        stopwatchReset = bar.addCommand(RESET_ICON) {
            Stopwatch.reset(context)
            showStopwatch()
            syncCommands()
            syncTicker()
        }
        return bar
    }

    private fun showStopwatch() {
        if (!::stopwatchReadout.isInitialized) return
        val state = Stopwatch.state(context)
        stopwatchReadout.text = split(state.elapsed())
        // Nothing at all before it has been started: a stopwatch reading 0:00.00 is
        // self-evidently one that has not started, and a line underneath saying so is the
        // page explaining its own zero.
        stopwatchNote.text = when {
            state.running -> "running"
            state.isClear -> ""
            else -> "paused"
        }

        // Rebuilt rather than patched, and only when the count has changed: laps arrive one
        // at a time and there are never many of them, so a row per lap costs nothing and a
        // list that agrees with the store costs nothing to reason about.
        if (lapsColumn.childCount != state.laps.size) {
            lapsColumn.removeAllViews()
            val total = state.laps.size
            for ((index, at) in state.laps.withIndex()) {
                // A lap time is the interval, not the running total: the total is already
                // the number at the top of the page.
                val previous = state.laps.getOrNull(index + 1) ?: 0L
                lapsColumn.addView(lapRow(total - index, at - previous, at), wide())
            }
        }
    }

    private fun lapRow(number: Int, interval: Long, total: Long): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
            addView(TextView(context).apply {
                text = two(number)
                typeface = font(R.font.segoeui_semibold)
                textSize = 13f
                setTextColor(palette.accent)
            }, LinearLayout.LayoutParams(dp(34), WRAP))
            addView(TextView(context).apply {
                text = split(interval)
                typeface = font(R.font.segoeui_regular)
                textSize = 18f
                setTextColor(palette.foreground)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(TextView(context).apply {
                text = split(total)
                typeface = font(R.font.segoeui_regular)
                textSize = 14f
                setTextColor(palette.foregroundSubtle)
            }, LinearLayout.LayoutParams(WRAP, WRAP))
        }

    // ---------------------------------------------------------------- countdown

    private fun countdownPage(): View {
        val column = sectionColumn()

        countdownReadout = TextView(context).apply {
            typeface = font(R.font.segoeui_light)
            textSize = 56f
            includeFontPadding = false
            setTextColor(palette.foreground)
            setPadding(0, dp(10), 0, 0)
            isClickable = true
            setOnClickListener {
                Haptics.tap(it)
                showDurationPicker()
            }
            TiltEffect.apply(this)
        }
        column.addView(countdownReadout, wide())

        countdownNote = TextView(context).apply {
            typeface = font(R.font.segoeui_regular)
            textSize = 14f
            setTextColor(palette.foregroundSubtle)
            setPadding(0, dp(4), 0, dp(16))
        }
        column.addView(countdownNote, wide())

        return scroller(column)
    }

    private fun countdownBar(): MetroAppBar {
        val bar = MetroAppBar(context, palette)
        countdownRun = bar.addCommand(PLAY_ICON) {
            val state = Countdown.state(context)
            when {
                state.running -> Countdown.pause(context)
                state.left() > 0L -> Countdown.start(context)
                // Nothing set yet: the command that would start it asks how long instead,
                // rather than doing nothing and leaving the user to find the readout.
                else -> showDurationPicker()
            }
            showCountdown()
            syncCommands()
            syncTicker()
        }
        countdownReset = bar.addCommand(RESET_ICON) {
            Countdown.reset(context)
            showCountdown()
            syncCommands()
            syncTicker()
        }
        // Start and reset, and that is the strip. Setting the length is done by tapping
        // the time itself, which is where somebody looking to change it looks first - a
        // third ring for it was a second way to the same page. There is no list behind the
        // dots either, so there are no dots.
        return bar
    }

    private fun showCountdown() {
        if (!::countdownReadout.isInitialized) return
        val state = Countdown.state(context)
        countdownReadout.text = duration(state.left())
        countdownNote.text = when {
            state.running -> "counting down"
            !state.isSet -> "tap the time to set a countdown"
            state.left() == state.duration -> "ready"
            else -> "paused"
        }
        // A countdown that has just run out is no longer running, and the strip has to stop
        // offering to pause it.
        if (state.running && state.left() == 0L) {
            syncCommands()
            syncTicker()
        }
    }

    /**
     * How long the countdown runs for.
     *
     * The same columns the alarm's time is set on, holding hours, minutes and seconds
     * instead of a clock. Which is the whole argument for [MetroLoopSelector] being the
     * shell's rather than the alarm editor's.
     */
    private fun showDurationPicker() {
        val page = overlayPage()
        val header = MetroPageHeader(context, palette).apply {
            setTitle("countdown")
            onBack = { handleBack() }
        }
        page.addView(header, wide())

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAGE_MARGIN_DP), 0, dp(PAGE_MARGIN_DP), dp(24))
        }
        page.addView(scroller(body), LinearLayout.LayoutParams(MATCH, 0, 1f))

        val started = Countdown.state(context)
        val whole = started.duration.takeIf { it > 0 } ?: DEFAULT_COUNTDOWN_MS
        val seconds = whole / 1000L

        val columns = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val hours = MetroLoopSelector(context, palette).apply {
            setValues((0..23).map { two(it) }, (seconds / 3600L).toInt())
        }
        val minutes = MetroLoopSelector(context, palette).apply {
            setValues((0..59).map { two(it) }, ((seconds / 60L) % 60L).toInt())
        }
        val secs = MetroLoopSelector(context, palette).apply {
            setValues((0..59).map { two(it) }, (seconds % 60L).toInt())
        }
        columns.addView(hours, LinearLayout.LayoutParams(0, MATCH, 1f))
        columns.addView(minutes, LinearLayout.LayoutParams(0, MATCH, 1f))
        columns.addView(secs, LinearLayout.LayoutParams(0, MATCH, 1f))
        body.addView(columns, LinearLayout.LayoutParams(MATCH, dp(PICKER_DP)))

        // One label per column, each on the same weight as the column above it, so they
        // stay under their own values at any screen width. Written out as a single string
        // with spaces in it they lined up on exactly one phone.
        val labels = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (name in listOf("hours", "minutes", "seconds")) {
            labels.addView(TextView(context).apply {
                text = name
                typeface = font(R.font.segoeui_regular)
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(palette.foregroundSubtle)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        body.addView(labels, wide())

        val bar = MetroAppBar(context, palette)
        bar.addCommand(SAVE_ICON) {
            val chosen = (hours.selectedIndex() * 3600L +
                minutes.selectedIndex() * 60L + secs.selectedIndex()) * 1000L
            if (chosen <= 0L) {
                onNotify("Countdown", "Choose a length longer than zero")
            } else {
                Countdown.setDuration(context, chosen)
                dismissOverlay(page)
                showCountdown()
                syncCommands()
                syncTicker()
            }
        }
        page.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))

        pushOverlay(page)
        header.playEntrance()
    }

    // ---------------------------------------------------------------- strip state

    /**
     * Puts the strips in step with what they command.
     *
     * A ring that says "play" while the thing is running, or that offers to reset a
     * stopwatch that has never been started, is a button that lies about the state of the
     * app - and on a strip of three identical circles it is the only thing to go on.
     */
    private fun syncCommands() {
        val watch = Stopwatch.state(context)
        stopwatchRun?.let { setGlyph(it, if (watch.running) PAUSE_ICON else PLAY_ICON) }
        stopwatchLap?.let { bars.getOrNull(PAGE_STOPWATCH)?.setCommandEnabled(it, watch.running) }
        stopwatchReset?.let {
            bars.getOrNull(PAGE_STOPWATCH)?.setCommandEnabled(it, !watch.isClear)
        }

        val timer = Countdown.state(context)
        countdownRun?.let { setGlyph(it, if (timer.running) PAUSE_ICON else PLAY_ICON) }
        countdownReset?.let {
            bars.getOrNull(PAGE_COUNTDOWN)
                ?.setCommandEnabled(it, timer.isSet && timer.left() != timer.duration)
        }
    }

    private fun setGlyph(button: ImageView, asset: String) {
        button.setImageDrawable(SvgIcon.fromAsset(context, asset))
    }

    /**
     * Redraws only while there is something to redraw.
     *
     * A stopwatch showing hundredths needs the screen thirty times a second, and a phone
     * doing that with the page not even on screen is a phone with a flat battery. So the
     * ticker runs when a running thing is the section being looked at, and not otherwise.
     */
    private fun syncTicker() {
        val page = if (::panorama.isInitialized) panorama.currentPage() else PAGE_ALARMS
        val wanted = when (page) {
            PAGE_STOPWATCH -> Stopwatch.state(context).running
            PAGE_COUNTDOWN -> Countdown.state(context).running
            else -> false
        }
        if (wanted == ticking) return
        ticking = wanted
        ticker.removeCallbacks(tick)
        if (wanted) ticker.postDelayed(tick, TICK_MS)
    }

    // ---------------------------------------------------------------- navigation

    /**
     * Back, from the inside out.
     *
     * The window this app lives in reads back as "put Alarms away", which is right on the
     * panorama and wrong on every page above it. Everything opened over the app is closed
     * first, one press at a time.
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
        for (bar in bars) if (bar.isMenuOpen()) {
            bar.closeMenu()
            return true
        }
        overlays.lastOrNull()?.let { top ->
            // A page carries its own strip, and a command list open on that strip is the
            // innermost thing on screen.
            if (barOf(top)?.closeMenu() == true) return true
            AlarmSounds.stopPreview()
            hideKeyboard()
            dismissOverlay(top)
            // Leaving a page may have changed what is behind it - an alarm saved, a
            // countdown set - and the page underneath has no other way to hear about it.
            refresh()
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

    private fun barOf(page: View): MetroAppBar? {
        val group = page as? android.view.ViewGroup ?: return null
        for (i in 0 until group.childCount) {
            (group.getChildAt(i) as? MetroAppBar)?.let { return it }
        }
        return null
    }

    private fun showMenu(title: String?, items: List<WP81ContextMenu.Item>, anchor: View) {
        val position = IntArray(2)
        anchor.getLocationInWindow(position)
        contextMenu.bringToFront()
        contextMenu.show(title, items, position[1].toFloat())
    }

    private fun ask(title: String, question: String, accept: String, onAccept: () -> Unit) {
        dialog.bringToFront()
        dialog.confirm(title, question, accept, onAccept)
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(root.windowToken, 0)
    }

    // ---------------------------------------------------------------- furniture

    private fun overlayPage(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(palette.background)
        // Anything that falls past the rows stops here, so the panorama underneath does not
        // page sideways while a page is open on top of it.
        isClickable = true
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
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun note(message: String) = TextView(context).apply {
        text = message
        typeface = font(R.font.segoeui_regular)
        textSize = 14f
        setTextColor(palette.foregroundSubtle)
        setPadding(0, dp(14), dp(8), dp(6))
    }

    /** A value sitting under its own label, tapped to change it. */
    private fun valueRow(text: String, onTap: () -> Unit): TextView = TextView(context).apply {
        this.text = text
        typeface = font(R.font.segoeui_regular)
        textSize = 18f
        setTextColor(palette.foreground)
        setPadding(0, dp(8), 0, dp(8))
        isClickable = true
        setOnClickListener {
            Haptics.tap(it)
            onTap()
        }
        TiltEffect.apply(this)
    }

    /**
     * The seven keys that say which days something comes round on.
     *
     * Writes straight into the set it is handed, which is the editor's own working copy -
     * so the alarm editor and the task editor share the control without either of them
     * having to hear about the other.
     */
    private fun dayKeyRow(days: MutableSet<Int>): View {
        val keys = mutableMapOf<Int, TextView>()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(6))
        }
        for (day in Schedule.ORDER) {
            val key = dayKey(Schedule.INITIAL[day].orEmpty(), day in days) {
                if (day in days) days.remove(day) else days.add(day)
                paintDayKey(keys.getValue(day), day in days)
            }
            keys[day] = key
            row.addView(key, LinearLayout.LayoutParams(0, dp(DAY_KEY_DP), 1f).apply {
                if (day != Schedule.ORDER.first()) marginStart = dp(6)
            })
        }
        return row
    }

    /** One day of the week, as a key that is either in or out. */
    private fun dayKey(initial: String, on: Boolean, onTap: () -> Unit): TextView =
        TextView(context).apply {
            text = initial
            typeface = font(R.font.segoeui_regular)
            textSize = 16f
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener {
                Haptics.tap(it)
                onTap()
            }
            TiltEffect.apply(this)
            paintDayKey(this, on)
        }

    private fun paintDayKey(key: TextView, on: Boolean) {
        key.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(if (on) palette.accent else Color.TRANSPARENT)
            setStroke(dp(2), if (on) palette.accent else palette.foregroundSubtle)
        }
        key.setTextColor(if (on) palette.onAccent() else palette.foreground)
    }

    // ---------------------------------------------------------------- words and numbers

    /**
     * A time of day, with the am or pm set smaller.
     *
     * The platform's own treatment: the hour and minute are the fact and are read at a
     * glance, and the two letters after them are a qualifier rather than half the number.
     */
    private fun timeOf(hour: Int, minute: Int): CharSequence {
        if (android.text.format.DateFormat.is24HourFormat(context)) {
            return "${two(hour)}:${two(minute)}"
        }
        val twelve = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        val suffix = if (hour < 12) " am" else " pm"
        val text = "$twelve:${two(minute)}$suffix"
        return SpannableString(text).apply {
            setSpan(
                RelativeSizeSpan(0.42f), text.length - suffix.length, text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /** The same, from a moment rather than an hour and a minute. */
    private fun timeOf(at: Long): CharSequence {
        val calendar = Calendar.getInstance().apply { timeInMillis = at }
        return timeOf(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))
    }

    /**
     * A stopwatch reading: minutes, seconds and hundredths, with the hour only once there
     * is one. A leading "0:" on every reading is a column of noughts nobody is timing.
     */
    private fun split(millis: Long): String {
        val hundredths = (millis / 10) % 100
        val seconds = (millis / 1000) % 60
        val minutes = (millis / 60_000) % 60
        val hours = millis / 3_600_000
        return if (hours > 0) "$hours:${two(minutes.toInt())}:${two(seconds.toInt())}.${two(hundredths.toInt())}"
        else "$minutes:${two(seconds.toInt())}.${two(hundredths.toInt())}"
    }

    /**
     * A countdown reading, which rounds up rather than down.
     *
     * A timer showing 0:00 for the last second before it goes off has already finished as
     * far as anybody reading it is concerned, and then rings a second later.
     */
    private fun duration(millis: Long): String {
        val total = (millis + 999L) / 1000L
        val seconds = total % 60
        val minutes = (total / 60) % 60
        val hours = total / 3600
        return if (hours > 0) "$hours:${two(minutes.toInt())}:${two(seconds.toInt())}"
        else "${two(minutes.toInt())}:${two(seconds.toInt())}"
    }

    private fun two(value: Int): String = if (value < 10) "0$value" else value.toString()

    private fun font(res: Int): Typeface? = ResourcesCompat.getFont(context, res)

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun wide() = LinearLayout.LayoutParams(MATCH, WRAP)

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        const val PAGE_ALARMS = 0
        const val PAGE_TASKS = 1
        const val PAGE_STOPWATCH = 2
        const val PAGE_COUNTDOWN = 3

        const val PAGE_MARGIN_DP = 22

        /** Tall enough for three values in a column: the one chosen, and one either side. */
        const val PICKER_DP = MetroLoopSelector.ROW_DP * 3

        const val DAY_KEY_DP = 44
        const val MARKER_DP = 20

        /** Thirty times a second, which is what a display of hundredths is worth. */
        const val TICK_MS = 33L

        /** Long enough for the ring service to have stopped and let go of what it held. */
        const val SETTLE_MS = 250L

        /** Where a new alarm starts: seven in the morning, and no repeat. */
        const val DEFAULT_HOUR = 7

        /** And a new task: nine, which is when the day's errands get remembered. */
        const val DEFAULT_TASK_HOUR = 9

        const val DEFAULT_COUNTDOWN_MS = 5 * 60 * 1000L

        const val ICON_DIR = "custom_icons_8"
        const val ADD_ICON = "$ICON_DIR/appbar.add.svg"
        const val SAVE_ICON = "$ICON_DIR/appbar.check.svg"
        const val DELETE_ICON = "$ICON_DIR/appbar.delete.svg"
        const val PLAY_ICON = "$ICON_DIR/appbar.control.play.svg"
        const val PAUSE_ICON = "$ICON_DIR/appbar.control.pause.svg"
        const val LAP_ICON = "$ICON_DIR/appbar.flag.svg"
        const val RESET_ICON = "$ICON_DIR/appbar.reset.svg"
    }
}
