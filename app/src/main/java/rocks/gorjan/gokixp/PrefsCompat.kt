package rocks.gorjan.gokixp

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit

/**
 * Numeric getters that tolerate a value stored under the wrong numeric type.
 *
 * Settings backups are plain JSON, and JSON has a single number type, so a restore can put a
 * Float where an Int belongs (or the reverse) - [PrefsBackup] can only get this exactly right
 * for backups new enough to carry a type map. SharedPreferences.getInt/getLong/getFloat throw
 * ClassCastException in that case, which crashed the properties dialog.
 *
 * These read the raw value back, coerce it, and rewrite the entry with the type the caller
 * expects, so a restored-from-backup preference repairs itself on first read.
 */
private const val TAG = "PrefsCompat"

fun SharedPreferences.getSafeInt(key: String, defaultValue: Int): Int {
    return try {
        getInt(key, defaultValue)
    } catch (e: ClassCastException) {
        val stored = all[key]
        val repaired = (stored as? Number)?.toInt()
        if (repaired == null) {
            Log.w(TAG, "Dropping $key: ${stored?.javaClass?.simpleName} is not usable as an Int")
            edit { remove(key) }
            defaultValue
        } else {
            Log.w(TAG, "Rewriting $key as Int (was ${stored.javaClass.simpleName})")
            edit { putInt(key, repaired) }
            repaired
        }
    }
}

fun SharedPreferences.getSafeLong(key: String, defaultValue: Long): Long {
    return try {
        getLong(key, defaultValue)
    } catch (e: ClassCastException) {
        val stored = all[key]
        val repaired = (stored as? Number)?.toLong()
        if (repaired == null) {
            Log.w(TAG, "Dropping $key: ${stored?.javaClass?.simpleName} is not usable as a Long")
            edit { remove(key) }
            defaultValue
        } else {
            Log.w(TAG, "Rewriting $key as Long (was ${stored.javaClass.simpleName})")
            edit { putLong(key, repaired) }
            repaired
        }
    }
}

fun SharedPreferences.getSafeFloat(key: String, defaultValue: Float): Float {
    return try {
        getFloat(key, defaultValue)
    } catch (e: ClassCastException) {
        val stored = all[key]
        val repaired = (stored as? Number)?.toFloat()
        if (repaired == null) {
            Log.w(TAG, "Dropping $key: ${stored?.javaClass?.simpleName} is not usable as a Float")
            edit { remove(key) }
            defaultValue
        } else {
            Log.w(TAG, "Rewriting $key as Float (was ${stored.javaClass.simpleName})")
            edit { putFloat(key, repaired) }
            repaired
        }
    }
}
