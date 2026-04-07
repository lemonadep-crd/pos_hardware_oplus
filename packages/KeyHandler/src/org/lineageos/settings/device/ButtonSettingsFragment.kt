/*
 * SPDX-FileCopyrightText: 2021-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.device

import android.os.Bundle
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.android.settingslib.widget.SettingsBasePreferenceFragment

class ButtonSettingsFragment : SettingsBasePreferenceFragment(), Preference.OnPreferenceChangeListener {
    private lateinit var topPositionPref: ListPreference
    private lateinit var middlePositionPref: ListPreference
    private lateinit var bottomPositionPref: ListPreference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.button_panel, rootKey)

        topPositionPref = findPreference("config_top_position")!!
        middlePositionPref = findPreference("config_middle_position")!!
        bottomPositionPref = findPreference("config_bottom_position")!!

        topPositionPref.onPreferenceChangeListener = this
        middlePositionPref.onPreferenceChangeListener = this
        bottomPositionPref.onPreferenceChangeListener = this
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        val value = newValue as? String ?: return true

        val (otherPref1, otherPref2) = when (preference.key) {
            "config_top_position" -> Pair(middlePositionPref, bottomPositionPref)
            "config_middle_position" -> Pair(topPositionPref, bottomPositionPref)
            "config_bottom_position" -> Pair(topPositionPref, middlePositionPref)
            else -> return true
        }

        if (value == otherPref1.value || value == otherPref2.value) {
            Toast.makeText(
                requireContext(),
                R.string.alert_slider_action_already_mapped,
                Toast.LENGTH_SHORT
            ).show()
            return false
        }

        return true
    }
}
