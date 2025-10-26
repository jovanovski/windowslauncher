package rocks.gorjan.gokixp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val gridPaint = Paint().apply {
        color = 0x80FFFFFF.toInt() // Semi-transparent white
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    
    private val cellPaint = Paint().apply {
        color = 0x20FFFFFF.toInt() // Very transparent white fill
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val highlightPaint = Paint().apply {
        color = 0x60007AFF.toInt() // Semi-transparent blue for highlight
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val highlightStrokePaint = Paint().apply {
        color = 0xFF007AFF.toInt() // Solid blue for highlight border
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    
    private var gridRows = 5
    private var gridColumns = 5
    private var highlightRow = -1
    private var highlightCol = -1
    private var cellWidth = 0f
    private var cellHeight = 0f
    
    fun setGridSize(rows: Int, columns: Int) {
        gridRows = rows
        gridColumns = columns
        calculateCellDimensions()
        invalidate()
    }
    
    fun highlightCell(row: Int, col: Int) {
        highlightRow = row
        highlightCol = col
        invalidate()
    }
    
    fun clearHighlight() {
        highlightRow = -1
        highlightCol = -1
        invalidate()
    }
    
    private fun calculateCellDimensions() {
        if (width > 0 && height > 0) {
            // Account for taskbar (40dp + 30dp margin = 70dp)
            val taskbarHeightPx = 70 * context.resources.displayMetrics.density
            val usableHeight = height - taskbarHeightPx
            
            cellWidth = width.toFloat() / gridColumns
            cellHeight = usableHeight / gridRows
        }
    }
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateCellDimensions()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (cellWidth <= 0 || cellHeight <= 0) {
            calculateCellDimensions()
            if (cellWidth <= 0 || cellHeight <= 0) return
        }
        
        // Account for taskbar (40dp + 30dp margin = 70dp)
        val taskbarHeightPx = 70 * context.resources.displayMetrics.density
        val usableHeight = height - taskbarHeightPx
        
        // Draw grid cells with background
        for (row in 0 until gridRows) {
            for (col in 0 until gridColumns) {
                val left = col * cellWidth
                val top = row * cellHeight
                val right = left + cellWidth
                val bottom = top + cellHeight
                
                val cellRect = RectF(left, top, right, bottom.coerceAtMost(usableHeight))
                
                // Fill cell background
                canvas.drawRect(cellRect, cellPaint)
                
                // Highlight specific cell if needed
                if (row == highlightRow && col == highlightCol) {
                    canvas.drawRect(cellRect, highlightPaint)
                    canvas.drawRect(cellRect, highlightStrokePaint)
                }
            }
        }
        
        // Draw vertical lines
        for (col in 0..gridColumns) {
            val x = col * cellWidth
            canvas.drawLine(x, 0f, x, usableHeight, gridPaint)
        }
        
        // Draw horizontal lines
        for (row in 0..gridRows) {
            val y = row * cellHeight
            if (y <= usableHeight) {
                canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            }
        }
    }
}