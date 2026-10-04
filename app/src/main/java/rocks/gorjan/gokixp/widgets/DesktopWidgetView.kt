package rocks.gorjan.gokixp.widgets

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import rocks.gorjan.gokixp.R
import kotlin.math.abs

/**
 * One Android app widget sitting on the desktop.
 *
 * The frame wraps the app's [AppWidgetHostView] and owns everything the launcher adds on top
 * of it: a long press for the context menu, and an edit mode (entered from that menu's
 * "Move / Resize") in which the whole widget is a drag handle and the four corner grips,
 * Windows selection-handle style, resize it. Outside edit mode every touch belongs to the
 * widget itself - its buttons, its lists - except a long press, which is ours.
 */
@SuppressLint("ViewConstructor")
class DesktopWidgetView(
    context: Context,
    val appWidgetId: Int,
    val hostView: AppWidgetHostView,
    val providerInfo: AppWidgetProviderInfo,
) : FrameLayout(context) {

    /** Long press outside edit mode; coordinates are on screen, for the context menu. */
    var onLongPress: ((DesktopWidgetView, Float, Float) -> Unit)? = null

    /** A drag or a resize was dropped: the new geometry wants saving. */
    var onGeometryChanged: ((DesktopWidgetView) -> Unit)? = null

    /** Edit mode was left from inside the widget (a tap without a drag). */
    var onEditModeExited: ((DesktopWidgetView) -> Unit)? = null

    var isEditMode = false
        private set

    /**
     * Where the user put the widget and how big they made it - what gets saved. What's on
     * screen can be smaller or shifted to fit a desktop that shrank (a rotation), and goes
     * back to this when there's room again.
     */
    var savedX = 0f
    var savedY = 0f
    var savedWidth = 0
    var savedHeight = 0

    /** Takes what's on screen now as the user's chosen geometry. */
    fun commitGeometry() {
        savedX = x
        savedY = y
        savedWidth = layoutParams.width
        savedHeight = layoutParams.height
    }

    /** Lays the widget out from its saved geometry, fitted inside a desktop of the given size. */
    fun fitInto(desktopWidth: Int, desktopHeight: Int) {
        if (desktopWidth <= 0 || desktopHeight <= 0) return
        val width = savedWidth.coerceAtMost(desktopWidth)
        val height = savedHeight.coerceAtMost(desktopHeight)
        if (width != layoutParams.width || height != layoutParams.height) {
            applySize(width, height)
            reportSizeToProvider(width, height)
        }
        x = savedX.coerceIn(0f, (desktopWidth - width).toFloat())
        y = savedY.coerceIn(0f, (desktopHeight - height).toFloat())
    }

    private val density = resources.displayMetrics.density

    /**
     * How big the widget's own content is drawn, 1 = as the app made it. The frame keeps its
     * size; the widget is laid out at frame / scale and then scaled to fill it, so at 0.8 the
     * app sees (and lays out for) a widget 25% bigger than the frame, with smaller text.
     */
    var contentScale = 1f
        set(value) {
            if (field == value) return
            field = value
            hostView.scaleX = value
            hostView.scaleY = value
            requestLayout()
            if (layoutParams != null) reportSizeToProvider(layoutParams.width, layoutParams.height)
        }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    // Corner grips: drawn small, but grabbed from a finger-sized area around each corner
    private val handleSize = 8f * density
    private val handleHitRadius = 28f * density
    private val minFloor = (40 * density).toInt()

    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = density
    }

    private val canResizeHorizontally =
        providerInfo.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
    private val canResizeVertically =
        providerInfo.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0

    // Long press tracking (normal mode)
    private var downRawX = 0f
    private var downRawY = 0f
    private var longPressFired = false
    private val longPressRunnable = Runnable {
        longPressFired = true
        // Take the gesture away from whatever in the widget had it, so the press
        // doesn't also land as a tap on one of its buttons
        hostView.cancelLongPress()
        onLongPress?.invoke(this, downRawX, downRawY)
    }

    // Edit mode gesture state
    private enum class Drag { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    private var drag = Drag.NONE
    private var hasDragged = false
    private var startX = 0f
    private var startY = 0f
    private var startW = 0
    private var startH = 0

    init {
        clipChildren = true
        addView(hostView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        hostView.pivotX = 0f
        hostView.pivotY = 0f
        // Draw the grips over the widget's own content
        setWillNotDraw(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (contentScale == 1f) return
        // Lay the widget out at frame / scale; the scale (pivot top-left) brings it back to fill the frame
        hostView.measure(
            MeasureSpec.makeMeasureSpec((measuredWidth / contentScale).toInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((measuredHeight / contentScale).toInt(), MeasureSpec.EXACTLY),
        )
    }

    fun setEditMode(enabled: Boolean) {
        if (isEditMode == enabled) return
        isEditMode = enabled
        removeCallbacks(longPressRunnable)
        drag = Drag.NONE
        hasDragged = false
        // The same dotted selection rectangle Quick Glance uses when it moves
        foreground = if (enabled) context.getDrawable(R.drawable.quick_glance_move_outline) else null
        if (enabled) hostView.cancelLongPress()
        invalidate()
    }

    // ---- Size --------------------------------------------------------------------------

    /** The smallest this widget says it can be drawn, never below a fingertip. */
    private fun minWidthPx(): Int {
        if (!canResizeHorizontally) return width
        val declared = listOf(providerInfo.minResizeWidth, providerInfo.minWidth).filter { it > 0 }.minOrNull() ?: 0
        return (declared * contentScale).toInt().coerceAtLeast(minFloor)
    }

    private fun minHeightPx(): Int {
        if (!canResizeVertically) return height
        val declared = listOf(providerInfo.minResizeHeight, providerInfo.minHeight).filter { it > 0 }.minOrNull() ?: 0
        return (declared * contentScale).toInt().coerceAtLeast(minFloor)
    }

    private fun maxWidthPx(desktopWidth: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && providerInfo.maxResizeWidth > 0) {
            return (providerInfo.maxResizeWidth * contentScale).toInt().coerceAtMost(desktopWidth)
        }
        return desktopWidth
    }

    private fun maxHeightPx(desktopHeight: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && providerInfo.maxResizeHeight > 0) {
            return (providerInfo.maxResizeHeight * contentScale).toInt().coerceAtMost(desktopHeight)
        }
        return desktopHeight
    }

    /**
     * Tells the widget's app how much room it has, so it can pick a layout to match -
     * apps lay a 4x1 out differently from a 4x4.
     */
    fun reportSizeToProvider(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        // The room the widget is laid out in, which is the frame before scaling
        val widthDp = widthPx / contentScale / density
        val heightDp = heightPx / contentScale / density
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                hostView.updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp, heightDp)))
            } else {
                @Suppress("DEPRECATION")
                hostView.updateAppWidgetSize(
                    null, widthDp.toInt(), heightDp.toInt(), widthDp.toInt(), heightDp.toInt()
                )
            }
        } catch (e: Exception) {
            // The provider's process may be gone; it gets the size again next time
            android.util.Log.w("DesktopWidgetView", "Could not report size for widget $appWidgetId", e)
        }
    }

    // ---- Touch -------------------------------------------------------------------------

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (isEditMode) return true
        trackLongPress(event)
        // Once the long press fired, the rest of the gesture is ours
        return longPressFired
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // A list inside the widget started scrolling: that's not a long press
        if (disallowIntercept) removeCallbacks(longPressRunnable)
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isEditMode) return handleEditTouch(event)
        // Here only for touches nothing in the widget took. Claim them anyway so the
        // desktop underneath doesn't read a long press on the widget as one on the wallpaper.
        trackLongPress(event)
        return true
    }

    private fun trackLongPress(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                longPressFired = false
                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, longPressTimeout)
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.rawX - downRawX) > touchSlop || abs(event.rawY - downRawY) > touchSlop) {
                    removeCallbacks(longPressRunnable)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> removeCallbacks(longPressRunnable)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPressRunnable)
        }
    }

    private fun handleEditTouch(event: MotionEvent): Boolean {
        val desktop = parent as? View ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = x
                startY = y
                startW = width
                startH = height
                hasDragged = false
                drag = cornerAt(event.x, event.y) ?: Drag.MOVE
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!hasDragged && (abs(dx) > touchSlop || abs(dy) > touchSlop)) hasDragged = true
                if (!hasDragged) return true
                if (drag == Drag.MOVE) {
                    x = (startX + dx).coerceIn(0f, (desktop.width - width).coerceAtLeast(0).toFloat())
                    y = (startY + dy).coerceIn(0f, (desktop.height - height).coerceAtLeast(0).toFloat())
                } else {
                    resizeFromCorner(dx, dy, desktop.width, desktop.height)
                }
            }

            MotionEvent.ACTION_UP -> {
                if (hasDragged) {
                    if (drag != Drag.MOVE) reportSizeToProvider(layoutParams.width, layoutParams.height)
                    commitGeometry()
                    onGeometryChanged?.invoke(this)
                } else {
                    // A plain tap on the widget means "done"
                    setEditMode(false)
                    onEditModeExited?.invoke(this)
                }
                drag = Drag.NONE
            }

            MotionEvent.ACTION_CANCEL -> {
                // Gesture taken away mid-drag: put it back where it was
                x = startX
                y = startY
                applySize(startW, startH)
                drag = Drag.NONE
                hasDragged = false
            }
        }
        return true
    }

    private fun cornerAt(touchX: Float, touchY: Float): Drag? {
        if (!canResizeHorizontally && !canResizeVertically) return null
        val w = width.toFloat()
        val h = height.toFloat()
        val corners = listOf(
            Drag.TOP_LEFT to (0f to 0f),
            Drag.TOP_RIGHT to (w to 0f),
            Drag.BOTTOM_LEFT to (0f to h),
            Drag.BOTTOM_RIGHT to (w to h),
        )
        // The closest corner within reach - on a small widget the areas overlap
        return corners
            .map { (corner, point) -> corner to Math.hypot((touchX - point.first).toDouble(), (touchY - point.second).toDouble()) }
            .filter { it.second <= handleHitRadius }
            .minByOrNull { it.second }
            ?.first
    }

    private fun resizeFromCorner(dx: Float, dy: Float, desktopWidth: Int, desktopHeight: Int) {
        var left = startX
        var top = startY
        var right = startX + startW
        var bottom = startY + startH

        val fromLeft = drag == Drag.TOP_LEFT || drag == Drag.BOTTOM_LEFT
        val fromTop = drag == Drag.TOP_LEFT || drag == Drag.TOP_RIGHT

        // A widget whose minimum is bigger than the room it has keeps the edge where it was
        // rather than jumping; clampOr covers the case where there's no valid range at all.
        if (canResizeHorizontally) {
            val minW = minWidthPx()
            val maxW = maxWidthPx(desktopWidth)
            if (fromLeft) {
                left = clampOr(startX + dx, (right - maxW).coerceAtLeast(0f), right - minW, startX)
            } else {
                right = clampOr(startX + startW + dx, left + minW, (left + maxW).coerceAtMost(desktopWidth.toFloat()), right)
            }
        }
        if (canResizeVertically) {
            val minH = minHeightPx()
            val maxH = maxHeightPx(desktopHeight)
            if (fromTop) {
                top = clampOr(startY + dy, (bottom - maxH).coerceAtLeast(0f), bottom - minH, startY)
            } else {
                bottom = clampOr(startY + startH + dy, top + minH, (top + maxH).coerceAtMost(desktopHeight.toFloat()), bottom)
            }
        }

        x = left
        y = top
        applySize((right - left).toInt(), (bottom - top).toInt())
    }

    private fun clampOr(value: Float, min: Float, max: Float, fallback: Float): Float =
        if (min > max) fallback else value.coerceIn(min, max)

    fun applySize(widthPx: Int, heightPx: Int) {
        val params = layoutParams ?: return
        if (params.width == widthPx && params.height == heightPx) return
        params.width = widthPx
        params.height = heightPx
        layoutParams = params
    }

    // ---- Drawing -----------------------------------------------------------------------

    override fun onDrawForeground(canvas: Canvas) {
        super.onDrawForeground(canvas)
        if (!isEditMode || (!canResizeHorizontally && !canResizeVertically)) return
        val w = width.toFloat()
        val h = height.toFloat()
        val half = handleSize / 2f
        for ((cx, cy) in listOf(half to half, w - half to half, half to h - half, w - half to h - half)) {
            canvas.drawRect(cx - half, cy - half, cx + half, cy + half, handleFill)
            canvas.drawRect(cx - half, cy - half, cx + half, cy + half, handleStroke)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }
}
