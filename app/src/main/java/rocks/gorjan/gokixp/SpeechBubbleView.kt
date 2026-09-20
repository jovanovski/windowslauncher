package rocks.gorjan.gokixp

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.text.TextWatcher
import android.text.Editable
import android.text.InputFilter

class SpeechBubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val MIN_CHARACTERS = 3
        // A line for the agent to read out; the speech synthesiser gets unhappy past this
        private const val MAX_CHARACTERS = 140
        // A question for the AI, which can be a little longer than something to read aloud
        private const val MAX_QUESTION_CHARACTERS = 300
        private const val DEFAULT_HINT = "What should I say?"
        private const val DEFAULT_CREDIT = "voice by tetyys.com"

        /**
         * Flattens anything pasted in: Enter is the send key here, so a line break the user never
         * typed shouldn't be the one thing that makes the box taller.
         */
        private val SINGLE_LINE_FILTER = InputFilter { source, start, end, _, _, _ ->
            val hasBreak = (start until end).any { source[it] == '\n' || source[it] == '\r' }
            if (!hasBreak) null else source.subSequence(start, end).toString().replace(Regex("[\\r\\n]+"), " ")
        }
    }

    private val speechText: TextView
    private val inputContainer: LinearLayout
    private val speechInput: EditText
    private val speechCredit: TextView
    private val sendButton: ImageButton
    private var onSpeechRequestListener: ((String) -> Unit)? = null

    // A tap anywhere dismisses a reply, but not the "loading" line it replaces - dismissing that
    // would throw away an answer that is already paid for and on its way
    private var isWaitingForReply = false

    // The agent the bubble was last placed against. An answer is a different size from the
    // "loading" line it replaces, so the bubble has to be placed again once it arrives or a long
    // one runs off the edge of the screen.
    private var anchorX = 0f
    private var anchorY = 0f
    private var anchorWidth = 0
    private var anchorHeight = 0

    init {
        LayoutInflater.from(context).inflate(R.layout.speech_bubble, this, true)
        speechText = findViewById(R.id.speechText)
        inputContainer = findViewById(R.id.inputContainer)
        speechInput = findViewById(R.id.speechInput)
        speechCredit = findViewById(R.id.speechCredit)
        sendButton = findViewById(R.id.sendButton)
        
        visibility = View.GONE
        
        // Set up text watcher to validate length and enable/disable send button
        speechInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim() ?: ""
                val isValid = text.length >= MIN_CHARACTERS
                sendButton.isEnabled = isValid
                sendButton.alpha = if (isValid) 1.0f else 0.5f
                
                Log.d("SpeechBubbleView", "Text length: ${text.length}, Send button enabled: $isValid")
            }
        })
        
        // Set up send button click listener
        sendButton.setOnClickListener { submit() }

        // Enter sends the line rather than growing the box. The field stays textMultiLine so long
        // text still wraps, which means the keyboard offers a real Enter key - so the key event is
        // swallowed here before it can insert anything.
        speechInput.setOnKeyListener { _, keyCode, event ->
            val isEnter = keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            if (isEnter && event.action == KeyEvent.ACTION_DOWN) {
                submit()
                true
            } else {
                // Still consume the matching key-up, or the field sees a stray newline
                isEnter
            }
        }

        // Keyboards that show a Send/Done button instead of Enter come through here
        speechInput.setOnEditorActionListener { _, _, _ ->
            submit()
            true
        }
    }

    /** Hands the typed text to the listener, if there is enough of it to be worth sending. */
    private fun submit() {
        val text = speechInput.text.toString().trim()
        if (text.length < MIN_CHARACTERS) return

        // Hide the soft keyboard
        val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(speechInput.windowToken, 0)

        // Clear focus from input field
        speechInput.clearFocus()

        onSpeechRequestListener?.invoke(text)
    }

    /** The original mode: a line is prefilled for the agent to read out, ready to be edited. */
    fun showInputBubble(defaultText: String, agentX: Float, agentY: Float, agentWidth: Int, agentHeight: Int) {
        showInput(defaultText, DEFAULT_HINT, DEFAULT_CREDIT, MAX_CHARACTERS, agentX, agentY, agentWidth, agentHeight)
        speechInput.selectAll()
    }

    /** The AI mode: nothing is prefilled, because whatever is typed becomes the question. */
    fun showAskBubble(hint: String, credit: String, agentX: Float, agentY: Float, agentWidth: Int, agentHeight: Int) {
        showInput("", hint, credit, MAX_QUESTION_CHARACTERS, agentX, agentY, agentWidth, agentHeight)
    }

    private fun showInput(
        text: String,
        hint: String,
        credit: String,
        maxCharacters: Int,
        agentX: Float,
        agentY: Float,
        agentWidth: Int,
        agentHeight: Int
    ) {
        // Show input mode
        inputContainer.visibility = View.VISIBLE
        speechText.visibility = View.GONE
        speechInput.filters = arrayOf(SINGLE_LINE_FILTER, InputFilter.LengthFilter(maxCharacters))
        speechInput.hint = hint
        speechCredit.text = credit
        speechInput.setText(text)
        
        // Validate initial text and set send button state
        val trimmedText = text.trim()
        val isValid = trimmedText.length >= MIN_CHARACTERS
        sendButton.isEnabled = isValid
        sendButton.alpha = if (isValid) 1.0f else 0.5f
        
        // Position the bubble relative to the agent
        positionBubble(agentX, agentY, agentWidth, agentHeight)
        
        visibility = View.VISIBLE
        
        // Focus on input field
        speechInput.requestFocus()
        isWaitingForReply = false
        
        Log.d("SpeechBubbleView", "Showing input bubble with text: '$text' (${trimmedText.length} chars, valid: $isValid)")
    }
    
    /** Says something unprompted. Like every reply, it waits to be tapped away. */
    fun showSpeech(message: String, agentX: Float, agentY: Float, agentWidth: Int, agentHeight: Int) {
        // Show speech mode
        inputContainer.visibility = View.GONE
        speechText.visibility = View.VISIBLE
        speechText.text = message
        isWaitingForReply = false
        
        // Position the bubble relative to the agent
        positionBubble(agentX, agentY, agentWidth, agentHeight)
        
        visibility = View.VISIBLE
        
        Log.d("SpeechBubbleView", "Showing speech: '$message'")
    }
    
    fun showLoadingBubble(agentX: Float, agentY: Float, agentWidth: Int, agentHeight: Int) {
        // Show speech mode with loading text
        inputContainer.visibility = View.GONE
        speechText.visibility = View.VISIBLE
        speechText.text = "( loading... )"
        isWaitingForReply = true
        
        // Position the bubble relative to the agent
        positionBubble(agentX, agentY, agentWidth, agentHeight)
        
        visibility = View.VISIBLE
    }
    
    fun setOnSpeechRequestListener(listener: (String) -> Unit) {
        onSpeechRequestListener = listener
    }
    
    fun isInInputMode(): Boolean {
        return visibility == View.VISIBLE && inputContainer.visibility == View.VISIBLE
    }
    
    /**
     * Puts the answer (or the reason there isn't one) in the bubble, replacing the question or the
     * "loading" line. It stays there until [dismissResponse] - there is no reading-time guess any
     * more, because a long answer being whisked away half-read was the whole problem with one.
     */
    fun showResponse(message: String) {
        inputContainer.visibility = View.GONE
        speechText.visibility = View.VISIBLE
        speechText.text = message
        isWaitingForReply = false
        repositionBubble()
        visibility = View.VISIBLE
    }

    /** Whether the bubble is in use - asking, waiting or answering. */
    fun isShowing(): Boolean = visibility == View.VISIBLE

    /**
     * Taps away a reply the agent is still holding up. Returns whether there was one, so a caller
     * that dismisses on every touch can tell whether it just did anything.
     */
    fun dismissResponse(): Boolean {
        if (visibility != View.VISIBLE || isInInputMode() || isWaitingForReply) return false
        hideSpeech()
        return true
    }

    private fun repositionBubble() {
        positionBubble(anchorX, anchorY, anchorWidth, anchorHeight)
    }

    private fun positionBubble(agentX: Float, agentY: Float, agentWidth: Int, agentHeight: Int) {
        anchorX = agentX
        anchorY = agentY
        anchorWidth = agentWidth
        anchorHeight = agentHeight

        measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        
        val bubbleWidth = measuredWidth
        val bubbleHeight = measuredHeight
        val parentWidth = (parent as View).width
        val parentHeight = (parent as View).height
        
        // Add -30dp offset to move bubble higher
        val verticalOffset = -30f * context.resources.displayMetrics.density
        
        var bubbleX = agentX
        var bubbleY = agentY
        
        // Position bubble to the right of agent by default
        bubbleX = agentX + agentWidth + 16f
        bubbleY = agentY - (bubbleHeight / 2f) + (agentHeight / 2f) + verticalOffset
        
        // Check if bubble goes off screen to the right
        if (bubbleX + bubbleWidth > parentWidth) {
            // Position to the left of agent
            bubbleX = agentX - bubbleWidth - 16f
        }
        
        // Check if bubble goes off screen to the left
        if (bubbleX < 0) {
            // Position above agent, but check if offset would cause top overflow
            bubbleX = agentX + (agentWidth / 2f) - (bubbleWidth / 2f)
            val proposedY = agentY - bubbleHeight - 16f + verticalOffset
            if (proposedY < 0) {
                // Don't apply offset if it would go off top
                bubbleY = agentY - bubbleHeight - 16f
            } else {
                bubbleY = proposedY
            }
        }
        
        // Check if bubble goes off screen at the top
        if (bubbleY < 0) {
            // Position below agent without offset to avoid going off screen
            bubbleY = agentY + agentHeight + 16f
        }
        
        // Check if bubble goes off screen at the bottom
        if (bubbleY + bubbleHeight > parentHeight) {
            // Position above agent, but check if offset would cause top overflow
            val proposedY = agentY - bubbleHeight - 16f + verticalOffset
            if (proposedY < 0) {
                // Don't apply offset if it would go off top
                bubbleY = agentY - bubbleHeight - 16f
            } else {
                bubbleY = proposedY
            }
        }
        
        // Final bounds check
        bubbleX = bubbleX.coerceAtLeast(0f).coerceAtMost((parentWidth - bubbleWidth).toFloat())
        bubbleY = bubbleY.coerceAtLeast(0f).coerceAtMost((parentHeight - bubbleHeight).toFloat())
        
        x = bubbleX
        y = bubbleY
        
        Log.d("SpeechBubbleView", "Positioned bubble at ($bubbleX, $bubbleY) for agent at ($agentX, $agentY)")
    }

    fun hideSpeech() {
        visibility = View.GONE
        isWaitingForReply = false
        Log.d("SpeechBubbleView", "Speech bubble hidden")
    }
}