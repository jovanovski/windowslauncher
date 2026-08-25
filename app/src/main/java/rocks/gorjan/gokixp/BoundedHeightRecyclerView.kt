package rocks.gorjan.gokixp

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

/**
 * A RecyclerView that refuses to be measured against an unbounded height.
 *
 * RecyclerView's auto-measure lays out *every* row inside onMeasure when its height spec is
 * UNSPECIFIED, and RelativeLayout hands its children exactly that whenever its own height is
 * unspecified - which is what the start menu's app list panel does. With ~85 installed apps that
 * meant inflating, binding and measuring 85 rows on the main thread every time the menu opened
 * on the app list: a measured ~815ms inside a single traversal.
 *
 * Clamping to AT_MOST the display height keeps that pass to the rows that could actually be on
 * screen. The real (EXACTLY) measure that follows lays the list out normally, so nothing about
 * the finished layout changes.
 */
class BoundedHeightRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val boundedHeightSpec = if (MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED) {
            MeasureSpec.makeMeasureSpec(resources.displayMetrics.heightPixels, MeasureSpec.AT_MOST)
        } else {
            heightSpec
        }
        super.onMeasure(widthSpec, boundedHeightSpec)
    }
}
