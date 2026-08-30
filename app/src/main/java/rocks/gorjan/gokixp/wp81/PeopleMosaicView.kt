package rocks.gorjan.gokixp.wp81

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R

/**
 * The People tile: a wall of the faces of the people the user marked, and enough of the
 * rest of the address book to fill it.
 *
 * This is the one Windows Phone tile that was never a reading or a headline. It filled
 * itself edge to edge with contact pictures - nine of them on the square tile, eighteen
 * across the wide one - each under some amount of black, so the wall was a mix of faces
 * caught in the light and faces most of the way into it. Every few seconds one square
 * turned over to somebody else, so a glance at it was a glance at the people rather than
 * at an app called People. Every so often it stopped shuffling and one of them took the
 * whole tile: a face, full bleed, with their name along the bottom, before it turned back
 * to the wall.
 *
 * All three are here. The squares turn one at a time, round-robin over the grid and over
 * whoever was handed over - favourites first, so they are the faces the wall opens on, and
 * the few behind them are what a square has to turn over to; each turn draws
 * its square a fresh depth of black - see [shade]; and after a few of those the whole
 * mosaic turns over onto one person and back again. The names are not drawn here - the
 * tile's own label carries them, so a name on this tile is set in the same type as a name
 * on any other, and [onHero] is how it hears about it.
 *
 * Drawn rather than assembled out of child views, unlike the folder preview it otherwise
 * resembles. Everything on it is a rectangle of a picture: the squares, the flip that
 * turns one of them over, and the same flip applied to the whole tile for the takeover.
 * One canvas doing all three keeps the takeover from having to rearrange a grid of views
 * mid-animation.
 *
 * The pictures come from [ContactFeed], which holds them for every tile at once and
 * decides who is on the wall in the first place. What is *not* held here is the address
 * book itself: the host reads that, because it is the host that has to ask for the
 * permission first. See MainActivity.refreshWP81People.
 */
@SuppressLint("ViewConstructor")
class PeopleMosaicView(
    context: Context,
    private var palette: WP81Palette
) : View(context) {

    private var people: List<ContactFeed.Person> = emptyList()

    /** Which person each square is showing, as indices into [people]. */
    private val shown = mutableListOf<Int>()

    /**
     * How far each square is darkened, 0 to [MAX_SHADE].
     *
     * Windows Phone did not show the faces at full strength and even: every square sat
     * under some amount of black, drawn afresh each time one turned over, so the wall read
     * as one surface with faces surfacing out of it rather than as a contact sheet. Some
     * are barely touched, some are most of the way into the dark, and which is which
     * changes every time the square does.
     */
    private val shade = mutableListOf<Float>()

    /** Pictures currently being decoded, so the same one is not asked for twice. */
    private val awaiting = mutableSetOf<String>()

    /** And pictures that came back empty, so a dead one is not asked for on every frame. */
    private val unreadable = mutableSetOf<String>()

    /** Round-robin cursors, so every square takes a turn and everybody gets shown. */
    private var nextSlot = 0
    private var nextPerson = 0
    private var nextHero = 0

    private var columns = 3
    private var rows = 3

    private var paused = false

    /**
     * Who currently has the whole tile, if anybody.
     *
     * Set at the halfway point of the turn that brings them, which is where the tile is
     * edge-on and the change cannot be seen - the same moment the tile itself swaps faces.
     */
    private var hero: ContactFeed.Person? = null

    /** How many squares have turned over since the last takeover. */
    private var sinceHero = 0

    /** Told whenever the takeover comes or goes, so the tile's label can carry the name. */
    var onHero: ((ContactFeed.Person?) -> Unit)? = null

    // --- What is mid-flip ---------------------------------------------------------------
    // Two flips, never at once: one square turning over, or the whole mosaic turning onto
    // a face and back. Both are the same rotation about the horizontal axis, so they share
    // [turn] - how far through it is, 0 to 1 - and differ only in what it is applied to.

    private var turningSlot = WHOLE
    private var turn = 0f
    private var turning = false
    private var animator: ValueAnimator? = null

    private val camera = Camera()
    private val matrix = Matrix()

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** The block a contact with no picture gets, lightening the tile's own fill. */
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** What each square is darkened with. Its alpha is set per square - see [shade]. */
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

    private val initialsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, R.font.segoeui_semilight)
        textAlign = Paint.Align.CENTER
    }

    private val src = Rect()
    private val dst = Rect()

    /**
     * This view's own offset into the cycle, 1-5 seconds.
     *
     * The same trick the tiles themselves use: without it every People tile on the wall -
     * and the mosaic and the tiles around it - would move on the same frame.
     */
    private val phaseMs: Long = 1000L + (Math.random() * 4000L).toLong()

    private val tick = object : Runnable {
        override fun run() {
            step()
            postDelayed(this, STEP_MS)
        }
    }

    init {
        applyPalette(palette)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(tick)
        postDelayed(tick, phaseMs)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(tick)
        animator?.cancel()
    }

    fun applyPalette(p: WP81Palette) {
        palette = p
        // The same translucent white the folder preview lightens its squares with: a
        // person with no picture is still a square of this tile, not a hole in it, and a
        // block of white lets the Start background through where the tile is a window.
        scrimPaint.color = Color.argb(SCRIM_ALPHA, 255, 255, 255)
        initialsPaint.color = p.onAccent()
        invalidate()
    }

    /** Nothing turns over while the tile is being arranged. */
    fun setPaused(value: Boolean) {
        paused = value
    }

    /**
     * Sets how many faces fill the tile, across and down.
     *
     * Taken from the tile's footprint by the caller rather than measured here - see
     * TileView.applyPeopleGrid, which is where the shapes are argued out.
     */
    fun setGrid(cols: Int, rows: Int) {
        val nextCols = cols.coerceAtLeast(1)
        val nextRows = rows.coerceAtLeast(1)
        if (nextCols == columns && nextRows == this.rows) return
        columns = nextCols
        this.rows = nextRows
        seed()
    }

    /**
     * The address book, favourites first.
     *
     * Called again on every refresh, so the shuffle is only restarted when the people
     * themselves change - re-seeding it every few seconds would leave the same nine faces
     * up for good.
     */
    fun setPeople(next: List<ContactFeed.Person>) {
        val same = next.size == people.size && next.indices.all { next[it].id == people[it].id }
        people = next
        if (!same) seed()
    }

    /** Starts over: the first people in the book, in the first squares. */
    private fun seed() {
        endTurn()
        clearHero()
        shown.clear()
        shade.clear()
        for (i in 0 until minOf(columns * rows, people.size)) {
            shown.add(i)
            shade.add(shadeOfTheMoment())
        }
        nextPerson = shown.size
        nextSlot = 0
        nextHero = 0
        sinceHero = 0
        for (index in shown) warm(people.getOrNull(index))
        invalidate()
    }

    /**
     * Asks for somebody's picture, if it is not already held and not already on its way.
     *
     * Called ahead of the square that will show it - when the wall is seeded, and when a
     * square is about to turn over to somebody new - and again from the drawing itself for
     * anyone whose picture has gone missing since. A picture can go missing: the cache is
     * shared and bounded, so a run of other work can evict a face out from under a square
     * that is still showing it, and without this the square would sit on the initials it
     * fell back to until it happened to turn over.
     *
     * [awaiting] is what keeps that from becoming a request per frame, and [unreadable]
     * does the same for a picture that will not decode at all - that one comes back null
     * immediately, so without it the draw would ask again forever.
     */
    private fun warm(person: ContactFeed.Person?) {
        val uri = person?.photoUri ?: return
        if (ContactFeed.cached(uri) != null) return
        if (uri in unreadable || !awaiting.add(uri)) return
        ContactFeed.load(context, uri) { bitmap ->
            awaiting.remove(uri)
            if (bitmap == null) unreadable.add(uri) else invalidate()
        }
    }

    /**
     * One move of the tile: a square turns over, or the takeover comes or goes.
     *
     * The takeover is counted in squares rather than in seconds so it keeps its place in
     * the rhythm: it arrives after [HERO_EVERY] of them, holds for one turn of the clock,
     * and hands the tile back to the wall of faces.
     */
    private fun step() {
        if (paused || !isShown || turning) return
        if (people.isEmpty()) return
        when {
            hero != null -> turnTo(null)
            sinceHero >= HERO_EVERY -> takeOver()
            else -> {
                sinceHero++
                rotateOne()
            }
        }
    }

    /**
     * Turns one square over onto somebody who is not currently up.
     *
     * Round-robin over both the squares and the address book, so the wall changes a face
     * at a time and works its way through everybody rather than flickering between the
     * same two people.
     */
    private fun rotateOne() {
        if (shown.isEmpty() || people.size <= shown.size) return

        val slot = nextSlot % shown.size
        nextSlot = slot + 1

        var pick = -1
        for (offset in people.indices) {
            val candidate = (nextPerson + offset) % people.size
            if (candidate !in shown) {
                pick = candidate
                nextPerson = candidate + 1
                break
            }
        }
        if (pick < 0) return

        warm(people[pick])
        startTurn(slot) {
            shown[slot] = pick
            // A new face, a new depth: the square is as likely to come back light as dark.
            shade[slot] = shadeOfTheMoment()
        }
    }

    /**
     * Hands the whole tile to one person, picture and all.
     *
     * Only somebody with a picture, and only once it has actually been decoded: the
     * takeover *is* the picture, and a tile that turned over onto a pair of initials
     * blown up to fill it would be a worse tile than the wall it interrupted. When
     * nobody in the book qualifies the counter is reset and the squares carry on.
     */
    private fun takeOver() {
        // Only so far down the book. Every candidate that is not decoded yet is asked
        // for, and asking for the whole address book at once - which is what walking all
        // of it would do on a phone where nobody at the front has a picture - is a queue
        // of decodes for a tile that wanted one face.
        for (offset in 0 until minOf(people.size, HERO_SCAN)) {
            val candidate = people[(nextHero + offset) % people.size]
            val uri = candidate.photoUri ?: continue
            if (ContactFeed.cached(uri) == null) {
                // Not decoded yet. Ask for it, so the next time round it is there.
                warm(candidate)
                continue
            }
            nextHero = (nextHero + offset + 1) % people.size
            sinceHero = 0
            startTurn(WHOLE) { setHero(candidate) }
            return
        }
        sinceHero = 0
    }

    private fun shadeOfTheMoment(): Float = (Math.random() * MAX_SHADE).toFloat()

    /** Turns the whole mosaic back to the wall of squares. */
    private fun turnTo(person: ContactFeed.Person?) {
        startTurn(WHOLE) { setHero(person) }
    }

    private fun setHero(person: ContactFeed.Person?) {
        hero = person
        onHero?.invoke(person)
    }

    private fun clearHero() {
        if (hero == null) return
        setHero(null)
    }

    /**
     * The turn itself, whatever is turning.
     *
     * [slot] is the square turning over, or [WHOLE] for the takeover, which turns the
     * tile itself.
     *
     * [swap] runs at the halfway point, where whatever is rotating is edge-on: the face
     * going away has just left the screen and the one arriving has not appeared yet, so a
     * change made there is a change nobody can see being made. Everything after that draws
     * itself, because the state it draws from has already moved on.
     */
    private fun startTurn(slot: Int, swap: () -> Unit) {
        // Cancelling the last one runs its ending, which puts the view back to rest -
        // including what is turning - so what this turn is about is set after it.
        animator?.cancel()
        var swapped = false
        turning = true
        turningSlot = slot
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FLIP_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                turn = animation.animatedValue as Float
                if (!swapped && turn >= 0.5f) {
                    swapped = true
                    swap()
                }
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!swapped) swap()
                    endTurn()
                }
            })
            start()
        }
    }

    private fun endTurn() {
        animator?.cancel()
        animator = null
        turning = false
        turn = 0f
        turningSlot = WHOLE
        invalidate()
    }

    // ------------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        if (people.isEmpty()) return
        val whole = turning && turningSlot == WHOLE
        if (whole) {
            // The takeover turns the tile itself, so the transform is applied to
            // everything on it and the state underneath is simply drawn as it stands.
            flipped(canvas, 0, 0, width, height) { drawContent(canvas) }
        } else {
            drawContent(canvas)
        }
    }

    private fun drawContent(canvas: Canvas) {
        val face = hero
        if (face != null) {
            // The takeover is the one square whose depth is not drawn out of a hat: it has
            // a name across it, and the tile has one shade at which a white name reads over
            // anything. The middle of the range the squares are drawn from.
            drawPerson(canvas, 0, 0, width, height, face, HERO_SHADE)
            return
        }
        for (slot in shown.indices) {
            val person = people.getOrNull(shown[slot]) ?: continue
            val left = edge(slot % columns, columns, width)
            val top = edge(slot / columns, rows, height)
            val right = edge(slot % columns + 1, columns, width)
            val bottom = edge(slot / columns + 1, rows, height)
            val depth = shade.getOrElse(slot) { 0f }
            if (turning && slot == turningSlot) {
                flipped(canvas, left, top, right, bottom) {
                    drawPerson(canvas, left, top, right, bottom, person, depth)
                }
            } else {
                drawPerson(canvas, left, top, right, bottom, person, depth)
            }
        }
    }

    /**
     * Where one column or row of the grid falls, in whole pixels.
     *
     * Rounded from the edge rather than stepped by a width, so neighbouring squares share
     * an edge exactly: a mosaic is one surface, and a rounding error between two of them
     * is a hairline of the tile's fill showing through the middle of somebody's face.
     */
    private fun edge(index: Int, count: Int, extent: Int): Int =
        Math.round(extent.toFloat() * index / count)

    /**
     * One person, filling the rectangle they were given, under [depth] of black.
     *
     * Their picture centre-cropped to it - a face is in the middle of a photograph, and a
     * squashed one is nobody - or their initials on a lightened block when they have none.
     * The black goes over either: the squares are one wall, and a block of initials left
     * at full strength beside a face half into the dark is the one square that reads as a
     * mistake. See [shade].
     */
    private fun drawPerson(
        canvas: Canvas,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        person: ContactFeed.Person,
        depth: Float
    ) {
        val photo = person.photoUri?.let { ContactFeed.cached(it) }
        dst.set(left, top, right, bottom)
        if (photo != null && !photo.isRecycled) {
            crop(photo, right - left, bottom - top)
            canvas.drawBitmap(photo, src, dst, bitmapPaint)
            drawShade(canvas, depth)
            return
        }
        // Somebody with a picture who is being drawn without one is somebody whose picture
        // has not arrived yet, or has been evicted since. Ask for it - the initials below
        // are what stands in the meantime, not what the square has settled on.
        warm(person)
        canvas.drawRect(dst, scrimPaint)
        drawShade(canvas, depth)
        if (person.initials.isEmpty()) return
        val side = minOf(right - left, bottom - top).toFloat()
        initialsPaint.textSize = side * INITIALS_FRACTION
        // Centred on the letters themselves rather than on the line: capitals have no
        // descender, so a text baseline placed on the middle of the box sits low.
        val metrics = initialsPaint.fontMetrics
        canvas.drawText(
            person.initials,
            (left + right) / 2f,
            (top + bottom) / 2f - (metrics.ascent + metrics.descent) / 2f,
            initialsPaint
        )
    }

    /** Lays [depth] of black over whatever was just drawn into [dst]. */
    private fun drawShade(canvas: Canvas, depth: Float) {
        if (depth <= 0f) return
        shadePaint.alpha = (depth.coerceIn(0f, 1f) * 255).toInt()
        canvas.drawRect(dst, shadePaint)
    }

    /** The largest part of [photo] with the shape of the square it is going into. */
    private fun crop(photo: Bitmap, width: Int, height: Int) {
        val scale = minOf(
            photo.width / width.toFloat(),
            photo.height / height.toFloat()
        )
        val takeW = (width * scale).toInt().coerceIn(1, photo.width)
        val takeH = (height * scale).toInt().coerceIn(1, photo.height)
        val x = (photo.width - takeW) / 2
        val y = (photo.height - takeH) / 2
        src.set(x, y, x + takeW, y + takeH)
    }

    /**
     * Draws [body] rotated about the horizontal axis through the middle of the rectangle.
     *
     * The angle runs 0 to 90 through the first half of the turn and -90 back to 0 through
     * the second, so what arrives is the right way up rather than a mirror of what left -
     * the state it draws from has already been swapped by then. See [startTurn].
     *
     * The eye is put at twice the longest side of whatever is turning, which is what keeps
     * a square of a wide tile and the whole of a small one turning over with the same
     * amount of perspective. The camera measures in units of 72 pixels.
     */
    private inline fun flipped(
        canvas: Canvas,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        body: () -> Unit
    ) {
        val degrees = if (turn < 0.5f) turn * 180f else turn * 180f - 180f
        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f
        val saved = canvas.save()
        camera.save()
        camera.setLocation(0f, 0f, -maxOf(right - left, bottom - top) * EYE_DISTANCE / 72f)
        camera.rotateX(degrees)
        camera.getMatrix(matrix)
        camera.restore()
        matrix.preTranslate(-centerX, -centerY)
        matrix.postTranslate(centerX, centerY)
        canvas.concat(matrix)
        body()
        canvas.restoreToCount(saved)
    }

    companion object {

        /** [startTurn] with this in place of a square turns the whole tile over. */
        private const val WHOLE = -1

        /** How long a face rests before the tile moves on. */
        private const val STEP_MS = 4_000L

        /** And how long a turn takes. The tile's own flip, so the wall reads as one thing. */
        private const val FLIP_MS = 500L

        /** How many squares turn over between one takeover and the next. */
        private const val HERO_EVERY = 6

        /** How far down the book a takeover looks for a face that is ready to show. */
        private const val HERO_SCAN = 12

        /** How strongly a contact with no picture lightens the fill. */
        private const val SCRIM_ALPHA = 40

        /** The deepest a square is ever taken into the dark. Half. */
        private const val MAX_SHADE = 0.5f

        /** What the takeover sits under, being the one square with a name across it. */
        private const val HERO_SHADE = 0.25f

        /** How much of a square a pair of initials fills. */
        private const val INITIALS_FRACTION = 0.34f

        /** How far the eye sits from what is turning, in multiples of its longest side. */
        private const val EYE_DISTANCE = 2f
    }
}
