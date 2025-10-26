package rocks.gorjan.gokixp.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.gif.GifDrawable
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.agent.TTSService
import rocks.gorjan.gokixp.agent.Agent
import kotlin.math.abs

class AgentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr) {

    private val handler = Handler(Looper.getMainLooper())
    private var currentAgent: Agent = Agent.ROVER
    private var isInTalkingState = false
    private var talkingStateTimer: Runnable? = null
    private var isLoadingSpeech = false
    
    // Drag functionality
    private var isDragging = false
    private var initialX = 0f
    private var initialY = 0f
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var dragThreshold = 10f
    private var hasMoved = false
    
    // Long-click functionality
    private var longPressRunnable: Runnable? = null
    private var isLongPress = false
    
    // SharedPreferences keys
    private val PREFS_NAME = "agent_settings"
    private val KEY_AGENT_X = "agent_x"
    private val KEY_AGENT_Y = "agent_y"
    private val KEY_CURRENT_AGENT = "current_agent_id"
    
    // Callback for speech events
    var onAgentTapped: ((Agent, Float, Float, Int, Int) -> Unit)? = null
    var onAgentSpeakingWithAudio: ((Agent, String, Float, Float, Int, Int, Long) -> Unit)? = null
    var onAgentSpeakingTextOnly: ((Agent, String, Float, Float, Int, Int) -> Unit)? = null
    
    // Callback for long-press context menu
    var onAgentLongPress: ((Agent, Float, Float) -> Unit)? = null
    
    // TTS service for voice synthesis
    private val ttsService = TTSService(context)

    init {
        // Set initial size to 100dp x 100dp (25% bigger than original 80dp)
        val size = (100 * context.resources.displayMetrics.density).toInt()
        dragThreshold = 10 * context.resources.displayMetrics.density
        
        Log.d("AgentView", "Setting size to ${size}px (100dp)")
        
        scaleType = ScaleType.FIT_CENTER
        visibility = View.VISIBLE
        setBackgroundResource(R.drawable.clippy_background)
        
        // Load saved agent or default
        loadCurrentAgent()
        
        // Start in waiting state
        switchToWaitingState()
        
        Log.d("AgentView", "AgentView initialization completed with agent: ${currentAgent.name}")
    }
    
    private fun loadCurrentAgent() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedAgentId = prefs.getString(KEY_CURRENT_AGENT, Agent.ROVER.id) ?: Agent.ROVER.id
        currentAgent = Agent.getAgentById(savedAgentId) ?: Agent.ROVER
        Log.d("AgentView", "Loaded current agent: ${currentAgent.name}")
    }
    
    fun setCurrentAgent(agent: Agent) {
        if (currentAgent != agent) {
            currentAgent = agent
            saveCurrentAgent()
            switchToWaitingState()
            Log.d("AgentView", "Switched to agent: ${agent.name}")
        }
    }
    
    private fun saveCurrentAgent() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CURRENT_AGENT, currentAgent.id).apply()
    }
    
    fun switchToWaitingState() {
        if (isInTalkingState) {
            talkingStateTimer?.let { handler.removeCallbacks(it) }
            talkingStateTimer = null
            isInTalkingState = false
        }
        
        // Clear loading state when returning to waiting
        isLoadingSpeech = false
        
        Log.d("AgentView", "Switching to waiting state for ${currentAgent.name}")
        try {
            Glide.with(context)
                .asGif()
                .load(currentAgent.waitingDrawableRes)
                .into(this)
            Log.d("AgentView", "Loaded waiting animation for ${currentAgent.name}")
        } catch (e: Exception) {
            Log.e("AgentView", "Failed to load waiting animation for ${currentAgent.name}", e)
        }
    }
    
    fun switchToTalkingState(durationMs: Long) {
        if (isInTalkingState) return
        
        isInTalkingState = true
        Log.d("AgentView", "Switching to talking state for ${currentAgent.name} for ${durationMs}ms")
        
        try {
            Glide.with(context)
                .asGif()
                .load(currentAgent.talkingDrawableRes)
                .into(this)
            
            // Schedule return to waiting state
            talkingStateTimer = Runnable {
                switchToWaitingState()
            }
            handler.postDelayed(talkingStateTimer!!, durationMs)
            
        } catch (e: Exception) {
            Log.e("AgentView", "Failed to load talking animation for ${currentAgent.name}", e)
            switchToWaitingState()
        }
    }
    
    fun triggerSpeech(message: String) {
        Log.d("AgentView", "Agent speech triggered with TTS")
        
        // Set loading state to prevent multiple taps
        isLoadingSpeech = true
        
        // Start TTS process
        ttsService.speakText(
            text = message,
            agent = currentAgent,
            onStart = {
                // Show loading bubble and switch to talking state temporarily
                onAgentTapped?.invoke(currentAgent, x, y, width, height)
                Log.d("AgentView", "TTS started, showing loading bubble")
            },
            onAudioReady = { audioDurationMs ->
                // Audio is ready and starting to play, switch to talking state for exact duration
                switchToTalkingState(audioDurationMs)
                onAgentSpeakingWithAudio?.invoke(currentAgent, message, x, y, width, height, audioDurationMs)
                Log.d("AgentView", "Audio ready (${audioDurationMs}ms), switching to talking state")
            },
            onComplete = {
                // Audio finished, return to waiting state
                switchToWaitingState()
                Log.d("AgentView", "TTS completed, returning to waiting state")
            },
            onError = { exception ->
                // Error occurred, fallback to text-only speech
                Log.e("AgentView", "TTS error, falling back to text-only", exception)
                val wordCount = message.split(" ").size
                val speechDurationMs = ((wordCount / 140.0) * 60 * 1000).toLong()
                val minDuration = 2000L
                val finalDuration = maxOf(speechDurationMs, minDuration)
                
                switchToTalkingState(finalDuration)
                onAgentSpeakingTextOnly?.invoke(currentAgent, message, x, y, width, height)
            }
        )
    }
    
    fun getCurrentAgent(): Agent {
        return currentAgent
    }
    
    
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = false
                hasMoved = false
                isLongPress = false
                initialX = x
                initialY = y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                
                // Start long-press detection
                longPressRunnable = Runnable {
                    if (!hasMoved) {
                        isLongPress = true
                        Log.d("AgentView", "Long press detected on agent")
                        onAgentLongPress?.invoke(currentAgent, x, y)
                    }
                }
                handler.postDelayed(longPressRunnable!!, 500) // 500ms for long press
                
                return true
            }
            
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - initialTouchX
                val deltaY = event.rawY - initialTouchY
                val distance = abs(deltaX) + abs(deltaY)
                
                if (distance > dragThreshold && !hasMoved) {
                    isDragging = true
                    hasMoved = true
                    // Cancel long press when movement is detected
                    longPressRunnable?.let { handler.removeCallbacks(it) }
                }
                
                if (isDragging) {
                    x = initialX + deltaX
                    y = initialY + deltaY
                }
                return true
            }
            
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Cancel long press if still pending
                longPressRunnable?.let { handler.removeCallbacks(it) }
                
                if (isDragging) {
                    isDragging = false
                    savePosition()
                } else if (!hasMoved && !isLongPress) {
                    // This was a tap, not a drag or long press - just notify the callback
                    if (!isLoadingSpeech) {
                        onAgentTapped?.invoke(currentAgent, x, y, width, height)
                        Log.d("AgentView", "Agent tapped - showing input bubble")
                    } else {
                        Log.d("AgentView", "Ignoring tap - agent is already loading/processing speech")
                    }
                }
                
                // Reset states
                isLongPress = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }
    
    fun savePosition() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putFloat(KEY_AGENT_X, x)
            putFloat(KEY_AGENT_Y, y)
            apply()
        }
        Log.d("AgentView", "Saved agent position: x=$x, y=$y")
    }
    
    fun restorePosition() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedX = prefs.getFloat(KEY_AGENT_X, -1f)
        val savedY = prefs.getFloat(KEY_AGENT_Y, -1f)
        
        if (savedX >= 0 && savedY >= 0) {
            x = savedX
            y = savedY
            Log.d("AgentView", "Restored agent position: x=$savedX, y=$savedY")
        } else {
            Log.d("AgentView", "No saved position found, using default placement")
        }
    }

    fun destroy() {
        talkingStateTimer?.let { handler.removeCallbacks(it) }
        talkingStateTimer = null
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
        ttsService.cleanup()
    }
}