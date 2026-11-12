package rocks.gorjan.gokixp.apps.mm2cars

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.IdentityHashMap

/**
 * Draws the toy with OpenGL ES 3 on TaskbarCarsView's render thread.
 *
 * Every mesh goes up to the GPU once, the first time it is drawn, and stays there; a frame is
 * a few dozen draw calls with a depth buffer, so nothing is sorted and nothing is transformed
 * on the CPU. The vertex shader projects exactly as [Camera.project] does - same tilt, same
 * focal length, same screen centre - so what is drawn is where the taps look for it.
 *
 * Shadows, smoke, fire, sparks and the light bar's glows are queued during the frame and
 * drawn at the end of it, over the solid geometry.
 */
internal class CarsRenderer(private val camera: Camera) {

    private var meshProgram = 0
    private var pointProgram = 0
    private val textures = IdentityHashMap<Bitmap, Int>()
    private val meshes = IdentityHashMap<Mm2CarModel.Part, Mesh>()
    private lateinit var shadowTexture: Bitmap
    private lateinit var shadowQuad: Mm2CarModel.Part

    private class Mesh(val buffer: Int, val count: Int)

    // Uniform locations
    private var uRot = 0; private var uPos = 0; private var uCam = 0; private var uView = 0; private var uScreen = 0
    private var uDepth = 0; private var uLight = 0; private var uLit = 0; private var uTex = 0; private var uAlpha = 0
    private var uShade = 0; private var uCutout = 0
    private var pCam = 0; private var pView = 0; private var pScreen = 0; private var pDepth = 0; private var pSoft = 0

    // Queued for the end of the frame
    private val shadows = FloatArray(MAX_SHADOWS * 8)
    private var shadowCount = 0
    private val puffs = FloatArray(MAX_POINTS * POINT_FLOATS)
    private var puffCount = 0
    private val glows = FloatArray(MAX_GLOWS * POINT_FLOATS)
    private var glowCount = 0
    private val pointData: FloatBuffer = ByteBuffer.allocateDirect(MAX_POINTS * POINT_FLOATS * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var pointBuffer = 0

    fun init() {
        meshProgram = program(MESH_VS, MESH_FS)
        uRot = GLES30.glGetUniformLocation(meshProgram, "uRot")
        uPos = GLES30.glGetUniformLocation(meshProgram, "uPos")
        uCam = GLES30.glGetUniformLocation(meshProgram, "uCam")
        uView = GLES30.glGetUniformLocation(meshProgram, "uView")
        uScreen = GLES30.glGetUniformLocation(meshProgram, "uScreen")
        uDepth = GLES30.glGetUniformLocation(meshProgram, "uDepth")
        uLight = GLES30.glGetUniformLocation(meshProgram, "uLight")
        uLit = GLES30.glGetUniformLocation(meshProgram, "uLit")
        uTex = GLES30.glGetUniformLocation(meshProgram, "uTex")
        uAlpha = GLES30.glGetUniformLocation(meshProgram, "uAlpha")
        uShade = GLES30.glGetUniformLocation(meshProgram, "uShade")
        uCutout = GLES30.glGetUniformLocation(meshProgram, "uCutout")
        pointProgram = program(POINT_VS, POINT_FS)
        pCam = GLES30.glGetUniformLocation(pointProgram, "uCam")
        pView = GLES30.glGetUniformLocation(pointProgram, "uView")
        pScreen = GLES30.glGetUniformLocation(pointProgram, "uScreen")
        pDepth = GLES30.glGetUniformLocation(pointProgram, "uDepth")
        pSoft = GLES30.glGetUniformLocation(pointProgram, "uSoft")
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        pointBuffer = ids[0]

        // A soft black blot, and a flat square on the road to lay it on
        shadowTexture = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    32f, 32f, 32f, intArrayOf(Color.BLACK, Color.argb(200, 0, 0, 0), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
                )
            }
            Canvas(it).drawCircle(32f, 32f, 32f, paint)
        }
        shadowQuad = Mm2CarModel.Part(
            "shadow", Mm2CarModel.Kind.BODY, FloatArray(3),
            floatArrayOf(-1f, 0f, 1f, 1f, 0f, 1f, 1f, 0f, -1f, -1f, 0f, 1f, 1f, 0f, -1f, -1f, 0f, -1f),
            floatArrayOf(0f, 64f, 64f, 64f, 64f, 0f, 0f, 64f, 64f, 0f, 0f, 0f), 0f
        )
    }

    /** Forgets everything on the GPU: the context it lived in is gone. */
    fun release() {
        textures.clear()
        meshes.clear()
    }

    fun beginFrame(width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClearDepthf(1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_BLEND)
        // Everything is premultiplied, as Android's bitmaps are
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glFrontFace(GLES30.GL_CCW)
        GLES30.glCullFace(GLES30.GL_BACK)

        GLES30.glUseProgram(meshProgram)
        GLES30.glUniform4f(uCam, camera.cx, camera.cy, camera.focal, 0f)
        GLES30.glUniform4f(uView, camera.camY, camera.camZ, camera.sinT, camera.cosT)
        GLES30.glUniform2f(uScreen, camera.width, camera.height)
        GLES30.glUniform2f(uDepth, DEPTH_A, DEPTH_B)
        GLES30.glUniform3f(uLight, LIGHT_X, LIGHT_Y, LIGHT_Z)
        GLES30.glUniform1i(uTex, 0)
        shadowCount = 0; puffCount = 0; glowCount = 0
    }

    /**
     * Draws [part] textured with [texture] (its UVs are in that bitmap's pixels), turned by
     * [rot] (row major, scale allowed) and placed at (x, y, z).
     */
    fun mesh(
        part: Mm2CarModel.Part, texture: Bitmap, rot: FloatArray, x: Float, y: Float, z: Float,
        alpha: Float = 1f, shade: Float = 1f, lit: Boolean = true, cull: Boolean = true, cutout: Boolean = false
    ) {
        if (alpha <= 0f) return
        val mesh = meshes.getOrPut(part) { upload(part, texture.width, texture.height) }
        if (mesh.count == 0) return
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture(texture))
        GLES30.glUniformMatrix3fv(uRot, 1, true, rot, 0)
        GLES30.glUniform3f(uPos, x, y, z)
        GLES30.glUniform1f(uLit, if (lit) 1f else 0f)
        GLES30.glUniform1f(uAlpha, alpha)
        GLES30.glUniform1f(uShade, shade)
        GLES30.glUniform1f(uCutout, if (cutout) 0.5f else -1f)
        if (cull) GLES30.glEnable(GLES30.GL_CULL_FACE) else GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mesh.buffer)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 32, 0)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 32, 12)
        GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, 32, 20)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, mesh.count)
    }

    /** A soft shadow on the road at (x, y, z), [halfLength] along the heading (hx, hz), [halfWidth] across. */
    fun shadow(x: Float, y: Float, z: Float, halfLength: Float, halfWidth: Float, hx: Float, hz: Float, alpha: Float) {
        if (shadowCount >= MAX_SHADOWS) return
        val o = shadowCount++ * 8
        shadows[o] = x; shadows[o + 1] = y; shadows[o + 2] = z; shadows[o + 3] = halfLength
        shadows[o + 4] = halfWidth; shadows[o + 5] = hx; shadows[o + 6] = hz; shadows[o + 7] = alpha
    }

    /** A round puff of smoke, fire, spark or dust, [size] metres across its radius, straight colour. */
    fun puff(x: Float, y: Float, z: Float, size: Float, r: Float, g: Float, b: Float, a: Float) {
        if (puffCount >= MAX_POINTS) return
        put(puffs, puffCount++, x, y, z, size, r * a, g * a, b * a, a)
    }

    /** A light, added onto whatever is behind it. */
    fun glow(x: Float, y: Float, z: Float, size: Float, color: Int) {
        if (glowCount >= MAX_GLOWS) return
        put(glows, glowCount++, x, y, z, size, Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f, 0f)
    }

    private fun put(into: FloatArray, i: Int, x: Float, y: Float, z: Float, size: Float, r: Float, g: Float, b: Float, a: Float) {
        val o = i * POINT_FLOATS
        into[o] = x; into[o + 1] = y; into[o + 2] = z; into[o + 3] = size
        into[o + 4] = r; into[o + 5] = g; into[o + 6] = b; into[o + 7] = a
    }

    fun endFrame() {
        // Shadows: on the road, under what is already drawn, writing no depth of their own
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(-1f, -2f)
        val rot = FloatArray(9)
        for (i in 0 until shadowCount) {
            val o = i * 8
            val hx = shadows[o + 5]; val hz = shadows[o + 6]
            val l = shadows[o + 3]; val w = shadows[o + 4]
            // Quad x along the heading, z across it
            rot[0] = hx * l; rot[1] = 0f; rot[2] = -hz * w
            rot[3] = 0f; rot[4] = 1f; rot[5] = 0f
            rot[6] = hz * l; rot[7] = 0f; rot[8] = hx * w
            mesh(shadowQuad, shadowTexture, rot, shadows[o], shadows[o + 1] + 0.02f, shadows[o + 2], alpha = shadows[o + 7], lit = false, cull = false)
        }
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)

        // Puffs over everything solid, then the glows added on top
        GLES30.glUseProgram(pointProgram)
        GLES30.glUniform4f(pCam, camera.cx, camera.cy, camera.focal, 0f)
        GLES30.glUniform4f(pView, camera.camY, camera.camZ, camera.sinT, camera.cosT)
        GLES30.glUniform2f(pScreen, camera.width, camera.height)
        GLES30.glUniform2f(pDepth, DEPTH_A, DEPTH_B)
        GLES30.glDisableVertexAttribArray(2)
        GLES30.glUniform1f(pSoft, 0f)
        points(puffs, puffCount)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glUniform1f(pSoft, 1f)
        points(glows, glowCount)
        GLES30.glDepthMask(true)
    }

    private fun points(data: FloatArray, count: Int) {
        if (count == 0) return
        pointData.clear()
        pointData.put(data, 0, count * POINT_FLOATS)
        pointData.flip()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, pointBuffer)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, count * POINT_FLOATS * 4, pointData, GLES30.GL_STREAM_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(0, 4, GLES30.GL_FLOAT, false, POINT_FLOATS * 4, 0)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, POINT_FLOATS * 4, 16)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, count)
    }

    // ---------------------------------------------------------------- uploads

    /** The part's triangles as position, UV (0..1 of a [texW] x [texH] texture) and face normal. */
    private fun upload(part: Mm2CarModel.Part, texW: Int, texH: Int): Mesh {
        val count = part.triangleCount * 3
        val data = ByteBuffer.allocateDirect(count * 32).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (t in 0 until part.triangleCount) {
            for (c in 0..2) {
                val i = t * 9 + c * 3
                data.put(part.positions[i]); data.put(part.positions[i + 1]); data.put(part.positions[i + 2])
                data.put(part.uvs[t * 6 + c * 2] / texW); data.put(part.uvs[t * 6 + c * 2 + 1] / texH)
                data.put(part.normals[t * 3]); data.put(part.normals[t * 3 + 1]); data.put(part.normals[t * 3 + 2])
            }
        }
        data.flip()
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, count * 32, data, GLES30.GL_STATIC_DRAW)
        return Mesh(ids[0], count)
    }

    private fun texture(bitmap: Bitmap): Int = textures.getOrPut(bitmap) {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        // The props' UVs run past their textures' edges; the atlases' never reach theirs
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
        ids[0]
    }

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, source: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, source)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Log.e(TAG, "Shader: " + GLES30.glGetShaderInfoLog(s))
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glBindAttribLocation(p, 0, "aPos")
        GLES30.glBindAttribLocation(p, 1, "aUv")
        GLES30.glBindAttribLocation(p, 2, "aNormal")
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "Program: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    companion object {
        private const val TAG = "CarsRenderer"
        private const val MAX_SHADOWS = 16
        private const val MAX_POINTS = 400
        private const val MAX_GLOWS = 16
        private const val POINT_FLOATS = 8

        // Light from the upper left, a little in front, and what the dark side still gets
        private const val LIGHT_X = -0.38f
        private const val LIGHT_Y = 0.83f
        private const val LIGHT_Z = 0.41f

        // Standard perspective depth between the camera's near and far planes
        private const val DEPTH_A = (Camera.FAR + Camera.NEAR) / (Camera.FAR - Camera.NEAR)
        private const val DEPTH_B = -2f * Camera.FAR * Camera.NEAR / (Camera.FAR - Camera.NEAR)

        /**
         * World to the camera's view (as Camera.project), then to clip space with sz as w, so
         * screenX = cx + focal * x / sz and screenY (down) = cy - focal * sy / sz.
         */
        private const val PROJECT = """
            uniform vec4 uCam;    // cx, cy, focal
            uniform vec4 uView;   // camY, camZ, sinT, cosT
            uniform vec2 uScreen; // width, height in pixels
            uniform vec2 uDepth;  // perspective depth A, B
            vec4 project(vec3 w) {
                float ry = w.y - uView.x;
                float rz = w.z - uView.y;
                float sy = ry * uView.w - rz * uView.z;
                float sz = -ry * uView.z - rz * uView.w;
                float x = uCam.x * sz + uCam.z * w.x;
                float y = uCam.y * sz - uCam.z * sy;
                return vec4(2.0 * x / uScreen.x - sz, sz - 2.0 * y / uScreen.y, uDepth.x * sz + uDepth.y, sz);
            }
        """

        private const val MESH_VS = "#version 300 es\n" + PROJECT + """
            uniform mat3 uRot;
            uniform vec3 uPos;
            uniform vec3 uLight;
            uniform float uLit;
            in vec3 aPos;
            in vec2 aUv;
            in vec3 aNormal;
            out vec2 vUv;
            out float vLight;
            void main() {
                gl_Position = project(uRot * aPos + uPos);
                vec3 n = normalize(uRot * aNormal);
                vLight = mix(1.0, min(1.0, 0.62 + 0.38 * max(0.0, dot(n, uLight))), uLit);
                vUv = aUv;
            }
        """

        private const val MESH_FS = """#version 300 es
            precision mediump float;
            uniform sampler2D uTex;
            uniform float uAlpha;
            uniform float uShade;
            uniform float uCutout;
            in vec2 vUv;
            in float vLight;
            out vec4 color;
            void main() {
                vec4 c = texture(uTex, vUv);
                if (c.a < uCutout) discard;
                color = vec4(c.rgb * vLight * uShade, c.a) * uAlpha;
            }
        """

        private const val POINT_VS = "#version 300 es\n" + PROJECT + """
            in vec4 aPos;   // x, y, z, radius in metres
            in vec4 aUv;    // premultiplied colour
            out vec4 vColor;
            void main() {
                gl_Position = project(aPos.xyz);
                gl_PointSize = max(2.0, 2.0 * aPos.w * uCam.z / gl_Position.w);
                vColor = aUv;
            }
        """

        private const val POINT_FS = """#version 300 es
            precision mediump float;
            uniform float uSoft;
            in vec4 vColor;
            out vec4 color;
            void main() {
                float d = length(gl_PointCoord - vec2(0.5)) * 2.0;
                if (d > 1.0) discard;
                // A puff is a disc with a soft rim; a glow fades all the way out from its middle
                float k = mix(smoothstep(1.0, 0.7, d), pow(1.0 - d, 1.6), uSoft);
                color = vColor * k;
            }
        """
    }
}
