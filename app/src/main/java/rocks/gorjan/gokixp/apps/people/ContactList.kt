package rocks.gorjan.gokixp.apps.people

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.MetroIndexList
import rocks.gorjan.gokixp.wp81.PeopleStore
import rocks.gorjan.gokixp.wp81.TiltEffect
import rocks.gorjan.gokixp.wp81.WP81Palette

/**
 * Everybody, on the app list's own page.
 *
 * The same [MetroIndexList] the app list is - letter squares, jump grid, held letter,
 * search band, all at the same sizes - with the three things that are about people rather
 * than programs said here: a row is a face and a name, the search is asked for from the
 * app bar rather than from a ring in a rail, and a name is matched by the start of its
 * words rather than by any part of it.
 *
 * Deliberately not a second look. Two alphabetical lists in one shell that differ only in
 * their measurements are two lists that merely resemble each other, and the resemblance is
 * the point of sharing the page at all.
 */
@SuppressLint("ViewConstructor")
class ContactList(
    context: Context,
    palette: WP81Palette
) : MetroIndexList<PeopleStore.Contact>(context, palette) {

    /**
     * No rail. The app list keeps its search ring down the left, which is what its gutter
     * is for; here search is on the app bar, where this app's other commands are, and a
     * column kept clear for nothing would only push the faces off the margin the panorama
     * has already set.
     */
    override val railDp: Int get() = 0

    override val edgeDp: Int get() = PAGE_MARGIN_DP

    override val hasSearchRing: Boolean get() = false

    override val searchHint: String get() = "search contacts"

    init {
        build()
    }

    override fun letterOf(item: PeopleStore.Contact): Char = bucketOf(item.name)

    /**
     * A name matched by the beginning of any of its words.
     *
     * Not "contains". Typing "an" should find Anna and Marko Andonov, not everybody with
     * an N in the middle of their name - a person is looked up by the start of what they
     * are called, and a list that answered otherwise would bury the two people meant among
     * thirty who merely share a letter.
     */
    override fun matches(item: PeopleStore.Contact, query: String): Boolean {
        if (query.isEmpty()) return true
        val lower = item.name.lowercase()
        if (lower.startsWith(query)) return true
        return lower.split(' ', '-', '.').any { it.startsWith(query) }
    }

    override fun createHolder(): ItemHolder = ContactHolder()

    private inner class ContactHolder : ItemHolder(LinearLayout(context)) {

        private val face = ContactFace(context, palette).apply { setLetterSize(15f) }
        private val name = TextView(context)
        private val star = TextView(context)
        private var bound: PeopleStore.Contact? = null

        init {
            val root = view as LinearLayout
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.CENTER_VERTICAL
            root.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_DP))
            root.setPadding(0, 0, rowEdge(), 0)
            root.isClickable = true

            root.addView(face, LinearLayout.LayoutParams(dp(FACE_DP), dp(FACE_DP)))

            name.textSize = 19f
            name.maxLines = 1
            name.ellipsize = android.text.TextUtils.TruncateAt.END
            name.typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            root.addView(name, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) })

            // Set to read at a glance down a column of names, which is the only thing it
            // is for: at the size of the small type beside it, it was a speck.
            star.textSize = 20f
            star.setPadding(dp(8), 0, 0, 0)
            root.addView(star, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            TiltEffect.apply(root)
            root.setOnClickListener { bound?.let { pick(it) } }
            root.setOnLongClickListener {
                bound?.let { held(it, root) }
                true
            }
        }

        override fun bind(item: PeopleStore.Contact) {
            bound = item
            face.show(item)
            name.text = item.name
            name.setTextColor(palette.foreground)
            // The same mark a favourite wears in every other list in this app.
            star.text = if (item.starred) STAR else ""
            star.setTextColor(palette.accent)
        }
    }

    private companion object {
        /**
         * A row, and the face in it.
         *
         * The app list's own numbers. A face is not an app icon, but a row of one is a row
         * of the other, and two lists in the same shell whose rows are a different height
         * with their squares set at different sizes are two lists that merely resemble each
         * other. The letter squares are left at the shared default, which is the same 44dp
         * they are on the app list - a hair larger than the faces they head, as they are
         * there a hair larger than the icons.
         */
        const val ROW_DP = 62
        const val FACE_DP = 42

        /** The panorama's own left margin, matched on the right so the page is even. */
        const val PAGE_MARGIN_DP = 22

        const val STAR = "★"
    }
}
