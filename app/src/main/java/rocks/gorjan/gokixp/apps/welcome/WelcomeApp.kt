package rocks.gorjan.gokixp.apps.welcome

import android.content.Context
import android.graphics.Typeface
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.MetroPanorama
import rocks.gorjan.gokixp.wp81.WP81Palette

/**
 * Welcome, as Windows Phone would have said it.
 *
 * The same thing the desktop themes put in a window after an update - what this is, who
 * made it, how to get around, and what changed - laid out as a panorama instead of a
 * dialog with a picture and two buttons.
 *
 * No music. The desktop welcome plays a startup theme on a loop, which belongs to a
 * machine booting up; a phone that started playing music because an app updated would be
 * a phone with something wrong with it.
 *
 * The release notes are fetched rather than bundled, from the same GitHub releases the
 * desktop welcome reads - so there is one list of what changed and it is never a build
 * behind.
 */
class WelcomeApp(
    private val context: Context,
    private val palette: WP81Palette,
    private val versionName: String,
    private val onOpenLink: (String) -> Unit,
    private val loadReleaseNotes: ((String) -> Unit) -> Unit
) {

    private lateinit var root: FrameLayout
    private lateinit var notes: TextView

    fun createView(): View {
        root = FrameLayout(context).apply { setBackgroundColor(palette.background) }

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(context).apply {
            text = "welcome"
            typeface = font(R.font.segoeui_semilight)
            textSize = 30f
            setTextColor(palette.foregroundSubtle)
            setPadding(dp(MARGIN_DP), dp(14), dp(MARGIN_DP), dp(2))
            includeFontPadding = false
        }, wide())

        val panorama = MetroPanorama(context, palette).apply {
            setPadding(dp(MARGIN_DP), 0, 0, 0)
            clipToPadding = false
            clipChildren = false
        }
        panorama.addPage("about", page(buildWelcome()))
        panorama.addPage("release notes", page(buildNotes()))
        column.addView(panorama, LinearLayout.LayoutParams(MATCH, 0, 1f))

        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        return root
    }

    private fun page(content: View): View = ScrollView(context).apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    private fun buildWelcome(): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), dp(MARGIN_DP), dp(28))
        }

        column.addView(TextView(context).apply {
            text = "version $versionName"
            typeface = font(R.font.segoeui_semibold)
            textSize = 12f
            setTextColor(palette.accent)
            setPadding(0, 0, 0, dp(10))
        }, wide())

        column.addView(body(WELCOME_TEXT), wide())

        // As rows: a phone is a poor place to aim at a word in the middle of a paragraph.
        // The address is not among them - it is in the text above, where Linkify has
        // already made it tappable, and twice is once too many.
        column.addView(link("the source, on GitHub", GITHUB_URL), wide())
        column.addView(link("donate to project", COFFEE_URL), wide())
        return column
    }

    private fun buildNotes(): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), dp(MARGIN_DP), dp(28))
        }
        notes = body("reading the release notes…")
        column.addView(notes, wide())
        loadReleaseNotes { text -> notes.text = text }
        return column
    }

    private fun body(text: String) = TextView(context).apply {
        this.text = text
        typeface = font(R.font.segoeui_semilight)
        textSize = 15f
        setTextColor(palette.foreground)
        setLineSpacing(0f, 1.05f)
        // Anything that looks like an address is one, the way the desktop welcome does it.
        Linkify.addLinks(this, Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES)
        // And the name of whoever wrote it, which looks like nothing at all but is the
        // one link in the paragraph worth having. Added after Linkify, which replaces
        // whatever spans it finds on the text rather than adding to them.
        linkName(this)
        movementMethod = LinkMovementMethod.getInstance()
        setLinkTextColor(palette.accent)
    }

    /** Makes the author's name in [view] open his site. */
    private fun linkName(view: TextView) {
        val text = view.text?.toString() ?: return
        val start = text.indexOf(AUTHOR)
        if (start < 0) return
        val spannable = android.text.SpannableString(view.text)
        spannable.setSpan(
            object : android.text.style.ClickableSpan() {
                override fun onClick(widget: View) = onOpenLink(AUTHOR_URL)

                override fun updateDrawState(paint: android.text.TextPaint) {
                    super.updateDrawState(paint)
                    paint.color = palette.accent
                    paint.isUnderlineText = false
                }
            },
            start, start + AUTHOR.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        view.text = spannable
    }

    private fun link(label: String, url: String) = TextView(context).apply {
        text = label
        typeface = font(R.font.segoeui_semilight)
        textSize = 17f
        setTextColor(palette.accent)
        setPadding(0, dp(12), 0, dp(12))
        isClickable = true
        setOnClickListener { onOpenLink(url) }
        rocks.gorjan.gokixp.wp81.TiltEffect.apply(this)
    }

    private fun font(res: Int): Typeface? = ResourcesCompat.getFont(context, res)

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun wide() = LinearLayout.LayoutParams(MATCH, WRAP)

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        const val MARGIN_DP = 22

        const val GITHUB_URL = "https://github.com/jovanovski/windowslauncher/"
        const val COFFEE_URL = "https://buymeacoffee.com/jovanovski"

        const val AUTHOR = "Gorjan Jovanovski"
        const val AUTHOR_URL = "https://gorjan.rocks"

        /**
         * The desktop welcome's own text, less the line about the music - there is none
         * here - and less the tips that were about a desktop: there is no wallpaper to
         * long-press and no window to swipe closed on a Start screen.
         */
        const val WELCOME_TEXT =
            "This is a passion project from Gorjan Jovanovski, a developer who grew up " +
                "with these aesthetics and prefers them over new design any day.\n\n" +
                "If you're an 80s or 90s kid, you remember these days fondly, and this is " +
                "a chance to relive them on a modern daily driver, in your pocket.\n\n" +
                "A few tips:\n" +
                "1) Tap on things that look tappable, chances are they are.\n" +
                "2) Hold a tile to move it, resize it, or paint it another colour.\n" +
                "3) Swipe left from Start for everything installed, and press the search " +
                "key to find one by name.\n" +
                "4) Tap the corner of a tile to turn it over.\n" +
                "5) The settings key on the left of the navigation bar is where the " +
                "accent, the background and the Start photo live.\n\n" +
                "All the copyrighted information belongs to their respective authors; the " +
                "aim here is to recreate nostalgia for fun.\n\n" +
                "For any feature requests, drop me an email at hey@gorjan.rocks\n\n" +
                "Thanks for using Windows!"
    }
}
