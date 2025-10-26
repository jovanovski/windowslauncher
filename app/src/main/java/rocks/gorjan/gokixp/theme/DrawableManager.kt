package rocks.gorjan.gokixp.theme

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import rocks.gorjan.gokixp.R

/**
 * Manages drawable resources and custom drawable creation for themes.
 * Handles theme-specific drawables including borders, backgrounds, and other UI elements.
 */
class DrawableManager(private val context: Context) {

    /**
     * Gets the context menu background drawable for a theme.
     * For Windows 98, creates a custom 3D border drawable.
     * For Windows XP, uses the context menu background resource.
     *
     * @param theme The theme to get the background for
     * @return The appropriate Drawable for the theme
     */
    fun getContextMenuBackground(theme: AppTheme): Drawable {
        return when (theme) {
            AppTheme.WindowsClassic -> {
                val bgColor = ContextCompat.getColor(context, R.color.window_98_background)
                val topLeftColor = ContextCompat.getColor(context, R.color.border_white)
                val bottomRightColor = ContextCompat.getColor(context, R.color.border_black)
                createWindows98Border(bgColor, topLeftColor, bottomRightColor)
            }
            AppTheme.WindowsXP -> {
                ContextCompat.getDrawable(context, R.drawable.context_menu_background)
                    ?: ColorDrawable(Color.WHITE)
            }
            AppTheme.WindowsVista -> {
                ContextCompat.getDrawable(context, R.drawable.context_menu_background)
                    ?: ColorDrawable(Color.WHITE)
            }
        }
    }

    /**
     * Creates a Windows 98-style 3D border drawable.
     * Uses raised border effect with light color on top/left and dark on bottom/right.
     *
     * @param backgroundColor The main background color
     * @param topLeftColor The color for top and left borders (light)
     * @param bottomRightColor The color for bottom and right borders (dark)
     * @return A Drawable with the Windows 98 border style
     */
    fun createWindows98Border(
        backgroundColor: Int,
        topLeftColor: Int,
        bottomRightColor: Int
    ): Drawable {
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            override fun draw(canvas: Canvas) {
                val bounds = bounds
                val borderSize = (1 * context.resources.displayMetrics.density).toInt()

                // Draw background
                paint.color = backgroundColor
                canvas.drawRect(bounds, paint)

                // Draw top border (light)
                paint.color = topLeftColor
                canvas.drawRect(
                    bounds.left.toFloat(),
                    bounds.top.toFloat(),
                    bounds.right.toFloat(),
                    (bounds.top + borderSize).toFloat(),
                    paint
                )

                // Draw left border (light)
                canvas.drawRect(
                    bounds.left.toFloat(),
                    bounds.top.toFloat(),
                    (bounds.left + borderSize).toFloat(),
                    bounds.bottom.toFloat(),
                    paint
                )

                // Draw bottom border (dark)
                paint.color = bottomRightColor
                canvas.drawRect(
                    bounds.left.toFloat(),
                    (bounds.bottom - borderSize).toFloat(),
                    bounds.right.toFloat(),
                    bounds.bottom.toFloat(),
                    paint
                )

                // Draw right border (dark)
                canvas.drawRect(
                    (bounds.right - borderSize).toFloat(),
                    bounds.top.toFloat(),
                    bounds.right.toFloat(),
                    bounds.bottom.toFloat(),
                    paint
                )
            }

            override fun setAlpha(alpha: Int) {
                paint.alpha = alpha
            }

            override fun setColorFilter(colorFilter: ColorFilter?) {
                paint.colorFilter = colorFilter
            }

            @Deprecated("Deprecated in Java",
                ReplaceWith("PixelFormat.OPAQUE", "android.graphics.PixelFormat")
            )
            override fun getOpacity(): Int = PixelFormat.OPAQUE
        }
    }

    /**
     * Creates a Windows 98-style sunken border drawable.
     * Uses the opposite colors from raised border for a depressed effect.
     *
     * @param backgroundColor The main background color
     * @param topLeftColor The color for top and left borders (dark)
     * @param bottomRightColor The color for bottom and right borders (light)
     * @return A Drawable with the Windows 98 sunken border style
     */
    fun createWindows98SunkenBorder(
        backgroundColor: Int,
        topLeftColor: Int,
        bottomRightColor: Int
    ): Drawable {
        // For sunken border, we reverse the colors
        return createWindows98Border(backgroundColor, topLeftColor, bottomRightColor)
    }

    /**
     * Gets a scrollbar track drawable for the given theme.
     *
     * @param theme The theme to get the scrollbar track for
     * @return The scrollbar track drawable resource ID
     */
    fun getScrollbarTrackRes(theme: AppTheme): Int = when (theme) {
        AppTheme.WindowsClassic -> R.drawable.scrollbar_track_98
        AppTheme.WindowsXP -> R.drawable.scrollbar_track_xp
        AppTheme.WindowsVista -> R.drawable.scrollbar_track_xp
    }

    /**
     * Gets a scrollbar thumb drawable for the given theme.
     *
     * @param theme The theme to get the scrollbar thumb for
     * @return The scrollbar thumb drawable resource ID
     */
    fun getScrollbarThumbRes(theme: AppTheme): Int = when (theme) {
        AppTheme.WindowsClassic -> R.drawable.win98_start_menu_border
        AppTheme.WindowsXP -> R.drawable.scrollbar_thumb_xp
        AppTheme.WindowsVista -> R.drawable.scrollbar_thumb_xp
    }
}
