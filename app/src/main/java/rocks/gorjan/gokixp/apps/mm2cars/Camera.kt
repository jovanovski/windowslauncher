package rocks.gorjan.gokixp.apps.mm2cars

import kotlin.math.cos
import kotlin.math.sin

/**
 * How the toy's world (metres: X along the taskbar, Y up, Z out of the screen) lands on the
 * strip it is drawn in: seen from [DISTANCE] away, looking down by [TILT], [PIXELS_PER_METRE_DP]
 * to the metre at the road. Shared by the GL renderer (as uniforms), the world (for where cars
 * are on screen, for taps) and the view (for where the road is, for dragging it).
 */
internal class Camera(private val density: Float) {
    val ppm = PIXELS_PER_METRE_DP * density
    val sinT = sin(TILT)
    val cosT = cos(TILT)
    val camY = DISTANCE * sinT
    val camZ = DISTANCE * cosT
    var focal = ppm * DISTANCE
    var cx = 0f
    var cy = 0f
    var width = 0f
    var height = 0f

    /** Half the strip's width in metres: the road runs from -halfWorld to +halfWorld. */
    val halfWorld get() = width / 2f / ppm

    /** Sizes the camera to a strip whose road (the near lane's tyres) sits at [groundY] pixels. */
    fun layout(w: Float, h: Float, groundY: Float) {
        width = w
        height = h
        focal = ppm * DISTANCE
        cx = w / 2f
        cy = groundY - LANE_Z * sinT * ppm - 1 * density
    }

    // The last projection, read straight after project()
    var px = 0f
    var py = 0f
    var pz = 0f

    fun project(x: Float, y: Float, z: Float) {
        val ry = y - camY
        val rz = z - camZ
        val sy = ry * cosT - rz * sinT
        val sz = -ry * sinT - rz * cosT
        val k = focal / sz
        px = cx + x * k
        py = cy - sy * k
        pz = sz
    }

    companion object {
        const val PIXELS_PER_METRE_DP = 13f
        /** Radians the camera looks down. */
        const val TILT = 0.24f
        const val DISTANCE = 26f
        /** The lanes' centres, either side of the road's middle. */
        const val LANE_Z = 1.1f
        /** Nothing nearer the camera than this is drawn (the GL near plane), metres. */
        const val NEAR = DISTANCE * 0.5f
        const val FAR = 120f
    }
}
