package dev.jdtech.jellyfin.presentation.player

import android.app.Dialog
import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.jdtech.jellyfin.player.local.R
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import java.util.Locale

class BitrateSelectionDialogFragment(private val viewModel: PlayerViewModel) : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val uiState = viewModel.uiState.value
        val sourceBitrate = uiState.sourceBitrate
        val originalLabel =
            if (sourceBitrate != null && sourceBitrate > 0) {
                val formatted =
                    if (sourceBitrate >= 1_000_000) {
                        String.format(Locale.US, "%.1f Mbps", sourceBitrate / 1_000_000.0)
                    } else {
                        "${sourceBitrate / 1_000} kbps"
                    }
                getString(R.string.bitrate_original_with_value, formatted)
            } else {
                getString(R.string.player_bitrate_original)
            }

        val bitrateTexts =
            listOf(
                originalLabel,
                "40 Mbps",
                "20 Mbps",
                "12 Mbps",
                "8 Mbps",
                "4 Mbps",
                "2 Mbps",
                "1.5 Mbps",
                "1 Mbps",
            )
        val bitrateValues =
            listOf(
                0,
                40_000_000,
                20_000_000,
                12_000_000,
                8_000_000,
                4_000_000,
                2_000_000,
                1_500_000,
                1_000_000,
            )

        val modeRes =
            if (uiState.isTranscoding) {
                R.string.bitrate_mode_transcode
            } else {
                R.string.bitrate_mode_direct
            }
        val modeText = getString(modeRes)
        val selectedIndex = bitrateValues.indexOf(viewModel.selectedBitrate)
        val checkedItem = if (selectedIndex >= 0) selectedIndex else 0

        return activity?.let { activity ->
            val builder = MaterialAlertDialogBuilder(activity)
            builder.setTitle("${getString(R.string.select_bitrate)} ($modeText)")
            val items = bitrateTexts.toTypedArray()
            builder.setSingleChoiceItems(items, checkedItem) { dialog, which ->
                viewModel.selectBitrate(bitrateValues[which])
                dialog.dismiss()
            }
            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    override fun onDestroy() {
        super.onDestroy()
        // Fix for hiding the system bars on API < 30
        activity?.window?.let {
            WindowCompat.getInsetsController(it, it.decorView).apply {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}
