/*
 * SPDX-FileCopyrightText: 2021-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.device

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import java.io.File

class ButtonSettingsFragment : SettingsBasePreferenceFragment(), Preference.OnPreferenceChangeListener {
    private lateinit var topPositionPref: ListPreference
    private lateinit var middlePositionPref: ListPreference
    private lateinit var bottomPositionPref: ListPreference

    private lateinit var emojiTopPref: EditTextPreference
    private lateinit var emojiMiddlePref: EditTextPreference
    private lateinit var emojiBottomPref: EditTextPreference

    private var pendingLottiePosition: String? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.button_panel, rootKey)

        topPositionPref = findPreference("config_top_position")!!
        middlePositionPref = findPreference("config_middle_position")!!
        bottomPositionPref = findPreference("config_bottom_position")!!

        emojiTopPref = findPreference("config_emoji_top")!!
        emojiMiddlePref = findPreference("config_emoji_middle")!!
        emojiBottomPref = findPreference("config_emoji_bottom")!!

        topPositionPref.onPreferenceChangeListener = this
        middlePositionPref.onPreferenceChangeListener = this
        bottomPositionPref.onPreferenceChangeListener = this

        findPreference<SwitchPreferenceCompat>("config_alert_slider_island")?.onPreferenceChangeListener = this
        findPreference<SwitchPreferenceCompat>("config_alert_slider_glass")?.onPreferenceChangeListener = this
        findPreference<SwitchPreferenceCompat>("config_alert_slider_hide_label")?.onPreferenceChangeListener = this
        findPreference<SwitchPreferenceCompat>("config_mute_media")?.onPreferenceChangeListener = this
        findPreference<SwitchPreferenceCompat>("config_show_dialog")?.onPreferenceChangeListener = this
        emojiTopPref.onPreferenceChangeListener = this
        emojiMiddlePref.onPreferenceChangeListener = this
        emojiBottomPref.onPreferenceChangeListener = this

        emojiTopPref.widgetLayoutResource = R.layout.preference_widget_add
        emojiMiddlePref.widgetLayoutResource = R.layout.preference_widget_add
        emojiBottomPref.widgetLayoutResource = R.layout.preference_widget_add

        val resolver = requireContext().contentResolver
        findPreference<SwitchPreferenceCompat>("config_alert_slider_island")?.isChecked =
            Settings.System.getInt(resolver, "config_alert_slider_island", 0) != 0
        findPreference<SwitchPreferenceCompat>("config_alert_slider_glass")?.isChecked =
            Settings.System.getInt(resolver, "config_alert_slider_glass", 0) != 0
        findPreference<SwitchPreferenceCompat>("config_alert_slider_hide_label")?.isChecked =
            Settings.System.getInt(resolver, "config_alert_slider_hide_label", 0) != 0
        findPreference<SwitchPreferenceCompat>("config_mute_media")?.isChecked =
            Settings.System.getInt(resolver, "config_mute_media", 0) != 0
        findPreference<SwitchPreferenceCompat>("config_show_dialog")?.isChecked =
            Settings.System.getInt(resolver, "config_show_dialog", 1) != 0

        syncPositionToSystem(topPositionPref)
        syncPositionToSystem(middlePositionPref)
        syncPositionToSystem(bottomPositionPref)
    }

    override fun onResume() {
        super.onResume()
        syncEmojiSummaries()
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        // Long-press on emoji pref shows clear dialog if something is set
        val pos = when (preference.key) {
            "config_emoji_top" -> "top"
            "config_emoji_middle" -> "middle"
            "config_emoji_bottom" -> "bottom"
            else -> return super.onPreferenceTreeClick(preference)
        }
        val resolver = requireContext().contentResolver
        val hasLottie = Settings.System.getString(resolver, "config_lottie_$pos")
            ?.takeIf { it.isNotEmpty() } != null
        val hasEmoji = Settings.System.getString(resolver, "config_emoji_$pos")
            ?.takeIf { it.isNotEmpty() } != null

        if (hasLottie) {
            // If animated file is set, show options: replace or clear
            AlertDialog.Builder(requireContext())
                .setTitle("Custom animation")
                .setItems(arrayOf("Replace animation", "Clear animation")) { _, which ->
                    when (which) {
                        0 -> launchLottiePickerForPosition(pos)
                        1 -> {
                            Settings.System.putString(resolver, "config_lottie_$pos", "")
                            val destDir = File(requireContext().filesDir, "lottie")
                            File(destDir, "emoji_${pos}.webp").delete()
                            File(destDir, "emoji_${pos}.json").delete()
                            syncEmojiSummaries()
                            Toast.makeText(requireContext(), "Animation cleared", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .show()
            return true
        }
        // Default: open edit text dialog for emoji entry
        return super.onPreferenceTreeClick(preference)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.post { bindWidgetClicks() }
    }

    private fun bindWidgetClicks() {
        val rv = listView ?: return
        val emojiKeys = setOf("config_emoji_top", "config_emoji_middle", "config_emoji_bottom")
        val posMap = mapOf(
            "config_emoji_top" to "top",
            "config_emoji_middle" to "middle",
            "config_emoji_bottom" to "bottom"
        )

        rv.addOnChildAttachStateChangeListener(object :
            androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(child: View) {
                val btn = child.findViewById<View>(R.id.add_lottie_button) ?: return
                // Walk the adapter to find which preference key this row belongs to
                val holder = rv.getChildViewHolder(child) ?: return
                val pos = holder.bindingAdapterPosition
                if (pos < 0) return

                val pref = findPreferenceByFlatPosition(pos)
                val position = posMap[pref?.key] ?: return
                btn.setOnClickListener { launchLottiePickerForPosition(position) }
            }
            override fun onChildViewDetachedFromWindow(child: View) {}
        })
    }

    /** Flattens the preference tree correctly, counting category headers as positions */
    private fun findPreferenceByFlatPosition(targetPos: Int): Preference? {
        var count = 0
        for (i in 0 until preferenceScreen.preferenceCount) {
            val pref = preferenceScreen.getPreference(i)
            if (pref is androidx.preference.PreferenceGroup) {
                if (count == targetPos) return pref  // category header itself
                count++
                for (j in 0 until pref.preferenceCount) {
                    if (count == targetPos) return pref.getPreference(j)
                    count++
                }
            } else {
                if (count == targetPos) return pref
                count++
            }
        }
        return null
    }

    private fun syncPositionToSystem(pref: ListPreference) {
        val resolver = requireContext().contentResolver
        val current = Settings.System.getString(resolver, pref.key)
        if (current == null) {
            val value = pref.value ?: pref.entryValues?.firstOrNull()?.toString() ?: "0"
            Settings.System.putString(resolver, pref.key, value)
        }
    }

    private fun syncEmojiSummaries() {
        val resolver = requireContext().contentResolver
        listOf(
            emojiTopPref to "top",
            emojiMiddlePref to "middle",
            emojiBottomPref to "bottom"
        ).forEach { (pref, pos) ->
            val saved = Settings.System.getString(resolver, "config_emoji_$pos")
            val lottiePath = Settings.System.getString(resolver, "config_lottie_$pos")
            val lottieFile = lottiePath?.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.exists() }
            if (lottieFile != null) {
                pref.summary = "\uD83C\uDFA8 ${lottieFile.name} (tap to replace/clear)"
                pref.text = ""
            } else if (!saved.isNullOrEmpty()) {
                pref.summary = saved
                pref.text = saved
            } else {
                pref.summary = "Not set — type emoji or tap ＋ for animation"
                pref.text = ""
            }
        }
    }

    fun launchLottiePickerForPosition(position: String) {
        pendingLottiePosition = position
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "image/webp"))
        }
        startActivityForResult(intent, REQUEST_LOTTIE_PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_LOTTIE_PICK || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val position = pendingLottiePosition ?: return
        pendingLottiePosition = null

        try {
            importLottieFile(uri, position)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to import: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importLottieFile(uri: Uri, position: String) {
        val ctx = requireContext()
        val resolver = ctx.contentResolver

        val inputStream = resolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open file")
        val bytes = inputStream.readBytes()
        inputStream.close()

        if (bytes.size > MAX_LOTTIE_SIZE_BYTES) {
            Toast.makeText(ctx, "File too large (max 512KB)", Toast.LENGTH_LONG).show()
            return
        }

        val content = String(bytes).trim()
        val isJson = content.startsWith("{") && content.endsWith("}")
        val isWebP = bytes.size >= 4 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                     bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()

        if (isJson) {
            if (!content.contains("\"v\"") || !content.contains("\"layers\"")) {
                Toast.makeText(ctx, "Not a valid Lottie JSON", Toast.LENGTH_LONG).show()
                return
            }
        } else if (!isWebP) {
            Toast.makeText(ctx, "Use .json (Lottie) or .webp (animated)", Toast.LENGTH_LONG).show()
            return
        }

        val destDir = File(ctx.filesDir, "lottie")
        destDir.mkdirs()
        val ext = if (isJson) "json" else "webp"
        val destFile = File(destDir, "emoji_${position}.$ext")
        destFile.writeBytes(bytes)

        Settings.System.putString(resolver, "config_lottie_$position", destFile.absolutePath)

        syncEmojiSummaries()
        Toast.makeText(ctx, "Animation set for $position!", Toast.LENGTH_SHORT).show()
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        val resolver = requireContext().contentResolver

        when (preference.key) {
            "config_top_position", "config_middle_position", "config_bottom_position" -> {
                val value = newValue as? String ?: return true
                val (otherPref1, otherPref2) = when (preference.key) {
                    "config_top_position" -> Pair(middlePositionPref, bottomPositionPref)
                    "config_middle_position" -> Pair(topPositionPref, bottomPositionPref)
                    "config_bottom_position" -> Pair(topPositionPref, middlePositionPref)
                    else -> return true
                }
                if (value == otherPref1.value || value == otherPref2.value) {
                    Toast.makeText(requireContext(), R.string.alert_slider_action_already_mapped, Toast.LENGTH_SHORT).show()
                    return false
                }
                Settings.System.putString(resolver, preference.key, value)
            }
            "config_alert_slider_island", "config_alert_slider_glass", "config_alert_slider_hide_label" -> {
                Settings.System.putInt(resolver, preference.key, if (newValue as Boolean) 1 else 0)
            }
            "config_mute_media", "config_show_dialog" -> {
                Settings.System.putInt(resolver, preference.key, if (newValue as Boolean) 1 else 0)
            }
            "config_emoji_top", "config_emoji_middle", "config_emoji_bottom" -> {
                val emoji = newValue as String
                Settings.System.putString(resolver, preference.key, emoji)
                preference.summary = if (emoji.isNotEmpty()) emoji
                    else "Not set — type emoji or tap ＋ for animation"
            }
        }
        return true
    }

    companion object {
        private const val REQUEST_LOTTIE_PICK = 1001
        private const val MAX_LOTTIE_SIZE_BYTES = 512 * 1024
    }
}
