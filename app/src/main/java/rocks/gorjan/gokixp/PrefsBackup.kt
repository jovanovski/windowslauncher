package rocks.gorjan.gokixp

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlin.math.floor

/**
 * Serialises the launcher's SharedPreferences to JSON and back.
 *
 * Shared by the local-file and Google Drive paths so they can't drift apart - they used to
 * have two different (and differently wrong) ideas about how to turn a JSON number back into
 * a preference.
 */
object PrefsBackup {

    private const val TAG = "PrefsBackup"

    /**
     * Reserved key holding the SharedPreferences type of every other entry.
     *
     * JSON has a single number type, so Gson hands every number back as a Double and the
     * restore has no way to tell an Int from a Long from a Float. Guessing is what stored
     * `christmas_lights_margin` as a Float and made getInt throw. Backups written from here
     * carry the real types; ones written before this existed have no [TYPES_KEY] and fall
     * back to [putGuessedNumber].
     */
    private const val TYPES_KEY = "__types__"

    fun toJson(prefs: SharedPreferences): String {
        val values = mutableMapOf<String, Any?>()
        val types = mutableMapOf<String, String>()

        prefs.all.forEach { (key, value) ->
            if (key == TYPES_KEY) return@forEach
            val type = when (value) {
                is Boolean -> "Boolean"
                is Int -> "Int"
                is Long -> "Long"
                is Float -> "Float"
                is String -> "String"
                is Set<*> -> "StringSet"
                else -> {
                    Log.w(TAG, "Skipping $key: unsupported type ${value?.javaClass?.simpleName}")
                    return@forEach
                }
            }
            values[key] = value
            types[key] = type
        }

        values[TYPES_KEY] = types
        return GsonBuilder().setPrettyPrinting().create().toJson(values)
    }

    /**
     * Replaces everything in [prefs] with the contents of [json]. Throws if [json] isn't a
     * JSON object, so callers can report a bad backup instead of wiping settings.
     */
    fun restore(prefs: SharedPreferences, json: String) {
        val parsed = Gson().fromJson(json, Map::class.java) as? Map<*, *>
            ?: throw IllegalArgumentException("Backup is not a JSON object")

        val types = parsed[TYPES_KEY] as? Map<*, *>
        if (types == null) {
            Log.w(TAG, "Backup has no type map - falling back to inferring number types")
        }

        prefs.edit {
            clear()
            parsed.forEach { (rawKey, value) ->
                val key = rawKey as? String ?: return@forEach
                if (key == TYPES_KEY) return@forEach

                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Number -> putNumber(key, value, types?.get(key) as? String)
                    // Gson turns a JSON array back into a List
                    is List<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                    null -> Unit
                    else -> Log.w(TAG, "Skipping $key: unsupported type ${value.javaClass.simpleName}")
                }
            }
        }
    }

    private fun SharedPreferences.Editor.putNumber(key: String, value: Number, declaredType: String?) {
        when (declaredType) {
            "Int" -> putInt(key, value.toInt())
            "Long" -> putLong(key, value.toLong())
            "Float" -> putFloat(key, value.toFloat())
            else -> putGuessedNumber(key, value)
        }
    }

    /**
     * Best effort for backups written before the type map existed. A whole number is far more
     * likely to be an Int or a Long than a Float, so that's the guess. A Float that happens to
     * hold a whole number (an agent parked at x=100.0, say) lands here as an Int - `getSafeFloat`
     * repairs that on the first read rather than throwing.
     */
    private fun SharedPreferences.Editor.putGuessedNumber(key: String, value: Number) {
        val asDouble = value.toDouble()
        when {
            asDouble.isNaN() || asDouble.isInfinite() || asDouble != floor(asDouble) ->
                putFloat(key, value.toFloat())
            asDouble >= Int.MIN_VALUE.toDouble() && asDouble <= Int.MAX_VALUE.toDouble() ->
                putInt(key, value.toInt())
            else ->
                putLong(key, value.toLong())
        }
    }
}
