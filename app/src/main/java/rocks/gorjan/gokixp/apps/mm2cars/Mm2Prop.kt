package rocks.gorjan.gokixp.apps.mm2cars

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.gson.Gson

/**
 * One of MM2's props as scripts/export_mm2_cars.py baked it: the race checkpoint (pt_check)
 * or the jump trailer (sp_jumptrailer_f). Each part has a texture of its own, because they
 * tile; UVs are in that texture's pixels and run past its edges.
 */
internal class Mm2Prop(
    val parts: List<Pair<Mm2CarModel.Part, Bitmap>>,
    /** The trailer's deck from its foot to its lip, as (x, height) pairs, unscaled. */
    val profile: List<FloatArray>,
    /** The trailer's width, unscaled. */
    val width: Float
) {
    companion object {
        private class PartJson(val tex: String = "", val pos: FloatArray = FloatArray(0), val uv: FloatArray = FloatArray(0))
        private class PropJson(
            val parts: List<PartJson> = emptyList(),
            val profile: List<FloatArray> = emptyList(),
            val width: Float = 0f
        )

        fun load(context: Context, id: String): Mm2Prop {
            val assets = context.assets
            val json = assets.open("mm2cars/$id.json").bufferedReader().use { it.readText() }
            val prop = Gson().fromJson(json, PropJson::class.java)
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val parts = prop.parts.map {
                val bitmap = assets.open("mm2cars/${it.tex}").use { stream -> BitmapFactory.decodeStream(stream, null, options) }!!
                Mm2CarModel.Part(it.tex, Mm2CarModel.Kind.BODY, FloatArray(3), it.pos, it.uv, 0f) to bitmap
            }
            return Mm2Prop(parts, prop.profile, prop.width)
        }
    }
}
