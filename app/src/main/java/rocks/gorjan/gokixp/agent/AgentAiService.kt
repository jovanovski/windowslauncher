package rocks.gorjan.gokixp.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import rocks.gorjan.gokixp.MainActivity
import java.net.HttpURLConnection
import java.net.URL

/**
 * The chat APIs the Desktop Agent can be pointed at. Each keeps its own key, so both can be
 * pasted in and the spinner decides which one actually answers.
 */
enum class AiProvider(
    val id: String,
    val label: String,
    val keyPref: String,
    // Shown in small print under the question box, where the TTS credit sits otherwise
    val credit: String
) {
    OPENAI("openai", "ChatGPT (OpenAI)", "agent_ai_key_openai", "answers by ChatGPT"),
    ANTHROPIC("anthropic", "Claude (Anthropic)", "agent_ai_key_anthropic", "answers by Claude");

    companion object {
        val DEFAULT = OPENAI

        fun fromId(id: String?): AiProvider = entries.find { it.id == id } ?: DEFAULT
    }
}

/**
 * Everything the "Send queries to AI" half of the Desktop Agent settings remembers.
 *
 * Kept next to the service rather than in MainActivity so the agent code can read its own
 * settings, but written into the launcher's usual preferences file so it backs up with the rest.
 */
object AgentAiSettings {

    const val KEY_PROVIDER = "agent_ai_provider"
    const val KEY_ENABLED = "agent_ai_enabled"
    const val KEY_SPEAK = "agent_ai_speak"

    private fun prefs(context: Context) =
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

    fun provider(context: Context): AiProvider =
        AiProvider.fromId(prefs(context).getString(KEY_PROVIDER, AiProvider.DEFAULT.id))

    fun setProvider(context: Context, provider: AiProvider) {
        prefs(context).edit().putString(KEY_PROVIDER, provider.id).apply()
    }

    fun apiKey(context: Context, provider: AiProvider): String =
        prefs(context).getString(provider.keyPref, "")?.trim() ?: ""

    fun setApiKey(context: Context, provider: AiProvider, key: String) {
        prefs(context).edit().putString(provider.keyPref, key.trim()).apply()
    }

    /** Whether the currently selected provider has a key to send anything with. */
    fun hasApiKey(context: Context): Boolean = apiKey(context, provider(context)).isNotEmpty()

    fun isAiModeChecked(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setAiModeChecked(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * The agent only talks to an AI when the box is ticked *and* the selected provider still has
     * a key - clearing the key falls back to the plain "make the agent say this" mode instead of
     * tapping into a dead setting.
     */
    fun isAiMode(context: Context): Boolean = isAiModeChecked(context) && hasApiKey(context)

    fun readAloud(context: Context): Boolean = prefs(context).getBoolean(KEY_SPEAK, false)

    fun setReadAloud(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SPEAK, enabled).apply()
    }
}

/**
 * The running conversation behind the agent, so a second question can say "and that one?".
 *
 * It is kept in preferences rather than in memory because the launcher's process is killed and
 * restarted all the time - losing the thread every time the user opened a game would make the
 * agent look forgetful for no good reason. An hour of silence ends the conversation: long enough
 * to carry on after a distraction, short enough that tomorrow starts clean.
 */
object AgentAiMemory {

    private const val TAG = "AgentAiMemory"

    /** How long a conversation survives without a word being said in it. */
    const val LIFETIME_MS = 60 * 60 * 1000L

    // Ten exchanges - enough to hold a thread, not enough to pay for a long transcript every turn.
    // Always an even number: the history is stored in user/assistant pairs, and Anthropic wants
    // the first message in a conversation to be the user's.
    private const val MAX_TURNS = 20

    private const val KEY_TURNS = "agent_ai_history"
    private const val KEY_UPDATED_AT = "agent_ai_history_at"
    private const val KEY_AGENT = "agent_ai_history_agent"

    data class Turn(val role: String, val text: String)

    private val gson = Gson()
    private val turnListType = object : TypeToken<List<Turn>>() {}.type

    private fun prefs(context: Context) =
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * What has been said so far, or nothing if the conversation has gone stale or belongs to a
     * character the user has since switched away from - Clippy shouldn't finish Rover's sentences.
     */
    fun history(context: Context, agent: Agent): List<Turn> {
        val prefs = prefs(context)
        if (prefs.getString(KEY_AGENT, null) != agent.id) return emptyList()
        if (System.currentTimeMillis() - prefs.getLong(KEY_UPDATED_AT, 0L) > LIFETIME_MS) return emptyList()

        val stored = prefs.getString(KEY_TURNS, null) ?: return emptyList()
        val turns = try {
            gson.fromJson<List<Turn>>(stored, turnListType)
        } catch (e: Exception) {
            Log.w(TAG, "Dropping unreadable conversation history", e)
            null
        } ?: return emptyList()

        // Insurance against a half-written history: the transcript has to open on a question
        return turns.dropWhile { it.role != ROLE_USER }
    }

    /** Adds one exchange to the conversation and pushes its hour out again. */
    fun record(context: Context, agent: Agent, question: String, reply: String) {
        val turns = (history(context, agent) + Turn(ROLE_USER, question) + Turn(ROLE_ASSISTANT, reply))
            .takeLast(MAX_TURNS)
        prefs(context).edit()
            .putString(KEY_TURNS, gson.toJson(turns))
            .putString(KEY_AGENT, agent.id)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun forget(context: Context) {
        prefs(context).edit()
            .remove(KEY_TURNS)
            .remove(KEY_AGENT)
            .remove(KEY_UPDATED_AT)
            .apply()
    }

    /** Whether there is a live conversation to carry on (or to throw away). */
    fun hasConversation(context: Context, agent: Agent): Boolean = history(context, agent).isNotEmpty()
}

internal const val ROLE_USER = "user"
internal const val ROLE_ASSISTANT = "assistant"

/**
 * Asks the configured provider a question in the agent's voice, carrying on whatever conversation
 * [AgentAiMemory] is still holding.
 */
object AgentAiService {

    private const val TAG = "AgentAiService"

    // Swap either of these for a cheaper/faster model if the answers don't need to be this good
    private const val OPENAI_MODEL = "gpt-4o-mini"
    private const val ANTHROPIC_MODEL = "claude-opus-5"
    private const val ANTHROPIC_VERSION = "2023-06-01"
    // Routes a refused answer to another model instead of coming back empty handed
    private const val ANTHROPIC_FALLBACK_BETA = "server-side-fallback-2026-07-01"

    private val gson = Gson()

    fun systemPrompt(agent: Agent): String = buildString {
        append("You are ")
        append(agent.name)
        if (agent.persona.isNotEmpty()) {
            append(", ")
            append(agent.persona)
        }
        append(" You live on a Windows desktop and answer in character.")
        append(" Answer in one or two short sentences - it has to fit in a small speech bubble,")
        append(" and it gets read out by a 90s speech synthesiser, so write plain spoken text:")
        append(" no markdown, no lists, no emoji, no stage directions.")
    }

    /**
     * Runs the request off the main thread and hands the answer back on it. [onError] gets a line
     * that is safe to show in the bubble as-is.
     */
    fun ask(
        context: Context,
        agent: Agent,
        question: String,
        onReply: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val provider = AgentAiSettings.provider(context)
        val apiKey = AgentAiSettings.apiKey(context, provider)
        if (apiKey.isEmpty()) {
            onError("No ${provider.label} API key is set.")
            return
        }

        // Read on the main thread so the request thread works from a fixed transcript
        val history = AgentAiMemory.history(context, agent)

        val handler = Handler(Looper.getMainLooper())
        Thread {
            try {
                val reply = when (provider) {
                    AiProvider.OPENAI -> askOpenAi(apiKey, agent, question, history)
                    AiProvider.ANTHROPIC -> askAnthropic(apiKey, agent, question, history)
                }
                handler.post {
                    if (reply.isBlank()) {
                        onError("I didn't get an answer back.")
                    } else {
                        // Only a real answer becomes part of the conversation
                        AgentAiMemory.record(context, agent, question, reply)
                        onReply(reply)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "AI request to ${provider.label} failed", e)
                handler.post { onError(e.message ?: "I couldn't reach the AI.") }
            }
        }.start()
    }

    private fun askOpenAi(
        apiKey: String,
        agent: Agent,
        question: String,
        history: List<AgentAiMemory.Turn>
    ): String {
        val messages = mutableListOf(mapOf("role" to "system", "content" to systemPrompt(agent)))
        history.forEach { messages += mapOf("role" to it.role, "content" to it.text) }
        messages += mapOf("role" to ROLE_USER, "content" to question)

        val body = JsonObject().apply {
            addProperty("model", OPENAI_MODEL)
            addProperty("max_tokens", 300)
            add("messages", gson.toJsonTree(messages))
        }

        val response = post(
            url = "https://api.openai.com/v1/chat/completions",
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            body = body
        )

        val message = response.getAsJsonArray("choices")?.firstOrNull()
            ?.asJsonObject?.getAsJsonObject("message")
        return message?.get("content")?.asString?.trim() ?: ""
    }

    private fun askAnthropic(
        apiKey: String,
        agent: Agent,
        question: String,
        history: List<AgentAiMemory.Turn>
    ): String {
        val messages = history.map { mapOf("role" to it.role, "content" to it.text) } +
            mapOf("role" to ROLE_USER, "content" to question)

        val body = JsonObject().apply {
            addProperty("model", ANTHROPIC_MODEL)
            // Thinking is on by default and shares this budget, so leave it room; low effort
            // keeps a one-liner from being deliberated over
            addProperty("max_tokens", 4096)
            addProperty("system", systemPrompt(agent))
            add("output_config", JsonObject().apply { addProperty("effort", "low") })
            addProperty("fallbacks", "default")
            add("messages", gson.toJsonTree(messages))
        }

        val response = post(
            url = "https://api.anthropic.com/v1/messages",
            headers = mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to ANTHROPIC_VERSION,
                "anthropic-beta" to ANTHROPIC_FALLBACK_BETA
            ),
            body = body
        )

        if (response.get("stop_reason")?.asString == "refusal") {
            return "I'd rather not answer that one."
        }

        // The answer arrives as blocks; thinking blocks come back empty, so only the text ones count
        return response.getAsJsonArray("content")
            ?.mapNotNull { block ->
                val obj = block.asJsonObject
                if (obj.get("type")?.asString == "text") obj.get("text")?.asString else null
            }
            ?.joinToString(" ")
            ?.trim()
            ?: ""
    }

    private fun post(url: String, headers: Map<String, String>, body: JsonObject): JsonObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 60000
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }

        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                throw Exception(errorMessage(code, text))
            }
            return JsonParser.parseString(text).asJsonObject
        } finally {
            connection.disconnect()
        }
    }

    /** Both providers wrap the reason in {"error": {"message": ...}}, so show that when it's there. */
    private fun errorMessage(code: Int, payload: String): String {
        val reason = try {
            JsonParser.parseString(payload).asJsonObject
                .getAsJsonObject("error")?.get("message")?.asString
        } catch (e: Exception) {
            null
        }
        return reason ?: "The AI said no ($code)."
    }
}
