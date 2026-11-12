package rocks.gorjan.gokixp.apps.mm2cars

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.gson.Gson

/**
 * The San Francisco street the toy drives along, as scripts/export_mm2_cars.py baked it: one
 * atlas with MM2's Victorian row-house facade tiles in every colour, the tar roof, the road
 * and sidewalk, and the street trees - which come as meshes, standing on y = 0.
 */
internal class Mm2Street(
    val atlas: Bitmap,
    /** name -> left, top, right, bottom in atlas pixels: "<colour>_<piece>", "roof", "road", "sidewalk". */
    val tiles: Map<String, FloatArray>,
    val colours: List<String>,
    /** "ground", "floor" and "top" -> the facade pieces for that storey. */
    val pieces: Map<String, List<String>>,
    val trees: List<Mm2CarModel.Part>
) {
    companion object {
        private class TreeJson(val name: String = "", val pos: FloatArray = FloatArray(0), val uv: FloatArray = FloatArray(0))
        private class StreetJson(
            val tiles: Map<String, FloatArray> = emptyMap(),
            val colours: List<String> = emptyList(),
            val pieces: Map<String, List<String>> = emptyMap(),
            val trees: List<TreeJson> = emptyList()
        )

        fun load(context: Context): Mm2Street {
            val assets = context.assets
            val json = assets.open("mm2cars/street.json").bufferedReader().use { it.readText() }
            val street = Gson().fromJson(json, StreetJson::class.java)
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val atlas = assets.open("mm2cars/street.png").use { BitmapFactory.decodeStream(it, null, options) }!!
            val trees = street.trees.map { Mm2CarModel.Part(it.name, Mm2CarModel.Kind.BODY, FloatArray(3), it.pos, it.uv, 0f) }
            return Mm2Street(atlas, street.tiles, street.colours, street.pieces, trees)
        }
    }
}
