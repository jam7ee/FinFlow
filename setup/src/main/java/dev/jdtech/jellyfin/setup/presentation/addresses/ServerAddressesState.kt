package dev.jdtech.jellyfin.setup.presentation.addresses

import dev.jdtech.jellyfin.models.ServerAddress
import java.util.UUID

data class ServerAddressesState(
    val serverId: String = "",
    val currentAddressId: UUID? = null,
    val addresses: List<ServerAddress> = emptyList(),
    val autoSwitchEnabled: Boolean = false,
    val localAddressId: UUID? = null,
    val externalAddressId: UUID? = null,
    val localWifiSsids: List<String> = emptyList(),
    val isValidatingAddress: Boolean = false,
    val pendingForceSaveAddress: String? = null,
    val pendingForceSaveError: String? = null,
    val errorMessage: String? = null,
)
