package me.hufman.androidautoidrive.phoneui.viewmodels

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.databinding.BindingAdapter
import androidx.databinding.InverseBindingAdapter
import androidx.databinding.InverseBindingListener
import com.google.android.material.animation.ArgbEvaluatorCompat
import me.hufman.androidautoidrive.phoneui.visible
import java.util.*
import kotlin.math.max


@BindingAdapter("android:src")
fun setImageViewBitmap(view: ImageView, bitmap: Bitmap) {
	view.setImageBitmap(bitmap)
}
@BindingAdapter("android:src")
fun setImageViewResource(view: ImageView, resource: Int) {
	view.setImageResource(resource)
}
@BindingAdapter("android:src")
fun setImageViewResource(view: ImageView, drawable: Context.() -> Drawable?) {
	view.setImageDrawable(view.context.run(drawable))
}

@BindingAdapter("android:visibility")
fun setViewVisibility(view: View, visible: Boolean) {
	view.visible = visible
}

/**
 * Finds the index of the given item in an Adapter
 * Which aren't normally iterable, so now they are
 * May return -1 if the item doesn't seem to exist
 */
fun <T> Adapter.indexOf(item: T): Int {
	for (i in 0 until count) {
		if (getItem(i) == item) {
			return i
		}
	}
	return -1
}

// Set up the spinner based on the selectedValue from the LiveData
@BindingAdapter("selectedValue")
fun setSelectedValue(spinner: Spinner, selectedValue: String) {
	val position = spinner.adapter.indexOf(selectedValue)
	spinner.setSelection(max(0, position))
}
// Set up the spinner's event listeners
@BindingAdapter("selectedValueAttrChanged")
fun setInverseBindingListener(spinner: Spinner, inverseBindingListener: InverseBindingListener?) {
	if (inverseBindingListener == null) {
		spinner.onItemSelectedListener = null
	} else {
		spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
			override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
				inverseBindingListener.onChange()
			}
			override fun onNothingSelected(parent: AdapterView<*>?) {}
		}
	}
}
// Triggered when the event listener fires from above
@InverseBindingAdapter(attribute="selectedValue", event="selectedValueAttrChanged")
fun getSelectedValue(spinner: Spinner): String {
	return spinner.selectedItem.toString()
}

// Dynamic text
@BindingAdapter("android:text")
fun setText(view: TextView, value: Context.() -> String) {
	view.text = view.context.run(value)
}

// Dynamic text
@BindingAdapter("android:visibility")
fun setVisibilityByTextGetter(view: View, value: Context.() -> String) {
	val text = view.context.run(value)
	view.visible = text.isNotBlank()
}

// Dynamic color with a smooth transition
@BindingAdapter("android:backgroundTint")
fun setBackgroundTint(view: View, value: Context.() -> Int) {
	val color = view.context.run(value)
	val startColor = view.backgroundTintList?.defaultColor
	if (startColor != color) {
		if (startColor == null) {
			view.backgroundTintList = ColorStateList.valueOf(color)
		} else {
			ValueAnimator.ofObject(ArgbEvaluatorCompat(), startColor, color).apply {
				addUpdateListener { view.backgroundTintList = ColorStateList.valueOf(it.animatedValue as Int) }
				start()
			}
		}
	}
}

// Add an animation for alpha
@BindingAdapter("android:alpha", "app:animationDuration")
fun setAlpha(view: View, value: Float, duration: Int) {
	view.animation?.cancel()
	if (duration > 0) {
		ValueAnimator.ofFloat(view.alpha, value).apply {
			addUpdateListener { view.alpha = it.animatedValue as Float }
			this.duration = duration.toLong()
			start()
		}
	}
}

// set an animator
val CANCELLABLE_ANIMATORS = WeakHashMap<View, Animator>()
@BindingAdapter("animator")
fun setAnimator(view: View, value: Animator?) {
	CANCELLABLE_ANIMATORS[view]?.cancel()
	if (value != null) {
		value.setTarget(view)
		value.start()
		CANCELLABLE_ANIMATORS[view] = value
	} else {
		view.animation?.cancel()
		view.clearAnimation()
	}
}

@BindingAdapter("onTouchDown")
fun setOnTouchDown(view: View, callback: View.OnClickListener) {
	view.setOnTouchListener { v, event ->
		if (event.actionMasked == MotionEvent.ACTION_DOWN) {
			callback.onClick(v)
		} else {
			v.performClick()
		}
		true
	}
}