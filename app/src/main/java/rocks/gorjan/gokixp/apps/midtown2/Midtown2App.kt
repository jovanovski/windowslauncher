package rocks.gorjan.gokixp.apps.midtown2

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider
import rocks.gorjan.gokixp.R
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Midtown Madness 2 simulator app
 */
class Midtown2App(
    private val context: Context,
    private val onRequestLocationPermission: () -> Unit,
    private val onShowNotification: (String, String, (() -> Unit)?) -> Unit,
    private val onSoundPlay: () -> Unit
) {
    companion object {
        private const val TAG = "Midtown2App"
        private const val CHECKPOINT_INTERVAL_WALK = 100 // meters
        private const val CHECKPOINT_INTERVAL_BIKE = 200 // meters
        private const val CHECKPOINT_INTERVAL_CAR = 500 // meters
        private const val CHECKPOINT_THRESHOLD_WALK = 20f // meters
        private const val CHECKPOINT_THRESHOLD_BIKE = 30f // meters
        private const val CHECKPOINT_THRESHOLD_CAR = 50f // meters
        private const val MARKER_SIZE_DP = 48
    }

    enum class TransportMode { WALK, BIKE, CAR }
    enum class RaceState { IDLE, ROUTE_READY, RACING, FINISHED }

    // UI references
    private var mapView: MapView? = null
    private var timerText: TextView? = null
    private var checkpointsText: TextView? = null
    private var statusText: TextView? = null
    private var startButton: ImageView? = null
    private var modeWalkButton: Button? = null
    private var modeBikeButton: Button? = null
    private var modeCarButton: Button? = null
    private var modeSelectorLayout: View? = null
    private var splashScreen: View? = null
    private var topBar: View? = null
    private var controlsLayout: View? = null

    // State
    private var transportMode = TransportMode.CAR
    private var raceState = RaceState.IDLE
    private var destination: GeoPoint? = null
    private var finishLinePoint: GeoPoint? = null  // Actual finish line from route (last point)
    private var currentLocation: GeoPoint? = null
    private var routePoints: List<GeoPoint> = emptyList()
    private var checkpoints: MutableList<CheckpointData> = mutableListOf()
    private var passedCheckpointsCount = 0
    private var hasInitialLocationFix = false

    // Markers and overlays
    private var destinationMarker: Marker? = null
    private var userMarker: Marker? = null
    private val checkpointMarkers = mutableListOf<Marker>()
    private var routeOverlay: Polyline? = null

    // Timer
    private val handler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null
    private var raceStartTime = 0L
    private var elapsedTime = 0L

    // Location
    private var locationProvider: GpsMyLocationProvider? = null
    private var lastBearing = 0f
    private var lastSpeed = 0f
    private var compassBearing = 0f

    // Sensors
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null
    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var currentMapOrientation = 0f
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.size)
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.size)
                }
            }
            updateCompassBearing()

            // Apply smooth compass rotation during race when static
            if (raceState == RaceState.RACING && lastSpeed <= 0.5f) {
                applySmoothCompassRotation()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // Media players
    private var menuMusicPlayer: MediaPlayer? = null
    private var raceMusicPlayer: MediaPlayer? = null
    private var lastCheckpointMusicPlayer: MediaPlayer? = null
    private var checkpointSoundPlayer: MediaPlayer? = null
    private var finishSoundPlayer: MediaPlayer? = null
    private var announcerPlayer: MediaPlayer? = null

    // Bitmaps for markers
    private var checkpointBitmap: Bitmap? = null
    private var finishBitmap: Bitmap? = null
    private var beetleBitmap: Bitmap? = null

    data class CheckpointData(
        val point: GeoPoint,
        var passed: Boolean = false,
        val marker: Marker
    )

    /**
     * Initialize the app UI
     */
    fun setupApp(contentView: View): View {
        // Configure OSMDroid
        Configuration.getInstance().userAgentValue = context.packageName

        // Get references to views
        mapView = contentView.findViewById(R.id.midtown_map)
        timerText = contentView.findViewById(R.id.midtown_timer)
        checkpointsText = contentView.findViewById(R.id.midtown_checkpoints)
        statusText = contentView.findViewById(R.id.midtown_status)
        startButton = contentView.findViewById(R.id.midtown_start_button)
        modeWalkButton = contentView.findViewById(R.id.midtown_mode_walk)
        modeBikeButton = contentView.findViewById(R.id.midtown_mode_bike)
        modeCarButton = contentView.findViewById(R.id.midtown_mode_car)
        modeSelectorLayout = contentView.findViewById(R.id.midtown_mode_selector)
        splashScreen = contentView.findViewById(R.id.midtown_splash_screen)
        topBar = contentView.findViewById(R.id.midtown_top_bar)
        controlsLayout = contentView.findViewById(R.id.midtown_controls)

        // Setup splash screen tap to dismiss
        splashScreen?.setOnClickListener {
            playButtonPressSound()
            hideSplashScreen()
        }

        // Load marker bitmaps
        loadMarkerBitmaps()

        // Setup map
        setupMap()

        // Setup button listeners
        setupButtonListeners()

        // Check permissions
        if (!hasLocationPermission()) {
            onRequestLocationPermission()
        } else {
            startLocationTracking()
        }

        // Setup sensors for compass
        setupSensors()

        // Start menu music
        playMenuMusic()

        return contentView
    }

    private fun setupSensors() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        startSensorListening()
    }

    private fun startSensorListening() {
        accelerometer?.let {
            sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        magnetometer?.let {
            sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun stopSensorListening() {
        sensorManager?.unregisterListener(sensorListener)
    }

    private fun updateCompassBearing() {
        if (SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            compassBearing = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            if (compassBearing < 0) compassBearing += 360f
        }
    }

    private fun applySmoothCompassRotation() {
        val targetOrientation = -compassBearing

        // Calculate the shortest rotation path (handle 360/0 wraparound)
        var delta = targetOrientation - currentMapOrientation
        if (delta > 180) delta -= 360
        if (delta < -180) delta += 360

        // Dead zone: ignore small rotations (less than 5 degrees) to prevent jitter
        if (abs(delta) < 5f) return

        // Smooth interpolation (lerp factor of 0.1 for smooth movement)
        currentMapOrientation += delta * 0.1f

        // Normalize to 0-360 range
        if (currentMapOrientation > 180) currentMapOrientation -= 360
        if (currentMapOrientation < -180) currentMapOrientation += 360

        mapView?.mapOrientation = currentMapOrientation
        mapView?.invalidate()
    }

    private fun hideSplashScreen() {
        splashScreen?.visibility = View.GONE
        mapView?.visibility = View.VISIBLE
        topBar?.visibility = View.VISIBLE
        controlsLayout?.visibility = View.VISIBLE
        statusText?.visibility = View.VISIBLE
    }

    private fun loadMarkerBitmaps() {
        val markerSizePx = (MARKER_SIZE_DP * context.resources.displayMetrics.density).toInt()

        try {
            // Load and scale checkpoint image
            val checkpointOriginal = BitmapFactory.decodeResource(context.resources, R.drawable.midtown_checkpoint)
            checkpointBitmap = Bitmap.createScaledBitmap(checkpointOriginal, markerSizePx, markerSizePx, true)

            // Load and scale finish image
            val finishOriginal = BitmapFactory.decodeResource(context.resources, R.drawable.midtown_finish)
            finishBitmap = Bitmap.createScaledBitmap(finishOriginal, markerSizePx, markerSizePx, true)

            // Load and scale beetle image
            val beetleOriginal = BitmapFactory.decodeResource(context.resources, R.drawable.midtown_beetle)
            beetleBitmap = Bitmap.createScaledBitmap(beetleOriginal, markerSizePx, markerSizePx, true)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading marker bitmaps", e)
        }
    }

    private fun setupMap() {
        mapView?.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)

            // Try to get last known location and center map there
            if (hasLocationPermission()) {
                try {
                    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                    val lastKnown = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                        ?: locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)

                    if (lastKnown != null) {
                        val startPoint = GeoPoint(lastKnown.latitude, lastKnown.longitude)
                        currentLocation = startPoint
                        hasInitialLocationFix = true
                        controller.setCenter(startPoint)
                        updateUserMarker(startPoint, 0f)
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Security exception getting last known location", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting last known location", e)
                }
            }

            // Set up gesture detector for single tap only (not drag or pinch)
            val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (raceState == RaceState.IDLE || raceState == RaceState.ROUTE_READY) {
                        val projection = projection
                        val geoPoint = projection.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint
                        setDestination(geoPoint)
                        return true
                    }
                    return false
                }
            })

            setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                false // Return false to allow map to handle pan/zoom
            }
        }
    }

    private fun setupButtonListeners() {
        // Transport mode buttons
        modeWalkButton?.setOnClickListener {
            playButtonPressSound()
            setTransportMode(TransportMode.WALK)
        }

        modeBikeButton?.setOnClickListener {
            playButtonPressSound()
            setTransportMode(TransportMode.BIKE)
        }

        modeCarButton?.setOnClickListener {
            playButtonPressSound()
            setTransportMode(TransportMode.CAR)
        }

        // Start/Reset button
        startButton?.setOnClickListener {
            playButtonPressSound()
            when (raceState) {
                RaceState.IDLE -> {
                    // Already prompting to select destination
                }
                RaceState.ROUTE_READY -> {
                    startRace()
                }
                RaceState.RACING, RaceState.FINISHED -> {
                    resetRace()
                }
            }
        }
    }

    private fun setTransportMode(mode: TransportMode) {
        transportMode = mode
        updateModeButtonStyles()

        // Recalculate route if destination is set
        if (destination != null && currentLocation != null) {
            calculateRoute(currentLocation!!, destination!!)
        }
    }

    private fun updateModeButtonStyles() {
        val activeColor = Color.parseColor("#4a4a6a")
        val inactiveColor = Color.parseColor("#4a4a6a")
        val activeTextColor = Color.parseColor("#FFD700") // Yellow for selected
        val inactiveTextColor = Color.WHITE

        modeWalkButton?.setBackgroundColor(if (transportMode == TransportMode.WALK) activeColor else inactiveColor)
        modeBikeButton?.setBackgroundColor(if (transportMode == TransportMode.BIKE) activeColor else inactiveColor)
        modeCarButton?.setBackgroundColor(if (transportMode == TransportMode.CAR) activeColor else inactiveColor)

        modeWalkButton?.setTextColor(if (transportMode == TransportMode.WALK) activeTextColor else inactiveTextColor)
        modeBikeButton?.setTextColor(if (transportMode == TransportMode.BIKE) activeTextColor else inactiveTextColor)
        modeCarButton?.setTextColor(if (transportMode == TransportMode.CAR) activeTextColor else inactiveTextColor)
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun onPermissionResult(granted: Boolean) {
        if (granted) {
            startLocationTracking()
        } else {
            onShowNotification(
                "Missing Permission",
                "Midtown Madness 2 needs your location to work, tap here to allow"
            ) {
                // Open app settings
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.fromParts("package", context.packageName, null)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            }
        }
    }

    private fun startLocationTracking() {
        if (!hasLocationPermission()) return

        locationProvider = GpsMyLocationProvider(context)
        locationProvider?.startLocationProvider(object : IMyLocationConsumer {
            override fun onLocationChanged(location: Location?, source: IMyLocationProvider?) {
                location?.let {
                    val newLocation = GeoPoint(it.latitude, it.longitude)
                    val newBearing = if (it.hasBearing()) it.bearing else lastBearing
                    val newSpeed = if (it.hasSpeed()) it.speed else 0f

                    // Update bearing for map rotation
                    if (it.hasBearing()) {
                        lastBearing = it.bearing
                    }
                    lastSpeed = newSpeed

                    handler.post {
                        updateUserLocation(newLocation, newBearing, newSpeed)
                    }
                }
            }
        })

        // Center map on current location when available
        val lastLocation = locationProvider?.lastKnownLocation
        if (lastLocation != null) {
            currentLocation = GeoPoint(lastLocation.latitude, lastLocation.longitude)
            mapView?.controller?.animateTo(currentLocation)
            updateUserMarker(currentLocation!!, lastBearing)
        }
    }

    private fun updateUserLocation(location: GeoPoint, bearing: Float, speed: Float = 0f) {
        // Only update location tracking during a race
        if (raceState != RaceState.RACING) {
            // Just store the location for when we need it, but don't update UI
            currentLocation = location

            // Only update marker on first fix or if marker doesn't exist
            if (!hasInitialLocationFix) {
                hasInitialLocationFix = true
                updateUserMarker(location, bearing)
                mapView?.controller?.animateTo(location)
            }
            return
        }

        currentLocation = location
        updateUserMarker(location, bearing)

        // Only apply GPS-based rotation when moving
        // Compass rotation is handled smoothly in sensor callback when static
        if (speed > 0.5f) {
            val targetOrientation = -bearing

            // Smooth interpolation for GPS bearing too
            var delta = targetOrientation - currentMapOrientation
            if (delta > 180) delta -= 360
            if (delta < -180) delta += 360
            currentMapOrientation += delta * 0.15f

            if (currentMapOrientation > 180) currentMapOrientation -= 360
            if (currentMapOrientation < -180) currentMapOrientation += 360

            mapView?.mapOrientation = currentMapOrientation
        }

        mapView?.controller?.animateTo(location)

        // Check checkpoints
        checkCheckpoints(location)
    }

    private fun updateUserMarker(location: GeoPoint, bearing: Float) {
        if (userMarker == null) {
            userMarker = Marker(mapView).apply {
                position = location
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                beetleBitmap?.let {
                    icon = BitmapDrawable(context.resources, it)
                }
                title = "You"
            }
            mapView?.overlays?.add(userMarker)
        } else {
            userMarker?.position = location
        }

        // Don't rotate the car marker since we're rotating the whole map
        mapView?.invalidate()
    }

    private fun setDestination(point: GeoPoint) {
        destination = point

        // Remove old destination marker
        destinationMarker?.let { mapView?.overlays?.remove(it) }

        // Add temporary destination marker (will be updated to actual route end)
        destinationMarker = Marker(mapView).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            finishBitmap?.let {
                icon = BitmapDrawable(context.resources, it)
            }
            title = "Finish"
        }
        mapView?.overlays?.add(destinationMarker)
        mapView?.invalidate()

        statusText?.text = "Calculating route..."

        // Calculate route
        currentLocation?.let { start ->
            calculateRoute(start, point)
        } ?: run {
            statusText?.text = "Waiting for GPS location..."
        }
    }

    private fun updateFinishMarker(point: GeoPoint) {
        // Update the finish marker to the actual route end point
        destinationMarker?.position = point
        mapView?.invalidate()
    }

    private fun calculateRoute(start: GeoPoint, end: GeoPoint) {
        thread {
            try {
                val profile = when (transportMode) {
                    TransportMode.WALK -> "foot"
                    TransportMode.BIKE -> "bike"
                    TransportMode.CAR -> "car"
                }

                val url = URL(
                    "https://router.project-osrm.org/route/v1/$profile/" +
                    "${start.longitude},${start.latitude};" +
                    "${end.longitude},${end.latitude}" +
                    "?overview=full&geometries=geojson"
                )

                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val response = BufferedReader(InputStreamReader(connection.inputStream)).use {
                    it.readText()
                }

                // Parse GeoJSON response
                val points = parseRouteResponse(response)

                handler.post {
                    if (points.isNotEmpty()) {
                        routePoints = points
                        // Set the actual finish line to the last point of the route
                        finishLinePoint = points.last()
                        // Update the finish marker to the actual route end point
                        updateFinishMarker(finishLinePoint!!)
                        drawRoute(points)
                        placeCheckpoints(points)
                        raceState = RaceState.ROUTE_READY
                        updateUI()
                    } else {
                        statusText?.text = "Could not calculate route. Try a different destination."
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error calculating route", e)
                handler.post {
                    statusText?.text = "Error calculating route. Check your connection."
                }
            }
        }
    }

    private fun parseRouteResponse(response: String): List<GeoPoint> {
        val points = mutableListOf<GeoPoint>()
        try {
            // Simple JSON parsing for coordinates array
            val coordsStart = response.indexOf("\"coordinates\":")
            if (coordsStart != -1) {
                val arrayStart = response.indexOf("[[", coordsStart)
                val arrayEnd = response.indexOf("]]", arrayStart) + 2

                if (arrayStart != -1 && arrayEnd > arrayStart) {
                    val coordsArray = response.substring(arrayStart, arrayEnd)
                    // Parse coordinate pairs
                    val pairs = coordsArray.replace("[[", "").replace("]]", "").split("],[")
                    for (pair in pairs) {
                        val coords = pair.split(",")
                        if (coords.size >= 2) {
                            val lon = coords[0].trim().toDoubleOrNull()
                            val lat = coords[1].trim().toDoubleOrNull()
                            if (lon != null && lat != null) {
                                points.add(GeoPoint(lat, lon))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing route response", e)
        }
        return points
    }

    private fun drawRoute(points: List<GeoPoint>) {
        // Remove old route
        routeOverlay?.let { mapView?.overlays?.remove(it) }

        // Draw new route
        routeOverlay = Polyline().apply {
            setPoints(points)
            outlinePaint.color = Color.parseColor("#FF4444")
            outlinePaint.strokeWidth = 8f
        }
        mapView?.overlays?.add(0, routeOverlay) // Add at bottom so markers are on top
        mapView?.invalidate()
    }

    private fun placeCheckpoints(points: List<GeoPoint>) {
        // Clear old checkpoints
        for (marker in checkpointMarkers) {
            mapView?.overlays?.remove(marker)
        }
        checkpointMarkers.clear()
        checkpoints.clear()

        val interval = when (transportMode) {
            TransportMode.WALK -> CHECKPOINT_INTERVAL_WALK
            TransportMode.BIKE -> CHECKPOINT_INTERVAL_BIKE
            TransportMode.CAR -> CHECKPOINT_INTERVAL_CAR
        }

        var accumulatedDistance = 0.0
        var lastPoint = points.firstOrNull() ?: return

        for (i in 1 until points.size - 1) { // Exclude last point (that's the finish)
            val currentPoint = points[i]
            val distance = lastPoint.distanceToAsDouble(currentPoint)
            accumulatedDistance += distance

            if (accumulatedDistance >= interval) {
                // Place checkpoint
                val marker = Marker(mapView).apply {
                    position = currentPoint
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    checkpointBitmap?.let {
                        icon = BitmapDrawable(context.resources, it)
                    }
                    title = "Checkpoint ${checkpoints.size + 1}"
                }
                mapView?.overlays?.add(marker)
                checkpointMarkers.add(marker)
                checkpoints.add(CheckpointData(currentPoint, false, marker))

                accumulatedDistance = 0.0
            }
            lastPoint = currentPoint
        }

        mapView?.invalidate()
        updateCheckpointsText()
    }

    private fun startRace() {
        if (raceState != RaceState.ROUTE_READY) return

        raceState = RaceState.RACING
        raceStartTime = System.currentTimeMillis()
        passedCheckpointsCount = 0

        // Hide mode selector during race
        modeSelectorLayout?.visibility = View.GONE

        // Zoom in to 14 for better race view
        mapView?.controller?.setZoom(18.0)

        // Stop menu music and start race music
        stopAllMusic()
        playRaceMusic()

        // Play announcer start sound
        playAnnouncerStart()

        // Start timer
        startTimer()

        updateUI()
    }

    private fun startTimer() {
        timerRunnable = object : Runnable {
            override fun run() {
                if (raceState == RaceState.RACING) {
                    elapsedTime = System.currentTimeMillis() - raceStartTime
                    updateTimerText()
                    handler.postDelayed(this, 100)
                }
            }
        }
        handler.post(timerRunnable!!)
    }

    private fun updateTimerText() {
        val totalSeconds = elapsedTime / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val millis = (elapsedTime % 1000) / 10

        timerText?.text = String.format("%02d:%02d:%02d.%02d", hours, minutes, seconds, millis)
    }

    private fun checkCheckpoints(location: GeoPoint) {
        val threshold = when (transportMode) {
            TransportMode.WALK -> CHECKPOINT_THRESHOLD_WALK
            TransportMode.BIKE -> CHECKPOINT_THRESHOLD_BIKE
            TransportMode.CAR -> CHECKPOINT_THRESHOLD_CAR
        }

        // Check regular checkpoints
        for (checkpoint in checkpoints) {
            if (!checkpoint.passed) {
                val distance = location.distanceToAsDouble(checkpoint.point)
                if (distance <= threshold) {
                    checkpoint.passed = true
                    passedCheckpointsCount++
                    playCheckpointSound()

                    // Check if this is the last checkpoint
                    if (passedCheckpointsCount == checkpoints.size) {
                        // Switch to last checkpoint music
                        stopAllMusic()
                        playLastCheckpointMusic()
                    }

                    updateCheckpointsText()

                    // Visual feedback - change marker appearance
                    checkpoint.marker.alpha = 0.5f
                    mapView?.invalidate()
                }
            }
        }

        // Check finish line (use actual route end point, not user tap location)
        finishLinePoint?.let { finish ->
            val distanceToFinish = location.distanceToAsDouble(finish)
            Log.d(TAG, "Distance to finish: $distanceToFinish meters (threshold: $threshold)")
            if (distanceToFinish <= threshold) {
                finishRace()
            }
        }
    }

    private fun finishRace() {
        if (raceState != RaceState.RACING) return

        raceState = RaceState.FINISHED

        // Stop timer
        timerRunnable?.let { handler.removeCallbacks(it) }

        // Stop race music and play finish sound
        stopAllMusic()
        playFinishSound()

        // Play announcer end sound
        playAnnouncerEnd()

        // Reset map orientation
        mapView?.mapOrientation = 0f

        updateUI()
    }

    private fun resetRace() {
        raceState = RaceState.IDLE
        destination = null
        finishLinePoint = null
        routePoints = emptyList()
        passedCheckpointsCount = 0
        elapsedTime = 0L

        // Stop timer
        timerRunnable?.let { handler.removeCallbacks(it) }

        // Clear markers and overlays (but keep user marker)
        destinationMarker?.let { mapView?.overlays?.remove(it) }
        destinationMarker = null
        routeOverlay?.let { mapView?.overlays?.remove(it) }
        routeOverlay = null
        for (marker in checkpointMarkers) {
            mapView?.overlays?.remove(marker)
        }
        checkpointMarkers.clear()
        checkpoints.clear()

        // Reset map orientation
        mapView?.mapOrientation = 0f
        mapView?.controller?.setZoom(16.0)


        // Show mode selector
        modeSelectorLayout?.visibility = View.VISIBLE

        // Stop any music and start menu music
        stopAllMusic()
        playMenuMusic()

        mapView?.invalidate()
        updateUI()
    }

    private fun updateUI() {
        when (raceState) {
            RaceState.IDLE -> {
                startButton?.setImageResource(R.drawable.midtown_drive)
                startButton?.alpha = 0.5f
                startButton?.isClickable = false
                statusText?.text = "Tap on the map to select destination"
                statusText?.visibility = View.VISIBLE
                timerText?.text = "00:00:00.00"
            }
            RaceState.ROUTE_READY -> {
                startButton?.setImageResource(R.drawable.midtown_drive)
                startButton?.alpha = 1.0f
                startButton?.isClickable = true
                statusText?.text = "Route ready! Tap GO DRIVE to begin"
                statusText?.visibility = View.VISIBLE
            }
            RaceState.RACING -> {
                startButton?.setImageResource(R.drawable.midtown_reset)
                startButton?.alpha = 1.0f
                startButton?.isClickable = true
                statusText?.visibility = View.GONE
            }
            RaceState.FINISHED -> {
                startButton?.setImageResource(R.drawable.midtown_reset)
                startButton?.alpha = 1.0f
                startButton?.isClickable = true
                statusText?.text = "FINISH! Time: ${timerText?.text}"
                statusText?.visibility = View.VISIBLE
            }
        }

        updateCheckpointsText()
    }

    private fun updateCheckpointsText() {
        // Show 0/0 when no route is set, otherwise include finish line as a checkpoint (+1)
        if (raceState == RaceState.IDLE || checkpoints.isEmpty()) {
            checkpointsText?.text = "Checkpoints: 0/0"
        } else {
            val totalCheckpoints = checkpoints.size + 1
            val passedCount = if (raceState == RaceState.FINISHED) totalCheckpoints else passedCheckpointsCount
            checkpointsText?.text = "Checkpoints: $passedCount/$totalCheckpoints"
        }
    }

    // Music and sound methods
    private fun playButtonPressSound() {
        try {
            val player = MediaPlayer.create(context, R.raw.midtown_button_press)
            player?.setOnCompletionListener { it.release() }
            player?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing button press sound", e)
        }
    }

    private fun playAnnouncerStart() {
        try {
            announcerPlayer?.release()
            announcerPlayer = MediaPlayer.create(context, R.raw.midtown_announcer_start)
            announcerPlayer?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing announcer start sound", e)
        }
    }

    private fun playAnnouncerEnd() {
        try {
            announcerPlayer?.release()
            announcerPlayer = MediaPlayer.create(context, R.raw.midtown_announcer_end)
            announcerPlayer?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing announcer end sound", e)
        }
    }

    private fun playMenuMusic() {
        try {
            menuMusicPlayer = MediaPlayer.create(context, R.raw.midtown_menu_loop)
            menuMusicPlayer?.isLooping = true
            menuMusicPlayer?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing menu music", e)
        }
    }

    private fun playRaceMusic() {
        try {
            raceMusicPlayer = MediaPlayer.create(context, R.raw.midtown_race_loop)
            raceMusicPlayer?.isLooping = true
            raceMusicPlayer?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing race music", e)
        }
    }

    private fun playLastCheckpointMusic() {
        try {
            lastCheckpointMusicPlayer = MediaPlayer.create(context, R.raw.midtown_race_loop_last_checkpoint)
            lastCheckpointMusicPlayer?.isLooping = true
            lastCheckpointMusicPlayer?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing last checkpoint music", e)
        }
    }

    private fun playCheckpointSound() {
        try {
            // Create new player for each checkpoint so they can overlap
            val player = MediaPlayer.create(context, R.raw.midtown_checkpoint)
            player?.setOnCompletionListener { it.release() }
            player?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing checkpoint sound", e)
        }
    }

    private fun playFinishSound() {
        try {
            finishSoundPlayer = MediaPlayer.create(context, R.raw.midtown_finish)
            finishSoundPlayer?.setOnCompletionListener {
                it.release()
                // Start menu music after finish sound
                playMenuMusic()
            }
            finishSoundPlayer?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing finish sound", e)
        }
    }

    private fun stopAllMusic() {
        menuMusicPlayer?.apply {
            if (isPlaying) stop()
            release()
        }
        menuMusicPlayer = null

        raceMusicPlayer?.apply {
            if (isPlaying) stop()
            release()
        }
        raceMusicPlayer = null

        lastCheckpointMusicPlayer?.apply {
            if (isPlaying) stop()
            release()
        }
        lastCheckpointMusicPlayer = null
    }

    /**
     * Cleanup when app is closed
     */
    fun cleanup() {
        // Stop timer
        timerRunnable?.let { handler.removeCallbacks(it) }

        // Stop location tracking
        locationProvider?.stopLocationProvider()

        // Stop sensor listening
        stopSensorListening()

        // Stop all audio
        stopAllMusic()
        checkpointSoundPlayer?.release()
        finishSoundPlayer?.release()
        announcerPlayer?.release()

        // Cleanup map
        mapView?.onDetach()
    }

    /**
     * Called when the app is resumed
     */
    fun onResume() {
        mapView?.onResume()
        startSensorListening()
    }

    /**
     * Called when the app is paused
     */
    fun onPause() {
        mapView?.onPause()
        stopSensorListening()
    }
}
