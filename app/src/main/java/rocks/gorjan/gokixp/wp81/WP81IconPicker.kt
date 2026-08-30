package rocks.gorjan.gokixp.wp81

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import rocks.gorjan.gokixp.R

/**
 * The Metro icon picker.
 *
 * Replaces the Vista "Change Icon" window for this shell: a lowercase page heading, two
 * commands along the top for resetting or browsing, and a flat grid of the theme's icon
 * set below. No window chrome, no tabbed dialog.
 *
 * Icons arrive in batches from the host rather than all at once - the bundled sets run to
 * several hundred files and decoding them up front stalls the page-in.
 */
@SuppressLint("ViewConstructor")
class WP81IconPicker(
    context: Context,
    private var palette: WP81Palette
) : FrameLayout(context) {

    /** An icon the user can choose: [path] is what gets persisted. */
    data class Choice(val path: String, val drawable: Drawable)

    var onPicked: ((String) -> Unit)? = null
    var onBrowse: (() -> Unit)? = null
    var onResetToDefault: (() -> Unit)? = null

    private val heading = TextView(context)
    private val resetCommand = TextView(context)
    private val browseCommand = TextView(context)
    private val grid = RecyclerView(context)
    private val choices = mutableListOf<Choice>()
    private val adapter = Adapter()

    init {
        visibility = GONE
        isClickable = true

        heading.typeface = ResourcesCompat.getFont(context, R.font.segoeui_light)
        heading.textSize = 34f
        heading.text = "choose icon"
        heading.includeFontPadding = false
        heading.setPadding(dp(22), dp(20), dp(22), dp(12))

        for ((command, label) in listOf(resetCommand to "use default", browseCommand to "browse")) {
            command.text = label
            command.textSize = 15f
            command.typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            command.setPadding(dp(14), dp(10), dp(14), dp(10))
            command.isClickable = true
            TiltEffect.apply(command)
        }
        resetCommand.setOnClickListener { onResetToDefault?.invoke() }
        browseCommand.setOnClickListener { onBrowse?.invoke() }

        val commands = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), dp(8))
            addView(resetCommand)
            // Room between them: two commands set in the same face and touching read as
            // one phrase - and the left of the two throws away what the right of them is
            // for, so a mis-tap costs the user their choice.
            addView(browseCommand, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(COMMAND_GAP_DP) })
        }

        grid.layoutManager = GridLayoutManager(context, COLUMNS)
        grid.adapter = adapter
        grid.setHasFixedSize(true)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(heading, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(commands, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(grid, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        applyPalette(palette)
    }

    fun show(forLabel: String) {
        heading.text = forLabel.lowercase().ifEmpty { "choose icon" }
        choices.clear()
        adapter.notifyDataSetChanged()
        visibility = VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(160).setInterpolator(DecelerateInterpolator()).start()
    }

    fun dismiss() {
        visibility = GONE
    }

    fun isShowing(): Boolean = visibility == VISIBLE

    /** Appends a decoded batch. Called repeatedly as the host works through the set. */
    fun addChoices(batch: List<Choice>) {
        if (batch.isEmpty()) return
        val start = choices.size
        choices.addAll(batch)
        adapter.notifyItemRangeInserted(start, batch.size)
    }

    fun applyPalette(p: WP81Palette) {
        palette = p
        setBackgroundColor(p.background)
        heading.setTextColor(p.foreground)
        resetCommand.setTextColor(p.foreground)
        browseCommand.setTextColor(p.foreground)
        resetCommand.setBackgroundColor(p.inactive)
        browseCommand.setBackgroundColor(p.inactive)
        adapter.notifyDataSetChanged()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {

        inner class Holder(val frame: FrameLayout) : RecyclerView.ViewHolder(frame) {
            val image = ImageView(frame.context)

            init {
                frame.addView(image, LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER).apply {
                    val pad = dp(10)
                    setMargins(pad, pad, pad, pad)
                })
                image.scaleType = ImageView.ScaleType.FIT_CENTER
                frame.isClickable = true
                TiltEffect.apply(frame)
                frame.setOnClickListener {
                    choices.getOrNull(bindingAdapterPosition)?.let { onPicked?.invoke(it.path) }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val frame = FrameLayout(parent.context)
            val side = parent.measuredWidth / COLUMNS
            frame.layoutParams = RecyclerView.LayoutParams(side, side)
            return Holder(frame)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.image.setImageDrawable(choices[position].drawable)
            holder.frame.setBackgroundColor(palette.inactive)
        }

        override fun getItemCount() = choices.size
    }

    companion object {
        /** Space between "use default" and "browse". */
        private const val COMMAND_GAP_DP = 22

        private const val COLUMNS = 4
    }
}
