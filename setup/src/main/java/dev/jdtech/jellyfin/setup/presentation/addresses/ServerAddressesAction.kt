package dev.jdtech.jellyfin.setup.presentation.addresses

import java.util.UUID

sealed interface ServerAddressesAction {
    data class OnServerClick(val addressId: UUID) : ServerAddressesAction

    data class AddAddress(val address: String) : ServerAddressesAction

    data class ConfirmForceAddAddress(val address: String) : ServerAddressesAction

    data object DismissForceAddAddress : ServerAddressesAction

    data class DeleteAddress(val addressId: UUID) : ServerAddressesAction

    data class SetLocalAddress(val addressId: UUID?) : ServerAddressesAction

    data class SetExternalAddress(val addressId: UUID?) : ServerAddressesAction

    data class SetAutoSwitchEnabled(val enabled: Boolean) : ServerAddressesAction

    data class AddWifiSsid(val ssid: String) : ServerAddressesAction

    data class RemoveWifiSsid(val ssid: String) : ServerAddressesAction

    data object DismissError : ServerAddressesAction

    data object OnBackClick : ServerAddressesAction
}
