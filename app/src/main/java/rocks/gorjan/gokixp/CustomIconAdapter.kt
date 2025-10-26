package rocks.gorjan.gokixp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CustomIconAdapter(
    private val icons: MutableList<CustomIconItem>,
    private val onIconSelected: (CustomIconItem) -> Unit
) : RecyclerView.Adapter<CustomIconAdapter.IconViewHolder>() {

    class IconViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val iconImage: ImageView = itemView.findViewById(R.id.icon_image)
        val iconText: TextView = itemView.findViewById(R.id.icon_text)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IconViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_custom_icon, parent, false)
        return IconViewHolder(view)
    }

    override fun onBindViewHolder(holder: IconViewHolder, position: Int) {
        val iconItem = icons[position]
        holder.iconImage.setImageDrawable(iconItem.drawable)
        holder.iconText.text = iconItem.name

        holder.itemView.setOnClickListener {
            onIconSelected(iconItem)
        }
    }

    override fun getItemCount(): Int = icons.size
    
    fun addIcon(icon: CustomIconItem) {
        icons.add(icon)
        notifyItemInserted(icons.size - 1)
    }
    
    fun addIcons(newIcons: List<CustomIconItem>) {
        val startPosition = icons.size
        icons.addAll(newIcons)
        notifyItemRangeInserted(startPosition, newIcons.size)
    }

    fun clearIcons() {
        val itemCount = icons.size
        icons.clear()
        notifyItemRangeRemoved(0, itemCount)
    }
}