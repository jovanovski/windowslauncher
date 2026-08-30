package rocks.gorjan.gokixp.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * The launcher's way onto a car screen.
 *
 * Android Auto never shows another app's own pixels: the head unit runs Google's UI and
 * apps hand it templates to fill in. The one exception is a navigation app, which is given
 * a real Surface so that it can draw a map on it. That Surface is the whole point of this
 * service - it is the only place a Start screen can be painted, so the app registers under
 * the navigation category in order to be handed one. See [CarStartScreen].
 */
class GokiCarAppService : CarAppService() {

    /**
     * Which hosts are allowed to drive this service.
     *
     * The strict validator checks the host against signatures Google publishes. This app
     * is sideloaded onto one phone rather than shipped, and spends as much time talking to
     * the desktop head unit as to a car, so it takes whichever host asks.
     */
    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = CarStartScreen(carContext)
    }
}
