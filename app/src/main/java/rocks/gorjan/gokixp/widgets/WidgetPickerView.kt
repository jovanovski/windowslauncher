package rocks.gorjan.gokixp.widgets

import android.annotation.SuppressLint
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import rocks.gorjan.gokixp.R
import java.util.concurrent.Executors

/**
 * The contents of the "Add Widget" window: a filter box over a list of every widget the
 * installed apps offer, each with its preview, its name, its app and its size in home-screen
 * cells. Tapping one picks it.
 */
@SuppressLint("ViewConstructor")
class WidgetPickerView(
    context: Context,
    private val providers: List<AppWidgetProviderInfo>,
    private val appLabel: (AppWidgetProviderInfo) -> String,
    private val font: Typeface?,
    private val onPicked: (AppWidgetProviderInfo) -> Unit,
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private fun Int.dp() = (this * density).toInt()

    private data class Entry(val info: AppWidgetProviderInfo, val label: String, val app: String)

    private val entries = providers.map {
        Entry(it, it.loadLabel(context.packageManager).orEmpty(), appLabel(it))
    }

    // Previews come out of other apps' resources and can be big; decode them off the main thread
    private val previewExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val previewCache = HashMap<AppWidgetProviderInfo, Drawable?>()

    private val adapter = PickerAdapter()

    init {
        orientation = VERTICAL
        setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())

        val filter = EditText(context).apply {
            hint = "Search widgets"
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
            highlightColor = "#7a94f4".toColorInt()
            textSize = 12f
            isSingleLine = true
            font?.let { typeface = it }
            setBackgroundResource(R.drawable.win98_edit_text_border)
            setPadding(8.dp(), 6.dp(), 8.dp(), 6.dp())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = adapter.filter(s?.toString().orEmpty())
            })
        }
        addView(filter, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 6.dp()
        })

        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@WidgetPickerView.adapter
            setBackgroundResource(R.drawable.win98_edit_text_border)
            setPadding(3.dp(), 3.dp(), 3.dp(), 3.dp())
            clipToPadding = true
        }
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        if (entries.isEmpty()) {
            list.visibility = View.GONE
            addView(TextView(context).apply {
                text = "None of your apps offer widgets."
                setTextColor(Color.BLACK)
                textSize = 12f
                font?.let { typeface = it }
                gravity = Gravity.CENTER
            }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        previewExecutor.shutdownNow()
    }

    /** Roughly how many launcher cells a widget asks for, the way Android's docs size them. */
    private fun cells(px: Int): Int = (((px / density) + 30) / 70).toInt().coerceAtLeast(1)

    private inner class PickerAdapter : RecyclerView.Adapter<PickerAdapter.Holder>() {
        private var shown: List<Entry> = entries

        @SuppressLint("NotifyDataSetChanged")
        fun filter(query: String) {
            val q = query.trim().lowercase()
            shown = if (q.isEmpty()) entries else entries.filter {
                it.label.lowercase().contains(q) || it.app.lowercase().contains(q)
            }
            notifyDataSetChanged()
        }

        inner class Holder(
            row: LinearLayout,
            val preview: ImageView,
            val title: TextView,
            val subtitle: TextView,
        ) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(parent.context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(6.dp(), 6.dp(), 6.dp(), 6.dp())
                isClickable = true
                isFocusable = true
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT
                )
            }
            val preview = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = false
            }
            row.addView(preview, LayoutParams(64.dp(), 48.dp()).apply { marginEnd = 8.dp() })

            val texts = LinearLayout(parent.context).apply { orientation = VERTICAL }
            val title = TextView(parent.context).apply {
                setTextColor(Color.BLACK)
                textSize = 12f
                typeface = Typeface.create(font, Typeface.BOLD)
                maxLines = 2
            }
            val subtitle = TextView(parent.context).apply {
                setTextColor("#555555".toColorInt())
                textSize = 11f
                font?.let { typeface = it }
                maxLines = 1
            }
            texts.addView(title)
            texts.addView(subtitle)
            row.addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            return Holder(row, preview, title, subtitle)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = shown[position]
            val info = entry.info
            holder.title.text = entry.label.ifEmpty { entry.app }
            holder.subtitle.text = "${entry.app}  ·  ${cells(info.minWidth)} × ${cells(info.minHeight)}"
            holder.itemView.setOnClickListener { onPicked(info) }
            bindPreview(holder, info)
        }

        private fun bindPreview(holder: Holder, info: AppWidgetProviderInfo) {
            holder.preview.tag = info
            if (previewCache.containsKey(info)) {
                holder.preview.setImageDrawable(previewCache[info] ?: loadIcon(info))
                return
            }
            // The app's icon stands in until the preview is ready
            holder.preview.setImageDrawable(loadIcon(info))
            if (previewExecutor.isShutdown) return
            previewExecutor.execute {
                val drawable = try {
                    info.loadPreviewImage(context, resources.displayMetrics.densityDpi)
                } catch (e: Exception) {
                    null
                }
                mainHandler.post {
                    previewCache[info] = drawable
                    // Only if the row still shows this widget - it may have been recycled since
                    if (holder.preview.tag === info && drawable != null) holder.preview.setImageDrawable(drawable)
                }
            }
        }

        private fun loadIcon(info: AppWidgetProviderInfo): Drawable? = try {
            info.loadIcon(context, resources.displayMetrics.densityDpi)
        } catch (e: Exception) {
            null
        }

        override fun getItemCount(): Int = shown.size
    }
}
