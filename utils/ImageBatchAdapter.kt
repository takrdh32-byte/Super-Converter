package com.superconverter.utils

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.superconverter.R

class ImageBatchAdapter(
    private val items: MutableList<ImageItem> = mutableListOf()
) : RecyclerView.Adapter<ImageBatchAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.iv_thumb)
        val name: TextView  = view.findViewById(R.id.tv_name)
        val size: TextView  = view.findViewById(R.id.tv_size)
        val status: TextView = view.findViewById(R.id.tv_status)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_batch_image, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.size.text = FileUtils.formatSize(item.sizeBytes)

        Glide.with(holder.itemView.context)
            .load(item.uri)
            .centerCrop()
            .into(holder.thumb)

        val ctx = holder.itemView.context
        when (item.status) {
            ImageItem.Status.PENDING -> {
                holder.status.text = "Pending"
                holder.status.setBackgroundColor(ctx.getColor(R.color.surface2))
                holder.status.setTextColor(ctx.getColor(R.color.text_muted))
            }
            ImageItem.Status.PROCESSING -> {
                holder.status.text = "Processing"
                holder.status.setBackgroundColor(ctx.getColor(R.color.blue_light))
                holder.status.setTextColor(ctx.getColor(R.color.blue))
            }
            ImageItem.Status.DONE -> {
                holder.status.text = "Done"
                holder.status.setBackgroundColor(ctx.getColor(R.color.green_light))
                holder.status.setTextColor(ctx.getColor(R.color.green))
            }
            ImageItem.Status.ERROR -> {
                holder.status.text = "Error"
                holder.status.setBackgroundColor(ctx.getColor(R.color.orange_light))
                holder.status.setTextColor(ctx.getColor(R.color.orange))
            }
        }
    }

    override fun getItemCount() = items.size

    fun submitList(newItems: List<ImageItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun updateItemStatus(index: Int, status: ImageItem.Status) {
        if (index in items.indices) {
            items[index] = items[index].copy(status = status)
            notifyItemChanged(index)
        }
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }
}