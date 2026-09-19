package rocks.gorjan.gokixp.apps.paint

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import rocks.gorjan.gokixp.ContextMenuItem
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.theme.AppTheme
import rocks.gorjan.gokixp.theme.FontManager
import rocks.gorjan.gokixp.theme.ThemeManager
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * Microsoft Paint.
 *
 * The tools, the pixel-exact drawing and the option pictures are the ones from the Paint that
 * runs in winos, ported across: [Raster] does the drawing, and the tool list, the sixteen tool
 * icons and the twenty-eight palette colors below are that Paint's own data.
 *
 * What had to change is the input. There is no right button on a phone, so the background
 * color is set by holding a swatch rather than right-clicking it, a selection is moved by
 * dragging it, and the picture pans with two fingers and zooms by pinching. The menus are the
 * launcher's context menus, opened from the menu bar, since Paint's menus are lists of
 * commands either way.
 */
class PaintApp(
    private val context: Context,
    private val onSoundPlay: (String) -> Unit,
    private val onShowMenu: (List<ContextMenuItem>, Float, Float) -> Unit,
    private val onAskText: (String, String, String, (String) -> Unit) -> Unit,
    private val onSayMessage: (String, String) -> Unit,
    private val onAskConfirm: (String, String, () -> Unit, () -> Unit) -> Unit,
    private val onUpdateWindowTitle: (String) -> Unit,
    private val onBrowseForPicture: () -> Unit,
    private val onSetWallpaper: (Uri) -> Unit,
    private val onClose: () -> Unit,
    private val initialFile: File? = null
) {

    companion object {
        private const val TAG = "PaintApp"

        /** Paint's twenty-eight colors, in the two rows the color box shows them in. */
        private val PALETTE = listOf(
            "#000000", "#808080", "#800000", "#808000", "#008000", "#008080", "#000080",
            "#800080", "#808040", "#004040", "#0080ff", "#004080", "#8000ff", "#804000",
            "#ffffff", "#c0c0c0", "#ff0000", "#ffff00", "#00ff00", "#00ffff", "#0000ff",
            "#ff00ff", "#ffff80", "#00ff80", "#80ffff", "#8080ff", "#ff0080", "#ff8040"
        ).map { Color.parseColor(it) }

        /** The forty-eight colors Edit Colors offers, in rows of eight. */
        private val BASIC_COLORS = listOf(
            "#ff8080", "#ffff80", "#80ff80", "#00ff80", "#80ffff", "#0080ff", "#ff80c0", "#ff80ff",
            "#ff0000", "#ffff00", "#80ff00", "#00ff40", "#00ffff", "#0080c0", "#8080c0", "#ff00ff",
            "#804040", "#ff8040", "#00ff00", "#008080", "#004080", "#8080ff", "#800040", "#ff0080",
            "#800000", "#ff8000", "#008000", "#008040", "#0000ff", "#0000a0", "#800080", "#8000ff",
            "#400000", "#804000", "#004000", "#004040", "#000080", "#000040", "#400040", "#400080",
            "#000000", "#808000", "#808040", "#808080", "#408080", "#c0c0c0", "#c0dcc0", "#ffffff"
        ).map { Color.parseColor(it) }

        /** id, name, and the sentence the status bar shows while the tool is picked. */
        private val TOOLS = listOf(
            Tool("free", "Free-Form Select", "Selects a free-form part of the picture to move, copy, or edit."),
            Tool("select", "Select", "Selects a rectangular part of the picture to move, copy, or edit."),
            Tool("eraser", "Eraser", "Erases a portion of the picture, using the selected eraser shape."),
            Tool("fill", "Fill With Color", "Fills an area with the current drawing color."),
            Tool("pick", "Pick Color", "Picks up a color from the picture for drawing."),
            Tool("magnifier", "Magnifier", "Changes the magnification."),
            Tool("pencil", "Pencil", "Draws a free-form line one pixel wide."),
            Tool("brush", "Brush", "Draws using a brush with the selected shape and size."),
            Tool("airbrush", "Airbrush", "Draws using an airbrush of the selected size."),
            Tool("text", "Text", "Inserts text into the picture."),
            Tool("line", "Line", "Draws a straight line with the selected line width."),
            Tool("curve", "Curve", "Draws a curved line with the selected line width."),
            Tool("rect", "Rectangle", "Draws a rectangle with the selected fill style."),
            Tool("polygon", "Polygon", "Draws a polygon with the selected fill style."),
            Tool("ellipse", "Ellipse", "Draws an ellipse with the selected fill style."),
            Tool("roundrect", "Rounded Rectangle", "Draws a rounded rectangle with the selected fill style.")
        )

        /** The colors the tool pictures are written in. */
        private val ICON_PAL = mapOf(
            'K' to Color.parseColor("#000000"), 'W' to Color.parseColor("#ffffff"),
            'g' to Color.parseColor("#808080"), 's' to Color.parseColor("#c0c0c0"),
            'y' to Color.parseColor("#ffff00"), 'o' to Color.parseColor("#808000"),
            'r' to Color.parseColor("#ff0000"), 'b' to Color.parseColor("#0000ff"),
            'n' to Color.parseColor("#000080"), 'p' to Color.parseColor("#ff8080"),
            'L' to Color.parseColor("#dfdfdf")
        )

        /** Each tool's picture, sixteen pixels square, one character per pixel. */
        private val ICONS = mapOf(
            "free" to arrayOf("................", ".......K........", "......K.K.......", ".....K...K......", "K.K.K.....K.K.K.", "................", ".K...........K..", "................", "...K.......K....", "................", "....K.......K...", "...K...K.K...K..", "................", "..K.K.......K.K.", "................", "................"),
            "select" to arrayOf("................", "................", ".K.K.K.K.K.K.K..", "................", ".K...........K..", "................", ".K...........K..", "................", ".K...........K..", "................", ".K...........K..", "................", ".K.K.K.K.K.K.K..", "................", "................", "................"),
            "eraser" to arrayOf("................", "................", "................", "................", "......KKKKKKKKK.", ".....KyyyyyyyKK.", "....KyyyyyyyKoK.", "...KyyyyyyyKooK.", "..KKKKKKKKKoooK.", "..KWWWWWWWKooK..", "..KWWWWWWWKoK...", "..KWWWWWWWKK....", "..KKKKKKKKK.....", "................", "................", "................"),
            "fill" to arrayOf("................", "......KK........", ".....K..K.......", ".....K.KsK......", ".....KKWWsK.....", "....KsWWWWsK....", "...KsWWWWWWsK...", "..KsWWWWWWWWKn..", ".KgsWWWWWWWKgnn.", ".KggsWWWWWKg.nnn", "..KggsWWWKg..nnn", "...KggsWKg...nnn", "....KggKg.....n.", ".....KKg........", "......g.........", "................"),
            "pick" to arrayOf("................", "............KK..", "...........KKKK.", "..........KKKKK.", ".......K.KKKKK..", "........KKKKK...", ".......KWKKK....", "......KWsKK.K...", ".....KWsK.......", "....KWsK........", "...KWsK.........", "..KWsK..........", "..KsK...........", ".KKK............", ".K..............", "................"),
            "magnifier" to arrayOf("................", "....KKKK........", "..KKsLLsKK......", "..KsLWWLLsK.....", ".KsLWLLLLLsK....", ".KLWLLLLLLLK....", ".KLLLLLLLLLK....", ".KLLLLLLLLLK....", ".KsLLLLLLLsK....", "..KsLLLLLsK.....", "..KKsLLLsKKK....", "....KKKK.KgKK...", "..........KgKK..", "...........KgKK.", "............KKK.", "................"),
            "pencil" to arrayOf("................", "...........KK...", "..........KppK..", ".........KppppK.", "........KsKppK..", ".......KysKKK...", "......KyyoK.....", ".....KyyoK......", "....KyyoK.......", "...KyyoK........", "...KWoK.........", "..KWWK..........", "..KWK...........", ".KKK............", ".KK.............", "................"),
            "brush" to arrayOf("................", ".............KK.", "............KnbK", "...........KnbK.", "..........KnbK..", ".........KnbK...", "........KnbK....", ".......KsgK.....", "......KWsgK.....", ".....KsWgK......", "....KKKKK.......", "...KKKKK........", "..KKKKK.........", "..KKKK..........", "..KK............", "................"),
            "airbrush" to arrayOf("................", ".K..K...........", "...K..K.........", "K.K.K...........", "..K..K.KK.......", ".K.K...KsK......", ".......KKKK.....", "......KgWsgK....", "......KgWsgK....", "......KgWsgK....", "......KgWsgK....", "......KgWsgK....", "......KgWsgK....", "......KgWsgK....", ".......KKKK.....", "................"),
            "text" to arrayOf("................", "................", "................", ".......KK.......", ".......KK.......", "......KKKK......", "......K.KK......", ".....KK..KK.....", ".....K...KK.....", "....KKKKKKKK....", "....K.....KK....", "...KK......KK...", "..KKKK....KKKK..", "................", "................", "................"),
            "line" to arrayOf("................", "................", ".............K..", "............K...", "...........K....", "..........K.....", ".........K......", "........K.......", ".......K........", "......K.........", ".....K..........", "....K...........", "...K............", "..K.............", "................", "................"),
            "curve" to arrayOf("................", "................", "..........KKK...", ".........K...K..", "........K.......", "........K.......", ".......K........", ".......K........", "......K.........", "......K.........", ".....K..........", ".....K..........", "..K.K...........", "...K............", "................", "................"),
            "rect" to arrayOf("................", "................", "................", ".KKKKKKKKKKKKKK.", ".K............K.", ".K............K.", ".K............K.", ".K............K.", ".K............K.", ".K............K.", ".K............K.", ".K............K.", ".KKKKKKKKKKKKKK.", "................", "................", "................"),
            "polygon" to arrayOf("................", "................", "...KKKKKKK......", "...K.....K......", "...K......K.....", "...K.......K....", "...K........KKK.", "...K..........K.", "..K...........K.", "..K...........K.", ".K............K.", ".K............K.", ".KKKKKKKKKKKKKK.", "................", "................", "................"),
            "ellipse" to arrayOf("................", "................", "................", ".....KKKKKK.....", "...KK......KK...", "..K..........K..", ".K............K.", ".K............K.", ".K............K.", "..K..........K..", "...KK......KK...", ".....KKKKKK.....", "................", "................", "................", "................"),
            "roundrect" to arrayOf("................", "................", "................", "...KKKKKKKKKK...", "..K..........K..", ".K............K.", ".K............K.", ".K............K.", ".K............K.", ".K............K.", "..K..........K..", "...KKKKKKKKKK...", "................", "................", "................", "................")
        )

        /** The Draw Opaque / Draw Transparent pair from the selection tools' option box. */
        private val OPAQUE = arrayOf(
            "#.#.#.#.#.#.#.#.#.#.#", ".WWWWWWWWWWWWWWWWWWW.", "#WWWWWWWWWggWWWWWWWW#",
            ".WWWWWWWWggggWWWWWWW.", "#WWWWWWWggggggWWWWWW#", ".WWWWWWggggggggWWWWW.",
            "#WWWWWggggggggggWWWW#", ".WWWWWWggggggggWWWWW.", "#WWWWWWWggggggWWWWWW#",
            ".WWWWWWWWggggWWWWWWW.", "#WWWWWWWWWggWWWWWWWW#", ".WWWWWWWWWWWWWWWWWWW.",
            "#.#.#.#.#.#.#.#.#.#.#"
        )

        private val BRUSHES = listOf(
            "circle-7", "circle-4", "circle-1", "square-8", "square-5", "square-2",
            "slash-8", "slash-5", "slash-2", "back-8", "back-5", "back-2"
        )
        private val SPRAY = intArrayOf(4, 8, 12)
        private val TEXT_SIZES = intArrayOf(8, 10, 12, 14, 18, 24, 36, 48, 72)
        private val ZOOMS = intArrayOf(1, 2, 4, 6, 8)

        /** Windows 98 Paint remembers three steps, and so does this one. */
        private const val UNDO_STEPS = 3

        private const val FACE = 0xFFC0C0C0.toInt()
        private const val LIGHT = 0xFFFFFFFF.toInt()
        private const val SHADOW = 0xFF808080.toInt()
        private const val DARK = 0xFF000000.toInt()
        private const val DESK = 0xFF808080.toInt()

        /** The largest new picture, so a big screen does not hand Paint a 4000-pixel canvas. */
        private const val MAX_NEW = 1400

        /** And the largest one that can be opened, for the same reason. */
        private const val MAX_OPEN = 2048
    }

    private data class Tool(val id: String, val name: String, val hint: String)

    /** A press being dragged across the picture. */
    private class Op(val startX: Int, val startY: Int) {
        var lastX = startX
        var lastY = startY
        var moved = false
        var base: Bitmap? = null
        var pen: Array<IntArray>? = null
        val pts = ArrayList<IntArray>()
    }

    /** A selection: a box on the picture, or, once lifted, a picture floating above it. */
    private class Sel(var x: Int, var y: Int, var w: Int, var h: Int) {
        var mask: Bitmap? = null   // a free-form selection is the box, less what falls outside it
        var src: Bitmap? = null    // the pixels, once picked up off the picture
        var view: Bitmap? = null   // src as drawn: as-is, or with the background color taken out
    }

    /** A polygon or a curve: both are drawn over several presses. */
    private class Shape(val base: Bitmap, val color: Int, val other: Int) {
        val pts = ArrayList<IntArray>()
        var stage = 1
        var lastPress = 0L
    }

    /* ---------- the picture ---------- */

    private var bmp: Bitmap = Raster.bitmap(1, 1)
    private var work: Canvas = Canvas(bmp)
    private val brush = Raster.flatPaint()
    private val blit = Raster.flatPaint()
    private val eraseWith = Raster.flatPaint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    private var fg = Color.BLACK
    private var bg = Color.WHITE
    private var tool = "pencil"
    private var prevTool = "pencil"
    private var zoom = 1
    private var showGrid = false
    private var dirty = false
    private var name = "untitled"
    private var sourceUri: Uri? = null
    private var sourceFile: File? = null

    // The tool options, one per tool that has any
    private var optEraser = 4
    private var optBrush = "circle-7"
    private var optAir = 0
    private var optLine = 1
    private var optFill = 0          // 0 outline, 1 outline on a filled shape, 2 filled
    private var optTransparent = false
    private var optMagnify = 4
    private var optTextSize = 12

    private val undo = ArrayList<Bitmap>()
    private val redo = ArrayList<Bitmap>()

    private var sel: Sel? = null
    private var poly: Shape? = null
    private var curve: Shape? = null
    private var op: Op? = null
    private var movingSel = false
    private var ghost: Rect? = null
    private var trace: ArrayList<IntArray>? = null
    private var clipboard: Bitmap? = null

    /* ---------- the window ---------- */

    private val themeManager = ThemeManager(context)
    private val fontManager = FontManager(context)
    private val theme: AppTheme = themeManager.getSelectedTheme()
    private val density = context.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    private var canvasArea: FrameLayout? = null
    private var canvasView: CanvasView? = null
    private var optionsBox: LinearLayout? = null
    private var statusHint: TextView? = null
    private var statusPos: TextView? = null
    private var statusSize: TextView? = null
    private var fgSwatch: View? = null
    private var bgSwatch: View? = null
    private val toolButtons = HashMap<String, View>()

    private var textEdit: EditText? = null
    private var textBox: Rect? = null

    private var spray: Runnable? = null
    private var lastMenuX = 0f
    private var lastMenuY = 0f

    private fun Int.dp(): Int = (this * density).roundToInt()

    /** The gap Paint leaves between the window and the picture's top left corner. */
    private val MARGIN = 4f * density

    /* ================================================================
       Putting the window together
       ================================================================ */

    fun setupApp(contentView: View): View {
        canvasArea = contentView.findViewById(R.id.paint_canvas_area)
        buildMenuBar(contentView.findViewById(R.id.paint_menu_bar))
        buildToolbox(contentView.findViewById(R.id.paint_toolbox))
        optionsBox = contentView.findViewById(R.id.paint_tool_options)
        buildColorBox(contentView.findViewById(R.id.paint_color_box))
        buildStatusBar(contentView.findViewById(R.id.paint_status_bar))

        val view = CanvasView(context)
        canvasView = view
        canvasArea?.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // The picture is made once the window knows how big it is, so a new one fills it.
        view.post {
            if (bmp.width <= 1) {
                val file = initialFile
                if (file != null && file.exists()) load(file) else blankPicture()
            }
        }

        renderOptions()
        updateTitle()
        setToolHint()
        return contentView
    }

    private fun label(text: String, size: Float = 12f): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(DARK)
            textSize = size
            fontManager.applyThemeFont(this, theme)
        }

    private fun buildMenuBar(bar: LinearLayout) {
        bar.setBackgroundColor(FACE)
        val menus = listOf("File", "Edit", "View", "Image", "Colors", "Help")
        for (title in menus) {
            val item = label(title).apply {
                setPadding(10.dp(), 5.dp(), 10.dp(), 5.dp())
                isClickable = true
                setOnClickListener { v ->
                    onSoundPlay("click")
                    val at = IntArray(2)
                    v.getLocationOnScreen(at)
                    lastMenuX = at[0].toFloat()
                    lastMenuY = (at[1] + v.height).toFloat()
                    onShowMenu(menuFor(title), lastMenuX, lastMenuY)
                }
            }
            bar.addView(item)
        }
    }

    private fun buildToolbox(box: LinearLayout) {
        box.setBackgroundColor(FACE)
        box.setPadding(3.dp(), 3.dp(), 3.dp(), 3.dp())
        toolButtons.clear()
        var row: LinearLayout? = null
        TOOLS.forEachIndexed { i, t ->
            if (i % 2 == 0) {
                row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                box.addView(row)
            }
            val button = FrameLayout(context).apply {
                background = Bevel(raised = true)
                layoutParams = LinearLayout.LayoutParams(27.dp(), 25.dp()).apply {
                    rightMargin = 1.dp()
                    bottomMargin = 1.dp()
                }
                isClickable = true
                addView(
                    GlyphView(context, ICONS.getValue(t.id), ICON_PAL),
                    FrameLayout.LayoutParams(18.dp(), 18.dp(), Gravity.CENTER)
                )
                setOnClickListener {
                    onSoundPlay("click")
                    setTool(t.id)
                }
            }
            toolButtons[t.id] = button
            row?.addView(button)
        }
        markPickedTool()
    }

    private fun markPickedTool() {
        for ((id, view) in toolButtons) view.background = Bevel(raised = id != tool)
    }

    /** The box under the tools, holding the choices the current tool has. */
    private fun renderOptions() {
        val box = optionsBox ?: return
        box.removeAllViews()
        box.setBackgroundColor(FACE)
        box.setPadding(4.dp(), 6.dp(), 4.dp(), 6.dp())

        fun option(on: Boolean, content: View, pick: () -> Unit): View =
            FrameLayout(context).apply {
                background = Bevel(raised = !on)
                setPadding(2.dp(), 2.dp(), 2.dp(), 2.dp())
                addView(content, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))
                isClickable = true
                setOnClickListener {
                    onSoundPlay("click")
                    pick()
                    renderOptions()
                }
            }

        fun rows(vararg views: View, perRow: Int) {
            var line: LinearLayout? = null
            views.forEachIndexed { i, v ->
                if (i % perRow == 0) {
                    line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    box.addView(line)
                }
                v.layoutParams = LinearLayout.LayoutParams(26.dp(), 24.dp()).apply {
                    rightMargin = 1.dp(); bottomMargin = 1.dp()
                }
                line?.addView(v)
            }
        }

        val textPal = mapOf('#' to DARK, 'W' to LIGHT, 'g' to SHADOW)
        when (tool) {
            "select", "free", "text" -> rows(
                option(!optTransparent, GlyphView(context, OPAQUE, textPal)) { optTransparent = false; reRenderSel() },
                option(optTransparent, GlyphView(context, OPAQUE.map { it.replace('W', '.') }.toTypedArray(), textPal)) { optTransparent = true; reRenderSel() },
                perRow = 1
            )
            "eraser" -> rows(
                *intArrayOf(4, 6, 8, 10).map { n ->
                    option(optEraser == n, GlyphView(context, penRows(Raster.square(n)), textPal)) { optEraser = n }
                }.toTypedArray(),
                perRow = 2
            )
            "magnifier" -> rows(
                *intArrayOf(1, 2, 6, 8).map { z ->
                    option(zoom == z, label("${z}x").apply { gravity = Gravity.CENTER }) {
                        if (z > 1) optMagnify = z
                        setZoom(z, null, null)
                    }
                }.toTypedArray(),
                perRow = 2
            )
            "brush" -> rows(
                *BRUSHES.map { k ->
                    option(optBrush == k, GlyphView(context, penRows(Raster.pen(k)), textPal)) { optBrush = k }
                }.toTypedArray(),
                perRow = 3
            )
            "airbrush" -> rows(
                *SPRAY.mapIndexed { i, r ->
                    option(optAir == i, GlyphView(context, sprayRows(r), textPal)) { optAir = i }
                }.toTypedArray(),
                perRow = 2
            )
            "line", "curve" -> rows(
                *intArrayOf(1, 2, 3, 4, 5).map { n ->
                    option(optLine == n, GlyphView(context, Array(n) { "#".repeat(25) }, textPal)) { optLine = n }
                }.toTypedArray(),
                perRow = 1
            )
            "rect", "polygon", "ellipse", "roundrect" -> {
                val edge = "#".repeat(23)
                fun pic(inner: Char) =
                    (listOf(edge) + List(7) { "#" + inner.toString().repeat(21) + "#" } + listOf(edge)).toTypedArray()
                rows(
                    option(optFill == 0, GlyphView(context, pic('.'), textPal)) { optFill = 0 },
                    option(optFill == 1, GlyphView(context, pic('g'), textPal)) { optFill = 1 },
                    option(optFill == 2, GlyphView(context, Array(9) { "g".repeat(23) }, textPal)) { optFill = 2 },
                    perRow = 1
                )
            }
        }

        if (tool == "text") {
            rows(
                *TEXT_SIZES.map { size ->
                    option(optTextSize == size, label("$size", 10f).apply { gravity = Gravity.CENTER }) {
                        optTextSize = size
                        textEdit?.textSize = (size * zoom).toFloat()
                    }
                }.toTypedArray(),
                perRow = 3
            )
        }
    }

    private fun buildColorBox(box: LinearLayout) {
        box.setBackgroundColor(FACE)
        box.setPadding(4.dp(), 4.dp(), 4.dp(), 4.dp())
        box.gravity = Gravity.CENTER_VERTICAL

        // The two colors in use, the background peeking out from behind the foreground
        val pair = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(34.dp(), 34.dp()).apply { rightMargin = 8.dp() }
        }
        val back = View(context).apply {
            background = Swatch(bg)
            layoutParams = FrameLayout.LayoutParams(20.dp(), 20.dp(), Gravity.BOTTOM or Gravity.END)
        }
        val front = View(context).apply {
            background = Swatch(fg)
            layoutParams = FrameLayout.LayoutParams(20.dp(), 20.dp(), Gravity.TOP or Gravity.START)
        }
        pair.addView(back)
        pair.addView(front)
        pair.setOnClickListener {
            onSoundPlay("click")
            val was = fg
            setColor(true, bg)
            setColor(false, was)
        }
        fgSwatch = front
        bgSwatch = back
        box.addView(pair)

        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (r in 0..1) {
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 14) {
                val color = PALETTE[r * 14 + c]
                line.addView(View(context).apply {
                    background = Swatch(color)
                    layoutParams = LinearLayout.LayoutParams(16.dp(), 16.dp()).apply {
                        rightMargin = 1.dp(); bottomMargin = 1.dp()
                    }
                    isClickable = true
                    isLongClickable = true
                    setOnClickListener {
                        onSoundPlay("click")
                        setColor(true, color)
                    }
                    // No right button here: holding a swatch is what sets the background color
                    setOnLongClickListener {
                        onSoundPlay("click")
                        setColor(false, color)
                        true
                    }
                })
            }
            grid.addView(line)
        }
        box.addView(grid)
    }

    private fun buildStatusBar(bar: LinearLayout) {
        bar.setBackgroundColor(FACE)
        bar.setPadding(2.dp(), 2.dp(), 2.dp(), 2.dp())
        fun cell(weight: Float, width: Int): TextView = label("", 10f).apply {
            background = Bevel(raised = false)
            setPadding(4.dp(), 2.dp(), 4.dp(), 2.dp())
            layoutParams = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
                .apply { rightMargin = 2.dp() }
        }
        statusHint = cell(1f, 0)
        statusPos = cell(0f, 74.dp())
        statusSize = cell(0f, 74.dp())
        bar.addView(statusHint)
        bar.addView(statusPos)
        bar.addView(statusSize)
    }

    /* ================================================================
       The picture itself
       ================================================================ */

    /** A new picture is the size of the window, so there is nothing to scroll to start with. */
    private fun blankPicture() {
        val view = canvasView
        val gap = (MARGIN * 2).roundToInt()
        val w = min(MAX_NEW, max(160, (view?.width ?: 480) - gap))
        val h = min(MAX_NEW, max(120, (view?.height ?: 360) - gap))
        newPicture(w, h)
    }

    private fun newPicture(w: Int, h: Int) {
        val fresh = Raster.bitmap(w, h)
        Canvas(fresh).drawColor(Color.WHITE)
        setImage(fresh)
        undo.clear()
        redo.clear()
        sel = null
        poly = null
        curve = null
        name = "untitled"
        sourceUri = null
        sourceFile = null
        dirty = false
        updateTitle()
    }

    private fun setImage(picture: Bitmap) {
        bmp = picture
        work = Canvas(bmp)
        canvasView?.clampPan()
        canvasView?.invalidate()
    }

    private fun pushUndo(): Bitmap {
        val snap = Raster.copy(bmp)
        undo.add(snap)
        while (undo.size > UNDO_STEPS) undo.removeAt(0)
        redo.clear()
        return snap
    }

    private fun undo() {
        commitText()
        finishShape()
        val snap = undo.removeLastOrNull() ?: return
        redo.add(Raster.copy(bmp))
        sel = null
        setImage(snap)
        markDirty()
    }

    private fun redo() {
        val snap = redo.removeLastOrNull() ?: return
        undo.add(Raster.copy(bmp))
        sel = null
        setImage(snap)
        markDirty()
    }

    private fun markDirty() {
        dirty = true
        canvasView?.invalidate()
        updateTitle()
    }

    private fun updateTitle() {
        onUpdateWindowTitle("$name${if (dirty) "*" else ""} - Paint")
    }

    /** The picture with any floating selection and text set down into it. */
    private fun flattened(): Bitmap {
        val s = sel ?: return bmp
        val floating = s.src ?: return bmp
        val out = Raster.copy(bmp)
        Canvas(out).drawBitmap(s.view ?: floating, s.x.toFloat(), s.y.toFloat(), blit)
        return out
    }

    /* ================================================================
       Colors, tools and zoom
       ================================================================ */

    private fun setColor(foreground: Boolean, color: Int) {
        if (foreground) fg = color else bg = color
        fgSwatch?.background = Swatch(fg)
        bgSwatch?.background = Swatch(bg)
        if (!foreground) reRenderSel()
        textEdit?.setTextColor(fg)
    }

    private fun setTool(id: String) {
        if (tool == id) return
        commitText()
        finishShape()
        if (tool != "magnifier" && tool != "pick") prevTool = tool
        if (id != "select" && id != "free") commitSel()
        tool = id
        markPickedTool()
        renderOptions()
        setToolHint()
        canvasView?.invalidate()
    }

    private fun setToolHint() {
        statusHint?.text = TOOLS.first { it.id == tool }.hint
    }

    private fun setZoom(z: Int, focusX: Int?, focusY: Int?) {
        commitText()
        val was = zoom
        zoom = z.coerceIn(1, 8)
        if (zoom == was) return
        if (zoom < 3) showGrid = false
        canvasView?.let { view ->
            if (focusX != null && focusY != null) view.centerOn(focusX, focusY) else view.clampPan()
            view.invalidate()
        }
        renderOptions()
    }

    /* ================================================================
       The tools
       ================================================================ */

    private fun inside(x: Int, y: Int) = x >= 0 && y >= 0 && x < bmp.width && y < bmp.height

    private fun clipBox(ax: Int, ay: Int, bx: Int, by: Int): Rect {
        val b = Raster.box(ax, ay, bx, by)
        val left = b[0].coerceIn(0, bmp.width)
        val top = b[1].coerceIn(0, bmp.height)
        val right = (b[2] + 1).coerceIn(0, bmp.width)
        val bottom = (b[3] + 1).coerceIn(0, bmp.height)
        return Rect(left, top, right, bottom)
    }

    private fun toolDown(x: Int, y: Int) {
        if (textBox != null) {
            commitText()
            if (tool == "text") return
        }
        val o = Op(x, y)
        when (tool) {
            "free" -> {
                commitSel()
                trace = arrayListOf(intArrayOf(x, y))
            }
            "select" -> commitSel()
            "eraser" -> {
                pushUndo()
                brush.color = bg
                Raster.stamp(work, brush, x, y, Raster.square(optEraser))
                markDirty()
            }
            "fill" -> {
                pushUndo()
                if (Raster.flood(bmp, x, y, fg)) markDirty() else undo.removeLastOrNull()
                return
            }
            "pick" -> {
                if (inside(x, y)) setColor(true, Raster.pixel(bmp, x, y))
                setTool(prevTool)
                return
            }
            "magnifier" -> {
                if (zoom > 1) setZoom(1, null, null) else setZoom(optMagnify, x, y)
                setTool(prevTool)
                return
            }
            "pencil" -> {
                pushUndo()
                brush.color = fg
                work.drawRect(x.toFloat(), y.toFloat(), (x + 1).toFloat(), (y + 1).toFloat(), brush)
                markDirty()
            }
            "brush" -> {
                pushUndo()
                o.pen = Raster.pen(optBrush)
                brush.color = fg
                Raster.stamp(work, brush, x, y, o.pen!!)
                markDirty()
            }
            "airbrush" -> {
                pushUndo()
                startSpraying(o)
            }
            "text" -> commitSel()
            "line" -> {
                o.base = pushUndo()
                drawLineOp(o, x, y)
            }
            "curve" -> {
                if (curve == null) {
                    val c = Shape(pushUndo(), fg, bg)
                    c.pts.add(intArrayOf(x, y))    // a
                    c.pts.add(intArrayOf(x, y))    // b
                    curve = c
                }
                drawCurve(x, y)
            }
            "polygon" -> {
                val g = poly
                if (g == null) {
                    val fresh = Shape(pushUndo(), fg, bg)
                    fresh.pts.add(intArrayOf(x, y))
                    fresh.pts.add(intArrayOf(x, y))
                    poly = fresh
                } else {
                    val first = g.pts.first()
                    val last = g.pts.last()
                    val near = { a: IntArray -> abs(x - a[0]) <= 4 && abs(y - a[1]) <= 4 }
                    if ((System.currentTimeMillis() - g.lastPress < 450 && near(last)) ||
                        (g.pts.size > 2 && near(first))
                    ) {
                        closePoly()
                        return
                    }
                    g.pts.add(intArrayOf(x, y))
                }
                drawPoly()
            }
            "rect", "ellipse", "roundrect" -> o.base = pushUndo()
        }
        op = o
    }

    private fun toolMove(x: Int, y: Int) {
        val o = op ?: return
        if (x == o.lastX && y == o.lastY) return
        o.moved = true
        when (tool) {
            "free" -> trace?.add(intArrayOf(x, y))
            "select", "text" -> {
                ghost = clipBox(o.startX, o.startY, x, y)
                statusSize?.text = "${ghost!!.width()}x${ghost!!.height()}"
            }
            "eraser" -> {
                brush.color = bg
                Raster.line(work, brush, o.lastX, o.lastY, x, y, Raster.square(optEraser))
            }
            "pencil" -> {
                brush.color = fg
                Raster.line(work, brush, o.lastX, o.lastY, x, y, arrayOf(intArrayOf(0, 0, 0)))
            }
            "brush" -> {
                brush.color = fg
                Raster.line(work, brush, o.lastX, o.lastY, x, y, o.pen ?: Raster.pen(optBrush))
            }
            "airbrush" -> Unit   // the spray keeps going wherever the finger rests
            "line" -> drawLineOp(o, x, y)
            "curve" -> drawCurve(x, y)
            "polygon" -> {
                poly?.let { g ->
                    g.pts[g.pts.size - 1] = intArrayOf(x, y)
                    drawPoly()
                }
            }
            "rect", "ellipse", "roundrect" -> drawShapeOp(o, x, y)
        }
        o.lastX = x
        o.lastY = y
        statusPos?.text = "$x,$y"
        canvasView?.invalidate()
    }

    private fun toolUp(x: Int, y: Int) {
        val o = op ?: return
        op = null
        when (tool) {
            "free" -> finishFreeForm()
            "select" -> {
                ghost = null
                statusSize?.text = ""
                val r = clipBox(o.startX, o.startY, x, y)
                if (o.moved && r.width() > 0 && r.height() > 0) makeSel(r, null)
            }
            "text" -> {
                ghost = null
                statusSize?.text = ""
                var r = clipBox(o.startX, o.startY, x, y)
                if (!o.moved || r.width() < 8 || r.height() < 8) {
                    val line = (optTextSize * 1.6f).roundToInt()
                    r = Rect(
                        o.startX, o.startY,
                        min(o.startX + max(140, optTextSize * 10), bmp.width),
                        min(o.startY + line + 6, bmp.height)
                    )
                }
                if (r.width() > 4 && r.height() > 4) makeText(r)
            }
            "airbrush" -> stopSpraying()
            "line" -> {
                drawLineOp(o, x, y)
                markDirty()
            }
            "curve" -> {
                val c = curve
                if (c != null) {
                    drawCurve(x, y)
                    if (c.stage == 1 && !o.moved) cancelShape()
                    else if (c.stage == 3) { curve = null; markDirty() }
                    else { c.stage++; markDirty() }
                }
            }
            "polygon" -> {
                val g = poly
                if (g != null) {
                    if (g.pts.size == 2 && !o.moved) cancelShape()
                    else { g.lastPress = System.currentTimeMillis(); markDirty() }
                }
            }
            "rect", "ellipse", "roundrect" -> {
                statusSize?.text = ""
                if (!o.moved) {
                    o.base?.let { setImage(it) }
                    undo.removeLastOrNull()
                } else {
                    drawShapeOp(o, x, y)
                    markDirty()
                }
            }
            "eraser", "pencil", "brush" -> markDirty()
        }
        canvasView?.invalidate()
    }

    private fun drawLineOp(o: Op, x: Int, y: Int) {
        val base = o.base ?: return
        work.drawBitmap(base, 0f, 0f, blit)
        brush.color = fg
        Raster.line(work, brush, o.startX, o.startY, x, y, Raster.square(optLine))
    }

    private fun drawShapeOp(o: Op, x: Int, y: Int) {
        val base = o.base ?: return
        work.drawBitmap(base, 0f, 0f, blit)
        val b = Raster.box(o.startX, o.startY, x, y)
        drawShape(tool, b[0], b[1], b[2], b[3], fg, bg, optFill, optLine)
        statusSize?.text = "${b[2] - b[0] + 1}x${b[3] - b[1] + 1}"
    }

    private fun drawShape(
        kind: String, x0: Int, y0: Int, x1: Int, y1: Int,
        stroke: Int, fill: Int, style: Int, width: Int
    ) {
        val rows: (Int, Int, Int, Int) -> List<IntArray> = when (kind) {
            "ellipse" -> Raster::ellipseRows
            "roundrect" -> Raster::roundRows
            else -> Raster::rectRows
        }
        if (style == 1) {
            brush.color = fill
            Raster.spans(work, brush, rows(x0, y0, x1, y1))
        }
        brush.color = stroke
        Raster.spans(
            work, brush,
            if (style == 2) rows(x0, y0, x1, y1) else Raster.outlineRows(rows, x0, y0, x1, y1, width)
        )
    }

    private fun drawCurve(x: Int, y: Int) {
        val c = curve ?: return
        when (c.stage) {
            1 -> c.pts[1] = intArrayOf(x, y)
            2 -> { if (c.pts.size < 3) c.pts.add(intArrayOf(x, y)) else c.pts[2] = intArrayOf(x, y) }
            else -> { if (c.pts.size < 4) c.pts.add(intArrayOf(x, y)) else c.pts[3] = intArrayOf(x, y) }
        }
        work.drawBitmap(c.base, 0f, 0f, blit)
        brush.color = c.color
        val pen = Raster.square(optLine)
        val a = c.pts[0]
        val b = c.pts[1]
        val line = if (c.stage == 1) listOf(a, b)
        else {
            val c1 = c.pts[2]
            val c2 = if (c.pts.size > 3) c.pts[3] else c1
            Raster.bezier(a, c1, c2, b)
        }
        for (i in 1 until line.size) {
            Raster.line(work, brush, line[i - 1][0], line[i - 1][1], line[i][0], line[i][1], pen)
        }
        canvasView?.invalidate()
    }

    private fun drawPoly() {
        val g = poly ?: return
        work.drawBitmap(g.base, 0f, 0f, blit)
        brush.color = g.color
        val pen = Raster.square(optLine)
        for (i in 1 until g.pts.size) {
            Raster.line(work, brush, g.pts[i - 1][0], g.pts[i - 1][1], g.pts[i][0], g.pts[i][1], pen)
        }
        canvasView?.invalidate()
    }

    private fun closePoly() {
        val g = poly ?: return
        poly = null
        work.drawBitmap(g.base, 0f, 0f, blit)
        if (optFill > 0) {
            brush.color = if (optFill == 1) g.other else g.color
            Raster.spans(work, brush, Raster.polygonRows(g.pts))
        }
        if (optFill < 2) {
            brush.color = g.color
            val pen = Raster.square(optLine)
            for (i in g.pts.indices) {
                val a = g.pts[i]
                val b = g.pts[(i + 1) % g.pts.size]
                Raster.line(work, brush, a[0], a[1], b[0], b[1], pen)
            }
        }
        markDirty()
    }

    /** A polygon or curve still being drawn is finished as far as it has got. */
    private fun finishShape() {
        poly?.let { if (it.pts.size > 2) closePoly() else poly = null }
        curve = null
    }

    private fun cancelShape() {
        val g = poly ?: curve ?: return
        work.drawBitmap(g.base, 0f, 0f, blit)
        undo.removeLastOrNull()
        poly = null
        curve = null
        canvasView?.invalidate()
    }

    private fun startSpraying(o: Op) {
        stopSpraying()
        val r = SPRAY[optAir]
        val n = ceilDiv(r * r, 6)
        val tick = object : Runnable {
            override fun run() {
                brush.color = fg
                repeat(n) {
                    val a = Random.nextDouble() * Math.PI * 2
                    val d = sqrt(Random.nextDouble()) * r
                    val px = (o.lastX + Math.cos(a) * d).roundToInt()
                    val py = (o.lastY + Math.sin(a) * d).roundToInt()
                    work.drawRect(px.toFloat(), py.toFloat(), (px + 1).toFloat(), (py + 1).toFloat(), brush)
                }
                canvasView?.invalidate()
                handler.postDelayed(this, 30)
            }
        }
        spray = tick
        handler.post(tick)
        markDirty()
    }

    private fun stopSpraying() {
        spray?.let { handler.removeCallbacks(it) }
        spray = null
    }

    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b

    private fun finishFreeForm() {
        val pts = trace ?: return
        trace = null
        if (pts.size < 3) return
        val x0 = max(0, pts.minOf { it[0] })
        val y0 = max(0, pts.minOf { it[1] })
        val x1 = min(bmp.width, pts.maxOf { it[0] } + 1)
        val y1 = min(bmp.height, pts.maxOf { it[1] } + 1)
        if (x1 - x0 < 2 || y1 - y0 < 2) return
        val mask = Raster.bitmap(x1 - x0, y1 - y0)
        val mc = Canvas(mask)
        val mp = Raster.flatPaint().apply { color = Color.BLACK }
        val local = pts.map { intArrayOf(it[0] - x0, it[1] - y0) }
        Raster.spans(mc, mp, Raster.polygonRows(local))
        for (i in local.indices) {
            val a = local[i]
            val b = local[(i + 1) % local.size]
            Raster.line(mc, mp, a[0], a[1], b[0], b[1], arrayOf(intArrayOf(0, 0, 0)))
        }
        makeSel(Rect(x0, y0, x1, y1), mask)
    }

    /* ================================================================
       Selections
       ================================================================ */

    private fun makeSel(r: Rect, mask: Bitmap?) {
        sel = Sel(r.left, r.top, r.width(), r.height()).also { it.mask = mask }
        reRenderSel()
    }

    private fun reRenderSel() {
        val s = sel
        if (s?.src != null) {
            s.view = if (optTransparent) Raster.clearColor(s.src!!, bg) else s.src
        }
        canvasView?.invalidate()
    }

    /** The selected pixels as they look now. */
    private fun selBitmap(): Bitmap? {
        val s = sel ?: return null
        s.src?.let { return s.view ?: it }
        val out = Raster.bitmap(s.w, s.h)
        val c = Canvas(out)
        c.drawBitmap(bmp, Rect(s.x, s.y, s.x + s.w, s.y + s.h), Rect(0, 0, s.w, s.h), blit)
        s.mask?.let { c.drawBitmap(it, 0f, 0f, eraseWith) }
        return out
    }

    /** Picks the selected pixels up off the picture, leaving the background color behind. */
    private fun lift(clear: Boolean) {
        val s = sel ?: return
        if (s.src != null) return
        pushUndo()
        s.src = selBitmap()
        if (clear) clearArea(s)
        s.mask = null
        reRenderSel()
        markDirty()
    }

    private fun clearArea(s: Sel) {
        val mask = s.mask
        if (mask == null) {
            brush.color = bg
            work.drawRect(
                s.x.toFloat(), s.y.toFloat(),
                (s.x + s.w).toFloat(), (s.y + s.h).toFloat(), brush
            )
            return
        }
        val hole = Raster.bitmap(s.w, s.h)
        val hc = Canvas(hole)
        hc.drawColor(bg)
        hc.drawBitmap(mask, 0f, 0f, eraseWith)
        work.drawBitmap(hole, s.x.toFloat(), s.y.toFloat(), blit)
    }

    /** Sets a floating selection down into the picture. */
    private fun commitSel() {
        val s = sel ?: return
        s.src?.let {
            work.drawBitmap(s.view ?: it, s.x.toFloat(), s.y.toFloat(), blit)
            markDirty()
        }
        sel = null
        canvasView?.invalidate()
    }

    private fun clearSel() {
        val s = sel ?: return
        if (s.src == null) {
            pushUndo()
            clearArea(s)
            markDirty()
        }
        sel = null
        canvasView?.invalidate()
    }

    private fun selectAll() {
        setTool("select")
        commitText()
        commitSel()
        makeSel(Rect(0, 0, bmp.width, bmp.height), null)
    }

    private fun copySel() {
        val c = selBitmap() ?: return
        clipboard = Raster.copy(c)
    }

    private fun cutSel() {
        if (sel == null) return
        copySel()
        clearSel()
    }

    private fun paste() {
        val c = clipboard ?: return
        commitText()
        finishShape()
        if (tool != "free") setTool("select")
        commitSel()
        val place = {
            pushUndo()
            val s = Sel(0, 0, c.width, c.height)
            s.src = Raster.copy(c)
            sel = s
            reRenderSel()
            markDirty()
        }
        if (c.width <= bmp.width && c.height <= bmp.height) place()
        else onAskConfirm(
            "Paint",
            "The image in the clipboard is larger than the bitmap.\nWould you like the bitmap enlarged?",
            {
                pushUndo()
                resizeImage(max(bmp.width, c.width), max(bmp.height, c.height))
                val s = Sel(0, 0, c.width, c.height)
                s.src = Raster.copy(c)
                sel = s
                reRenderSel()
                markDirty()
            },
            { place() }
        )
    }

    /* ================================================================
       Text
       ================================================================ */

    private fun makeText(r: Rect) {
        commitText()
        textBox = r
        val edit = EditText(context).apply {
            background = null
            setPadding(0, 0, 0, 0)
            gravity = Gravity.TOP or Gravity.START
            setTextColor(fg)
            setBackgroundColor(if (optTransparent) Color.TRANSPARENT else bg)
            textSize = (optTextSize * zoom).toFloat()
            includeFontPadding = false
            typeface = Typeface.SANS_SERIF
            isSingleLine = false
        }
        textEdit = edit
        canvasArea?.addView(edit, FrameLayout.LayoutParams(r.width() * zoom, r.height() * zoom))
        placeText()
        edit.requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun placeText() {
        val edit = textEdit ?: return
        val r = textBox ?: return
        val view = canvasView ?: return
        val lp = edit.layoutParams as FrameLayout.LayoutParams
        lp.width = r.width() * zoom
        lp.height = r.height() * zoom
        lp.leftMargin = (view.offsetX() + r.left * zoom).roundToInt()
        lp.topMargin = (view.offsetY() + r.top * zoom).roundToInt()
        edit.layoutParams = lp
        edit.textSize = (optTextSize * zoom).toFloat()
    }

    /** Sets what was typed into the picture, the way clicking away from the box does. */
    private fun commitText() {
        val edit = textEdit ?: return
        val r = textBox ?: return
        val typed = edit.text.toString()
        textEdit = null
        textBox = null
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(edit.windowToken, 0)
        (edit.parent as? ViewGroup)?.removeView(edit)
        if (typed.isEmpty()) return

        pushUndo()
        if (!optTransparent) {
            brush.color = bg
            work.drawRect(
                r.left.toFloat(), r.top.toFloat(),
                r.right.toFloat(), r.bottom.toFloat(), brush
            )
        }
        val tp = TextPaint().apply {
            isAntiAlias = true
            color = fg
            textSize = optTextSize.toFloat()
            typeface = Typeface.SANS_SERIF
        }
        val layout = StaticLayout.Builder
            .obtain(typed, 0, typed.length, tp, max(1, r.width()))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
        work.save()
        work.clipRect(r)
        work.translate(r.left.toFloat(), r.top.toFloat())
        layout.draw(work)
        work.restore()
        markDirty()
    }

    /* ================================================================
       The Image menu
       ================================================================ */

    private fun resizeImage(w: Int, h: Int) {
        val old = bmp
        val fresh = Raster.bitmap(w, h)
        val c = Canvas(fresh)
        c.drawColor(bg)
        c.drawBitmap(old, 0f, 0f, blit)
        setImage(fresh)
        markDirty()
    }

    /**
     * Runs a picture-to-picture change on the selection, or on the whole picture when there
     * is no selection - which is what every item on the Image menu does.
     */
    private fun transform(make: (Bitmap, Int) -> Bitmap) {
        commitText()
        finishShape()
        val s = sel
        if (s != null) {
            if (s.src == null) lift(true)
            s.src = make(s.src!!, Color.TRANSPARENT)
            s.w = s.src!!.width
            s.h = s.src!!.height
            reRenderSel()
        } else {
            pushUndo()
            setImage(make(bmp, bg))
        }
        markDirty()
    }

    private fun flipRotate() {
        val items =
            listOf(
                ContextMenuItem("Flip horizontal", action = {
                    transform { src, fill ->
                        Raster.remap(src, src.width, src.height, fill) { x, y, at ->
                            at[0] = src.width - 1 - x; at[1] = y
                        }
                    }
                }),
                ContextMenuItem("Flip vertical", action = {
                    transform { src, fill ->
                        Raster.remap(src, src.width, src.height, fill) { x, y, at ->
                            at[0] = x; at[1] = src.height - 1 - y
                        }
                    }
                }),
                ContextMenuItem("", isEnabled = false),
                ContextMenuItem("Rotate by 90°", action = {
                    transform { src, fill ->
                        Raster.remap(src, src.height, src.width, fill) { x, y, at ->
                            at[0] = y; at[1] = src.height - 1 - x
                        }
                    }
                }),
                ContextMenuItem("Rotate by 180°", action = {
                    transform { src, fill ->
                        Raster.remap(src, src.width, src.height, fill) { x, y, at ->
                            at[0] = src.width - 1 - x; at[1] = src.height - 1 - y
                        }
                    }
                }),
                ContextMenuItem("Rotate by 270°", action = {
                    transform { src, fill ->
                        Raster.remap(src, src.height, src.width, fill) { x, y, at ->
                            at[0] = src.width - 1 - y; at[1] = x
                        }
                    }
                })
            )
        handler.post { onShowMenu(items, menuAnchorX(), menuAnchorY()) }
    }

    private fun stretchSkew() {
        onAskText(
            "Stretch and Skew",
            "100 100 0 0",
            "Stretch H V, Skew H V"
        ) { typed ->
            val parts = typed.split(Regex("[,\\s]+")).mapNotNull { it.trim().toIntOrNull() }
            if (parts.size < 4) {
                onSayMessage("Paint", "Enter four whole numbers: stretch across, stretch down, skew across, skew down.")
                return@onAskText
            }
            val ph = parts[0]
            val pv = parts[1]
            val ah = parts[2]
            val av = parts[3]
            if (ph !in 1..500 || pv !in 1..500) {
                onSayMessage("Paint", "Please enter an integer between 1 and 500 for the stretch.")
                return@onAskText
            }
            if (ah !in -89..89 || av !in -89..89) {
                onSayMessage("Paint", "Please enter an integer between -89 and 89 for the skew.")
                return@onAskText
            }
            if (ph == 100 && pv == 100 && ah == 0 && av == 0) return@onAskText
            transform { src, fill ->
                var c = src
                if (ph != 100 || pv != 100) {
                    val from = c
                    val w = max(1, (from.width * ph / 100.0).roundToInt())
                    val h = max(1, (from.height * pv / 100.0).roundToInt())
                    c = Raster.remap(from, w, h, fill) { x, y, at ->
                        at[0] = (x.toLong() * from.width / w).toInt()
                        at[1] = (y.toLong() * from.height / h).toInt()
                    }
                }
                if (ah != 0) {
                    val from = c
                    val t = tan(Math.toRadians(ah.toDouble()))
                    val h = from.height
                    val w = from.width + (abs(t) * (h - 1)).roundToInt()
                    c = Raster.remap(from, w, h, fill) { x, y, at ->
                        at[0] = (x - (if (t > 0) t * (h - 1 - y) else -t * y)).roundToInt()
                        at[1] = y
                    }
                }
                if (av != 0) {
                    val from = c
                    val t = tan(Math.toRadians(av.toDouble()))
                    val w = from.width
                    val h = from.height + (abs(t) * (w - 1)).roundToInt()
                    c = Raster.remap(from, w, h, fill) { x, y, at ->
                        at[0] = x
                        at[1] = (y - (if (t > 0) t * (w - 1 - x) else -t * x)).roundToInt()
                    }
                }
                c
            }
        }
    }

    private fun invertColors() {
        commitText()
        val s = sel
        if (s?.src != null) {
            s.src = Raster.invert(s.src!!)
            reRenderSel()
        } else if (s != null) {
            pushUndo()
            val patch = Raster.invert(selBitmap()!!)
            work.drawBitmap(patch, s.x.toFloat(), s.y.toFloat(), blit)
        } else {
            pushUndo()
            setImage(Raster.invert(bmp))
        }
        markDirty()
    }

    private fun clearImage() {
        commitText()
        finishShape()
        if (sel != null) return clearSel()
        pushUndo()
        work.drawColor(bg)
        markDirty()
    }

    private fun attributes() {
        commitText()
        onAskText("Attributes", "${bmp.width} x ${bmp.height}", "Width x Height") { typed ->
            val parts = typed.split(Regex("[^0-9]+")).mapNotNull { it.toIntOrNull() }
            if (parts.size < 2 || parts[0] < 1 || parts[1] < 1) {
                onSayMessage("Paint", "Please enter a width and a height, in pixels.")
                return@onAskText
            }
            val w = min(4000, parts[0])
            val h = min(4000, parts[1])
            if (w == bmp.width && h == bmp.height) return@onAskText
            commitSel()
            pushUndo()
            resizeImage(w, h)
        }
    }

    /* ================================================================
       Opening and saving
       ================================================================ */

    /**
     * Paint asks before it throws work away. There are two buttons here rather than three, so
     * OK saves and carries on and Cancel carries on without saving - the Yes and No of the
     * original, with its third button, which backed out altogether, left off.
     */
    private fun confirmSave(then: () -> Unit) {
        if (!dirty) return then()
        onAskConfirm(
            "Paint",
            "Save changes to $name?\n\nOK saves the picture first. Cancel carries on without saving.",
            { save { then() } },
            { dirty = false; then() }
        )
    }

    private fun load(file: File) {
        val picture = try {
            decode(file.readBytes())
        } catch (e: Exception) {
            Log.w(TAG, "Could not read ${file.name}", e)
            null
        }
        if (picture == null) {
            // The window still needs something to draw on, so it opens empty and says why
            blankPicture()
            cannotRead()
            return
        }
        setImage(picture)
        undo.clear()
        redo.clear()
        sel = null
        name = file.name
        sourceUri = null
        sourceFile = if (file.canWrite()) file else null
        dirty = false
        updateTitle()
    }

    fun onPictureChosen(uri: Uri?) {
        if (uri == null) return
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the picture that was chosen", e)
            null
        }
        val picture = bytes?.let { decode(it) }
        if (picture == null) {
            cannotRead()
            return
        }
        setImage(picture)
        undo.clear()
        redo.clear()
        sel = null
        name = displayName(uri) ?: "untitled"
        sourceUri = uri
        sourceFile = null
        dirty = false
        updateTitle()
    }

    /** A picture file, as an editable picture. Bitmaps go through [Raster], which Android cannot read. */
    private fun decode(bytes: ByteArray): Bitmap? {
        val decoded = decodeScaled(bytes)
            ?: Raster.decodeBmp(bytes)
            ?: return null
        // See-through parts land on white, since a bitmap has no transparency
        val out = Raster.bitmap(decoded.width, decoded.height)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.drawBitmap(decoded, 0f, 0f, blit)
        return out
    }

    /**
     * A phone camera's picture is far bigger than anything worth editing by finger, and three
     * undo steps of one would be tens of megabytes, so it comes in halved until it fits.
     */
    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_OPEN) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val at = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            if (at >= 0 && cursor.moveToFirst()) cursor.getString(at) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun cannotRead() {
        onSayMessage(
            "Paint",
            "Paint cannot read this file.\nThis is not a valid bitmap file, or its format is not currently supported."
        )
    }

    private fun save(then: (() -> Unit)? = null) {
        val onDisk = sourceFile
        if (onDisk != null) {
            commitText()
            commitSel()
            try {
                onDisk.writeBytes(bytesFor(onDisk.name))
                dirty = false
                updateTitle()
                then?.invoke()
            } catch (e: Exception) {
                Log.w(TAG, "Could not save over ${onDisk.name}", e)
                saveAs(then)
            }
            return
        }
        val target = sourceUri
        if (target == null) {
            saveAs(then)
            return
        }
        commitText()
        commitSel()
        try {
            context.contentResolver.openOutputStream(target, "wt")?.use {
                it.write(bytesFor(name))
            } ?: throw IllegalStateException("no stream")
            dirty = false
            updateTitle()
            then?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "Could not save over $target", e)
            saveAs(then)
        }
    }

    private fun saveAs(then: (() -> Unit)? = null) {
        commitText()
        commitSel()
        val suggestion = if (name.contains('.')) name else "$name.png"
        onAskText("Save As", suggestion, "File name") { typed ->
            val fileName = if (typed.contains('.')) typed else "$typed.png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeFor(fileName))
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + File.separator + "Paint"
                )
            }
            try {
                val uri = context.contentResolver
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("no row")
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytesFor(fileName)) }
                sourceUri = uri
                sourceFile = null
                name = fileName
                dirty = false
                updateTitle()
                then?.invoke()
            } catch (e: Exception) {
                Log.w(TAG, "Could not save $fileName", e)
                onSayMessage("Paint", "Paint could not save this picture.")
            }
        }
    }

    private fun extensionOf(fileName: String) = fileName.substringAfterLast('.', "").lowercase()

    private fun mimeFor(fileName: String) = when (extensionOf(fileName)) {
        "bmp", "dib" -> "image/bmp"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "image/png"
    }

    private fun bytesFor(fileName: String): ByteArray {
        val picture = flattened()
        return when (extensionOf(fileName)) {
            "bmp", "dib" -> Raster.bmp(picture, 24)
            "jpg", "jpeg" -> Raster.encode(picture, Bitmap.CompressFormat.JPEG)
            else -> Raster.encode(picture, Bitmap.CompressFormat.PNG)
        }
    }

    /** File > Set As Wallpaper: the picture goes to the gallery, then to the launcher's own dialog. */
    private fun setAsWallpaper() {
        commitText()
        commitSel()
        val fileName = "${name.substringBeforeLast('.')}-wallpaper.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + File.separator + "Paint"
            )
        }
        try {
            val uri = context.contentResolver
                .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("no row")
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(Raster.encode(flattened(), Bitmap.CompressFormat.PNG))
            }
            onSetWallpaper(uri)
        } catch (e: Exception) {
            Log.w(TAG, "Could not hand the picture over as a wallpaper", e)
            onSayMessage("Paint", "Paint could not set this picture as the wallpaper.")
        }
    }

    /* ================================================================
       The menus
       ================================================================ */

    private fun menuAnchorX() = lastMenuX
    private fun menuAnchorY() = lastMenuY

    private fun menuFor(title: String): List<ContextMenuItem> = when (title) {
        "File" -> listOf(
            ContextMenuItem("New", action = { confirmSave { blankPicture() } }),
            ContextMenuItem("Open...", action = { confirmSave { onBrowseForPicture() } }),
            ContextMenuItem("Save", action = { save() }),
            ContextMenuItem("Save As...", action = { saveAs() }),
            ContextMenuItem("", isEnabled = false),
            ContextMenuItem("Set As Wallpaper", action = { setAsWallpaper() }),
            ContextMenuItem("", isEnabled = false),
            ContextMenuItem("Exit", action = { confirmSave { onClose() } })
        )
        "Edit" -> listOf(
            ContextMenuItem("Undo", isEnabled = undo.isNotEmpty(), action = { undo() }),
            ContextMenuItem("Repeat", isEnabled = redo.isNotEmpty(), action = { redo() }),
            ContextMenuItem("", isEnabled = false),
            ContextMenuItem("Cut", isEnabled = sel != null, action = { cutSel() }),
            ContextMenuItem("Copy", isEnabled = sel != null, action = { copySel() }),
            ContextMenuItem("Paste", isEnabled = clipboard != null, action = { paste() }),
            ContextMenuItem("Clear Selection", isEnabled = sel != null, action = { clearSel() }),
            ContextMenuItem("Select All", action = { selectAll() })
        )
        "View" -> listOf(
            ContextMenuItem("Normal Size", isEnabled = zoom != 1, action = { setZoom(1, null, null) }),
            ContextMenuItem("Large Size", isEnabled = zoom != 4, action = { setZoom(4, null, null) }),
            ContextMenuItem("", isEnabled = false),
            ContextMenuItem(
                "Show Grid", isEnabled = zoom >= 4, hasCheckbox = true, isChecked = showGrid,
                action = { showGrid = !showGrid; canvasView?.invalidate() }
            ),
            ContextMenuItem("", isEnabled = false),
            ContextMenuItem("View Bitmap", action = { setZoom(1, null, null); canvasView?.resetPan() })
        )
        "Image" -> listOf(
            ContextMenuItem("Flip/Rotate...", action = { flipRotate() }),
            ContextMenuItem("Stretch/Skew...", action = { stretchSkew() }),
            ContextMenuItem("Invert Colors", action = { invertColors() }),
            ContextMenuItem("Attributes...", action = { attributes() }),
            ContextMenuItem("Clear Image", action = { clearImage() }),
            ContextMenuItem("", isEnabled = false),
            ContextMenuItem(
                "Draw Opaque", hasCheckbox = true, isChecked = !optTransparent,
                action = { optTransparent = !optTransparent; reRenderSel(); renderOptions() }
            )
        )
        "Colors" -> listOf(
            ContextMenuItem("Edit Colors...", action = { editColors() })
        )
        else -> listOf(
            ContextMenuItem("Help Topics", action = {
                onSayMessage("Paint", "Pick a tool on the left, a color below, and draw.\n\nHold a color to make it the background. Pinch to zoom, and drag with two fingers to move around the picture.")
            }),
            ContextMenuItem("", isEnabled = false),
            ContextMenuItem("About Paint", action = {
                onSayMessage(
                    "About Paint",
                    "Microsoft ® Paint\nCopyright © 1981-1998 Microsoft Corp."
                )
            })
        )
    }

    /** Colors > Edit Colors: the basic colors, as a menu, since there is no dialog for it here. */
    private fun editColors() {
        onAskText("Edit Colors", "#%06X".format(fg and 0xFFFFFF), "Color, as #rrggbb") { typed ->
            val text = typed.trim()
            val parsed = try {
                Color.parseColor(if (text.startsWith("#")) text else "#$text")
            } catch (e: IllegalArgumentException) {
                val basic = BASIC_COLORS.firstOrNull { "#%06X".format(it and 0xFFFFFF).equals(text, true) }
                basic ?: run {
                    onSayMessage("Paint", "Enter a color as #rrggbb, for example #ff8040.")
                    return@onAskText
                }
            }
            setColor(true, parsed)
        }
    }

    /* ================================================================
       Window plumbing
       ================================================================ */

    fun onMinimize() {
        commitText()
        stopSpraying()
    }

    fun hasUnsavedChanges(): Boolean = dirty

    fun cleanup() {
        stopSpraying()
        textEdit?.let { (it.parent as? ViewGroup)?.removeView(it) }
        textEdit = null
        textBox = null
        undo.clear()
        redo.clear()
    }

    /* ================================================================
       The picture on screen
       ================================================================ */

    private fun penRows(pen: Array<IntArray>): Array<String> {
        val ys = pen.map { it[0] }
        val xs = pen.flatMap { listOf(it[1], it[2]) }
        val y0 = ys.min()
        val x0 = xs.min()
        val rows = Array(ys.max() - y0 + 1) { CharArray(xs.max() - x0 + 1) { '.' } }
        for (s in pen) for (x in s[1]..s[2]) rows[s[0] - y0][x - x0] = '#'
        return rows.map { String(it) }.toTypedArray()
    }

    private fun sprayRows(r: Int): Array<String> {
        val rows = ArrayList<String>()
        for (y in -r..r) {
            val line = StringBuilder()
            for (x in -r..r) {
                val on = x * x + y * y <= r * r && ((x * 7 + y * 13 + x * y * 3) and 5) == 0
                line.append(if (on) '#' else '.')
            }
            rows.add(line.toString())
        }
        return rows.toTypedArray()
    }

    /** The picture, the selection over it and the grid, with everything drawn pixel for pixel. */
    inner class CanvasView(ctx: Context) : View(ctx) {

        private var panX = 0f
        private var panY = 0f
        private var gesturing = false
        private var lastPanX = 0f
        private var lastPanY = 0f
        private var pinchFrom = 1f
        private var pinchScale = 1f

        private val gridPaint = Paint().apply {
            color = Color.parseColor("#C0C0C0")
            strokeWidth = 1f
        }
        private val antsLight = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = Color.WHITE
        }
        private val antsDark = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = Color.BLACK
            pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
        }
        private val edgePaint = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1f }
        private val tracePaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 1f
            pathEffect = DashPathEffect(floatArrayOf(3f, 3f), 0f)
        }

        private val pinch = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinchFrom = zoom.toFloat()
                pinchScale = 1f
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pinchScale *= detector.scaleFactor
                val wanted = pinchFrom * pinchScale
                // Paint magnifies in steps, so the pinch lands on the nearest one
                val snapped = ZOOMS.minByOrNull { abs(it - wanted) } ?: 1
                if (snapped != zoom) setZoom(snapped, imageX(detector.focusX), imageY(detector.focusY))
                return true
            }
        })

        fun offsetX(): Float {
            val w = bmp.width * zoom
            return if (w <= width - MARGIN * 2) MARGIN else -panX
        }

        fun offsetY(): Float {
            val h = bmp.height * zoom
            return if (h <= height - MARGIN * 2) MARGIN else -panY
        }

        fun clampPan() {
            panX = panX.coerceIn(0f, max(0f, bmp.width * zoom - width.toFloat()))
            panY = panY.coerceIn(0f, max(0f, bmp.height * zoom - height.toFloat()))
            placeText()
        }

        fun resetPan() {
            panX = 0f
            panY = 0f
            placeText()
            invalidate()
        }

        fun centerOn(ix: Int, iy: Int) {
            panX = ix * zoom - width / 2f
            panY = iy * zoom - height / 2f
            clampPan()
        }

        private fun imageX(screenX: Float) = floorDiv(screenX - offsetX(), zoom)
        private fun imageY(screenY: Float) = floorDiv(screenY - offsetY(), zoom)

        private fun floorDiv(v: Float, by: Int): Int = Math.floor((v / by).toDouble()).toInt()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(DESK)
            if (bmp.width <= 1) return

            val z = zoom.toFloat()
            val left = offsetX()
            val top = offsetY()
            val dst = RectF(left, top, left + bmp.width * z, top + bmp.height * z)
            canvas.drawBitmap(bmp, null, dst, blit)

            sel?.let { s ->
                s.src?.let { src ->
                    val at = RectF(
                        left + s.x * z, top + s.y * z,
                        left + (s.x + s.w) * z, top + (s.y + s.h) * z
                    )
                    canvas.drawBitmap(s.view ?: src, null, at, blit)
                }
                val box = RectF(
                    left + s.x * z, top + s.y * z,
                    left + (s.x + s.w) * z, top + (s.y + s.h) * z
                )
                canvas.drawRect(box, antsLight)
                canvas.drawRect(box, antsDark)
            }

            ghost?.let { g ->
                val box = RectF(
                    left + g.left * z, top + g.top * z,
                    left + g.right * z, top + g.bottom * z
                )
                canvas.drawRect(box, antsLight)
                canvas.drawRect(box, antsDark)
            }

            trace?.let { pts ->
                if (pts.size > 1) {
                    val line = FloatArray((pts.size - 1) * 4)
                    for (i in 1 until pts.size) {
                        line[(i - 1) * 4] = left + pts[i - 1][0] * z
                        line[(i - 1) * 4 + 1] = top + pts[i - 1][1] * z
                        line[(i - 1) * 4 + 2] = left + pts[i][0] * z
                        line[(i - 1) * 4 + 3] = top + pts[i][1] * z
                    }
                    canvas.drawLines(line, tracePaint)
                }
            }

            if (showGrid && zoom >= 4) {
                // Only the lines that fall on screen: a large picture has thousands of them
                val firstX = max(0, ((-left) / z).toInt())
                val lastX = min(bmp.width, ((width - left) / z).toInt() + 1)
                for (x in firstX..lastX) {
                    canvas.drawLine(left + x * z, max(top, 0f), left + x * z, min(dst.bottom, height.toFloat()), gridPaint)
                }
                val firstY = max(0, ((-top) / z).toInt())
                val lastY = min(bmp.height, ((height - top) / z).toInt() + 1)
                for (y in firstY..lastY) {
                    canvas.drawLine(max(left, 0f), top + y * z, min(dst.right, width.toFloat()), top + y * z, gridPaint)
                }
            }

            canvas.drawRect(dst.left - 1, dst.top - 1, dst.right, dst.bottom, edgePaint)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            clampPan()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (bmp.width <= 1) return false
            pinch.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val x = imageX(event.x)
                    val y = imageY(event.y)
                    val s = sel
                    // A press inside a selection moves it; that is what dragging one means here
                    if (s != null && x >= s.x && y >= s.y && x < s.x + s.w && y < s.y + s.h) {
                        movingSel = true
                        lastPanX = event.x
                        lastPanY = event.y
                        lift(true)
                    } else {
                        toolDown(x, y)
                    }
                    statusPos?.text = "$x,$y"
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // A second finger pans and zooms, so whatever the first was drawing stops here
                    if (movingSel) movingSel = false
                    else if (op != null) toolUp(op!!.lastX, op!!.lastY)
                    stopSpraying()
                    gesturing = true
                    pinchFrom = zoom.toFloat()
                    lastPanX = event.getX(0)
                    lastPanY = event.getY(0)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (gesturing) {
                        if (event.pointerCount >= 2 && !pinch.isInProgress) {
                            panX -= event.getX(0) - lastPanX
                            panY -= event.getY(0) - lastPanY
                            lastPanX = event.getX(0)
                            lastPanY = event.getY(0)
                            clampPan()
                            invalidate()
                        }
                    } else if (movingSel) {
                        val s = sel
                        if (s != null) {
                            val dx = ((event.x - lastPanX) / zoom).roundToInt()
                            val dy = ((event.y - lastPanY) / zoom).roundToInt()
                            if (dx != 0 || dy != 0) {
                                s.x += dx
                                s.y += dy
                                lastPanX += dx * zoom
                                lastPanY += dy * zoom
                                statusPos?.text = "${s.x},${s.y}"
                                invalidate()
                            }
                        }
                    } else {
                        toolMove(imageX(event.x), imageY(event.y))
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (gesturing) gesturing = false
                    else if (movingSel) { movingSel = false; markDirty() }
                    else toolUp(imageX(event.x), imageY(event.y))
                    stopSpraying()
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.pointerCount <= 2) {
                        lastPanX = event.getX(if (event.actionIndex == 0) 1 else 0)
                        lastPanY = event.getY(if (event.actionIndex == 0) 1 else 0)
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    /* ================================================================
       Small drawn pieces of chrome
       ================================================================ */

    /** A pixel picture written as rows of characters, drawn crisply at whatever size it gets. */
    private class GlyphView(
        ctx: Context,
        private val rows: Array<String>,
        private val palette: Map<Char, Int>
    ) : View(ctx) {
        private val paint = Raster.flatPaint()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (rows.isEmpty()) return
            val cols = rows.maxOf { it.length }
            val scale = min(width.toFloat() / cols, height.toFloat() / rows.size)
            val left = (width - cols * scale) / 2f
            val top = (height - rows.size * scale) / 2f
            for ((y, row) in rows.withIndex()) {
                for ((x, ch) in row.withIndex()) {
                    val color = palette[ch] ?: continue
                    paint.color = color
                    canvas.drawRect(
                        left + x * scale, top + y * scale,
                        left + (x + 1) * scale, top + (y + 1) * scale, paint
                    )
                }
            }
        }
    }

    /** The raised and sunken 3D edges everything in Paint's chrome is drawn with. */
    private class Bevel(private val raised: Boolean) : android.graphics.drawable.Drawable() {
        private val paint = Paint().apply { isAntiAlias = false }

        override fun draw(canvas: Canvas) {
            val b = bounds
            paint.color = FACE
            canvas.drawRect(b, paint)
            val topLeft = if (raised) LIGHT else SHADOW
            val bottomRight = if (raised) SHADOW else LIGHT
            paint.color = topLeft
            canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.top + 1f, paint)
            canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.left + 1f, b.bottom.toFloat(), paint)
            paint.color = bottomRight
            canvas.drawRect(b.left.toFloat(), b.bottom - 1f, b.right.toFloat(), b.bottom.toFloat(), paint)
            canvas.drawRect(b.right - 1f, b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
    }

    /** One color in the color box, sunk into the panel the way Paint's swatches are. */
    private class Swatch(private val color: Int) : android.graphics.drawable.Drawable() {
        private val paint = Paint().apply { isAntiAlias = false }

        override fun draw(canvas: Canvas) {
            val b = bounds
            paint.color = SHADOW
            canvas.drawRect(b, paint)
            paint.color = LIGHT
            canvas.drawRect(b.left + 1f, b.bottom - 1f, b.right.toFloat(), b.bottom.toFloat(), paint)
            canvas.drawRect(b.right - 1f, b.top + 1f, b.right.toFloat(), b.bottom.toFloat(), paint)
            paint.color = color
            canvas.drawRect(b.left + 1f, b.top + 1f, b.right - 1f, b.bottom - 1f, paint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
    }
}
