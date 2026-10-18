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
import rocks.gorjan.gokixp.MainActivity
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.agent.TTSService
import rocks.gorjan.gokixp.agent.Agent
import kotlin.math.abs
import kotlin.random.Random
import rocks.gorjan.gokixp.getSafeFloat

class AgentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr) {

    private val handler = Handler(Looper.getMainLooper())
    private var currentAgent: Agent = Agent.DEFAULT
    private var isInTalkingState = false
    private var isInWaitingState = false
    private var talkingStateTimer: Runnable? = null
    private var isLoadingSpeech = false
    private var isGifLoaded = false

    // Sprite animation state
    private var spriteSheet: SpriteSheet? = null
    private var frameDrawable: SpriteFrameDrawable? = null
    // The step showing the playing animation's next frame; held while hidden so playback resumes where it was
    private var frameStep: Runnable? = null
    private var isShownOnScreen = true
    private var nextIdleRunnable: Runnable? = null
    private var lastIdleAnimation: String? = null
    private val idlePools = mutableMapOf<String, List<String>>()
    
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
    
    // SharedPreferences keys - use MainActivity.PREFS_NAME for consistency
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
        dragThreshold = 10 * context.resources.displayMetrics.density
        
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
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val savedAgentId = prefs.getString(KEY_CURRENT_AGENT, Agent.DEFAULT.id) ?: Agent.DEFAULT.id
        currentAgent = Agent.getAgentById(savedAgentId) ?: Agent.DEFAULT
        Log.d("AgentView", "Loaded current agent: ${currentAgent.name}")
    }
    
    fun setCurrentAgent(agent: Agent) {
        if (currentAgent != agent) {
            currentAgent = agent
            saveCurrentAgent()
            applyAgentSize()
            isInWaitingState = false
            lastIdleAnimation = null
            switchToWaitingState()
            Log.d("AgentView", "Switched to agent: ${agent.name}")
        }
    }
    
    private fun saveCurrentAgent() {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CURRENT_AGENT, currentAgent.id).apply()
    }

    private fun applyAgentSize() {
        val params = layoutParams ?: return
        val density = resources.displayMetrics.density
        params.width = (currentAgent.widthDp * density).toInt()
        params.height = (currentAgent.heightDp * density).toInt()
        layoutParams = params
    }
    
    fun switchToWaitingState() {
        if (isInTalkingState) {
            talkingStateTimer?.let { handler.removeCallbacks(it) }
            talkingStateTimer = null
            isInTalkingState = false
        }
        
        // Clear loading state when returning to waiting
        isLoadingSpeech = false

        // Both the talking timer and the end of the audio land here; don't cut the idle loop short
        if (isInWaitingState) return
        isInWaitingState = true
        
        Log.d("AgentView", "Switching to waiting state for ${currentAgent.name}")
        if (currentAgent.sprites != null) {
            clearGif()
            rest()
            return
        }
        stopSprite()
        try {
            isGifLoaded = true
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
        isInWaitingState = false
        Log.d("AgentView", "Switching to talking state for ${currentAgent.name} for ${durationMs}ms")
        
        try {
            val sprites = currentAgent.sprites
            if (sprites != null) {
                clearGif()
                playSprite(sprites.talking, loop = true)
            } else {
                stopSprite()
                isGifLoaded = true
                Glide.with(context)
                    .asGif()
                    .load(currentAgent.talkingDrawableRes)
                    .into(this)
            }
            
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

    // Hold the rest pose for a beat, then play a random idle animation; each one ends back here
    private fun rest() {
        val sprites = currentAgent.sprites ?: return
        playSprite(sprites.rest, loop = false)
        val next = Runnable { playNextIdle() }
        nextIdleRunnable = next
        handler.postDelayed(next, Random.nextLong(IDLE_REST_MIN_MS, IDLE_REST_MAX_MS))
    }

    private fun playNextIdle() {
        val sprites = currentAgent.sprites ?: return
        val pool = idlePool(currentAgent, sprites)
        val name = pool.filter { it != lastIdleAnimation }.randomOrNull() ?: pool.randomOrNull()
        if (name == null) {
            rest()
            return
        }
        lastIdleAnimation = name
        playSprite(name, loop = false) { rest() }
    }

    private fun idlePool(agent: Agent, sprites: SpriteAnimations): List<String> = idlePools.getOrPut(agent.id) {
        val excluded = sprites.notIdle + sprites.rest + sprites.talking
        currentSpriteSheet()?.animationNames.orEmpty().filter { it !in excluded }
    }

    private fun currentSpriteSheet(): SpriteSheet? {
        spriteSheet?.let { if (it.agentId == currentAgent.id) return it }
        val sheet = SpriteSheet.load(context.assets, currentAgent.id)
        spriteSheet = sheet
        frameDrawable = sheet?.let { SpriteFrameDrawable(it.frameWidth, it.frameHeight) }
        return sheet
    }

    /**
     * Shows one of the agent's animations. Frames only advance while the agent is on screen, so the idle
     * loop pauses when the agent is hidden or the launcher is in the background.
     */
    private fun playSprite(name: String, loop: Boolean, onEnd: (() -> Unit)? = null) {
        stopSprite()
        val sheet = currentSpriteSheet()
        val drawable = frameDrawable
        val animation = sheet?.animation(name)
        if (sheet == null || drawable == null || animation == null || animation.frames.isEmpty()) {
            Log.e("AgentView", "Missing animation $name for ${currentAgent.name}")
            setImageDrawable(null)
            onEnd?.let { handler.post(it) }
            return
        }
        if (getDrawable() !== drawable) setImageDrawable(drawable)
        if (animation.frames.size == 1) {
            // A still frame has nothing to wait for
            drawable.showFrame(sheet, animation.frames[0])
            onEnd?.let { handler.post(it) }
            return
        }
        var next = 0
        val step = object : Runnable {
            override fun run() {
                if (frameStep !== this) return
                if (next == animation.frames.size) {
                    if (!loop) {
                        frameStep = null
                        onEnd?.invoke()
                        return
                    }
                    next = 0
                }
                drawable.showFrame(sheet, animation.frames[next])
                val duration = animation.durationsMs[next].toLong().coerceAtLeast(MIN_FRAME_MS)
                next++
                if (isShownOnScreen) handler.postDelayed(this, duration)
            }
        }
        frameStep = step
        step.run()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        isShownOnScreen = isVisible
        val step = frameStep ?: return
        handler.removeCallbacks(step)
        if (isVisible) handler.post(step)
    }

    // A pending Glide load would otherwise land on top of the sprite
    private fun clearGif() {
        if (!isGifLoaded) return
        isGifLoaded = false
        try {
            Glide.with(context).clear(this)
        } catch (e: Exception) {
            Log.e("AgentView", "Failed to clear GIF animation", e)
        }
    }

    private fun stopSprite() {
        nextIdleRunnable?.let { handler.removeCallbacks(it) }
        nextIdleRunnable = null
        frameStep?.let { handler.removeCallbacks(it) }
        frameStep = null
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
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putFloat(KEY_AGENT_X, x)
            putFloat(KEY_AGENT_Y, y)
            apply()
        }
        Log.d("AgentView", "Saved agent position: x=$x, y=$y")
    }
    
    fun restorePosition() {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val savedX = prefs.getSafeFloat(KEY_AGENT_X, -1f)
        val savedY = prefs.getSafeFloat(KEY_AGENT_Y, -1f)
        
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
        stopSprite()
        ttsService.cleanup()
    }

    companion object {
        private const val IDLE_REST_MIN_MS = 1000L
        private const val IDLE_REST_MAX_MS = 3000L
        private const val MIN_FRAME_MS = 10L
    }
}
