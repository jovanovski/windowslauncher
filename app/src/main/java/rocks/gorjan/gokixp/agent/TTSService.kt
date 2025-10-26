package rocks.gorjan.gokixp.agent

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.*
import rocks.gorjan.gokixp.agent.Agent
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

class TTSService(private val context: Context) {
    
    private var currentMediaPlayer: MediaPlayer? = null
    private val ttsCache = File(context.cacheDir, "tts_cache")
    
    // Cache management constants
    private val MAX_CACHE_SIZE_MB = 50 // Maximum 50MB cache
    private val MAX_CACHE_FILES = 100 // Maximum 100 cached files
    private val MAX_CACHE_SIZE_BYTES = MAX_CACHE_SIZE_MB * 1024L * 1024L
    
    init {
        if (!ttsCache.exists()) {
            ttsCache.mkdirs()
        }
        
        // Initialize cache cleanup on startup
        CoroutineScope(Dispatchers.IO).launch {
            manageCacheSize()
        }
    }
    
    fun speakText(
        text: String,
        agent: Agent,
        onStart: () -> Unit,
        onAudioReady: (audioDurationMs: Long) -> Unit,
        onComplete: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        // Stop any currently playing audio
        stopCurrentAudio()
        
        // Signal that TTS process started
        onStart()
        
        // Start TTS process in background
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val audioFile = fetchOrGetCachedAudio(text, agent)
                
                withContext(Dispatchers.Main) {
                    playAudioFile(audioFile, onAudioReady, onComplete, onError)
                }
                
            } catch (e: Exception) {
                Log.e("TTSService", "Error in TTS process", e)
                withContext(Dispatchers.Main) {
                    onError(e)
                }
            }
        }
    }
    
    private suspend fun fetchOrGetCachedAudio(text: String, agent: Agent): File {
        // Create cache key based on text and agent voice parameters
        val cacheKey = createCacheKey(text, agent)
        val cachedFile = File(ttsCache, "$cacheKey.wav")
        
        if (cachedFile.exists() && cachedFile.length() > 0) {
            Log.d("TTSService", "✓ CACHE HIT - Using cached audio for: \"$text\" (${cachedFile.length()} bytes)")
            Log.d("TTSService", "Cache key: $cacheKey")
            return cachedFile
        }
        
        Log.d("TTSService", "✗ CACHE MISS - Fetching new audio for: \"$text\"")
        Log.d("TTSService", "Cache key: $cacheKey")
        
        // Build SAPI4 URL
        val encodedText = URLEncoder.encode(text, "UTF-8")
        val encodedVoice = URLEncoder.encode(agent.voiceName, "UTF-8")
        val url = "https://www.tetyys.com/SAPI4/SAPI4?text=$encodedText&voice=$encodedVoice&pitch=${agent.pitch}&speed=${agent.speed}"
        
        Log.d("TTSService", "Fetching TTS audio from: $url")
        
        // Download and cache the audio file
        val connection = URL(url).openConnection()
        connection.connectTimeout = 10000 // 10 seconds
        connection.readTimeout = 30000 // 30 seconds
        
        connection.inputStream.use { input ->
            FileOutputStream(cachedFile).use { output ->
                input.copyTo(output)
            }
        }
        
        val cacheStats = getCacheStats()
        Log.d("TTSService", "✓ Audio cached successfully for: \"$text\" (${cachedFile.length()} bytes)")
        Log.d("TTSService", "Cache stats: ${cacheStats.first} files, ${formatBytes(cacheStats.second)} total")
        
        // Manage cache size after adding new file
        CoroutineScope(Dispatchers.IO).launch {
            manageCacheSize()
        }
        
        return cachedFile
    }
    
    private fun createCacheKey(text: String, agent: Agent): String {
        // Normalize text to ensure consistent caching (trim, lowercase for cache key generation)
        val normalizedText = text.trim()
        
        // Create a comprehensive input string that includes all voice parameters
        val input = buildString {
            append("text=").append(normalizedText)
            append("&voice=").append(agent.voiceName)
            append("&pitch=").append(agent.pitch)
            append("&speed=").append(agent.speed)
            append("&agent=").append(agent.id) // Include agent ID for additional uniqueness
        }
        
        Log.d("TTSService", "Cache key input: $input")
        
        // Generate MD5 hash for cache key
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
    
    private fun playAudioFile(
        audioFile: File,
        onAudioReady: (audioDurationMs: Long) -> Unit,
        onComplete: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        try {
            currentMediaPlayer = MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                
                setOnPreparedListener { mediaPlayer ->
                    val audioDuration = mediaPlayer.duration.toLong()
                    Log.d("TTSService", "Audio prepared, duration: ${audioDuration}ms, starting playback")
                    onAudioReady(audioDuration)
                    mediaPlayer.start()
                }
                
                setOnCompletionListener { mediaPlayer ->
                    Log.d("TTSService", "Audio playback completed")
                    mediaPlayer.release()
                    currentMediaPlayer = null
                    onComplete()
                }
                
                setOnErrorListener { mediaPlayer, what, extra ->
                    Log.e("TTSService", "MediaPlayer error: what=$what, extra=$extra")
                    mediaPlayer.release()
                    currentMediaPlayer = null
                    onError(Exception("MediaPlayer error: what=$what, extra=$extra"))
                    true
                }
                
                prepareAsync()
            }
            
        } catch (e: Exception) {
            Log.e("TTSService", "Error setting up MediaPlayer", e)
            onError(e)
        }
    }
    
    fun stopCurrentAudio() {
        currentMediaPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            } catch (e: Exception) {
                Log.w("TTSService", "Error stopping MediaPlayer", e)
            } finally {
                currentMediaPlayer = null
            }
        }
    }
    
    private fun manageCacheSize() {
        try {
            val files = ttsCache.listFiles() ?: return
            val (fileCount, totalSize) = getCacheStats()
            
            Log.d("TTSService", "Cache management: $fileCount files, ${formatBytes(totalSize)}")
            
            // Check if cache cleanup is needed
            if (fileCount > MAX_CACHE_FILES || totalSize > MAX_CACHE_SIZE_BYTES) {
                Log.d("TTSService", "Cache cleanup needed - files: $fileCount/$MAX_CACHE_FILES, size: ${formatBytes(totalSize)}/${formatBytes(MAX_CACHE_SIZE_BYTES)}")
                
                // Sort files by last accessed time (oldest first)
                val sortedFiles = files.sortedBy { it.lastModified() }
                
                var deletedFiles = 0
                var freedBytes = 0L
                
                // Delete oldest files until we're under limits
                for (file in sortedFiles) {
                    if (fileCount - deletedFiles <= MAX_CACHE_FILES * 0.8 && 
                        totalSize - freedBytes <= MAX_CACHE_SIZE_BYTES * 0.8) {
                        break // Keep 80% of limits to avoid frequent cleanups
                    }
                    
                    val fileSize = file.length()
                    if (file.delete()) {
                        deletedFiles++
                        freedBytes += fileSize
                        Log.d("TTSService", "Deleted cached file: ${file.name} (${formatBytes(fileSize)})")
                    }
                }
                
                Log.d("TTSService", "Cache cleanup complete: deleted $deletedFiles files, freed ${formatBytes(freedBytes)}")
            }
            
        } catch (e: Exception) {
            Log.w("TTSService", "Error managing cache size", e)
        }
    }
    
    private fun getCacheStats(): Pair<Int, Long> {
        return try {
            val files = ttsCache.listFiles() ?: return Pair(0, 0L)
            val fileCount = files.size
            val totalSize = files.sumOf { it.length() }
            Pair(fileCount, totalSize)
        } catch (e: Exception) {
            Log.w("TTSService", "Error getting cache stats", e)
            Pair(0, 0L)
        }
    }
    
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes bytes"
        }
    }
    
    fun cleanup() {
        stopCurrentAudio()
        // Clean up old cache files (older than 7 days)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val oneWeekAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000)
                ttsCache.listFiles()?.forEach { file ->
                    if (file.lastModified() < oneWeekAgo) {
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                Log.w("TTSService", "Error cleaning up cache", e)
            }
        }
    }
}