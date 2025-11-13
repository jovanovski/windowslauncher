package rocks.gorjan.gokixp.apps.solitare

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import rocks.gorjan.gokixp.Helpers
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.theme.AppTheme
import rocks.gorjan.gokixp.theme.ThemeManager
import kotlin.math.abs

/**
 * Classic Windows 98/2000 Solitaire (Klondike) game
 */
class SolitareGame(
    private val context: Context,
) {
    companion object {
        private const val PREFS_NAME = "SolitarePrefs"
        private const val PREF_CARD_BACK = "cardBack"
        private const val PREF_LARGE_CARDS = "largeCards"
    }

    // Card dimensions in dp - will be converted to pixels
    private val density = context.resources.displayMetrics.density
    private val CARD_WIDTH = 49.5f * density  // 10% larger (45 * 1.1)
    private val CARD_HEIGHT = 66f * density  // 10% larger (60 * 1.1)
    private val CARD_SPACING_X = 53.25f * density  // Adjusted to keep same total width
    private val CARD_SPACING_Y = 18f * density  // Spacing for face-up cards (2x from 14dp)
    private val CARD_SPACING_Y_FACE_DOWN = 5f * density  // Spacing for face-down cards
    private val TOP_MARGIN = 10f * density
    private val LEFT_MARGIN = 8f * density

    // Card suits and ranks
    enum class Suit { CLUB, DIAMOND, HEART, SPADE }
    enum class Rank(val value: Int) {
        ACE(1), TWO(2), THREE(3), FOUR(4), FIVE(5), SIX(6), SEVEN(7),
        EIGHT(8), NINE(9), TEN(10), JACK(11), QUEEN(12), KING(13)
    }

    // Card data structure
    data class Card(
        val suit: Suit,
        val rank: Rank,
        var faceUp: Boolean = false,
        var x: Float = 0f,
        var y: Float = 0f
    ) {
        fun isRed(): Boolean = suit == Suit.HEART || suit == Suit.DIAMOND
        fun isBlack(): Boolean = !isRed()

        fun getDrawableId(context: Context, cardBackIndex: Int): Int {
            val postfix = if (ThemeManager(context).getSelectedTheme() is AppTheme.WindowsVista) "_vista" else ""

            if (!faceUp) {
                val backName = "solitare_card_back_$cardBackIndex"
                return context.resources.getIdentifier(backName, "drawable", context.packageName)
            }

            val suitName = when (suit) {
                Suit.CLUB -> "club"
                Suit.DIAMOND -> "diamond"
                Suit.HEART -> "heart"
                Suit.SPADE -> "spade"
            }
            val resourceName = "solitare_${suitName}_${rank.value}" + postfix
            return context.resources.getIdentifier(resourceName, "drawable", context.packageName)
        }
    }

    // Pile types
    enum class PileType {
        STOCK, WASTE, FOUNDATION, TABLEAU
    }

    // Pile data structure
    data class Pile(
        val type: PileType,
        val index: Int = 0,
        val cards: MutableList<Card> = mutableListOf(),
        var x: Float = 0f,
        var y: Float = 0f
    )

    // Game state
    private var deck = mutableListOf<Card>()
    private var stockPile = Pile(PileType.STOCK)
    private var wastePile = Pile(PileType.WASTE)
    private var foundations = List(4) { Pile(PileType.FOUNDATION, it) }
    private var tableaus = List(7) { Pile(PileType.TABLEAU, it) }

    private var selectedCards = mutableListOf<Card>()
    private var sourcePile: Pile? = null
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    private var cardBackIndex = 1
    private var useLargeCards = false
    private var gameView: SolitareView? = null
    private var noMovesLeftTextView: android.widget.TextView? = null
    private var hintTextView: android.widget.TextView? = null

    // Hint timer tracking
    private val hintHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var hintRunnable: Runnable? = null

    // Track the index in waste pile where current "group of 3" starts
    private var wasteGroupStartIndex = 0

    // Animation state for dealing cards
    private var isDealingCards = false
    private var dealAnimationStartTime = 0L
    private var cardsBeingDealt = mutableListOf<Card>()
    private val DEAL_ANIMATION_DURATION = 250L // Total animation time in ms
    private val CARD_STAGGER_DELAY = DEAL_ANIMATION_DURATION / 3 // Delay between each card (500ms / 3 cards ≈ 166ms)

    // Draw pile cycle tracking for game over detection
    private var drawPileCardsAtCycleStart = 0  // Number of cards in stock+waste when cycle started
    private var cardsDrawnThisCycle = 0        // How many cards drawn from stock this cycle
    private var wasteCardUsedThisCycle = false // Whether any waste card was used this cycle
    private var isGameOver = false             // True when game is definitively over (stays until new game)

    // Store captured slot positions
    private var tableauSlotPositions = mutableListOf<Pair<Float, Float>>()
    private var contentViewRef: View? = null
    private var gameAreaRef: FrameLayout? = null

    /**
     * Initialize the game
     */
    fun setupGame(contentView: View): View {
        contentViewRef = contentView
        val gameArea = contentView.findViewById<FrameLayout>(R.id.solitare_game_area)
        gameAreaRef = gameArea

        // Load saved preferences
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        cardBackIndex = prefs.getInt(PREF_CARD_BACK, 1)
        useLargeCards = prefs.getBoolean(PREF_LARGE_CARDS, false)

        // Add layout change listener to recalculate positions on resize
        gameArea.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val oldWidth = oldRight - oldLeft
            val oldHeight = oldBottom - oldTop
            val newWidth = right - left
            val newHeight = bottom - top

            // Only recalculate if size actually changed
            if (oldWidth != newWidth || oldHeight != newHeight) {
                if (oldWidth > 0 && oldHeight > 0) { // Ignore initial layout
                    recalculatePositions()
                }
            }
        }

        // Capture tableau slot positions before removing them
        val tableauContainer = contentView.findViewById<android.widget.LinearLayout>(R.id.solitare_tableau_container)
        if (tableauContainer != null) {
            tableauContainer.post {
                // Wait for layout to complete
                tableauSlotPositions.clear()
                for (i in 0 until tableauContainer.childCount) {
                    val child = tableauContainer.getChildAt(i)
                    if (child is android.widget.ImageView) {
                        val location = IntArray(2)
                        child.getLocationInWindow(location)

                        // Get game area location to calculate relative position
                        val gameAreaLocation = IntArray(2)
                        gameArea.getLocationInWindow(gameAreaLocation)

                        val relativeX = (location[0] - gameAreaLocation[0]).toFloat()
                        val relativeY = (location[1] - gameAreaLocation[1]).toFloat()
                        tableauSlotPositions.add(Pair(relativeX, relativeY))
                    }
                }

                // Now that we have positions, setup the game view
                gameView = SolitareView(context)
                val layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )

                gameArea.removeAllViews() // Remove placeholder slots
                gameArea.addView(gameView, layoutParams)

                resetGame()
            }
        } else {
            // No tableau container found, setup normally
            gameView = SolitareView(context)
            val layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

            gameArea.removeAllViews() // Remove placeholder slots
            gameArea.addView(gameView, layoutParams)
        }

        // Setup menu buttons
        val newGameButton = contentView.findViewById<android.widget.TextView>(R.id.solitare_new_game)
        newGameButton?.setOnClickListener {
            Helpers.performHapticFeedback(context)
            resetGame()
        }

        val changeDeckButton = contentView.findViewById<android.widget.TextView>(R.id.solitare_change_deck)
        changeDeckButton?.setOnClickListener {
            Helpers.performHapticFeedback(context)
            changeDeck()
        }

        val changeCardSizeButton = contentView.findViewById<android.widget.TextView>(R.id.solitare_change_card_size)
        changeCardSizeButton?.setOnClickListener {
            Helpers.performHapticFeedback(context)
            toggleCardSize()
        }

        hintTextView = contentView.findViewById(R.id.solitare_hint)
        hintTextView?.setOnClickListener {
            Helpers.performHapticFeedback(context)
            showHint()
        }

        noMovesLeftTextView = contentView.findViewById(R.id.no_moves_left)

        // resetGame() is now called inside the post block after capturing positions
        // or needs to be called manually if tableau container wasn't found
        if (tableauContainer == null) {
            resetGame()
        }

        return contentView
    }

    /**
     * Custom view for rendering the game
     */
    inner class SolitareView(context: Context) : View(context) {
        private val cardPaint = Paint()
        private val slotPaint = Paint().apply {
            color = Color.parseColor("#004000")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // Draw empty slots
            drawEmptySlots(canvas)

            // Draw stock pile
            drawPile(canvas, stockPile)

            // Draw waste pile
            drawPile(canvas, wastePile)

            // Draw foundations
            foundations.forEach { drawPile(canvas, it) }

            // Draw tableaus
            tableaus.forEach { drawTableauPile(canvas, it) }

            // Draw dragged cards on top
            if (selectedCards.isNotEmpty()) {
                selectedCards.forEachIndexed { index, card ->
                    drawCard(canvas, card, card.x, card.y)
                }
            }
        }

        private fun drawEmptySlots(canvas: Canvas) {
            // Stock slot - show special background if empty
            if (stockPile.cards.isEmpty()) {
                drawStockEmptySlot(canvas, stockPile.x, stockPile.y)
            } else {
                drawSlot(canvas, stockPile.x, stockPile.y)
            }

            // Waste slot
            drawSlot(canvas, wastePile.x, wastePile.y)

            // Foundation slots
            foundations.forEach { foundation ->
                drawSlot(canvas, foundation.x, foundation.y)
            }

            // Tableau slots
            tableaus.forEach { tableau ->
                drawSlot(canvas, tableau.x, tableau.y)
            }
        }

        private fun drawStockEmptySlot(canvas: Canvas, x: Float, y: Float) {
            val drawable = context.getDrawable(
                context.resources.getIdentifier("solitare_card_green_circle", "drawable", context.packageName)
            )
            drawable?.setBounds(x.toInt(), y.toInt(), (x + CARD_WIDTH).toInt(), (y + CARD_HEIGHT).toInt())
            drawable?.draw(canvas)
        }

        private fun drawSlot(canvas: Canvas, x: Float, y: Float) {
            val rect = RectF(x, y, x + CARD_WIDTH, y + CARD_HEIGHT)
            canvas.drawRoundRect(rect, 8f, 8f, slotPaint)
        }

        private fun drawPile(canvas: Canvas, pile: Pile) {
            if (pile.cards.isEmpty()) return

            if (pile.type == PileType.STOCK || pile.type == PileType.FOUNDATION) {
                // For stock and foundation, only draw the top card
                val topCard = pile.cards.lastOrNull() ?: return
                drawCard(canvas, topCard, pile.x, pile.y)
            } else if (pile.type == PileType.WASTE) {
                // For waste pile, draw cards from the current group only
                // Show cards from wasteGroupStartIndex to end of pile (max 3)
                val cardsToShow = pile.cards.subList(wasteGroupStartIndex, pile.cards.size)

                // During animation, show cards from right to left
                val visibleCount = if (isDealingCards && cardsBeingDealt.isNotEmpty()) {
                    val elapsed = System.currentTimeMillis() - dealAnimationStartTime
                    val cardIndex = (elapsed / CARD_STAGGER_DELAY).toInt()
                    minOf(cardIndex + 1, cardsToShow.size)
                } else {
                    cardsToShow.size
                }

                // Draw only visible cards, starting from the leftmost (bottom card first)
                for (i in 0 until visibleCount) {
                    val offsetX = i * (CARD_WIDTH * 0.4f) // Offset each card horizontally
                    drawCard(canvas, cardsToShow[i], pile.x + offsetX, pile.y)
                }
            }
        }

        private fun drawTableauPile(canvas: Canvas, pile: Pile) {
            pile.cards.forEachIndexed { index, card ->
                val yOffset = getCardYOffset(pile, index)
                drawCard(canvas, card, pile.x, pile.y + yOffset)
            }
        }

        private fun getCardYOffset(pile: Pile, cardIndex: Int): Float {
            var offset = 0f
            for (i in 0 until cardIndex) {
                val card = pile.cards[i]
                offset += if (card.faceUp) CARD_SPACING_Y else CARD_SPACING_Y_FACE_DOWN
            }
            return offset
        }

        private fun drawCard(canvas: Canvas, card: Card, x: Float, y: Float) {
            if (useLargeCards && card.faceUp) {
                // Draw card with large text/symbols
                drawLargeCard(canvas, card, x, y)
            } else {
                // Draw original card graphics
                val drawable = context.getDrawable(card.getDrawableId(context, cardBackIndex)) ?: return
                drawable.setBounds(x.toInt(), y.toInt(), (x + CARD_WIDTH).toInt(), (y + CARD_HEIGHT).toInt())
                drawable.draw(canvas)
            }
        }

        private fun drawLargeCard(canvas: Canvas, card: Card, x: Float, y: Float) {
            // Draw empty card background
            val emptyCardDrawable = context.getDrawable(
                context.resources.getIdentifier("solitare_card_empty", "drawable", context.packageName)
            )
            emptyCardDrawable?.setBounds(x.toInt(), y.toInt(), (x + CARD_WIDTH).toInt(), (y + CARD_HEIGHT).toInt())
            emptyCardDrawable?.draw(canvas)

            // Load custom font
            val currentTheme = ThemeManager(context).getSelectedTheme()
            val customTypeface =  context.resources.getFont(ThemeManager(context).getPrimaryFontRes(currentTheme))

            // Prepare paint for text
            val textPaint = Paint().apply {
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
                color = if (card.isRed()) Color.RED else Color.BLACK
                textSize = if(currentTheme == AppTheme.WindowsClassic) 90f else 60f
                typeface = customTypeface
            }

            // Get rank string
            val rankStr = when (card.rank) {
                Rank.ACE -> "A"
                Rank.JACK -> "J"
                Rank.QUEEN -> "Q"
                Rank.KING -> "K"
                else -> card.rank.value.toString()
            }

            // Get suit drawable resource name
            val suitResourceName = when (card.suit) {
                Suit.CLUB -> "solitare_club_symbol_large"
                Suit.SPADE -> "solitare_spade_symbol_large"
                Suit.HEART -> "solitare_heart_symbol_large"
                Suit.DIAMOND -> "solitare_diamond_symbol_large"
            }

            // Calculate positions (with padding from edges)
            val padding = CARD_WIDTH * 0.05f
            val topYText = y + padding + (if(currentTheme == AppTheme.WindowsClassic) 65f else 55f)

            // Draw rank in top-left corner
            var rankX = x + CARD_WIDTH * 0.20f
            if(card.rank == Rank.TEN){
                rankX += 7f
            }

            if(currentTheme == AppTheme.WindowsClassic) {rankX+= 5f}

            canvas.drawText(rankStr, rankX, topYText, textPaint)

            // Draw suit symbol in top-right corner
            val suitDrawableTop = context.getDrawable(
                context.resources.getIdentifier(suitResourceName, "drawable", context.packageName)
            )
            val suitSize = CARD_WIDTH * 0.35f // Size for top suit symbol
            val suitX = x + CARD_WIDTH * 0.75f - suitSize / 2f + 4f
            val suitY = y + padding + 3f
            suitDrawableTop?.setBounds(
                suitX.toInt(),
                suitY.toInt(),
                (suitX + suitSize).toInt(),
                (suitY + suitSize).toInt()
            )
            suitDrawableTop?.draw(canvas)

            // Draw larger suit symbol in middle bottom
            val suitDrawableBottom = context.getDrawable(
                context.resources.getIdentifier(suitResourceName, "drawable", context.packageName)
            )
            val largeSuitSize = CARD_WIDTH * 0.7f // 2x the top suit size
            val largeSuitX = x + (CARD_WIDTH - largeSuitSize) / 2f // Centered horizontally
            val largeSuitY = y + CARD_HEIGHT - largeSuitSize - padding + 0f // Near bottom with padding
            suitDrawableBottom?.setBounds(
                largeSuitX.toInt(),
                largeSuitY.toInt(),
                (largeSuitX + largeSuitSize).toInt(),
                (largeSuitY + largeSuitSize).toInt()
            )
            suitDrawableBottom?.draw(canvas)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downTime = System.currentTimeMillis()
                    hasMoved = false

                    if (selectedCards.isEmpty()) {
                        handleCardSelection(event.x, event.y)
                    }
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (selectedCards.isNotEmpty()) {
                        // Check if user has moved enough to be considered dragging
                        val dx = event.x - downX
                        val dy = event.y - downY
                        if (abs(dx) > 10 || abs(dy) > 10) {
                            hasMoved = true
                        }

                        // Update dragged card positions
                        selectedCards[0].x = event.x - dragOffsetX
                        selectedCards[0].y = event.y - dragOffsetY

                        selectedCards.forEachIndexed { index, card ->
                            if (index > 0) {
                                card.x = selectedCards[0].x
                                card.y = selectedCards[0].y + index * CARD_SPACING_Y
                            }
                        }
                        invalidate()
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Handle stock pile click on release (but not during animation)
                    if (selectedCards.isEmpty() && !isDealingCards && isPointInPile(event.x, event.y, stockPile)) {
                        onStockClick()
                    } else if (selectedCards.isNotEmpty()) {
                        // Check if this was a tap (not a drag)
                        val timeDiff = System.currentTimeMillis() - downTime
                        if (!hasMoved && timeDiff < 300) {
                            // This was a tap - try auto-move
                            tryAutoMove()
                        } else {
                            // This was a drag - handle normal drop
                            handleCardDrop(event.x, event.y)
                        }
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private var downX = 0f
        private var downY = 0f
        private var downTime = 0L
        private var hasMoved = false

        private fun handleCardSelection(x: Float, y: Float) {
            // Stock pile is handled in ACTION_UP

            // Check waste pile - check if clicking on any of the visible cards in current group
            // Don't allow interaction during animation
            if (!isDealingCards && wastePile.cards.isNotEmpty() && wasteGroupStartIndex < wastePile.cards.size) {
                val visibleCards = wastePile.cards.subList(wasteGroupStartIndex, wastePile.cards.size)
                // Check from right to left (top card first)
                for (i in visibleCards.indices.reversed()) {
                    val cardIndex = wasteGroupStartIndex + i
                    val card = wastePile.cards[cardIndex]
                    val offsetX = i * (CARD_WIDTH * 0.4f) // Match the drawing offset
                    val cardX = wastePile.x + offsetX

                    // Check if click is on this card
                    if (x >= cardX && x <= cardX + CARD_WIDTH &&
                        y >= wastePile.y && y <= wastePile.y + CARD_HEIGHT) {

                        // Only allow picking up the top card
                        if (cardIndex == wastePile.cards.size - 1) {
                            selectedCards.add(card)
                            sourcePile = wastePile
                            dragOffsetX = x - cardX
                            dragOffsetY = y - wastePile.y
                            card.x = cardX
                            card.y = wastePile.y

                            // Remove from waste pile immediately
                            wastePile.cards.removeAt(wastePile.cards.size - 1)
                            invalidate()
                        }
                        return
                    }
                }
            }

            // Check foundations
            for (foundation in foundations) {
                if (foundation.cards.isNotEmpty() && isPointInPile(x, y, foundation)) {
                    val card = foundation.cards.last()
                    selectedCards.add(card)
                    sourcePile = foundation
                    dragOffsetX = x - foundation.x
                    dragOffsetY = y - foundation.y
                    card.x = foundation.x
                    card.y = foundation.y

                    // Remove from foundation immediately so it shows underneath
                    foundation.cards.removeAt(foundation.cards.size - 1)
                    invalidate()
                    return
                }
            }

            // Check tableaus
            for (tableau in tableaus) {
                val clickedIndex = getClickedCardInTableau(x, y, tableau)
                if (clickedIndex >= 0) {
                    val card = tableau.cards[clickedIndex]

                    // Can only pick up face-up cards
                    if (!card.faceUp) {
                        return
                    }

                    // Pick up this card and all cards above it
                    for (i in clickedIndex until tableau.cards.size) {
                        selectedCards.add(tableau.cards[i])
                    }

                    sourcePile = tableau
                    val cardY = tableau.y + getCardYOffset(tableau, clickedIndex)
                    dragOffsetX = x - tableau.x
                    dragOffsetY = y - cardY

                    selectedCards.forEachIndexed { index, c ->
                        c.x = tableau.x
                        c.y = cardY + index * CARD_SPACING_Y
                    }

                    // Remove from tableau immediately so cards underneath show
                    for (i in clickedIndex until tableau.cards.size) {
                        tableau.cards.removeAt(clickedIndex)
                    }
                    invalidate()
                    return
                }
            }
        }

        private fun handleCardDrop(x: Float, y: Float) {
            var validMove = false

            // Try to place on foundations
            for (foundation in foundations) {
                if (isPointInPile(x, y, foundation) && canPlaceOnFoundation(selectedCards, foundation)) {
                    moveCardsTo(foundation)
                    validMove = true
                    break
                }
            }

            // Try to place on tableaus
            if (!validMove) {
                for (tableau in tableaus) {
                    if (isPointInTableau(x, y, tableau) && canPlaceOnTableau(selectedCards, tableau)) {
                        moveCardsTo(tableau)
                        validMove = true
                        break
                    }
                }
            }

            if (!validMove) {
                // Return cards to source
                returnCards()
            }

            selectedCards.clear()
            sourcePile = null
            invalidate()

            // Check for valid moves after every card move
            checkForValidMoves()
        }

        private fun isPointInPile(x: Float, y: Float, pile: Pile): Boolean {
            // Add padding to make drop zones more forgiving (20dp on each side)
            val padding = 20f * density
            return x >= pile.x - padding && x <= pile.x + CARD_WIDTH + padding &&
                   y >= pile.y - padding && y <= pile.y + CARD_HEIGHT + padding
        }

        private fun isPointInTableau(x: Float, y: Float, tableau: Pile): Boolean {
            // Add padding to make drop zones more forgiving (20dp on each side)
            val padding = 20f * density

            // Check if point is horizontally within the tableau column (with padding)
            if (x < tableau.x - padding || x > tableau.x + CARD_WIDTH + padding) return false

            // Check if point is vertically anywhere in the column (with padding)
            val topY = tableau.y - padding
            val bottomY = if (tableau.cards.isEmpty()) {
                tableau.y + CARD_HEIGHT + padding
            } else {
                tableau.y + getCardYOffset(tableau, tableau.cards.size - 1) + CARD_HEIGHT + padding
            }

            return y >= topY && y <= bottomY
        }

        private fun getClickedCardInTableau(x: Float, y: Float, tableau: Pile): Int {
            if (tableau.cards.isEmpty()) return -1
            if (x < tableau.x || x > tableau.x + CARD_WIDTH) return -1

            // Check from top to bottom
            for (i in tableau.cards.indices.reversed()) {
                val cardY = tableau.y + getCardYOffset(tableau, i)
                val spacing = if (tableau.cards[i].faceUp) CARD_SPACING_Y else CARD_SPACING_Y_FACE_DOWN
                val cardBottom = if (i == tableau.cards.size - 1) cardY + CARD_HEIGHT else cardY + spacing

                if (y >= cardY && y <= cardBottom) {
                    return i
                }
            }

            return -1
        }

        private fun moveCardsTo(targetPile: Pile) {
            // Cards were already removed from source in handleCardSelection

            // Track if a card was used from waste pile - this resets the cycle
            if (sourcePile?.type == PileType.WASTE) {
                wasteCardUsedThisCycle = true
                // Reset cycle tracking since progress was made
                drawPileCardsAtCycleStart = 0
                cardsDrawnThisCycle = 0
            }

            // Any valid move means game is not over
            isGameOver = false

            // Flip top card of source tableau if it's face down
            if (sourcePile?.type == PileType.TABLEAU) {
                sourcePile?.cards?.lastOrNull()?.let { topCard ->
                    if (!topCard.faceUp) {
                        topCard.faceUp = true
                    }
                }
            }

            // Add cards to target
            targetPile.cards.addAll(selectedCards)
        }

        private fun returnCards() {
            // Return cards to source pile since the move was invalid
            sourcePile?.cards?.addAll(selectedCards)
        }

        private fun tryAutoMove() {
            // Try to find a valid move for the selected cards
            var validMove = false

            // First, try foundations (left to right)
            for (foundation in foundations) {
                if (canPlaceOnFoundation(selectedCards, foundation)) {
                    moveCardsTo(foundation)
                    validMove = true
                    break
                }
            }

            // If not possible on foundations, try tableaus (left to right)
            if (!validMove) {
                for (tableau in tableaus) {
                    // Skip the source tableau to avoid moving to the same place
                    if (tableau == sourcePile) continue

                    if (canPlaceOnTableau(selectedCards, tableau)) {
                        moveCardsTo(tableau)
                        validMove = true
                        break
                    }
                }
            }

            // If no valid move found, return cards to source
            if (!validMove) {
                returnCards()
            }

            selectedCards.clear()
            sourcePile = null
            invalidate()

            // Check for valid moves after auto-move
            checkForValidMoves()
        }

        private fun onStockClick() {
            if (stockPile.cards.isEmpty()) {
                // Recycle waste pile
                if (wastePile.cards.isNotEmpty()) {
                    stockPile.cards.addAll(wastePile.cards.reversed())
                    stockPile.cards.forEach { it.faceUp = false }
                    wastePile.cards.clear()
                    wasteGroupStartIndex = 0
                    invalidate()

                    // Check for valid moves after recycling
                    checkForValidMoves()
                }
            } else {
                // Draw 3 cards (or remaining cards if less than 3) with animation
                // Set the start index for this new group
                wasteGroupStartIndex = wastePile.cards.size

                val cardsToDraw = minOf(3, stockPile.cards.size)
                cardsBeingDealt.clear()

                for (i in 0 until cardsToDraw) {
                    val card = stockPile.cards.removeAt(stockPile.cards.size - 1)
                    card.faceUp = true
                    cardsBeingDealt.add(card)
                }

                // Add all cards to waste pile immediately but mark them for animation
                wastePile.cards.addAll(cardsBeingDealt)

                // Track cards drawn for cycle detection
                cardsDrawnThisCycle += cardsToDraw

                // Start animation
                isDealingCards = true
                dealAnimationStartTime = System.currentTimeMillis()
                startDealAnimation()
            }
        }

        private fun startDealAnimation() {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val runnable = object : Runnable {
                override fun run() {
                    if (!isDealingCards) return

                    val elapsed = System.currentTimeMillis() - dealAnimationStartTime

                    if (elapsed >= DEAL_ANIMATION_DURATION) {
                        // Animation complete
                        cardsBeingDealt.clear()
                        isDealingCards = false
                        invalidate()

                        // Check for valid moves after dealing cards
                        checkForValidMoves()
                    } else {
                        invalidate()
                        handler.postDelayed(this, 16) // ~60 FPS
                    }
                }
            }
            handler.post(runnable)
        }

    }

    /**
     * Change the card back design
     */
    private fun changeDeck() {
        // Cycle through card backs
        cardBackIndex++
        if (cardBackIndex > 14) {
            cardBackIndex = 1
        }

        // Save preference
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(PREF_CARD_BACK, cardBackIndex).apply()

        gameView?.invalidate()
    }

    /**
     * Toggle between original card graphics and large text cards
     */
    private fun toggleCardSize() {
        useLargeCards = !useLargeCards

        // Save preference
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_LARGE_CARDS, useLargeCards).apply()

        gameView?.invalidate()
    }

    /**
     * Reset and start a new game
     */
    private fun resetGame() {
        // Create and shuffle deck
        shuffleDeck()

        // Reset waste group tracking
        wasteGroupStartIndex = 0

        // Reset draw pile cycle tracking
        drawPileCardsAtCycleStart = 0
        cardsDrawnThisCycle = 0
        wasteCardUsedThisCycle = false
        isGameOver = false

        // Position piles - call recalculate to ensure responsive positioning
        recalculatePositions()

        // Hide "no moves left" message on new game
        noMovesLeftTextView?.visibility = android.view.View.GONE

        gameView?.invalidate()
    }

    /**
     * Shuffle and deal cards randomly
     */
    private fun shuffleDeck() {
        // Create all 52 cards
        deck.clear()
        for (suit in Suit.values()) {
            for (rank in Rank.values()) {
                deck.add(Card(suit, rank))
            }
        }

        // Shuffle the deck randomly
        deck.shuffle()

        // Clear all piles
        stockPile.cards.clear()
        wastePile.cards.clear()
        foundations.forEach { it.cards.clear() }
        tableaus.forEach { it.cards.clear() }

        // Deal cards to tableaus
        var deckIndex = 0
        for (i in tableaus.indices) {
            for (j in 0..i) {
                val card = deck[deckIndex++]
                card.faceUp = (j == i) // Only top card is face-up
                tableaus[i].cards.add(card)
            }
        }

        // Remaining cards go to stock pile
        for (i in deckIndex until deck.size) {
            stockPile.cards.add(deck[i])
        }

        deck.clear()
    }

    /**
     * Recalculate positions when window is resized
     */
    private fun recalculatePositions() {
        val gameArea = gameAreaRef ?: return

        val gameAreaWidth = gameArea.width.toFloat()

        // Recalculate stock and waste positions
        stockPile.x = LEFT_MARGIN
        stockPile.y = TOP_MARGIN

        wastePile.x = LEFT_MARGIN + CARD_SPACING_X
        wastePile.y = TOP_MARGIN

        // Recalculate foundation positions - they should be right-aligned
        // Foundation slots are after the spacer, so calculate from right edge
        val rightMargin = 10f * density // Match the paddingEnd from XML
        foundations.forEachIndexed { index, foundation ->
            // Place foundations from right to left
            val fromRight = (3 - index) // 3, 2, 1, 0
            val spacing = 9f * density // marginStart between cards
            foundation.x = gameAreaWidth - rightMargin - CARD_WIDTH - (fromRight * (CARD_WIDTH + spacing))
            foundation.y = TOP_MARGIN
        }

        // For tableaus, use the same proportional spacing as the layout
        // The XML layout uses paddingStart=10dp, paddingEnd=10dp with evenly spaced items
        val padding = 10f * density
        val availableWidth = gameAreaWidth - (padding * 2)
        val cardsTotalWidth = CARD_WIDTH * 7
        val spaceBetween = (availableWidth - cardsTotalWidth) / 6

        tableauSlotPositions.clear()
        tableaus.forEachIndexed { index, tableau ->
            val x = padding + (index * (CARD_WIDTH + spaceBetween))
            val y = TOP_MARGIN + CARD_HEIGHT + 30f

            tableau.x = x
            tableau.y = y
            tableauSlotPositions.add(Pair(x, y))
        }

        gameView?.invalidate()
    }

    /**
     * Cleanup when game is closed
     */
    fun cleanup() {
        // Nothing to clean up for now
    }

    /**
     * Show a hint for the next valid move
     */
    private fun showHint() {
        // Cancel any existing timer
        hintRunnable?.let { hintHandler.removeCallbacks(it) }

        val move = findFirstValidMove()
        if (move != null) {
            val cardName = formatCardName(move)
            hintTextView?.text = cardName
        }
        else{
            hintTextView?.text = "Draw"
        }

        // Revert back to "Hint" after 2 seconds (reset timer on each tap)
        hintRunnable = Runnable {
            hintTextView?.text = "Hint"
        }
        hintHandler.postDelayed(hintRunnable!!, 2000)
    }

    /**
     * Format a card as a string with suit symbol
     */
    private fun formatCardName(card: Card): String {
        val rankStr = when (card.rank) {
            Rank.ACE -> "A"
            Rank.JACK -> "J"
            Rank.QUEEN -> "Q"
            Rank.KING -> "K"
            else -> card.rank.value.toString()
        }

        val suitSymbol = when (card.suit) {
            Suit.CLUB -> "♣"
            Suit.SPADE -> "♠"
            Suit.HEART -> "♥"
            Suit.DIAMOND -> "♦"
        }

        return "$rankStr$suitSymbol"
    }

    /**
     * Find the first valid move available (only checks visible cards)
     */
    private fun findFirstValidMove(): Card? {
        // Only check visible cards for non-dead-end moves

        // Check moves from tableaus to foundations
        for (tableau in tableaus) {
            if (tableau.cards.isNotEmpty()) {
                val topCard = tableau.cards.last()
                if (topCard.faceUp) {
                    for (foundation in foundations) {
                        if (canPlaceOnFoundation(listOf(topCard), foundation)) {
                            return topCard
                        }
                    }
                }
            }
        }

        // Check moves from tableaus to other tableaus (skip dead-end moves)
        for (sourceTableau in tableaus) {
            if (sourceTableau.cards.isNotEmpty()) {
                val firstFaceUpIndex = sourceTableau.cards.indexOfFirst { it.faceUp }
                if (firstFaceUpIndex >= 0) {
                    val cardToMove = sourceTableau.cards[firstFaceUpIndex]
                    val currentBaseCard = if (firstFaceUpIndex > 0) {
                        sourceTableau.cards[firstFaceUpIndex - 1]
                    } else {
                        null
                    }

                    for (targetTableau in tableaus) {
                        if (sourceTableau != targetTableau && canPlaceOnTableau(listOf(cardToMove), targetTableau)) {
                            // Skip moving from empty slot to empty slot
                            if (targetTableau.cards.isEmpty() && firstFaceUpIndex == 0) {
                                continue
                            }

                            // Skip dead-end moves (same rank)
                            if (targetTableau.cards.isNotEmpty() && currentBaseCard != null && currentBaseCard.faceUp) {
                                if (targetTableau.cards.last().rank.value == currentBaseCard.rank.value) {
                                    continue
                                }
                            }
                            return cardToMove
                        }
                    }
                }
            }
        }

        // Check current top waste pile card (if visible)
        if (wastePile.cards.isNotEmpty() && wasteGroupStartIndex < wastePile.cards.size) {
            val topWasteCard = wastePile.cards.last()

            for (foundation in foundations) {
                if (canPlaceOnFoundation(listOf(topWasteCard), foundation)) {
                    return topWasteCard
                }
            }

            for (tableau in tableaus) {
                if (canPlaceOnTableau(listOf(topWasteCard), tableau)) {
                    return topWasteCard
                }
            }
        }

        // No visible moves found - player should draw or game is over
        return null
    }

    /**
     * Check if there are any valid moves left in the game
     * This simulates going through the stock pile once to check all possible moves
     */
    private fun checkForValidMoves() {
        // Check if the game is won first
        if (isGameWon()) {
            noMovesLeftTextView?.text = "Game won!"
            noMovesLeftTextView?.visibility = View.VISIBLE
            isGameOver = true
            Helpers.performHapticFeedback(context)
            return
        }

        // Once game is over, keep the message shown until new game
        if (isGameOver) {
            noMovesLeftTextView?.text = "Game over - no valid moves"
            noMovesLeftTextView?.visibility = View.VISIBLE
            Helpers.performHapticFeedback(context)
            return
        }

        if (hasAnyValidMove()) {
            noMovesLeftTextView?.visibility = android.view.View.GONE
        } else {
            // Game is now definitively over - set flag so it stays shown
            isGameOver = true
            noMovesLeftTextView?.text = "Game over - no valid moves"
            noMovesLeftTextView?.visibility = View.VISIBLE
            Helpers.performHapticFeedback(context)
        }
    }

    /**
     * Check if the game is won (all 52 cards in foundations)
     */
    private fun isGameWon(): Boolean {
        // Each foundation should have 13 cards (Ace through King)
        return foundations.all { it.cards.size == 13 }
    }

    private fun hasAnyValidMove(): Boolean {
        // Only check visible cards for non-dead-end moves
        // Do NOT simulate future draws or recycles

        // Check moves from tableaus to foundations
        for (tableau in tableaus) {
            if (tableau.cards.isNotEmpty()) {
                val topCard = tableau.cards.last()
                if (topCard.faceUp) {
                    for (foundation in foundations) {
                        if (canPlaceOnFoundation(listOf(topCard), foundation)) {
                            return true
                        }
                    }
                }
            }
        }

        // Check moves from tableaus to other tableaus (skip dead-end moves)
        for (sourceTableau in tableaus) {
            if (sourceTableau.cards.isNotEmpty()) {
                val firstFaceUpIndex = sourceTableau.cards.indexOfFirst { it.faceUp }
                if (firstFaceUpIndex >= 0) {
                    val cardToMove = sourceTableau.cards[firstFaceUpIndex]
                    val currentBaseCard = if (firstFaceUpIndex > 0) {
                        sourceTableau.cards[firstFaceUpIndex - 1]
                    } else {
                        null
                    }

                    for (targetTableau in tableaus) {
                        if (sourceTableau != targetTableau && canPlaceOnTableau(listOf(cardToMove), targetTableau)) {
                            // Skip moving from empty slot to empty slot
                            if (targetTableau.cards.isEmpty() && firstFaceUpIndex == 0) {
                                continue
                            }

                            // Skip dead-end moves (same rank)
                            if (targetTableau.cards.isNotEmpty() && currentBaseCard != null && currentBaseCard.faceUp) {
                                if (targetTableau.cards.last().rank.value == currentBaseCard.rank.value) {
                                    continue
                                }
                            }
                            return true
                        }
                    }
                }
            }
        }

        // Check current top waste pile card (if visible)
        if (wastePile.cards.isNotEmpty() && wasteGroupStartIndex < wastePile.cards.size) {
            val topWasteCard = wastePile.cards.last()

            for (foundation in foundations) {
                if (canPlaceOnFoundation(listOf(topWasteCard), foundation)) {
                    return true
                }
            }

            for (tableau in tableaus) {
                if (canPlaceOnTableau(listOf(topWasteCard), tableau)) {
                    return true
                }
            }
        }

        // ONE-LEVEL LOOKAHEAD: Check if moving cards between tableaus can free up a card for foundation
        // This checks if moving a sequence from one tableau to another would expose a card that can go to foundation
        for (sourceTableau in tableaus) {
            if (sourceTableau.cards.isNotEmpty()) {
                val firstFaceUpIndex = sourceTableau.cards.indexOfFirst { it.faceUp }
                if (firstFaceUpIndex >= 0) {
                    // Try moving cards from this tableau to another
                    for (targetTableau in tableaus) {
                        if (sourceTableau != targetTableau) {
                            // Check each possible card from this tableau that could be moved
                            for (cardIndex in firstFaceUpIndex until sourceTableau.cards.size) {
                                val cardsToMove = sourceTableau.cards.subList(cardIndex, sourceTableau.cards.size)

                                if (canPlaceOnTableau(cardsToMove, targetTableau)) {
                                    // This move is valid - check if it would expose a card that can go to foundation
                                    val cardBelowIndex = cardIndex - 1
                                    if (cardBelowIndex >= 0) {
                                        val exposedCard = sourceTableau.cards[cardBelowIndex]
                                        // Check if the exposed card (when flipped) could go to a foundation
                                        for (foundation in foundations) {
                                            if (canPlaceOnFoundation(listOf(exposedCard), foundation)) {
                                                // This move would free up a card for the foundation!
                                                return true
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // If no visible moves, check if we can draw or if game is truly over
        // Game is only over if: stock is empty AND (waste is empty OR we've cycled through everything)
        if (stockPile.cards.isEmpty() && wastePile.cards.isEmpty()) {
            // No cards left anywhere - game is definitely over
            return false
        }

        if (stockPile.cards.isNotEmpty()) {
            // Can still draw cards - suggest "Draw" by hiding "no moves" message
            return true
        }

        // Stock is empty but waste has cards - can recycle
        // Check if we've completed a full cycle without using any waste cards
        if (hasCompletedFullCycle()) {
            // Completed full cycle with no moves - game is over
            return false
        }

        // Haven't completed full cycle yet - can still recycle and try
        return true
    }

    /**
     * Check if we've completed a full cycle through the draw pile without making progress
     */
    private fun hasCompletedFullCycle(): Boolean {
        // A full cycle means:
        // 1. We've drawn through all cards in stock+waste pile at least once
        // 2. No waste cards were used during this cycle

        val totalDrawPileCards = stockPile.cards.size + wastePile.cards.size

        // If we haven't started tracking a cycle yet, start now
        if (drawPileCardsAtCycleStart == 0 && totalDrawPileCards > 0) {
            drawPileCardsAtCycleStart = totalDrawPileCards
            // Don't reset cardsDrawnThisCycle - it may have already been incremented
            wasteCardUsedThisCycle = false
            return false
        }

        // Check if we've drawn all cards and cycled back
        return cardsDrawnThisCycle >= drawPileCardsAtCycleStart && !wasteCardUsedThisCycle
    }

    private fun canPlaceOnFoundation(cards: List<Card>, foundation: Pile): Boolean {
        // Can only place one card at a time on foundation
        if (cards.size != 1) return false

        val card = cards[0]

        if (foundation.cards.isEmpty()) {
            // Only aces can start a foundation
            return card.rank == Rank.ACE
        }

        val topCard = foundation.cards.last()
        // Same suit, next rank
        return card.suit == topCard.suit && card.rank.value == topCard.rank.value + 1
    }

    private fun canPlaceOnTableau(cards: List<Card>, tableau: Pile): Boolean {
        if (cards.isEmpty()) return false

        val card = cards[0]

        if (tableau.cards.isEmpty()) {
            // Only kings can be placed on empty tableau
            return card.rank == Rank.KING
        }

        val topCard = tableau.cards.last()
        if (!topCard.faceUp) return false

        // Alternating colors, descending rank
        return card.isRed() != topCard.isRed() && card.rank.value == topCard.rank.value - 1
    }
}
