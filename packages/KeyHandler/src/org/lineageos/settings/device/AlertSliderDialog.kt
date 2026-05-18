/*
 * SPDX-FileCopyrightText: 2019 CypherOS
 * SPDX-FileCopyrightText: 2014-2020 Paranoid Android
 * SPDX-FileCopyrightText: 2023-2026 The LineageOS Project
 * SPDX-FileCopyrightText: 2023 Yet Another AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.device

import android.animation.Animator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.provider.Settings
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class AlertSliderDialog(private val context: Context) :
    Dialog(context, R.style.alert_slider_theme) {

    private val dialogView by lazy { findViewById<LinearLayout>(R.id.alert_slider_dialog)!! }
    private val iconView by lazy { findViewById<ImageView>(R.id.alert_slider_icon)!! }
    private val textView by lazy { findViewById<TextView>(R.id.alert_slider_text)!! }
    private val emojiView by lazy { findViewById<TextView>(R.id.alert_slider_emoji_view)!! }

    private val rotation: Int = context.display.rotation
    private val isLandscape = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
    private val flip = context.resources.getBoolean(R.bool.alert_slider_dialog_left)

    // Status bar height (used to place popup just below it)
    private val statusBarHeight: Int by lazy {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resId > 0) context.resources.getDimensionPixelSize(resId) else 96
    }

    // Edge position calculations (portrait, non-island mode)
    private val length: Int
    private val edgeXPos: Int
    private val edgeYPos: Int

    private var isAnimating = false
    private var animator = ValueAnimator()

    init {
        window?.let {
            it.requestFeature(Window.FEATURE_NO_TITLE)
            it.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            it.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
            it.addPrivateFlags(WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY)
            it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            it.setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY)
            it.attributes = it.attributes.apply {
                format = PixelFormat.TRANSLUCENT
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                title = TAG
            }
        }

        setCanceledOnTouchOutside(false)
        setContentView(R.layout.alert_slider_dialog)

        // Pre-calculate edge positioning values
        val res = context.resources
        val fraction = res.getFraction(R.fraction.alert_slider_dialog_y, 1, 1)
        val widthPixels = res.displayMetrics.widthPixels
        val heightPixels = res.displayMetrics.heightPixels
        val pads = dialogView.paddingTop * 2

        length = if (isLandscape)
            res.getDimension(R.dimen.alert_slider_dialog_width).toInt()
        else
            res.getDimension(R.dimen.alert_slider_dialog_height).toInt()

        val hv = (length + pads) * 0.5
        edgeXPos = if (flip) 0 else widthPixels / 100
        edgeYPos = (heightPixels * fraction - hv).toInt()
    }

    fun refreshBlur() {
        val resolver = context.contentResolver
        val blurPopup = Settings.System.getInt(resolver, "config_alert_slider_glass", 0) != 0
        window?.let { win ->
            val lp = win.attributes
            if (blurPopup) {
                lp.blurBehindRadius = 75
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            } else {
                lp.blurBehindRadius = 0
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
            }
            win.attributes = lp
        }
    }

    fun setState(position: Int, ringerMode: Int) {
        val resolver = context.contentResolver
        val islandMode = Settings.System.getInt(resolver, "config_alert_slider_island", 0) != 0
        val blurPopup = Settings.System.getInt(resolver, "config_alert_slider_glass", 0) != 0
        val hideLabel = Settings.System.getInt(resolver, "config_alert_slider_hide_label", 0) != 0

        // === Positioning — recalculated on every setState so toggles take immediate effect ===
        window?.let { win ->
            win.attributes = win.attributes.apply {
                // Update blur flag based on current blur popup setting
                if (blurPopup) {
                    blurBehindRadius = 75
                    flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                } else {
                    blurBehindRadius = 0
                    flags = flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                }

                when {
                    islandMode -> {
                        // Island mode: top-center, just below the status bar
                        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        x = 0
                        y = statusBarHeight + 8 // 8px gap below status bar
                    }
                    isLandscape -> {
                        // Landscape: always top-center, no position shift per-mode
                        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        x = 0
                        y = statusBarHeight + 8
                    }
                    else -> {
                        // Portrait edge mode: shift position per slider position
                        val delta = length * when (position) {
                            KeyHandler.POSITION_TOP -> -1
                            KeyHandler.POSITION_BOTTOM -> 1
                            else -> 0
                        }
                        gravity = when (rotation) {
                            Surface.ROTATION_0 ->
                                if (flip) Gravity.TOP or Gravity.LEFT
                                else Gravity.TOP or Gravity.RIGHT
                            else ->
                                if (flip) Gravity.BOTTOM or Gravity.LEFT
                                else Gravity.TOP or Gravity.LEFT
                        }
                        x = edgeXPos
                        y = edgeYPos + delta
                    }
                }
            }
        }

        // === Background: Blur popup or system color ===
        val bgDrawable = dialogView.background as? android.graphics.drawable.GradientDrawable
        if (bgDrawable != null) {
            if (blurPopup) {
                bgDrawable.setColor(Color.argb(102, 30, 30, 30))
            } else {
                val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackgroundFloating))
                bgDrawable.setColor(ta.getColor(0, Color.BLACK))
                ta.recycle()
            }
        }

        // === Emoji: 2 Unicode codepoints max ===
        val emojiKey = when (position) {
            KeyHandler.POSITION_TOP -> "config_emoji_top"
            KeyHandler.POSITION_MIDDLE -> "config_emoji_middle"
            KeyHandler.POSITION_BOTTOM -> "config_emoji_bottom"
            else -> null
        }
        val rawEmoji = emojiKey?.let { Settings.System.getString(resolver, it) }
        val emoji = rawEmoji?.let {
            val codePoints = it.codePoints().toArray()
            if (codePoints.size > 2) String(codePoints.take(2).toIntArray(), 0, 2) else it
        }

        if (!emoji.isNullOrEmpty()) {
            emojiView.text = emoji
            emojiView.visibility = View.VISIBLE
            iconView.visibility = View.GONE
        } else {
            emojiView.visibility = View.GONE
            iconView.visibility = View.VISIBLE
            iconView.setImageResource(
                when (ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> R.drawable.ic_volume_ringer_mute
                    AudioManager.RINGER_MODE_VIBRATE -> R.drawable.ic_volume_ringer_vibrate
                    AudioManager.RINGER_MODE_NORMAL -> R.drawable.ic_volume_ringer
                    KeyHandler.ZEN_PRIORITY_ONLY -> R.drawable.ic_notifications_alert
                    KeyHandler.ZEN_TOTAL_SILENCE -> R.drawable.ic_notifications_silence
                    KeyHandler.ZEN_ALARMS_ONLY -> R.drawable.ic_alarm
                    else -> R.drawable.ic_info
                }
            )
        }

        // === Text label: only hidden when user explicitly enables "Hide Text Label" ===
        if (hideLabel) {
            textView.visibility = View.GONE
        } else {
            textView.visibility = View.VISIBLE
            textView.text = when (ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "Silent"
                AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
                AudioManager.RINGER_MODE_NORMAL -> "Ring"
                KeyHandler.ZEN_PRIORITY_ONLY -> "Priority Only"
                KeyHandler.ZEN_TOTAL_SILENCE -> "Total Silence"
                KeyHandler.ZEN_ALARMS_ONLY -> "Alarms Only"
                else -> "None"
            }
        }
    }

    companion object {
        private const val TAG = "AlertSliderDialog"
    }
}
