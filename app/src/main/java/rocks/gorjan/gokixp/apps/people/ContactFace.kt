package rocks.gorjan.gokixp.apps.people

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.ContactFeed
import rocks.gorjan.gokixp.wp81.PeopleStore
import rocks.gorjan.gokixp.wp81.SvgIcon
import rocks.gorjan.gokixp.wp81.WP81Palette

/**
 * Somebody's face, or the outlined square with their initials that stands in for one.
 *
 * Not a placeholder silhouette. An address book is mostly people who never got round to a
 * picture, and a column of identical grey outlines tells the reader nothing; two letters at
 * least say who the row is about at a glance.
 *
 * Rebindable, which is the whole reason it is a view of its own rather than a few lines
 * wherever a face is needed: the contact list recycles its rows, so a square that had
 * somebody in it a moment ago has to be able to become somebody else - and to *not* become
 * the first person again when their picture finally decodes. See [token].
 */
@SuppressLint("ViewConstructor")
class ContactFace(
    context: Context,
    private val palette: WP81Palette
) : FrameLayout(context) {

    private val initials = TextView(context).apply {
        typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
    }

    /**
     * The silhouette for somebody with neither a picture nor a name.
     *
     * A bare telephone number has nothing to abbreviate - see [PeopleStore.initialsOf],
     * which gives nothing rather than the first two characters of a number - and the
     * square was then the accent and nothing else, which reads as a face that failed to
     * load rather than as somebody the phone does not know.
     */
    private val glyph = ImageView(context).apply {
        setImageDrawable(SvgIcon.fromAsset(context, USER_ICON))
        scaleType = ImageView.ScaleType.FIT_CENTER
        imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        visibility = View.GONE
    }

    private val photo = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        visibility = View.GONE
    }

    /**
     * Which person this square is currently about.
     *
     * A picture is decoded off the main thread and handed back whenever it is ready, by
     * which time a recycled row may be showing somebody else entirely. The answer carries
     * the number it was asked under, and one that no longer matches is dropped.
     */
    private var token = 0

    init {
        background = placeholder(context, palette.accent)
        addView(initials, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(glyph, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(photo, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * The mark is set as a fraction of the square rather than in fixed units.
     *
     * This view is drawn at four sizes across the app - a row, a wall tile, the head of a
     * profile - and a silhouette inset by a fixed number of points would be a postage
     * stamp on the largest and fill the smallest edge to edge.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = (minOf(w, h) * GLYPH_INSET).toInt()
        glyph.setPadding(inset, inset, inset, inset)
    }

    /** How large the two letters are set, for the sizes a face is drawn at. */
    fun setLetterSize(sp: Float) {
        initials.textSize = sp
    }

    /** Shows [person], or clears the square if there is nobody. */
    fun show(person: PeopleStore.Contact?) = show(person?.name, person?.photoUri)

    /**
     * The same, for somebody the address book has never heard of.
     *
     * A conversation can be with a name that is not a contact - a bank, a network, a
     * two-factor code, all of which arrive from a word rather than a number - and those
     * had been drawn as an empty accent square, which looks like a face that failed to
     * load. They have a name; it abbreviates like any other. A row titled with a bare
     * number still comes out blank, because [PeopleStore.initialsOf] has nothing to give
     * it and inventing something would be worse.
     */
    fun show(name: String?, photoUri: String?) {
        token++
        val mine = token
        val letters = PeopleStore.initialsOf(name.orEmpty())
        initials.text = letters
        // Their letters if there are any to have, the silhouette if not.
        initials.visibility = if (letters.isEmpty()) View.GONE else View.VISIBLE
        glyph.visibility = if (letters.isEmpty()) View.VISIBLE else View.GONE
        photo.visibility = View.GONE
        photo.setImageDrawable(null)

        if (photoUri.isNullOrBlank()) return
        // Through the tile's own cache: the wall on Start has usually decoded most of
        // these already, and a second cache would decode the same faces twice.
        ContactFeed.load(context, photoUri) { bitmap ->
            if (token != mine || bitmap == null) return@load
            photo.setImageBitmap(bitmap)
            photo.visibility = View.VISIBLE
            initials.visibility = View.GONE
            glyph.visibility = View.GONE
        }
    }

    companion object {
        /** The shell's own silhouette, the one the call screen shows for a stranger. */
        private const val USER_ICON = "custom_icons_8/appbar.user.svg"

        /** How much of the square is margin around the mark. See [onSizeChanged]. */
        private const val GLYPH_INSET = 0.25f

        /** How heavy the line round a face that isn't there is drawn. */
        private const val OUTLINE_DP = 2

        /**
         * The square a missing face is drawn on, wherever one is drawn.
         *
         * It used to be the accent filled solid, which on a red accent is the hang-up
         * button: the same colour in the same square, a thumb's width from the real one on
         * the call screen. Black with the accent drawn round it says both of the things
         * the fill was there to say - that this is a person, and which colour the phone is
         * set to - without ever reading as something to press.
         *
         * Handed out rather than kept to this view because the call screen and the editor
         * each build a square of their own, and three definitions of the same square is
         * how two of them end up a version behind.
         */
        fun placeholder(context: Context, accent: Int): Drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.BLACK)
            setStroke(
                (OUTLINE_DP * context.resources.displayMetrics.density).toInt()
                    .coerceAtLeast(1),
                accent
            )
        }
    }
}
