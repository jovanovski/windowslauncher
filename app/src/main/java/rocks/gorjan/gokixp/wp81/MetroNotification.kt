package rocks.gorjan.gokixp.wp81

import android.app.Notification
import android.content.Context
import android.os.Bundle
import androidx.core.app.NotificationCompat
import rocks.gorjan.gokixp.theme.ThemeManager

/**
 * The look every notification this shell posts shares: the accent, and the program's own
 * mark rather than the launcher's.
 *
 * A notification has always had a *small icon*, the silhouette in the status bar, which is
 * what `setSmallIcon` sets. From **Android 17 (API 37)** the platform stopped using it on
 * the notification as well: the body now carries the app's *launcher* icon, in a circle,
 * and the small icon is left to the status bar alone.
 *
 * For an ordinary app that is an improvement - one recognisable mark instead of a white
 * blob. For this one it is not. A launcher is not one program, it is a shell full of them,
 * and its notifications come from Alarms, People, Messages and Zune. Under the new rule all
 * four arrived wearing the same Windows logo, which is the one thing they have in common
 * and the least useful thing about any of them.
 *
 * [Notification.EXTRA_PREFER_SMALL_ICON] is the way back, and it is exactly this narrow: it
 * asks the platform to keep using the small icon on the notification. Written as its literal
 * string rather than the constant because the constant only exists from API 37 and this app
 * is built against 36 - the key works at any compile level, and Android 16 and below ignore
 * an extra they do not know, so there is no version check to get wrong and nothing to undo
 * when the compile level moves.
 *
 * With the accent set alongside it, the circle in the shade comes out as the shell's accent
 * with the white glyph on it, which is what a Windows Phone notification looked like.
 *
 * There are two of these because the shell posts notifications both ways: the platform
 * builder where a notification needs something only the platform has - CallStyle, a
 * full-screen intent - and the support builder everywhere else. They do the same thing.
 */
fun Notification.Builder.metroLook(context: Context): Notification.Builder = this
    .setColor(ThemeManager(context).getWP81Accent())
    .addExtras(Bundle().apply { putBoolean(PREFER_SMALL_ICON, true) })

/** The same, for the support builder. See above. */
fun NotificationCompat.Builder.metroLook(context: Context): NotificationCompat.Builder = this
    .setColor(ThemeManager(context).getWP81Accent())
    .addExtras(Bundle().apply { putBoolean(PREFER_SMALL_ICON, true) })

/** `Notification.EXTRA_PREFER_SMALL_ICON`, which this app cannot yet name directly. */
private const val PREFER_SMALL_ICON = "android.app.preferSmallIcon"
