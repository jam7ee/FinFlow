package dev.jdtech.jellyfin.models

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import java.util.UUID

@Entity(tableName = "servers")
data class Server(
    @PrimaryKey val id: String,
    val name: String,
    var currentServerAddressId: UUID?,
    var currentUserId: UUID?,
    @ColumnInfo(defaultValue = "0") val autoSwitchAddress: Boolean = false,
    val localAddressId: UUID? = null,
    val externalAddressId: UUID? = null,
    @ColumnInfo(defaultValue = "''") val localWifiSsids: String = "",
)

fun Server.parsedLocalWifiSsids(): List<String> =
    localWifiSsids.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()

fun encodeLocalWifiSsids(ssids: List<String>): String =
    ssids.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")
