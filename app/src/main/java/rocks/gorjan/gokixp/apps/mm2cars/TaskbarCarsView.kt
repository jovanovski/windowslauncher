package rocks.gorjan.gokixp.apps.mm2cars

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Choreographer
import android.view.TextureView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The strip of desktop the toy lives in: from above the row houses down to just below the
 * road, the width of the screen. It is a TextureView drawn with OpenGL on a thread of its own,
 * which runs the world too, so the launcher's own thread does nothing for it but composite a
 * texture; and it is a view like any other, so the icons, windows and start menu still go over it.
 *
 * Moving the whole scene up or down the desktop is moving this view ([translationY]); the
 * render thread never hears of it.
 */
@SuppressLint("ViewConstructor")
internal class TaskbarCarsView(
    context: Context,
    private val models: List<Mm2CarModel>,
    private val gateModel: Mm2Prop,
    private val trailerModel: Mm2Prop,
    private val street: Mm2Street,
    private val sounds: CarSounds,
    /** Gets a little render of the first car, for the tray, once there is a GL context to make it in. */
    private val onIcon: (Bitmap) -> Unit
) : TextureView(context), TextureView.SurfaceTextureListener {

    private val density = resources.displayMetrics.density

    /** The strip's height, and how much of it is below the road (the near sidewalk, behind the taskbar). */
    val stripHeight = ((ABOVE_DP + BELOW_DP) * density).toInt()
    val belowRoad = BELOW_DP * density

    /** The main thread's own camera, for where the road is. The render thread has the other. */
    private val viewCamera = Camera(density)

    private val thread = HandlerThread("mm2cars-gl").also { it.start() }
    private val handler = Handler(thread.looper)

    // Owned by the render thread
    private val camera = Camera(density)
    private val world = CarsWorld(models, gateModel, trailerModel, street, sounds, camera)
    private val renderer = CarsRenderer(camera)
    private var egl: Gl? = null
    private var running = false
    private var lastFrame = 0L
    private var iconMade = false

    /** Where each car is in this view, left, top, right, bottom; written by the render thread. */
    @Volatile private var rects = FloatArray(0)

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    // ---------------------------------------------------------------- main thread

    fun start() = handler.post { running = true; schedule() }

    fun stop() {
        sounds.pause()
        handler.post { running = false }
    }

    fun resume() {
        sounds.resume()
        start()
    }

    fun buildRamp() = handler.post { world.buildRamp() }
    fun buildGate() = handler.post { world.buildGate() }
    fun demolitionDerby() = handler.post { world.demolitionDerby() }

    /** The car under (x, y) in this view's pixels, if any, gets kicked. True when there was one. */
    fun pokeAt(x: Float, y: Float): Boolean {
        val r = rects
        val slop = 10 * density
        for (i in 0 until r.size / 4) {
            if (r[i * 4].isNaN()) continue
            if (x < r[i * 4] - slop || x > r[i * 4 + 2] + slop) continue
            if (y < r[i * 4 + 1] - slop || y > r[i * 4 + 3] + slop) continue
            handler.post { world.kick(i) }
            return true
        }
        return false
    }

    /** The road, sidewalk to sidewalk, as the top and bottom of it in this view's pixels. */
    fun roadBand(): Pair<Float, Float> {
        viewCamera.layout(width.toFloat(), height.toFloat(), height - belowRoad)
        viewCamera.project(0f, 0f, CarsWorld.ROAD_FAR_Z)
        val top = viewCamera.py
        viewCamera.project(0f, 0f, CarsWorld.ROAD_NEAR_Z)
        return top to viewCamera.py
    }

    fun release() {
        handler.post {
            running = false
            teardown()
            thread.quitSafely()
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
        handler.post {
            setup(surface)
            resize(w, h)
            schedule()
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) {
        handler.post { resize(w, h) }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        // GL has to let go of the surface before it is released
        val done = CountDownLatch(1)
        handler.post { teardown(); done.countDown() }
        done.await(500, TimeUnit.MILLISECONDS)
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    // ---------------------------------------------------------------- render thread

    private val frame = Choreographer.FrameCallback { now -> onFrame(now) }

    private fun schedule() {
        if (running && egl != null) Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun onFrame(now: Long) {
        val gl = egl ?: return
        if (!running) return
        // Thirty frames a second is plenty for a toy, and half the work of sixty
        if (lastFrame != 0L && now - lastFrame < FRAME_NANOS - 2_000_000L) {
            schedule()
            return
        }
        val dt = if (lastFrame == 0L) 1f / 30f else ((now - lastFrame) / 1e9f).coerceIn(0f, 1f / 15f)
        lastFrame = now

        try {
            world.step(dt)
            val r = FloatArray(world.carCount * 4)
            world.carRects(r)
            rects = r

            if (!iconMade) makeIcon()
            renderer.beginFrame(camera.width.toInt(), camera.height.toInt())
            world.draw(renderer)
            renderer.endFrame()
            gl.swap()
        } catch (e: Exception) {
            // This is the home screen: a toy that breaks stops, it doesn't take the launcher with it
            Log.e(TAG, "The cars stopped", e)
            running = false
            return
        }
        schedule()
    }

    private fun resize(w: Int, h: Int) {
        camera.layout(w.toFloat(), h.toFloat(), h - belowRoad)
        world.layout()
        lastFrame = 0L
    }

    private fun setup(surface: SurfaceTexture) {
        if (egl != null) return
        egl = try {
            Gl(surface)
        } catch (e: Exception) {
            Log.e(TAG, "No GL for the cars", e)
            null
        } ?: return
        renderer.init()
    }

    private fun teardown() {
        renderer.release()
        egl?.release()
        egl = null
    }

    /** The first car, three-quarters on, rendered once into a small offscreen image for the tray. */
    private fun makeIcon() {
        iconMade = true
        val model = models.firstOrNull() ?: return
        val size = (19 * density).toInt().coerceAtLeast(16)
        val ids = IntArray(3)
        GLES30.glGenFramebuffers(1, ids, 0)
        GLES30.glGenTextures(1, ids, 1)
        GLES30.glGenRenderbuffers(1, ids, 2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[1])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, size, size, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, ids[2])
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT16, size, size)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, ids[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, ids[1], 0)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, ids[2])

        // A camera of its own, the car filling the square
        val iconCamera = Camera(density)
        iconCamera.layout(size.toFloat(), size.toFloat(), size * 0.8f)
        iconCamera.focal = size / (model.length * 0.92f) * Camera.DISTANCE
        iconCamera.cy = size * 0.74f
        val r = CarsRenderer(iconCamera).also { it.init() }
        r.beginFrame(size, size)
        val yaw = -PI.toFloat() / 2f + 0.55f
        val c = cos(yaw); val s = sin(yaw)
        val rot = floatArrayOf(c, 0f, s, 0f, 1f, 0f, -s, 0f, c)
        val midX = (model.bounds[0] + model.bounds[3]) / 2f
        val midZ = (model.bounds[2] + model.bounds[5]) / 2f
        for (part in model.parts) {
            val lx = part.pivot[0] - midX; val ly = part.pivot[1]; val lz = part.pivot[2] - midZ
            r.mesh(part, model.atlas, rot, c * lx + s * lz, ly, -s * lx + c * lz)
        }
        val pixels = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, size, size, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, ids, 0)
        GLES30.glDeleteTextures(1, ids, 1)
        GLES30.glDeleteRenderbuffers(1, ids, 2)

        val upsideDown = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        upsideDown.copyPixelsFromBuffer(pixels)
        // GL's rows run bottom up
        val icon = Bitmap.createBitmap(upsideDown, 0, 0, size, size, Matrix().apply { preScale(1f, -1f) }, false)
        post { onIcon(icon) }
    }

    /** An EGL context and window surface on the TextureView's SurfaceTexture. */
    private class Gl(surface: SurfaceTexture) {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: EGLContext
        private val window: EGLSurface

        init {
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
            val attributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 24,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val found = IntArray(1)
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, found, 0) && found[0] > 0) { "eglChooseConfig" }
            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
            )
            window = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, window, window, context)) { "eglMakeCurrent" }
        }

        fun swap() {
            EGL14.eglSwapBuffers(display, window)
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, window)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    companion object {
        private const val TAG = "TaskbarCarsView"
        /** The strip: room above the road for the houses and the jumps, and below it for the near sidewalk. */
        private const val ABOVE_DP = 200f
        private const val BELOW_DP = 24f
        private const val FRAME_NANOS = 1_000_000_000L / 30
    }
}
