/*
 * SPDX-FileCopyrightText: 2019 CypherOS
 * SPDX-FileCopyrightText: 2014-2020 Paranoid Android
 * SPDX-FileCopyrightText: 2023-2026 The LineageOS Project
 * SPDX-FileCopyrightText: 2023 Yet Another AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.device

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.PixelFormat
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.provider.Settings
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

class AlertSliderDialog(private val context: Context) :
    Dialog(context, R.style.alert_slider_theme) {
    private val dialogView by lazy { findViewById<LinearLayout>(R.id.alert_slider_dialog)!! }
    private val frameView by lazy { findViewById<ViewGroup>(R.id.alert_slider_view)!! }
    private val iconView by lazy { findViewById<ImageView>(R.id.alert_slider_icon)!! }
    private val textView by lazy { findViewById<TextView>(R.id.alert_slider_text)!! }
    private val emojiView by lazy { findViewById<TextView>(R.id.alert_slider_emoji_view)!! }
    private val lottieView by lazy { findViewById<ImageView>(R.id.alert_slider_lottie_view)!! }

    private val rotation: Int = context.getDisplay().getRotation()
    private val isLandscape = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
    private val flip = context.resources.getBoolean(R.bool.alert_slider_dialog_left)

    private val length: Int
    private val xPos: Int
    private val yPos: Int

    private var isAnimating = false
    private var animator = ValueAnimator()
    private var isBlurEnabled = false

    init {
        window?.let {
            it.requestFeature(Window.FEATURE_NO_TITLE)
            it.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            it.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
            it.addPrivateFlags(WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY)
            it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            it.setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY)
            it.attributes =
                it.attributes.apply {
                    format = PixelFormat.TRANSLUCENT
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    title = TAG
                }
        }

        setCanceledOnTouchOutside(false)
        setContentView(R.layout.alert_slider_dialog)

        val res = context.resources
        val fraction = res.getFraction(R.fraction.alert_slider_dialog_y, 1, 1)
        val widthPixels = res.displayMetrics.widthPixels
        val heightPixels = res.displayMetrics.heightPixels
        val pads = dialogView.paddingTop * 2
        length =
            if (isLandscape) res.getDimension(R.dimen.alert_slider_dialog_width).toInt()
            else res.getDimension(R.dimen.alert_slider_dialog_height).toInt()
        val hv = (length + pads) * 0.5

        xPos =
            if (isLandscape) (widthPixels * fraction - hv).toInt()
            else if (flip) 0 else widthPixels / 100
        yPos =
            if (isLandscape) (if (flip) (widthPixels / 100) else 0)
            else (heightPixels * fraction - hv).toInt()

        window?.let {
            it.attributes =
                it.attributes.apply {
                    gravity =
                        when (rotation) {
                            Surface.ROTATION_0 ->
                                if (flip) Gravity.TOP or Gravity.LEFT
                                else Gravity.TOP or Gravity.RIGHT
                            Surface.ROTATION_90 ->
                                if (flip) Gravity.BOTTOM or Gravity.LEFT
                                else Gravity.TOP or Gravity.LEFT
                            Surface.ROTATION_270 ->
                                if (flip) Gravity.TOP or Gravity.RIGHT
                                else Gravity.BOTTOM or Gravity.RIGHT
                            else ->
                                if (flip) Gravity.BOTTOM or Gravity.LEFT
                                else Gravity.TOP or Gravity.LEFT
                        }
                    x = xPos
                    y = yPos
                }
        }
    }

    fun refreshBlur() {
        window?.let {
            if (isBlurEnabled) {
                it.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                it.attributes = it.attributes.apply { blurBehindRadius = BLUR_RADIUS }
            } else {
                it.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                it.attributes = it.attributes.apply { blurBehindRadius = 0 }
            }
        }
    }

    @Synchronized
    fun setState(position: Int, ringerMode: Int) {
        val resolver = context.contentResolver
        val islandMode = Settings.System.getInt(resolver, "config_alert_slider_island", 0) != 0
        val blurPopup = Settings.System.getInt(resolver, "config_alert_slider_glass", 0) != 0
        val hideLabel = Settings.System.getInt(resolver, "config_alert_slider_hide_label", 0) != 0
        isBlurEnabled = blurPopup

        // Position
        val delta =
            length *
                when (position) {
                    KeyHandler.POSITION_TOP -> -1
                    KeyHandler.POSITION_BOTTOM -> 1
                    else -> 0
                }

        if (islandMode) {
            val statusBarHeight = run {
                val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
                if (resId > 0) context.resources.getDimensionPixelSize(resId) else 96
            }
            window?.let {
                it.attributes = it.attributes.apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    x = 0
                    y = statusBarHeight + 8
                }
            }
        } else {
            var endX = xPos
            var endY = yPos
            if (isLandscape) endX += delta else endY += delta
            if (isShowing) animatePosition(endX, endY, position, ringerMode)
            else applyPositionAndBackground(endX, endY, position)
        }

        // Background
        val bgDrawable = dialogView.background as? android.graphics.drawable.GradientDrawable
        if (bgDrawable != null) {
            if (blurPopup) {
                // Frosted glass: light tint in light mode, dark tint in dark mode
                val nightMode = (context.resources.configuration.uiMode
                    and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                bgDrawable.setColor(
                    if (nightMode) Color.argb(80, 30, 30, 40)
                    else Color.argb(80, 240, 240, 245)
                )
            } else {
                val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackgroundFloating))
                bgDrawable.setColor(ta.getColor(0, Color.BLACK))
                ta.recycle()
            }
        }

        // Content
        applyUiContent(position, ringerMode, hideLabel, blurPopup)

        // Text label
        if (hideLabel) {
            textView.visibility = View.GONE
        } else {
            textView.visibility = View.VISIBLE
            textView.setText(
                when (ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> R.string.alert_slider_mode_silent
                    AudioManager.RINGER_MODE_VIBRATE -> R.string.alert_slider_mode_vibration
                    AudioManager.RINGER_MODE_NORMAL -> R.string.alert_slider_mode_normal
                    KeyHandler.ZEN_PRIORITY_ONLY -> R.string.alert_slider_mode_dnd_priority_only
                    KeyHandler.ZEN_TOTAL_SILENCE -> R.string.alert_slider_mode_dnd_total_silence
                    KeyHandler.ZEN_ALARMS_ONLY -> R.string.alert_slider_mode_dnd_alarms_only
                    else -> R.string.alert_slider_mode_none
                }
            )
            val textColor = if (blurPopup) {
                val nightMode = (context.resources.configuration.uiMode
                    and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                if (nightMode) Color.WHITE else Color.parseColor("#1A1A1A")
            } else {
                context.getColor(R.color.alert_slider_text_color)
            }
            textView.setTextColor(textColor)
        }
    }

    @Synchronized
    private fun animatePosition(endX: Int, endY: Int, position: Int, ringerMode: Int) {
        if (isAnimating) animator.cancel()
        animator = ValueAnimator()
        animator.duration = 100
        animator.interpolator = OvershootInterpolator()

        window?.let {
            animator.setValues(
                PropertyValuesHolder.ofInt("x", it.attributes.x, endX),
                PropertyValuesHolder.ofInt("y", it.attributes.y, endY),
            )
        }

        animator.addUpdateListener { animation ->
            window?.let {
                it.attributes =
                    it.attributes.apply {
                        x = animation.getAnimatedValue("x") as Int
                        y = animation.getAnimatedValue("y") as Int
                    }
            }
        }

        animator.addListener(
            object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) { isAnimating = true }
                override fun onAnimationEnd(animation: Animator) {
                    applyPositionAndBackground(endX, endY, position)
                    isAnimating = false
                }
                override fun onAnimationCancel(animation: Animator) {}
                override fun onAnimationRepeat(animation: Animator) {}
            }
        )
        animator.start()
    }

    private fun applyUiContent(position: Int, ringerMode: Int, hideLabel: Boolean, blurActive: Boolean) {
        val resolver = context.contentResolver
        val posKey = when (position) {
            KeyHandler.POSITION_TOP -> "top"
            KeyHandler.POSITION_MIDDLE -> "middle"
            KeyHandler.POSITION_BOTTOM -> "bottom"
            else -> null
        }

        val lottiePath = posKey?.let { Settings.System.getString(resolver, "config_lottie_$it") }
        val lottieFile = lottiePath?.takeIf { it.isNotEmpty() }?.let { File(it) }
            ?.takeIf { it.exists() && it.length() <= MAX_LOTTIE_SIZE_BYTES }

        val rawEmoji = posKey?.let { Settings.System.getString(resolver, "config_emoji_$it") }
        val emoji = rawEmoji?.takeIf { it.isNotEmpty() }

        (iconView.drawable as? AnimatedVectorDrawable)?.stop()
        (lottieView.drawable as? AnimatedImageDrawable)?.stop()

        when {
            lottieFile != null -> {
                iconView.visibility = View.GONE
                emojiView.visibility = View.GONE
                lottieView.visibility = View.VISIBLE
                lottieView.background = null
                try {
                    val source = ImageDecoder.createSource(lottieFile)
                    val drawable = ImageDecoder.decodeDrawable(source)
                    lottieView.setImageDrawable(drawable)
                    (drawable as? AnimatedImageDrawable)?.start()
                } catch (_: Exception) {
                    lottieView.visibility = View.GONE
                    iconView.visibility = View.VISIBLE
                    applyDefaultIcon(ringerMode, blurActive)
                }
            }
            emoji != null -> {
                iconView.visibility = View.GONE
                lottieView.visibility = View.GONE
                emojiView.visibility = View.VISIBLE
                emojiView.text = emoji
                animateEmoji(emojiView)
            }
            else -> {
                emojiView.visibility = View.GONE
                lottieView.visibility = View.GONE
                iconView.visibility = View.VISIBLE
                applyDefaultIcon(ringerMode, blurActive)
            }
        }
    }

    private fun applyDefaultIcon(ringerMode: Int, blurActive: Boolean) {
        val animDrawableRes = when (ringerMode) {
            AudioManager.RINGER_MODE_VIBRATE -> R.drawable.ic_volume_ringer_vibrate_anim
            AudioManager.RINGER_MODE_NORMAL -> R.drawable.ic_volume_ringer_anim
            AudioManager.RINGER_MODE_SILENT -> R.drawable.ic_volume_ringer_mute_anim
            else -> 0
        }
        val staticDrawableRes = when (ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> R.drawable.ic_volume_ringer_mute
            AudioManager.RINGER_MODE_VIBRATE -> R.drawable.ic_volume_ringer_vibrate
            AudioManager.RINGER_MODE_NORMAL -> R.drawable.ic_volume_ringer
            KeyHandler.ZEN_PRIORITY_ONLY -> R.drawable.ic_notifications_alert
            KeyHandler.ZEN_TOTAL_SILENCE -> R.drawable.ic_notifications_silence
            KeyHandler.ZEN_ALARMS_ONLY -> R.drawable.ic_alarm
            else -> R.drawable.ic_info
        }

        if (animDrawableRes != 0) {
            iconView.setImageResource(animDrawableRes)
            // Tint for blur mode readability
            if (blurActive) {
                val nightMode = (context.resources.configuration.uiMode
                    and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                iconView.setColorFilter(
                    if (nightMode) Color.WHITE else Color.parseColor("#1A1A1A")
                )
            } else {
                iconView.clearColorFilter()
            }
            (iconView.drawable as? AnimatedVectorDrawable)?.start()
        } else {
            iconView.setImageResource(staticDrawableRes)
            if (blurActive) {
                val nightMode = (context.resources.configuration.uiMode
                    and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                iconView.setColorFilter(
                    if (nightMode) Color.WHITE else Color.parseColor("#1A1A1A")
                )
            } else {
                iconView.clearColorFilter()
            }
        }
    }

    private fun applyPositionAndBackground(endX: Int, endY: Int, position: Int) {
        window?.let {
            it.attributes =
                it.attributes.apply {
                    x = endX
                    y = endY
                }
        }
        frameView.setBackgroundResource(backgroundFor(rotation, position, flip))
    }

    private fun backgroundFor(rotation: Int, position: Int, flip: Boolean): Int {
        fun base(position: Int): Int =
            when (position) {
                KeyHandler.POSITION_TOP ->
                    if (flip) R.drawable.alert_slider_top_flip else R.drawable.alert_slider_top
                KeyHandler.POSITION_MIDDLE -> R.drawable.alert_slider_middle
                KeyHandler.POSITION_BOTTOM ->
                    if (flip) R.drawable.alert_slider_bottom_flip
                    else R.drawable.alert_slider_bottom
                else -> R.drawable.alert_slider_middle
            }

        return when (rotation) {
            Surface.ROTATION_90 ->
                when (position) {
                    KeyHandler.POSITION_TOP ->
                        if (flip) R.drawable.alert_slider_top_90_flip
                        else R.drawable.alert_slider_top_90
                    KeyHandler.POSITION_BOTTOM ->
                        if (flip) R.drawable.alert_slider_bottom_90_flip
                        else R.drawable.alert_slider_bottom_90
                    else -> R.drawable.alert_slider_middle
                }
            Surface.ROTATION_270 ->
                when (position) {
                    KeyHandler.POSITION_TOP ->
                        if (flip) R.drawable.alert_slider_top_270_flip
                        else R.drawable.alert_slider_top_270
                    KeyHandler.POSITION_BOTTOM ->
                        if (flip) R.drawable.alert_slider_bottom_270_flip
                        else R.drawable.alert_slider_bottom_270
                    else -> R.drawable.alert_slider_middle
                }
            else -> base(position)
        }
    }

    private fun animateEmoji(view: TextView) {
        view.scaleX = 0.4f
        view.scaleY = 0.4f
        view.alpha = 0f

        val scaleX = ObjectAnimator.ofFloat(view, "scaleX", 0.4f, 1.15f, 0.95f, 1f)
        val scaleY = ObjectAnimator.ofFloat(view, "scaleY", 0.4f, 1.15f, 0.95f, 1f)
        val alpha = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f)

        AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            duration = 400
            interpolator = OvershootInterpolator(1.0f)
            start()
        }
    }

    companion object {
        private const val TAG = "AlertSliderDialog"
        private const val MAX_LOTTIE_SIZE_BYTES = 512 * 1024L
        private const val BLUR_RADIUS = 25
    }
}
