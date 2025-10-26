package rocks.gorjan.gokixp

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class WallpaperAdapter(
    private val wallpapers: List<WallpaperItem>,
    private val onWallpaperSelected: (WallpaperItem) -> Unit
) : RecyclerView.Adapter<WallpaperAdapter.WallpaperViewHolder>() {

    private var selectedPosition: Int = -1

    class WallpaperViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val wallpaperName: TextView = itemView.findViewById(R.id.wallpaper_name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WallpaperViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_wallpaper, parent, false)
        return WallpaperViewHolder(view)
    }

    override fun onBindViewHolder(holder: WallpaperViewHolder, position: Int) {
        val wallpaperItem = wallpapers[position]
        holder.wallpaperName.text = wallpaperItem.name

        // Update selection state
        val isSelected = position == selectedPosition
        if (isSelected) {
            holder.itemView.setBackgroundColor(Color.parseColor("#0A246A"))
            holder.wallpaperName.setTextColor(Color.WHITE)
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            holder.wallpaperName.setTextColor(Color.BLACK)
        }

        holder.itemView.setOnClickListener {
            val previousPosition = selectedPosition
            selectedPosition = holder.adapterPosition

            // Notify adapter to update both old and new selection
            notifyItemChanged(previousPosition)
            notifyItemChanged(selectedPosition)

            onWallpaperSelected(wallpaperItem)
        }
    }

    override fun getItemCount(): Int = wallpapers.size
}