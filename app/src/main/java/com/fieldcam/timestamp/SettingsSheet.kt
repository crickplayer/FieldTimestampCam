package com.fieldcam.timestamp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings shown as a bottom sheet over the live camera. Because it is a dialog
 * (the activity stays resumed), every change applies to the live watermark
 * immediately — including while a video is being recorded.
 */
class SettingsSheet : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val height = (resources.displayMetrics.heightPixels * 0.8f).toInt()
        return FrameLayout(requireContext()).apply {
            id = View.generateViewId()
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
            minimumHeight = height
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            childFragmentManager.beginTransaction().replace(view.id, SettingsFragment()).commit()
        }
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        // Keep the camera bright behind the sheet so effect changes can be judged live.
        dialog?.window?.setDimAmount(0.15f)
    }
}

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        val activity = requireActivity() as MainActivity

        findPreference<ListPreference>("time_format")?.apply {
            val now = Date()
            entries = TimeFormats.PATTERNS.mapIndexed { i, p ->
                "${i + 1}. " + SimpleDateFormat(p, Locale.getDefault()).format(now)
            }.toTypedArray()
            entryValues = TimeFormats.PATTERNS.indices.map { it.toString() }.toTypedArray()
            if (value == null) value = "0"
        }

        findPreference<Preference>("pick_location")?.apply {
            summary = CameraSettings.load(requireContext()).customLocation.ifBlank { "Automatic (GPS address)" }
            setOnPreferenceClickListener { activity.showLocationPicker(); true }
        }
        findPreference<Preference>("pick_logo")?.setOnPreferenceClickListener { activity.pickLogo(); true }
        findPreference<Preference>("reset_index")?.setOnPreferenceClickListener {
            preferenceManager.sharedPreferences?.edit()?.putInt("photo_index", 1)?.apply()
            it.summary = "Next photo will be #0001"
            true
        }
        findPreference<SwitchPreferenceCompat>("save_to_sd")?.summaryOff =
            if (activity.hasSdCard()) "SD card detected – turn on to save photos & videos directly to it"
            else "No removable SD card detected (Android 10+ required)"
    }
}
