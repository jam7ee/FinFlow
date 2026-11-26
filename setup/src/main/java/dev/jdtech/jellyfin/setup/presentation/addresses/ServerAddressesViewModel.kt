package dev.jdtech.jellyfin.setup.presentation.addresses

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.encodeLocalWifiSsids
import dev.jdtech.jellyfin.models.parsedLocalWifiSsids
import dev.jdtech.jellyfin.network.ServerConnectionManager
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.setup.R as SetupR
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class ServerAddressesViewModel
@Inject
constructor(
    val application: Application,
    private val database: ServerDatabaseDao,
    private val jellyfinApi: JellyfinApi,
    private val appPreferences: AppPreferences,
    private val connectionManager: ServerConnectionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(ServerAddressesState())
    val state = _state.asStateFlow()

    private var currentServerId: String = ""

    fun loadAddresses(serverId: String) {
        currentServerId = serverId
        viewModelScope.launch { reloadServerState() }
    }

    private suspend fun reloadServerState() {
        try {
            val serverWithAddresses = database.getServerWithAddresses(currentServerId)
            val server = serverWithAddresses.server
            _state.update {
                it.copy(
                    serverId = server.id,
                    currentAddressId = server.currentServerAddressId,
                    addresses = serverWithAddresses.addresses,
                    autoSwitchEnabled = server.autoSwitchAddress,
                    localAddressId = server.localAddressId,
                    externalAddressId = server.externalAddressId,
                    localWifiSsids = server.parsedLocalWifiSsids(),
                )
            }
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    fun deleteAddress(addressId: UUID) {
        viewModelScope.launch(Dispatchers.IO) {
            val server = database.getServer(currentServerId) ?: return@launch
            if (addressId == server.currentServerAddressId) {
                _state.update {
                    it.copy(
                        errorMessage =
                            application.getString(SetupR.string.cannot_delete_current_address),
                    )
                }
                return@launch
            }
            val newLocalId = if (server.localAddressId == addressId) null else server.localAddressId
            val newExternalId =
                if (server.externalAddressId == addressId) null else server.externalAddressId
            if (newLocalId != server.localAddressId || newExternalId != server.externalAddressId) {
                database.updateServerNetworkConfig(
                    serverId = currentServerId,
                    enabled = server.autoSwitchAddress,
                    localAddressId = newLocalId,
                    externalAddressId = newExternalId,
                    localWifiSsids = server.localWifiSsids,
                )
            }
            database.deleteServerAddress(addressId)
            reloadServerState()
            refreshConnectionIfActive()
        }
    }

    fun addAddress(address: String) {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return
        if (_state.value.addresses.any { it.address.equals(trimmed, ignoreCase = true) }) {
            _state.update {
                it.copy(errorMessage = application.getString(SetupR.string.address_already_exists))
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isValidatingAddress = true, errorMessage = null) }
            try {
                val tempApi =
                    JellyfinApi(
                        androidContext = application.applicationContext,
                        requestTimeout = 5_000L,
                        connectTimeout = 4_000L,
                        socketTimeout = 5_000L,
                    )
                tempApi.api.update(baseUrl = trimmed)
                val systemInfo by tempApi.systemApi.getPublicSystemInfo()
                if (systemInfo.id != currentServerId) {
                    _state.update {
                        it.copy(
                            isValidatingAddress = false,
                            errorMessage =
                                application.getString(SetupR.string.address_server_id_mismatch),
                        )
                    }
                    return@launch
                }
                val serverAddress = ServerAddress(UUID.randomUUID(), currentServerId, trimmed)
                database.insertServerAddress(serverAddress)
                _state.update { it.copy(isValidatingAddress = false) }
                reloadServerState()
            } catch (e: Exception) {
                Timber.w(e, "Address validation failed for $trimmed")
                _state.update {
                    it.copy(
                        isValidatingAddress = false,
                        pendingForceSaveAddress = trimmed,
                        pendingForceSaveError = e.localizedMessage ?: e.javaClass.simpleName,
                    )
                }
            }
        }
    }

    private fun confirmForceAddAddress(address: String) {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val serverAddress = ServerAddress(UUID.randomUUID(), currentServerId, trimmed)
            database.insertServerAddress(serverAddress)
            _state.update { it.copy(pendingForceSaveAddress = null, pendingForceSaveError = null) }
            reloadServerState()
        }
    }

    private fun selectCurrentAddress(addressId: UUID) {
        viewModelScope.launch(Dispatchers.IO) {
            val address = database.getAddress(addressId)
            if (address.serverId != currentServerId) return@launch
            database.updateServerCurrentAddress(currentServerId, addressId)
            if (appPreferences.getValue(appPreferences.currentServer) == currentServerId) {
                jellyfinApi.api.update(baseUrl = address.address)
                connectionManager.onManualAddressSelected(currentServerId, addressId)
            }
            reloadServerState()
        }
    }

    private fun setLocalAddress(addressId: UUID?) {
        val current = _state.value
        val newExternal =
            if (addressId != null && current.externalAddressId == addressId) null
            else current.externalAddressId
        updateNetworkConfig(
            enabled = current.autoSwitchEnabled,
            localAddressId = addressId,
            externalAddressId = newExternal,
            ssids = current.localWifiSsids,
        )
    }

    private fun setExternalAddress(addressId: UUID?) {
        val current = _state.value
        val newLocal =
            if (addressId != null && current.localAddressId == addressId) null
            else current.localAddressId
        updateNetworkConfig(
            enabled = current.autoSwitchEnabled,
            localAddressId = newLocal,
            externalAddressId = addressId,
            ssids = current.localWifiSsids,
        )
    }

    private fun setAutoSwitchEnabled(enabled: Boolean) {
        val current = _state.value
        updateNetworkConfig(
            enabled = enabled,
            localAddressId = current.localAddressId,
            externalAddressId = current.externalAddressId,
            ssids = current.localWifiSsids,
        )
    }

    private fun addWifiSsid(ssid: String) {
        val trimmed = ssid.trim()
        if (trimmed.isEmpty()) return
        val current = _state.value
        val updated = (current.localWifiSsids + trimmed).distinct()
        updateNetworkConfig(
            enabled = current.autoSwitchEnabled,
            localAddressId = current.localAddressId,
            externalAddressId = current.externalAddressId,
            ssids = updated,
        )
    }

    private fun removeWifiSsid(ssid: String) {
        val current = _state.value
        val updated = current.localWifiSsids.filterNot { it == ssid }
        updateNetworkConfig(
            enabled = current.autoSwitchEnabled,
            localAddressId = current.localAddressId,
            externalAddressId = current.externalAddressId,
            ssids = updated,
        )
    }

    private fun updateNetworkConfig(
        enabled: Boolean,
        localAddressId: UUID?,
        externalAddressId: UUID?,
        ssids: List<String>,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            database.updateServerNetworkConfig(
                serverId = currentServerId,
                enabled = enabled,
                localAddressId = localAddressId,
                externalAddressId = externalAddressId,
                localWifiSsids = encodeLocalWifiSsids(ssids),
            )
            reloadServerState()
            refreshConnectionIfActive()
        }
    }

    private suspend fun refreshConnectionIfActive() {
        if (appPreferences.getValue(appPreferences.currentServer) == currentServerId) {
            connectionManager.evaluateAndApply(force = true)
        }
    }

    fun hasLocationPermission(): Boolean = connectionManager.hasLocationPermission()

    fun isLocationServiceEnabled(): Boolean = connectionManager.isLocationServiceEnabled()

    fun getCurrentWifiSsid(): String? = connectionManager.getCurrentWifiSsid()

    fun onAction(action: ServerAddressesAction) {
        when (action) {
            is ServerAddressesAction.OnServerClick -> selectCurrentAddress(action.addressId)
            is ServerAddressesAction.AddAddress -> addAddress(action.address)
            is ServerAddressesAction.ConfirmForceAddAddress ->
                confirmForceAddAddress(action.address)
            is ServerAddressesAction.DismissForceAddAddress -> {
                _state.update {
                    it.copy(pendingForceSaveAddress = null, pendingForceSaveError = null)
                }
            }
            is ServerAddressesAction.DeleteAddress -> deleteAddress(action.addressId)
            is ServerAddressesAction.SetLocalAddress -> setLocalAddress(action.addressId)
            is ServerAddressesAction.SetExternalAddress -> setExternalAddress(action.addressId)
            is ServerAddressesAction.SetAutoSwitchEnabled -> setAutoSwitchEnabled(action.enabled)
            is ServerAddressesAction.AddWifiSsid -> addWifiSsid(action.ssid)
            is ServerAddressesAction.RemoveWifiSsid -> removeWifiSsid(action.ssid)
            is ServerAddressesAction.DismissError -> _state.update { it.copy(errorMessage = null) }
            else -> Unit
        }
    }
}
