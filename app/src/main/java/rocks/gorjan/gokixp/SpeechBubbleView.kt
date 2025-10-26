package rocks.gorjan.gokixp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
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
        private const val MAX_CHARACTERS = 140
    }

    private val speechText: TextView
    private val inputContainer: LinearLayout
    private val speechInput: EditText
    private val sendButton: ImageButton
    private val handler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null
    private var onSpeechRequestListener: ((String) -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.speech_bubble, this, true)
        speechText = findViewById(R.id.speechText)
        inputContainer = findViewById(R.id.inputContainer)
        speechInput = findViewById(R.id.speechInput)
        sendButton = findViewById(R.id.sendButton)
        
        visibility = View.GONE
        
        // Set maximum character limit filter
        speechInput.filters = arrayOf(InputFilter.LengthFilter(MAX_CHARACTERS))
        
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
        sendButton.setOnClickListener {
            val text = speechInput.text.toString().trim()
            if (text.length >= MIN_CHARACTERS) {
                // Hide the soft keyboard
                val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                inputMethodManager.hideSoftInputFromWindow(speechInput.windowToken, 0)
                
                // Clear focus from input field
                speechInput.clearFocus()
                
                onSpeechRequestListener?.invoke(text)
            }
        }
    }

    fun showInputBubble(defaultText: String, agentX: Float, agentY: Float, agentWidth: Int, agentHeight: Int) {
        // Show input mode
        inputContainer.visibility = View.VISIBLE
        speechText.visibility = View.GONE
        speechInput.setText(defaultText)
        speechInput.selectAll()
        
        // Validate initial text and set send button state
        val trimmedText = defaultText.trim()
        val isValid = trimmedText.length >= MIN_CHARACTERS
        sendButton.isEnabled = isValid
        sendButton.alpha = if (isValid) 1.0f else 0.5f
        
        // Position the bubble relative to the agent
        positionBubble(agentX, agentY, agentWidth, agentHeight)
        
        visibility = View.VISIBLE
        
        // Focus on input field
        speechInput.requestFocus()
        
        // Cancel any existing hide timer
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideRunnable = null
        
        Log.d("SpeechBubbleView", "Showing input bubble with default text: '$defaultText' (${trimmedText.length} chars, valid: $isValid)")
    }
    
    fun showSpeech(message: String, agentX: Float, agentY: Float, agentWidth: Int, agentHeight: Int) {
        // Show speech mode
        inputContainer.visibility = View.GONE
        speechText.visibility = View.VISIBLE
        speechText.text = message
        
        // Position the bubble relative to the agent
        positionBubble(agentX, agentY, agentWidth, agentHeight)
        
        visibility = View.VISIBLE
        
        // Calculate reading time (140 words per minute)
        val wordCount = message.split(" ").size
        val readingTimeMs = ((wordCount / 140.0) * 60 * 1000).toLong()
        val minDisplayTime = 2000L // Minimum 2 seconds
        val displayTime = maxOf(readingTimeMs, minDisplayTime)
        
        Log.d("SpeechBubbleView", "Showing speech: '$message' for ${displayTime}ms (${wordCount} words)")
        
        // Hide after calculated time
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideRunnable = Runnable {
            hideSpeech()
        }
        handler.postDelayed(hideRunnable!!, displayTime)
    }
    
    fun showLoadingBubble(agentX: Float, agentY: Float, agentWidth: Int, agentHeight: Int) {
        // Show speech mode with loading text
        inputContainer.visibility = View.GONE
        speechText.visibility = View.VISIBLE
        speechText.text = "..."
        
        // Position the bubble relative to the agent
        positionBubble(agentX, agentY, agentWidth, agentHeight)
        
        visibility = View.VISIBLE
        
        // Cancel any existing hide timer
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideRunnable = null
        
        Log.d("SpeechBubbleView", "Showing loading bubble with '...'")
    }
    
    fun setOnSpeechRequestListener(listener: (String) -> Unit) {
        onSpeechRequestListener = listener
    }
    
    fun isInInputMode(): Boolean {
        return visibility == View.VISIBLE && inputContainer.visibility == View.VISIBLE
    }
    
    fun updateBubbleText(message: String, audioDurationMs: Long) {
        // Switch to speech mode and update text
        inputContainer.visibility = View.GONE
        speechText.visibility = View.VISIBLE
        speechText.text = message
        
        // Calculate display time based on audio duration plus a buffer
        val displayTime = audioDurationMs + 1000L // Show for audio duration + 1 second buffer
        
        Log.d("SpeechBubbleView", "Updated bubble text: '$message' for ${displayTime}ms")
        
        // Schedule hide after audio completes
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideRunnable = Runnable {
            hideSpeech()
        }
        handler.postDelayed(hideRunnable!!, displayTime)
    }
    
    fun updateBubbleTextWithCountdown(message: String) {
        // Switch to speech mode and update text
        inputContainer.visibility = View.GONE
        speechText.visibility = View.VISIBLE
        speechText.text = message
        
        // Calculate reading time (140 words per minute)
        val wordCount = message.split(" ").size
        val readingTimeMs = ((wordCount / 140.0) * 60 * 1000).toLong()
        val minDisplayTime = 2000L // Minimum 2 seconds
        val displayTime = maxOf(readingTimeMs, minDisplayTime)
        
        Log.d("SpeechBubbleView", "Updated bubble text: '$message' for ${displayTime}ms (${wordCount} words)")
        
        // Schedule hide after calculated reading time
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideRunnable = Runnable {
            hideSpeech()
        }
        handler.postDelayed(hideRunnable!!, displayTime)
    }

    private fun positionBubble(agentX: Float, agentY: Float, agentWidth: Int, agentHeight: Int) {
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
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideRunnable = null
        visibility = View.GONE
        Log.d("SpeechBubbleView", "Speech bubble hidden")
    }

    fun destroy() {
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideRunnable = null
    }
}