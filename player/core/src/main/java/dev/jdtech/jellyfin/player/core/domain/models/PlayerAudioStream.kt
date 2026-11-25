package dev.jdtech.jellyfin.player.core.domain.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class PlayerAudioStream(
    val index: Int,
    val displayTitle: String,
    val language: String,
    val codec: String,
) : Parcelable
