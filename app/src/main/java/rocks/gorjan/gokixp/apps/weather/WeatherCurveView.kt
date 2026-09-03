package rocks.gorjan.gokixp.apps.weather

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.WP81Palette

/**
 * The hours ahead, as a line rather than as a list.
 *
 * A column of hourly rows answers "what is it at four" and hides the thing anybody
 * actually scrolls an hourly forecast for, which is the shape of the day - when it warms
 * up, how far it falls tonight, whether the rain is at the start of the afternoon or the
 * end of it. A line says that in one look and the rows never do.
 *
 * Drawn rather than laid out, for the same reason the tile's forecast panel is: this is
 * one plot with five bands of different kinds of thing stacked against each other, and
 * keeping them on one baseline is a canvas's work, not a nest of rows.
 *
 * It is deliberately wider than the screen and lives in a horizontal scroller. A day
 * squeezed to fit a phone's width is a line with no shape left in it, so each hour gets a
 * fixed column and the reader pushes the day past instead.
 */
@SuppressLint("ViewConstructor")
class WeatherCurveView(
    context: Context,
    private var palette: WP81Palette
) : View(context) {

    /**
     * One hour on the line.
     *
     * [value] is what the curve is plotted against and [reading] is what is written above
     * the point - two fields for one number because the plot is metric and the page may be
     * in Fahrenheit, and a curve replotted in converted degrees would have a different
     * shape for the same weather.
     */
    data class Point(
        val label: String,
        val value: Double,
        val reading: String,
        val glyph: Int?,
        /** Chance of rain, 0-100, or -1 where the forecast did not say. */
        val chance: Int,
        /** The hour being lived through, marked so the line has a "you are here". */
        val now: Boolean = false
    )

    private var points: List<Point> = emptyList()

    /** The marks, held past the first draw: every column asks for one on every frame. */
    private val icons = HashMap<Int, Drawable?>()

    private val readingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, R.font.segoeui_semibold)
        textAlign = Paint.Align.CENTER
        textSize = sp(READING_SP)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
        textAlign = Paint.Align.CENTER
        textSize = sp(LABEL_SP)
    }

    private val chancePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
        textAlign = Paint.Align.CENTER
        textSize = sp(CHANCE_SP)
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(LINE_DP)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** The two halves of a point: the disc under it, and the ring round that. */
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(LINE_DP)
    }

    private val curve = Path()

    fun setPoints(points: List<Point>) {
        if (points == this.points) return
        this.points = points
        requestLayout()
        invalidate()
    }

    fun applyPalette(p: WP81Palette) {
        palette = p
        // The marks are tinted as they are drawn, and a tinted drawable cannot be
        // re-tinted once it has been.
        icons.clear()
        invalidate()
    }

    /**
     * As wide as it has hours, and as tall as the bands come to.
     *
     * The width is stated rather than negotiated: the scroller around it would otherwise
     * offer it the screen, and a day is not a screen wide.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = (points.size * dp(HOUR_DP)).toInt().coerceAtLeast(1)
        setMeasuredDimension(width, dp(HEIGHT_DP).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (points.isEmpty() || width == 0) return

        val column = dp(HOUR_DP)
        val readingHeight = readingPaint.descent() - readingPaint.ascent()
        val labelHeight = labelPaint.descent() - labelPaint.ascent()
        val chanceHeight = chancePaint.descent() - chancePaint.ascent()
        val glyph = dp(GLYPH_DP)

        // The plot is what is left once every band that has to be a fixed size has taken
        // its share: the reading over each point, then the line, then the mark, the chance
        // and the hour under it.
        val top = dp(EDGE_DP) + readingHeight
        val bottom = height - dp(EDGE_DP) - labelHeight - chanceHeight - glyph -
            dp(BAND_GAP_DP) - 2 * dp(MARK_GAP_DP)
        val plot = (bottom - top).coerceAtLeast(dp(MIN_PLOT_DP))

        // Against the day's own range rather than a fixed scale. A twelve-degree day and a
        // two-degree one are both worth seeing the shape of, and a line drawn on an
        // absolute scale flattens the second into a straight one. The floor stops a run of
        // identical hours turning into a plot with no vertical extent to divide by.
        val high = points.maxOf { it.value }
        val low = points.minOf { it.value }
        val span = (high - low).coerceAtLeast(MIN_SPAN)

        fun yOf(value: Double) = (bottom - ((value - low) / span) * plot).toFloat()

        curve.reset()
        for ((index, point) in points.withIndex()) {
            val x = column * (index + 0.5f)
            val y = yOf(point.value)
            if (index == 0) curve.moveTo(x, y) else curve.lineTo(x, y)
        }
        linePaint.color = palette.accent
        canvas.drawPath(curve, linePaint)

        fillPaint.color = palette.background
        ringPaint.color = palette.accent
        for ((index, point) in points.withIndex()) {
            val x = column * (index + 0.5f)
            val y = yOf(point.value)

            // The current hour is filled in the accent; the rest are hollowed out of the
            // page, so the line has a place to be read from rather than a row of identical
            // beads. The hollow ones are cut out of the background first so the line does
            // not show through the middle of them.
            if (point.now) {
                canvas.drawCircle(x, y, dp(DOT_DP), ringPaint.also { it.style = Paint.Style.FILL })
                ringPaint.style = Paint.Style.STROKE
            } else {
                canvas.drawCircle(x, y, dp(DOT_DP), fillPaint)
                canvas.drawCircle(x, y, dp(DOT_DP), ringPaint)
            }

            readingPaint.color = palette.foreground
            canvas.drawText(point.reading, x, y - dp(READING_LIFT_DP), readingPaint)

            // The chance of rain first, then the mark, then the hour. Above rather than
            // below, because the band is reserved whether or not there is a figure to put
            // in it - so with the figure underneath, a mark with rain over it sat at the
            // same height as one without, but the row of marks read as two rows to anyone
            // scanning across. Over the mark, the marks are the line and the figures hang
            // off the top of it.
            var band = bottom + dp(BAND_GAP_DP)

            // Only where there is a chance worth mentioning. A column of "0%" under a
            // clear day is a row of noughts that says nothing and reads as data.
            if (point.chance >= CHANCE_FLOOR) {
                chancePaint.color = palette.accent
                canvas.drawText("${point.chance}%", x, band - chancePaint.ascent(), chancePaint)
            }
            band += chanceHeight + dp(MARK_GAP_DP)

            icon(point.glyph)?.let { mark ->
                mark.setBounds(
                    (x - glyph / 2f).toInt(), band.toInt(),
                    (x + glyph / 2f).toInt(), (band + glyph).toInt()
                )
                mark.draw(canvas)
            }
            band += glyph + dp(MARK_GAP_DP)

            labelPaint.color =
                if (point.now) palette.foreground else palette.foregroundSubtle
            canvas.drawText(point.label, x, band - labelPaint.ascent(), labelPaint)
        }
    }

    /** The mark for a condition, tinted and held. */
    private fun icon(res: Int?): Drawable? {
        if (res == null) return null
        return icons.getOrPut(res) {
            ContextCompat.getDrawable(context, res)?.mutate()?.apply {
                setTint(palette.foregroundSubtle)
            }
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun sp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    companion object {
        /** How wide one hour is. Wide enough for "12:00 am" under it without crowding. */
        const val HOUR_DP = 66f

        /** The whole plot, bands and all. */
        const val HEIGHT_DP = 216f

        private const val EDGE_DP = 6f
        private const val BAND_GAP_DP = 8f

        /**
         * Air either side of the condition mark.
         *
         * The chance above it and the hour below it are both type, and type set hard
         * against a solid mark reads as part of it - the figure looked like a label on the
         * cloud rather than a reading of its own.
         */
        private const val MARK_GAP_DP = 3f
        /**
         * The condition mark under each hour.
         *
         * Large enough to be read as a sky rather than as a bullet: this row is the only
         * place on the page that says what the next day is *doing*, and at tile-corner
         * size the difference between a cloud and a cloud with rain under it was gone.
         *
         * Four fifths of what it was, now that the marks are the pack's solid ones rather
         * than the outlines this was first sized against - the same box of ink reads
         * heavier filled, and at the old size a row of clouds was competing with the line
         * above it for the eye.
         */
        private const val GLYPH_DP = 25.6f

        /** However flat the day, the line is still meant to have somewhere to go. */
        private const val MIN_PLOT_DP = 40f

        /** Degrees. A day that varies by less than this is drawn as though it varied by this. */
        private const val MIN_SPAN = 3.0

        private const val LINE_DP = 2f
        private const val DOT_DP = 3.5f

        /** How far the reading sits above its own point. */
        private const val READING_LIFT_DP = 9f

        private const val READING_SP = 14f
        private const val LABEL_SP = 12f
        private const val CHANCE_SP = 12f

        /** Under this, rain is not worth a line of its own. */
        private const val CHANCE_FLOOR = 20
    }
}
