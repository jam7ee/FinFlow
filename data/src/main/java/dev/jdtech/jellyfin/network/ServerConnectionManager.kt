package dev.jdtech.jellyfin.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.parsedLocalWifiSsids
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

enum class NetworkTier {
    LOCAL,
    WIFI,
    MOBILE,
}

enum class AddressKind {
    LOCAL,
    EXTERNAL,
    OTHER,
}

data class ConnectionSnapshot(
    val serverId: String? = null,
    val currentAddressId: UUID? = null,
    val currentBaseUrl: String? = null,
    val addressKind: AddressKind = AddressKind.OTHER,
    val networkTier: NetworkTier = NetworkTier.WIFI,
    val currentSsid: String? = null,
    val canQuickToggle: Boolean = false,
    val nextToggleTargetKind: AddressKind? = null,
    val version: Long = 0L,
)

sealed interface QuickToggleResult {
    data class Switched(val targetKind: AddressKind, val newAddress: String) : QuickToggleResult

    data object NotEnoughAddresses : QuickToggleResult

    data object NoActiveServer : QuickToggleResult
}

/**
 * Manages automatic LAN/External URL switching per server, combined A+B LAN detection for playback
 * bitrate tiers, and manual quick-toggling between LAN and External server addresses.
 */
class ServerConnectionManager(
    private val context: Context,
    private val jellyfinApi: JellyfinApi,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var debounceJob: Job? = null

    @Volatile private var lastKnownCapabilities: NetworkCapabilities? = null

    @Volatile private var lastKnownSsid: String? = null

    @Volatile private var isMonitoring = false

    private var manualOverrideFingerprint: String? = null

    private val _snapshot = MutableStateFlow(ConnectionSnapshot())
    val snapshot: StateFlow<ConnectionSnapshot> = _snapshot.asStateFlow()

    fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun isLocationServiceEnabled(): Boolean =
        try {
            context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
        } catch (_: Exception) {
            false
        }

    fun getCurrentWifiSsid(): String? {
        if (!hasLocationPermission() || !isLocationServiceEnabled()) return null

        val cm = context.getSystemService(ConnectivityManager::class.java)
        val activeCaps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val isWifi =
            (activeCaps ?: lastKnownCapabilities)?.hasTransport(
                NetworkCapabilities.TRANSPORT_WIFI,
            ) == true
        if (!isWifi) return null

        val fromActiveCaps = normalizeWifiSsid((activeCaps?.transportInfo as? WifiInfo)?.ssid)
        if (fromActiveCaps != null) return fromActiveCaps

        val fromCallback = normalizeWifiSsid(lastKnownSsid)
        if (fromCallback != null) return fromCallback

        return try {
            @Suppress("DEPRECATION")
            val wifiInfo =
                context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
            normalizeWifiSsid(wifiInfo?.ssid)
        } catch (_: Exception) {
            null
        }
    }

    fun startMonitoring() {
        if (isMonitoring) return
        isMonitoring = true

        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm != null) {
            val callback =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                        override fun onAvailable(network: Network) {
                            updateCallbackState(cm.getNetworkCapabilities(network))
                        }

                        override fun onCapabilitiesChanged(
                            network: Network,
                            networkCapabilities: NetworkCapabilities,
                        ) {
                            updateCallbackState(networkCapabilities)
                        }

                        override fun onLost(network: Network) {
                            lastKnownCapabilities =
                                cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
                            lastKnownSsid = null
                            scheduleEvaluation()
                        }
                    }
                } else {
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            updateCallbackState(cm.getNetworkCapabilities(network))
                        }

                        override fun onCapabilitiesChanged(
                            network: Network,
                            networkCapabilities: NetworkCapabilities,
                        ) {
                            updateCallbackState(networkCapabilities)
                        }

                        override fun onLost(network: Network) {
                            lastKnownCapabilities =
                                cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
                            lastKnownSsid = null
                            scheduleEvaluation()
                        }
                    }
                }

            try {
                cm.registerDefaultNetworkCallback(callback)
            } catch (e: Exception) {
                Timber.w(e, "Failed to register default network callback")
            }
        }

        scope.launch { evaluateAndApply(force = false) }
    }

    fun triggerRefresh() {
        scope.launch { evaluateAndApply(force = false) }
    }

    private fun updateCallbackState(caps: NetworkCapabilities?) {
        lastKnownCapabilities = caps
        lastKnownSsid =
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                normalizeWifiSsid((caps.transportInfo as? WifiInfo)?.ssid)
            } else {
                null
            }
        scheduleEvaluation()
    }

    private fun scheduleEvaluation() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(250L)
            evaluateAndApply(force = false)
        }
    }

    fun getCurrentNetworkTier(): NetworkTier {
        val snap = _snapshot.value
        if (snap.version > 0L) return snap.networkTier
        val caps = readActiveCapabilities()
        return resolveNetworkTier(
            server = null,
            currentAddress = null,
            caps = caps,
            ssid = getCurrentWifiSsid(),
        )
    }

    /**
     * Combined A + B LAN detection:
     * 1. Cellular-only networks always resolve to MOBILE (even with VPN).
     * 2. On Wi-Fi: if server has configured LAN SSIDs and current SSID is readable (B), trust SSID
     *    matching so VPN over external Wi-Fi does not falsely match LAN. Otherwise fall back to
     *    (A): non-VPN local/private address or tagged LAN address -> LOCAL.
     */
    fun resolveNetworkTier(
        server: Server?,
        currentAddress: ServerAddress?,
        caps: NetworkCapabilities?,
        ssid: String?,
    ): NetworkTier {
        if (caps == null) return NetworkTier.WIFI

        val hasWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val hasEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val hasCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val hasVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val isUnmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        if (hasCellular && !hasWifi && !hasEthernet) {
            return NetworkTier.MOBILE
        }

        if (hasWifi) {
            val configuredSsids = server?.parsedLocalWifiSsids().orEmpty()
            if (configuredSsids.isNotEmpty() && ssid != null) {
                return if (configuredSsids.any { it == ssid }) {
                    NetworkTier.LOCAL
                } else {
                    NetworkTier.WIFI
                }
            }
            if (hasVpn) return NetworkTier.WIFI
            return if (isLocalServerAddress(server, currentAddress)) {
                NetworkTier.LOCAL
            } else {
                NetworkTier.WIFI
            }
        }

        if (hasEthernet || (isUnmetered && !hasCellular)) {
            if (!hasVpn && isLocalServerAddress(server, currentAddress)) {
                return NetworkTier.LOCAL
            }
            return NetworkTier.WIFI
        }

        return NetworkTier.MOBILE
    }

    suspend fun probeServerAddress(address: String, expectedServerId: String): Boolean =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val base = address.trim().trimEnd('/')
                val url = URL("$base/System/Info/Public")
                connection =
                    (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = PROBE_CONNECT_TIMEOUT_MS
                        readTimeout = PROBE_READ_TIMEOUT_MS
                        instanceFollowRedirects = true
                    }
                val code = connection.responseCode
                if (code !in 200..299) return@withContext false
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val id =
                    Json.parseToJsonElement(body).jsonObject["Id"]?.jsonPrimitive?.contentOrNull
                id != null &&
                    id.replace("-", "").equals(expectedServerId.replace("-", ""), ignoreCase = true)
            } catch (e: Throwable) {
                Timber.d("LAN probe failed for $address: ${e.message}")
                false
            } finally {
                connection?.disconnect()
            }
        }

    suspend fun evaluateAndApply(force: Boolean = false) {
        mutex.withLock {
            val serverId = appPreferences.getValue(appPreferences.currentServer)
            if (serverId == null) {
                emitSnapshot(ConnectionSnapshot())
                return
            }

            val serverWithAddresses =
                try {
                    database.getServerWithAddresses(serverId)
                } catch (_: Exception) {
                    emitSnapshot(ConnectionSnapshot())
                    return
                }

            val server = serverWithAddresses.server
            val addresses = serverWithAddresses.addresses
            val caps = readActiveCapabilities()
            val ssid = getCurrentWifiSsid()
            val fingerprint = computeFingerprint(caps, ssid)

            if (force) {
                manualOverrideFingerprint = null
            }

            val skipAutoSwitch =
                if (manualOverrideFingerprint != null) {
                    if (manualOverrideFingerprint == fingerprint) {
                        true
                    } else {
                        manualOverrideFingerprint = null
                        false
                    }
                } else {
                    false
                }

            var activeAddr =
                addresses.firstOrNull { it.id == server.currentServerAddressId }
                    ?: addresses.firstOrNull()

            val offlineMode = appPreferences.getValue(appPreferences.offlineMode)
            if (!skipAutoSwitch && !offlineMode && server.autoSwitchAddress && caps != null) {
                val localAddr =
                    server.localAddressId?.let { id -> addresses.firstOrNull { it.id == id } }
                val externalAddr =
                    server.externalAddressId?.let { id -> addresses.firstOrNull { it.id == id } }
                val configuredSsids = server.parsedLocalWifiSsids()

                val hasWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val hasCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

                val targetAddr: ServerAddress? =
                    when {
                        hasWifi -> {
                            when {
                                // Keep current URL unchanged if SSID cannot be read
                                ssid == null -> null
                                configuredSsids.isNotEmpty() &&
                                    configuredSsids.any { it == ssid } -> {
                                    if (localAddr != null) {
                                        if (probeServerAddress(localAddr.address, server.id)) {
                                            localAddr
                                        } else {
                                            externalAddr
                                        }
                                    } else {
                                        null
                                    }
                                }
                                else -> externalAddr
                            }
                        }
                        hasCellular -> externalAddr
                        else -> null
                    }

                if (
                    targetAddr != null &&
                        (targetAddr.id != server.currentServerAddressId ||
                            jellyfinApi.api.baseUrl != targetAddr.address)
                ) {
                    Timber.i("Auto-switching server address to ${targetAddr.address}")
                    database.updateServerCurrentAddress(server.id, targetAddr.id)
                    jellyfinApi.api.update(baseUrl = targetAddr.address)
                    activeAddr = targetAddr
                }
            }

            emitComputedSnapshot(server, addresses, activeAddr, caps, ssid)
        }
    }

    suspend fun quickToggleLocalExternal(): QuickToggleResult {
        mutex.withLock {
            val serverId =
                appPreferences.getValue(appPreferences.currentServer)
                    ?: return QuickToggleResult.NoActiveServer
            val serverWithAddresses =
                try {
                    database.getServerWithAddresses(serverId)
                } catch (_: Exception) {
                    return QuickToggleResult.NoActiveServer
                }

            val server = serverWithAddresses.server
            val addresses = serverWithAddresses.addresses
            val localAddr =
                server.localAddressId?.let { id -> addresses.firstOrNull { it.id == id } }
            val externalAddr =
                server.externalAddressId?.let { id -> addresses.firstOrNull { it.id == id } }

            val (targetAddr, targetKind) =
                if (localAddr != null && externalAddr != null && localAddr.id != externalAddr.id) {
                    if (server.currentServerAddressId == localAddr.id) {
                        externalAddr to AddressKind.EXTERNAL
                    } else {
                        localAddr to AddressKind.LOCAL
                    }
                } else if (addresses.size >= 2) {
                    val curIdx = addresses.indexOfFirst { it.id == server.currentServerAddressId }
                    val nextAddr = addresses[(curIdx + 1) % addresses.size]
                    nextAddr to classifyAddressKind(server, nextAddr)
                } else {
                    return QuickToggleResult.NotEnoughAddresses
                }

            database.updateServerCurrentAddress(server.id, targetAddr.id)
            jellyfinApi.api.update(baseUrl = targetAddr.address)

            val caps = readActiveCapabilities()
            val ssid = getCurrentWifiSsid()
            manualOverrideFingerprint = computeFingerprint(caps, ssid)

            emitComputedSnapshot(
                server.copy(currentServerAddressId = targetAddr.id),
                addresses,
                targetAddr,
                caps,
                ssid,
            )
            return QuickToggleResult.Switched(targetKind, targetAddr.address)
        }
    }

    suspend fun onManualAddressSelected(serverId: String, addressId: UUID) {
        mutex.withLock {
            val activeServerId = appPreferences.getValue(appPreferences.currentServer)
            if (activeServerId != serverId) return

            val caps = readActiveCapabilities()
            val ssid = getCurrentWifiSsid()
            manualOverrideFingerprint = computeFingerprint(caps, ssid)

            val serverWithAddresses =
                try {
                    database.getServerWithAddresses(serverId)
                } catch (_: Exception) {
                    return
                }
            val targetAddr =
                serverWithAddresses.addresses.firstOrNull { it.id == addressId } ?: return
            emitComputedSnapshot(
                serverWithAddresses.server.copy(currentServerAddressId = addressId),
                serverWithAddresses.addresses,
                targetAddr,
                caps,
                ssid,
            )
        }
    }

    suspend fun onServerChanged() {
        evaluateAndApply(force = true)
    }

    private fun emitComputedSnapshot(
        server: Server,
        addresses: List<ServerAddress>,
        activeAddr: ServerAddress?,
        caps: NetworkCapabilities?,
        ssid: String?,
    ) {
        val addressKind = classifyAddressKind(server, activeAddr)
        val networkTier = resolveNetworkTier(server, activeAddr, caps, ssid)
        val localAddr = server.localAddressId?.let { id -> addresses.firstOrNull { it.id == id } }
        val externalAddr =
            server.externalAddressId?.let { id -> addresses.firstOrNull { it.id == id } }

        val (canQuickToggle, nextToggleTargetKind) =
            if (localAddr != null && externalAddr != null && localAddr.id != externalAddr.id) {
                val target =
                    if (activeAddr?.id == localAddr.id) {
                        AddressKind.EXTERNAL
                    } else {
                        AddressKind.LOCAL
                    }
                true to target
            } else if (addresses.size >= 2) {
                val curIdx = addresses.indexOfFirst { it.id == activeAddr?.id }
                val nextAddr = addresses[(curIdx + 1) % addresses.size]
                true to classifyAddressKind(server, nextAddr)
            } else {
                false to null
            }

        emitSnapshot(
            ConnectionSnapshot(
                serverId = server.id,
                currentAddressId = activeAddr?.id,
                currentBaseUrl = activeAddr?.address ?: jellyfinApi.api.baseUrl,
                addressKind = addressKind,
                networkTier = networkTier,
                currentSsid = ssid,
                canQuickToggle = canQuickToggle,
                nextToggleTargetKind = nextToggleTargetKind,
                version = _snapshot.value.version,
            ),
        )
    }

    private fun emitSnapshot(candidate: ConnectionSnapshot) {
        val prev = _snapshot.value
        val changed =
            prev.serverId != candidate.serverId ||
                prev.currentAddressId != candidate.currentAddressId ||
                prev.currentBaseUrl != candidate.currentBaseUrl ||
                prev.addressKind != candidate.addressKind ||
                prev.networkTier != candidate.networkTier ||
                prev.canQuickToggle != candidate.canQuickToggle ||
                prev.nextToggleTargetKind != candidate.nextToggleTargetKind ||
                prev.version == 0L
        val nextVersion = if (changed) prev.version + 1L else prev.version
        _snapshot.value = candidate.copy(version = nextVersion)
    }

    private fun readActiveCapabilities(): NetworkCapabilities? {
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: lastKnownCapabilities
        } catch (_: Exception) {
            lastKnownCapabilities
        }
    }

    private fun computeFingerprint(caps: NetworkCapabilities?, ssid: String?): String {
        if (caps == null) return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi:${ssid ?: "unknown"}"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            else -> "other"
        }
    }

    private fun isLocalServerAddress(server: Server?, address: ServerAddress?): Boolean {
        if (address == null) return false
        if (server?.localAddressId != null && address.id == server.localAddressId) return true
        return isPrivateOrLocalAddress(address.address)
    }

    private fun classifyAddressKind(server: Server, address: ServerAddress?): AddressKind {
        if (address == null) return AddressKind.OTHER
        if (server.localAddressId != null && address.id == server.localAddressId) {
            return AddressKind.LOCAL
        }
        if (server.externalAddressId != null && address.id == server.externalAddressId) {
            return AddressKind.EXTERNAL
        }
        return if (isPrivateOrLocalAddress(address.address)) {
            AddressKind.LOCAL
        } else {
            AddressKind.EXTERNAL
        }
    }

    companion object {
        private const val PROBE_CONNECT_TIMEOUT_MS = 1500
        private const val PROBE_READ_TIMEOUT_MS = 2000
    }
}
