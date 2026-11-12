package rocks.gorjan.gokixp.apps.mm2cars

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.gson.Gson

/**
 * One Midtown Madness 2 car as scripts/export_mm2_cars.py baked it: a mesh split into the
 * parts that move on their own (the body, four wheels, and everything MM2 lets break off),
 * and two atlases with the same layout - showroom paint and MM2's own damaged paint.
 *
 * Coordinates are metres in MM2's frame: Y up, X to the car's right, the nose towards -Z.
 */
internal class Mm2CarModel(
    val id: String,
    val parts: List<Part>,
    val atlas: Bitmap,
    val atlasDamaged: Bitmap,
    /** minX, minY, minZ, maxX, maxY, maxZ of the whole car. */
    val bounds: FloatArray,
    /** The police car's light bar: where its red (left) and blue (right) glows sit. */
    val siren: FloatArray? = null
) {
    enum class Kind { BODY, WHEEL, BREAK }

    class Part(
        val name: String,
        val kind: Kind,
        /** Where the part sits on the car; [positions] are relative to it. */
        val pivot: FloatArray,
        /** x, y, z per corner, three corners per triangle. */
        val positions: FloatArray,
        /** Atlas pixel u, v per corner. */
        val uvs: FloatArray,
        /** Rolling radius, for wheels. */
        val radius: Float
    ) {
        val triangleCount = positions.size / 9

        /** Per-triangle face normal in the part's own frame, x, y, z. */
        val normals = FloatArray(triangleCount * 3).also { out ->
            for (t in 0 until triangleCount) {
                val i = t * 9
                val ax = positions[i + 3] - positions[i]
                val ay = positions[i + 4] - positions[i + 1]
                val az = positions[i + 5] - positions[i + 2]
                val bx = positions[i + 6] - positions[i]
                val by = positions[i + 7] - positions[i + 1]
                val bz = positions[i + 8] - positions[i + 2]
                var nx = ay * bz - az * by
                var ny = az * bx - ax * bz
                var nz = ax * by - ay * bx
                val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
                nx /= len; ny /= len; nz /= len
                out[t * 3] = nx; out[t * 3 + 1] = ny; out[t * 3 + 2] = nz
            }
        }

        /** Half the part's largest extent: what it bounces on once it has come off. */
        val extent: Float = run {
            var m = 0.05f
            for (v in positions) m = maxOf(m, kotlin.math.abs(v))
            m
        }
    }

    val length get() = bounds[5] - bounds[2]
    val width get() = bounds[3] - bounds[0]
    val height get() = bounds[4] - bounds[1]

    companion object {
        /** The cars of the toy, in the order they first come out. */
        val IDS = listOf("bullet", "beetle", "cab", "panoz", "cop")

        private class PartJson(
            val name: String = "",
            val kind: String = "",
            val pivot: FloatArray = FloatArray(3),
            val pos: FloatArray = FloatArray(0),
            val uv: FloatArray = FloatArray(0),
            val radius: Float = 0f
        )

        private class CarJson(
            val id: String = "",
            val atlas: String = "",
            val atlasDamaged: String = "",
            val bounds: FloatArray = FloatArray(6),
            val parts: List<PartJson> = emptyList(),
            val siren: FloatArray? = null
        )

        fun load(context: Context, id: String): Mm2CarModel {
            val assets = context.assets
            val json = assets.open("mm2cars/$id.json").bufferedReader().use { it.readText() }
            val car = Gson().fromJson(json, CarJson::class.java)
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val atlas = assets.open("mm2cars/${car.atlas}").use { BitmapFactory.decodeStream(it, null, options) }
            val damaged = assets.open("mm2cars/${car.atlasDamaged}").use { BitmapFactory.decodeStream(it, null, options) }
            val parts = car.parts.map {
                Part(
                    name = it.name,
                    kind = when (it.kind) {
                        "body" -> Kind.BODY
                        "wheel" -> Kind.WHEEL
                        else -> Kind.BREAK
                    },
                    pivot = it.pivot,
                    positions = it.pos,
                    uvs = it.uv,
                    radius = it.radius
                )
            }
            return Mm2CarModel(car.id, parts, atlas!!, damaged!!, car.bounds, car.siren)
        }
    }
}
