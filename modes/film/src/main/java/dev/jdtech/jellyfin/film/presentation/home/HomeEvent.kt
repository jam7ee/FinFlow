package dev.jdtech.jellyfin.film.presentation.home

import dev.jdtech.jellyfin.network.AddressKind

sealed interface HomeEvent {
    data class AddressSwitched(val targetKind: AddressKind, val address: String) : HomeEvent

    data object AddressToggleUnavailable : HomeEvent
}
