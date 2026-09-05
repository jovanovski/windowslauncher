package rocks.gorjan.gokixp.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The theme a stored preference resolves to.
 *
 * Worth pinning because [AppTheme.fromString] is the *only* place the Windows Phone
 * fallback lives, and it has to be: that string can arrive from a cloud backup restore, a
 * Google Drive sync, an imported .reg or the Registry Editor long after any one-shot
 * migration would have run. If this ever goes back to falling through to the `else` arm,
 * everyone who was running the phone theme silently lands on XP instead of Vista.
 */
class AppThemeTest {

    @Test
    fun `the three desktop themes round-trip through their stored names`() {
        for (theme in AppTheme.all()) {
            assertEquals(theme, AppTheme.fromString(theme.toString()))
        }
    }

    @Test
    fun `both spellings of the phone theme land on Vista`() {
        // It was called 8.1 for a while, and that string is in real preferences.
        assertEquals(AppTheme.WindowsVista, AppTheme.fromString("Windows Phone 8"))
        assertEquals(AppTheme.WindowsVista, AppTheme.fromString("Windows Phone 8.1"))
    }

    @Test
    fun `the phone theme is no longer offered as a choice`() {
        assertFalse(AppTheme.all().contains(AppTheme.WindowsPhone81))
    }

    @Test
    fun `anything unrecognised still falls back to XP`() {
        // Deliberately not Vista: this arm covers fresh installs and genuinely unknown
        // values, and XP is what they have always got.
        assertEquals(AppTheme.WindowsXP, AppTheme.fromString(null))
        assertEquals(AppTheme.WindowsXP, AppTheme.fromString(""))
        assertEquals(AppTheme.WindowsXP, AppTheme.fromString("Windows 11"))
    }

    @Test
    fun `the phone theme keeps its own icon key so its icons stay identifiable`() {
        // MainActivity.RETIRED_CUSTOM_ICON_KEYS names this key to keep
        // pruneUnusedImportedIcons from deleting a phone user's imported icon files.
        assertEquals("custom_icons_wp8", AppTheme.WindowsPhone81.customIconsKey)
        for (theme in AppTheme.all()) {
            assertFalse(theme.customIconsKey == AppTheme.WindowsPhone81.customIconsKey)
        }
    }
}
