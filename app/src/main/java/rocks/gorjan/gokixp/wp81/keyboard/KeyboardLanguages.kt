package rocks.gorjan.gokixp.wp81.keyboard

import android.content.Context
import android.os.Build
import android.view.inputmethod.InputMethodManager
import rocks.gorjan.gokixp.theme.ThemeManager

/**
 * Which languages the keyboard offers, and telling Android about it.
 *
 * Two separate things have to agree and neither is derivable from the other. The setting is
 * what the user chose. Android's *enabled subtypes* are what the system will let the globe in
 * the navigation bar cycle through - and by default it decides that for itself, enabling only
 * the subtypes that match the phone's own languages. So a Macedonian layout on an English
 * phone is declared, registered, and never offered: holding the globe shows English alone.
 *
 * [applyToSystem] is the bridge. It is the only way a keyboard can have a language list of its
 * own that the rest of the system honours.
 */
internal object KeyboardLanguages {

    /** The layouts the user has turned on, in the order the keyboard should cycle them. */
    fun enabled(themeManager: ThemeManager): List<KeyboardLayout> {
        val chosen = themeManager.getWP81KeyboardLanguages()
        val kept = Layouts.ALL_LANGUAGES.filter { it.id in chosen }
        // A list that ended up empty - a stale setting naming a layout that no longer ships -
        // would leave the keyboard with no letters at all.
        return kept.ifEmpty { listOf(Layouts.EN_QWERTY) }
    }

    /** Whether [layout] is one of them. */
    fun isEnabled(themeManager: ThemeManager, layout: KeyboardLayout): Boolean =
        layout.id in themeManager.getWP81KeyboardLanguages()

    /**
     * Turns [layout] on or off, and tells the system.
     *
     * @return false when the change was refused, which happens only for turning off the last
     *   remaining language.
     */
    fun setEnabled(
        context: Context,
        themeManager: ThemeManager,
        layout: KeyboardLayout,
        on: Boolean
    ): Boolean {
        val current = themeManager.getWP81KeyboardLanguages().toMutableSet()
        if (on) {
            current.add(layout.id)
        } else {
            if (current.size <= 1) return false
            current.remove(layout.id)
        }
        themeManager.setWP81KeyboardLanguages(current)
        applyToSystem(context, themeManager)
        return true
    }

    /**
     * Makes Android's enabled-subtype list match the setting.
     *
     * Android 14 and up only - [InputMethodManager.setExplicitlyEnabledInputMethodSubtypes]
     * is where this became possible for an input method to do for itself. Below that the list
     * is the system's to decide and the user has to go through its own subtype screen, which
     * is why this fails quietly rather than reporting: there is nothing to be done about it
     * and nothing useful to say.
     */
    fun applyToSystem(context: Context, themeManager: ThemeManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        try {
            val imm = context.getSystemService(InputMethodManager::class.java) ?: return
            val self = imm.inputMethodList.firstOrNull { it.packageName == context.packageName }
                ?: return

            val wanted = enabled(themeManager).map { it.language }.toSet()
            val hashes = (0 until self.subtypeCount)
                .map { self.getSubtypeAt(it) }
                .filter { subtype ->
                    // The declared subtypes carry a full tag - `en-US` - where a layout knows
                    // only the language. Region is not part of the choice: `en-US` and `en-GB`
                    // are the same twenty-six keys.
                    val tag = @Suppress("DEPRECATION") subtype.languageTag.ifEmpty { subtype.locale }
                    tag.substringBefore('-').substringBefore('_').lowercase() in wanted
                }
                .map { it.hashCode() }
                .toIntArray()

            if (hashes.isNotEmpty()) {
                imm.setExplicitlyEnabledInputMethodSubtypes(self.id, hashes)
            }
        } catch (e: Exception) {
            // A keyboard that cannot rearrange the system's language list still types.
        }
    }
}
