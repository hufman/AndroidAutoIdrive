package me.hufman.androidautoidrive.phoneui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import me.hufman.androidautoidrive.BR

/** Implemented based on https://medium.com/androiddevelopers/android-data-binding-recyclerview-db7c40d9f0e4 */
class DataBoundListAdapter<I, C>(val data: MutableList<I>, val layoutId: Int, val callback: C): RecyclerView.Adapter<DataBoundViewHolder<I, C>>() {
	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DataBoundViewHolder<I, C> {
		val layoutInflater = LayoutInflater.from(parent.context)
		val binding = DataBindingUtil.inflate<ViewDataBinding>(layoutInflater, layoutId, parent, false)
		return DataBoundViewHolder(binding, callback)
	}

	override fun onBindViewHolder(holder: DataBoundViewHolder<I, C>, position: Int) {
		holder.setItem(data[position])
	}

	override fun getItemCount(): Int {
		return data.size
	}
}

class DataBoundViewHolder<I, C>(val binding: ViewDataBinding, val callback: C?): RecyclerView.ViewHolder(binding.root) {
	var data: I? = null
	fun setItem(item: I) {
		this.data = item
		binding.setVariable(BR.data, item)
		if (callback != null) {
			binding.setVariable(BR.callback, callback)
		}
		binding.executePendingBindings()
	}
}

/**
 * Handles reordering and swiping in a RecyclerView
 * by updating the given MutableList
 */
class ReorderableItemsCallback<I>(private val items: MutableList<I>): ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START or ItemTouchHelper.END) {
	override fun isLongPressDragEnabled() = true

	override fun onMove(recyclerView: RecyclerView, source: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
		val startIndex = source.adapterPosition
		val destIndex = target.adapterPosition

		val item = items.removeAt(startIndex)
		items.add(destIndex, item)
		recyclerView.adapter?.notifyItemMoved(startIndex, destIndex)

		return true
	}

	override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
		val recyclerView = viewHolder.itemView.parent as? RecyclerView ?: return
		val index = viewHolder.adapterPosition
		items.removeAt(index)
		recyclerView.adapter?.notifyItemRemoved(index)
	}
}