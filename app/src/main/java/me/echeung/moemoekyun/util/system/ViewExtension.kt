package me.echeung.moemoekyun.util.system

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.ImageView
import androidx.annotation.ColorInt
import coil.api.load
import me.echeung.moemoekyun.App

private const val TRANSITION_DURATION = 250

fun ImageView.loadImage(bitmap: Bitmap?) {
    if (bitmap == null || !App.preferenceUtil!!.shouldDownloadImage(context)) {
        return
    }

    this.load(bitmap) {
//        placeholder(drawable)
        crossfade(TRANSITION_DURATION)
    }
}

fun ImageView.loadImage(url: String?) {
    if (url == null || !App.preferenceUtil!!.shouldDownloadImage(context)) {
        return
    }

    this.load(url) {
//        placeholder(drawable)
        crossfade(TRANSITION_DURATION)
    }
}

fun EditText.getTrimmedText(): String {
    return this.text.toString().trim()
}

fun View.transitionBackgroundColor(@ColorInt toColor: Int) {
    if (background == null) {
        setBackgroundColor(toColor)
        return
    }

    val fromColor = (background as ColorDrawable).color

    val valueAnimator = ValueAnimator.ofArgb(fromColor, toColor)
    valueAnimator.duration = TRANSITION_DURATION.toLong()
    valueAnimator.interpolator = LinearInterpolator()
    valueAnimator.addUpdateListener { animator -> setBackgroundColor(animator.animatedValue as Int) }
    valueAnimator.start()
}

fun View.toggleVisibility(visible: Boolean) {
    visibility = if (visible) View.VISIBLE else View.GONE
}
