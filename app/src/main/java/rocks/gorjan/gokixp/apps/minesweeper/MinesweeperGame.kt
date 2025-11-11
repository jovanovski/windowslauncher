package rocks.gorjan.gokixp.apps.minesweeper

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import rocks.gorjan.gokixp.Helpers
import rocks.gorjan.gokixp.R
import kotlin.random.Random

/**
 * Minesweeper game logic and UI controller
 * This structure can be used as a template for other system apps
 */
class MinesweeperGame(
    private val context: Context,
    private val onSoundPlay: (String) -> Unit
) {
    companion object {
        private const val GRID_SIZE = 9
        private const val MINE_COUNT = 10
    }

    // Game state
    private enum class GameState {
        IDLE, PLAYING, WON, LOST
    }

    private var gameState = GameState.IDLE
    private val grid = Array(GRID_SIZE) { Array(GRID_SIZE) { Cell() } }
    private var flagsPlaced = 0
    private var cellsRevealed = 0
    private var gameTime = 0
    private var timerHandler: Handler? = null
    private var timerRunnable: Runnable? = null

    // UI references
    private var bombDigit1: ImageView? = null
    private var bombDigit2: ImageView? = null
    private var bombDigit3: ImageView? = null
    private var timerDigit1: ImageView? = null
    private var timerDigit2: ImageView? = null
    private var timerDigit3: ImageView? = null
    private var smileyButton: ImageView? = null
    private var newGameMenuItem: TextView? = null
    private var gridButtons = Array(GRID_SIZE) { Array<ImageView?>(GRID_SIZE) { null } }

    // Cell data structure
    private data class Cell(
        var hasMine: Boolean = false,
        var isRevealed: Boolean = false,
        var isFlagged: Boolean = false,
        var isQuestionMarked: Boolean = false,
        var adjacentMines: Int = 0
    )

    /**
     * Initialize the game UI
     */
    fun setupGame(contentView: View): View {
        bombDigit1 = contentView.findViewById(R.id.bomb_digit_1)
        bombDigit2 = contentView.findViewById(R.id.bomb_digit_2)
        bombDigit3 = contentView.findViewById(R.id.bomb_digit_3)
        timerDigit1 = contentView.findViewById(R.id.timer_digit_1)
        timerDigit2 = contentView.findViewById(R.id.timer_digit_2)
        timerDigit3 = contentView.findViewById(R.id.timer_digit_3)
        smileyButton = contentView.findViewById(R.id.smiley_button)
        newGameMenuItem = contentView.findViewById(R.id.minesweeper_new_game)

        val mineGrid = contentView.findViewById<GridLayout>(R.id.mine_grid)

        // Create grid buttons
        for (row in 0 until GRID_SIZE) {
            for (col in 0 until GRID_SIZE) {
                val button = ImageView(context).apply {
                    setImageResource(R.drawable.minesweeper_button)
                    scaleType = ImageView.ScaleType.FIT_CENTER

                    val size = (28 * context.resources.displayMetrics.density).toInt()
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = size
                        height = size
                        setMargins(0, 0, 0, 0)
                    }

                    var isLongPress = false
                    val longPressHandler = Handler(Looper.getMainLooper())
                    var longPressRunnable: Runnable? = null

                    // Handle touch events for suspense face and clicks
                    setOnTouchListener { _, event ->
                        when (event.action) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                isLongPress = false
                                // Show suspense face on press down
                                if (gameState != GameState.WON && gameState != GameState.LOST) {
                                    smileyButton?.setImageResource(R.drawable.minesweeper_smiley_suspence)
                                }

                                // Set up long press detection
                                longPressRunnable = Runnable {
                                    isLongPress = true
                                    onCellLongClick(row, col)
                                    // Restore smiley after long click
                                    if (gameState == GameState.PLAYING || gameState == GameState.IDLE) {
                                        smileyButton?.setImageResource(R.drawable.minesweeper_smiley)
                                    } else if (gameState == GameState.WON) {
                                        smileyButton?.setImageResource(R.drawable.minesweeper_smiley_won)
                                    } else if (gameState == GameState.LOST) {
                                        smileyButton?.setImageResource(R.drawable.minesweeper_smiley_lost)
                                    }
                                }
                                longPressHandler.postDelayed(longPressRunnable!!, 500)
                                true
                            }
                            android.view.MotionEvent.ACTION_UP -> {
                                // Cancel long press detection
                                longPressRunnable?.let { longPressHandler.removeCallbacks(it) }

                                // Restore appropriate smiley face
                                if (gameState == GameState.PLAYING || gameState == GameState.IDLE) {
                                    smileyButton?.setImageResource(R.drawable.minesweeper_smiley)
                                } else if (gameState == GameState.WON) {
                                    smileyButton?.setImageResource(R.drawable.minesweeper_smiley_won)
                                } else if (gameState == GameState.LOST) {
                                    smileyButton?.setImageResource(R.drawable.minesweeper_smiley_lost)
                                }

                                // Handle click if not long press
                                if (!isLongPress) {
                                    onCellClick(row, col)
                                }
                                true
                            }
                            android.view.MotionEvent.ACTION_CANCEL -> {
                                // Cancel long press detection
                                longPressRunnable?.let { longPressHandler.removeCallbacks(it) }

                                // Restore smiley on cancel
                                if (gameState == GameState.PLAYING || gameState == GameState.IDLE) {
                                    smileyButton?.setImageResource(R.drawable.minesweeper_smiley)
                                } else if (gameState == GameState.WON) {
                                    smileyButton?.setImageResource(R.drawable.minesweeper_smiley_won)
                                } else if (gameState == GameState.LOST) {
                                    smileyButton?.setImageResource(R.drawable.minesweeper_smiley_lost)
                                }
                                true
                            }
                            else -> false
                        }
                    }
                }

                gridButtons[row][col] = button
                mineGrid.addView(button)
            }
        }

        // Smiley button to restart
        smileyButton?.setOnClickListener {
            onSoundPlay("click")
            Helpers.performHapticFeedback(context)
            resetGame()
        }

        newGameMenuItem?.setOnClickListener {
            onSoundPlay("click")
            Helpers.performHapticFeedback(context)
            resetGame()
        }

        resetGame()
        return contentView
    }

    /**
     * Reset the game to initial state
     */
    private fun resetGame() {
        gameState = GameState.IDLE
        flagsPlaced = 0
        cellsRevealed = 0
        gameTime = 0

        // Stop timer
        timerRunnable?.let { timerHandler?.removeCallbacks(it) }

        // Reset grid
        for (row in 0 until GRID_SIZE) {
            for (col in 0 until GRID_SIZE) {
                grid[row][col] = Cell()
                gridButtons[row][col]?.setImageResource(R.drawable.minesweeper_button)
            }
        }

        // Update UI
        updateBombCounter()
        updateTimer()
        smileyButton?.setImageResource(R.drawable.minesweeper_smiley)
    }

    /**
     * Place mines randomly on first click
     */
    private fun placeMines(excludeRow: Int, excludeCol: Int) {
        var minesPlaced = 0

        while (minesPlaced < MINE_COUNT) {
            val row = Random.nextInt(GRID_SIZE)
            val col = Random.nextInt(GRID_SIZE)

            // Don't place mine on first clicked cell or on already mined cell
            if ((row == excludeRow && col == excludeCol) || grid[row][col].hasMine) {
                continue
            }

            grid[row][col].hasMine = true
            minesPlaced++
        }

        // Calculate adjacent mine counts
        for (row in 0 until GRID_SIZE) {
            for (col in 0 until GRID_SIZE) {
                if (!grid[row][col].hasMine) {
                    grid[row][col].adjacentMines = countAdjacentMines(row, col)
                }
            }
        }
    }

    /**
     * Count mines adjacent to a cell
     */
    private fun countAdjacentMines(row: Int, col: Int): Int {
        var count = 0
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val newRow = row + dr
                val newCol = col + dc
                if (newRow in 0 until GRID_SIZE && newCol in 0 until GRID_SIZE) {
                    if (grid[newRow][newCol].hasMine) count++
                }
            }
        }
        return count
    }

    /**
     * Handle cell click
     */
    private fun onCellClick(row: Int, col: Int) {
        if (gameState == GameState.WON || gameState == GameState.LOST) return

        val cell = grid[row][col]
        if (cell.isRevealed || cell.isFlagged) return

        // Start game on first click
        if (gameState == GameState.IDLE) {
            placeMines(row, col)
            gameState = GameState.PLAYING
            startTimer()
        }

        // Reveal cell
        if (cell.hasMine) {
            // Game over - hit a mine
            revealCell(row, col, true)
            gameOver(false)
        } else {
            revealCell(row, col, false)

            // Auto-reveal adjacent cells if no adjacent mines
            if (cell.adjacentMines == 0) {
                revealAdjacentCells(row, col)
            }

            checkWinCondition()
        }
    }

    /**
     * Handle long click to flag/question
     */
    private fun onCellLongClick(row: Int, col: Int) {
        if (gameState == GameState.WON || gameState == GameState.LOST) return

        val cell = grid[row][col]
        if (cell.isRevealed) return

        onSoundPlay("click")

        val button = gridButtons[row][col] ?: return

        // Provide haptic feedback
        button.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

        when {
            !cell.isFlagged && !cell.isQuestionMarked -> {
                // Place flag
                cell.isFlagged = true
                button.setImageResource(R.drawable.minesweeper_button_flag)
                flagsPlaced++
            }
            cell.isFlagged -> {
                // Change to question mark
                cell.isFlagged = false
                cell.isQuestionMarked = true
                button.setImageResource(R.drawable.minesweeper_button_question)
                flagsPlaced--
            }
            cell.isQuestionMarked -> {
                // Remove question mark
                cell.isQuestionMarked = false
                button.setImageResource(R.drawable.minesweeper_button)
            }
        }

        updateBombCounter()
    }

    /**
     * Reveal a single cell
     */
    private fun revealCell(row: Int, col: Int, exploded: Boolean) {
        val cell = grid[row][col]
        if (cell.isRevealed) return

        cell.isRevealed = true
        cellsRevealed++

        val button = gridButtons[row][col] ?: return

        when {
            cell.hasMine && exploded -> {
                button.setImageResource(R.drawable.minesweeper_mine_exploded)
            }
            cell.hasMine -> {
                button.setImageResource(R.drawable.minesweeper_mine_unexploded)
            }
            cell.adjacentMines == 0 -> {
                button.setImageResource(R.drawable.minesweeper_grid_empty)
            }
            cell.adjacentMines == 1 -> {
                button.setImageResource(R.drawable.minesweeper_1)
            }
            else -> {
                // Use minesweeper_grid_2 through minesweeper_grid_8
                val resourceName = "minesweeper_grid_${cell.adjacentMines}"
                val resourceId = context.resources.getIdentifier(resourceName, "drawable", context.packageName)
                button.setImageResource(resourceId)
            }
        }
    }

    /**
     * Recursively reveal adjacent empty cells
     */
    private fun revealAdjacentCells(row: Int, col: Int) {
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val newRow = row + dr
                val newCol = col + dc

                if (newRow in 0 until GRID_SIZE && newCol in 0 until GRID_SIZE) {
                    val cell = grid[newRow][newCol]
                    if (!cell.isRevealed && !cell.isFlagged && !cell.hasMine) {
                        revealCell(newRow, newCol, false)

                        if (cell.adjacentMines == 0) {
                            revealAdjacentCells(newRow, newCol)
                        }
                    }
                }
            }
        }
    }

    /**
     * Check if player has won
     */
    private fun checkWinCondition() {
        val totalCells = GRID_SIZE * GRID_SIZE
        val safeCells = totalCells - MINE_COUNT

        if (cellsRevealed == safeCells) {
            gameOver(true)
        }
    }

    /**
     * Handle game over
     */
    private fun gameOver(won: Boolean) {
        gameState = if (won) GameState.WON else GameState.LOST

        // Stop timer
        timerRunnable?.let { timerHandler?.removeCallbacks(it) }

        // Update smiley
        smileyButton?.setImageResource(
            if (won) R.drawable.minesweeper_smiley_won else R.drawable.minesweeper_smiley_lost
        )

        // Reveal all mines if lost
        if (!won) {
            Helpers.performHapticFeedback(context)
            for (row in 0 until GRID_SIZE) {
                for (col in 0 until GRID_SIZE) {
                    val cell = grid[row][col]
                    if (cell.hasMine && !cell.isRevealed) {
                        revealCell(row, col, false)
                    }
                }
            }
        }
    }

    /**
     * Start the game timer
     */
    private fun startTimer() {
        timerHandler = Handler(Looper.getMainLooper())
        timerRunnable = object : Runnable {
            override fun run() {
                if (gameState == GameState.PLAYING) {
                    gameTime++
                    updateTimer()

                    if (gameTime < 999) {
                        timerHandler?.postDelayed(this, 1000)
                    }
                }
            }
        }
        timerHandler?.postDelayed(timerRunnable!!, 1000)
    }

    /**
     * Update bomb counter display
     */
    private fun updateBombCounter() {
        val remaining = MINE_COUNT - flagsPlaced
        val value = remaining.coerceIn(-99, 999)
        updateDigitDisplay(bombDigit1, bombDigit2, bombDigit3, value)
    }

    /**
     * Update timer display
     */
    private fun updateTimer() {
        val value = gameTime.coerceAtMost(999)
        updateDigitDisplay(timerDigit1, timerDigit2, timerDigit3, value)
    }

    /**
     * Update three-digit sprite display
     */
    private fun updateDigitDisplay(digit1: ImageView?, digit2: ImageView?, digit3: ImageView?, value: Int) {
        val displayValue = String.format("%03d", value.coerceIn(-99, 999))

        digit1?.setImageResource(getDigitDrawable(displayValue[0]))
        digit2?.setImageResource(getDigitDrawable(displayValue[1]))
        digit3?.setImageResource(getDigitDrawable(displayValue[2]))
    }

    /**
     * Get drawable resource for a digit character
     */
    private fun getDigitDrawable(char: Char): Int {
        return when (char) {
            '0' -> R.drawable.minesweeper_timer_0
            '1' -> R.drawable.minesweeper_timer_1
            '2' -> R.drawable.minesweeper_timer_2
            '3' -> R.drawable.minesweeper_timer_3
            '4' -> R.drawable.minesweeper_timer_4
            '5' -> R.drawable.minesweeper_timer_5
            '6' -> R.drawable.minesweeper_timer_6
            '7' -> R.drawable.minesweeper_timer_7
            '8' -> R.drawable.minesweeper_timer_8
            '9' -> R.drawable.minesweeper_timer_9
            '-' -> R.drawable.minesweeper_timer_dash
            else -> R.drawable.minesweeper_timer_blank
        }
    }

    /**
     * Cleanup when game is closed
     */
    fun cleanup() {
        timerRunnable?.let { timerHandler?.removeCallbacks(it) }
    }
}
