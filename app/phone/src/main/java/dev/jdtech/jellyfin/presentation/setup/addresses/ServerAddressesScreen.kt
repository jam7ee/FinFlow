package dev.jdtech.jellyfin.presentation.setup.addresses

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyServerAddress
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.presentation.components.BaseDialog
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.presentation.addresses.ServerAddressesAction
import dev.jdtech.jellyfin.setup.presentation.addresses.ServerAddressesState
import dev.jdtech.jellyfin.setup.presentation.addresses.ServerAddressesViewModel

@Composable
fun ServerAddressesScreen(
    serverId: String,
    navigateBack: () -> Unit,
    viewModel: ServerAddressesViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    var hasLocationPermission by remember { mutableStateOf(viewModel.hasLocationPermission()) }
    var isLocationEnabled by remember { mutableStateOf(viewModel.isLocationServiceEnabled()) }
    var pendingAddCurrentWifi by remember { mutableStateOf(false) }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            hasLocationPermission = viewModel.hasLocationPermission()
            isLocationEnabled = viewModel.isLocationServiceEnabled()
            if (pendingAddCurrentWifi) {
                pendingAddCurrentWifi = false
                if (hasLocationPermission && isLocationEnabled) {
                    val ssid = viewModel.getCurrentWifiSsid()
                    if (ssid != null) {
                        viewModel.onAction(ServerAddressesAction.AddWifiSsid(ssid))
                    } else {
                        Toast.makeText(
                                context,
                                context.getString(SetupR.string.wifi_ssid_unavailable),
                                Toast.LENGTH_SHORT,
                            )
                            .show()
                    }
                }
            }
        }

    LaunchedEffect(serverId) { viewModel.loadAddresses(serverId) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasLocationPermission = viewModel.hasLocationPermission()
        isLocationEnabled = viewModel.isLocationServiceEnabled()
    }

    ServerAddressesLayout(
        state = state,
        hasLocationPermission = hasLocationPermission,
        isLocationEnabled = isLocationEnabled,
        onRequestLocationPermission = {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        },
        onOpenLocationSettings = {
            try {
                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } catch (_: Exception) {}
        },
        onAddCurrentWifiClick = {
            if (!viewModel.hasLocationPermission()) {
                pendingAddCurrentWifi = true
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            } else if (!viewModel.isLocationServiceEnabled()) {
                Toast.makeText(
                        context,
                        context.getString(SetupR.string.location_service_disabled_summary),
                        Toast.LENGTH_SHORT,
                    )
                    .show()
                try {
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } catch (_: Exception) {}
            } else {
                val ssid = viewModel.getCurrentWifiSsid()
                if (ssid != null) {
                    viewModel.onAction(ServerAddressesAction.AddWifiSsid(ssid))
                } else {
                    Toast.makeText(
                            context,
                            context.getString(SetupR.string.wifi_ssid_unavailable),
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                }
            }
        },
        onAction = { action ->
            when (action) {
                is ServerAddressesAction.OnBackClick -> navigateBack()
                else -> Unit
            }
            viewModel.onAction(action)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ServerAddressesLayout(
    state: ServerAddressesState,
    hasLocationPermission: Boolean = true,
    isLocationEnabled: Boolean = true,
    onRequestLocationPermission: () -> Unit = {},
    onOpenLocationSettings: () -> Unit = {},
    onAddCurrentWifiClick: () -> Unit = {},
    onAction: (ServerAddressesAction) -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val safePadding = rememberSafePadding()

    val paddingStart = safePadding.start + MaterialTheme.spacings.default
    val paddingTop = MaterialTheme.spacings.default
    val paddingEnd = safePadding.end + MaterialTheme.spacings.default
    val paddingBottom = safePadding.bottom + MaterialTheme.spacings.default + 72.dp

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var selectedAddress by remember { mutableStateOf<ServerAddress?>(null) }
    var openAddDialog by remember { mutableStateOf(false) }
    var openDeleteDialog by remember { mutableStateOf(false) }
    var openAddSsidDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(SetupR.string.addresses)) },
                navigationIcon = {
                    IconButton(onClick = { onAction(ServerAddressesAction.OnBackClick) }) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_arrow_left),
                            contentDescription = null,
                        )
                    }
                },
                windowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(SetupR.string.add_address)) },
                icon = {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_plus),
                        contentDescription = null,
                    )
                },
                onClick = { openAddDialog = true },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(top = innerPadding.calculateTopPadding())) {
            if (state.isValidatingAddress) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(
                        start = paddingStart + innerPadding.calculateStartPadding(layoutDirection),
                        top = paddingTop,
                        end = paddingEnd + innerPadding.calculateEndPadding(layoutDirection),
                        bottom = paddingBottom + innerPadding.calculateBottomPadding(),
                    ),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
            ) {
                items(items = state.addresses, key = { it.id }) { address ->
                    val isCurrent = address.id == state.currentAddressId
                    val isLocal = address.id == state.localAddressId
                    val isExternal = address.id == state.externalAddressId

                    OutlinedCard(
                        onClick = { onAction(ServerAddressesAction.OnServerClick(address.id)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(MaterialTheme.spacings.medium),
                            verticalArrangement =
                                Arrangement.spacedBy(MaterialTheme.spacings.small),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = address.address,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                if (!isCurrent) {
                                    IconButton(
                                        onClick = {
                                            selectedAddress = address
                                            openDeleteDialog = true
                                        },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(CoreR.drawable.ic_trash),
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }

                            FlowRow(
                                horizontalArrangement =
                                    Arrangement.spacedBy(MaterialTheme.spacings.small),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (isCurrent) {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                    ) {
                                        Text(
                                            text =
                                                stringResource(SetupR.string.address_badge_current),
                                            modifier =
                                                Modifier.padding(
                                                    horizontal = 6.dp,
                                                    vertical = 2.dp,
                                                ),
                                        )
                                    }
                                }
                                FilterChip(
                                    selected = isLocal,
                                    onClick = {
                                        onAction(
                                            ServerAddressesAction.SetLocalAddress(
                                                if (isLocal) null else address.id,
                                            ),
                                        )
                                    },
                                    label = {
                                        Text(stringResource(SetupR.string.set_as_local_address))
                                    },
                                )
                                FilterChip(
                                    selected = isExternal,
                                    onClick = {
                                        onAction(
                                            ServerAddressesAction.SetExternalAddress(
                                                if (isExternal) null else address.id,
                                            ),
                                        )
                                    },
                                    label = {
                                        Text(stringResource(SetupR.string.set_as_external_address))
                                    },
                                )
                            }
                        }
                    }
                }

                item(key = "auto_switch_config") {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(MaterialTheme.spacings.medium),
                            verticalArrangement =
                                Arrangement.spacedBy(MaterialTheme.spacings.small),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text(
                                        text = stringResource(SetupR.string.auto_switch_url_title),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text =
                                            stringResource(SetupR.string.auto_switch_url_summary),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = state.autoSwitchEnabled,
                                    onCheckedChange = {
                                        onAction(ServerAddressesAction.SetAutoSwitchEnabled(it))
                                    },
                                )
                            }

                            if (
                                state.autoSwitchEnabled &&
                                    (state.localAddressId == null ||
                                        state.externalAddressId == null)
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text =
                                            stringResource(
                                                SetupR.string.auto_switch_missing_addresses_hint,
                                            ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(MaterialTheme.spacings.small),
                                    )
                                }
                            }

                            if (state.autoSwitchEnabled && !hasLocationPermission) {
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(MaterialTheme.spacings.small),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text =
                                                stringResource(
                                                    SetupR.string
                                                        .location_permission_required_title,
                                                ),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                        Text(
                                            text =
                                                stringResource(
                                                    SetupR.string
                                                        .location_permission_required_summary,
                                                ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                        TextButton(onClick = onRequestLocationPermission) {
                                            Text(
                                                text =
                                                    stringResource(
                                                        SetupR.string.grant_location_permission,
                                                    ),
                                            )
                                        }
                                    }
                                }
                            } else if (state.autoSwitchEnabled && !isLocationEnabled) {
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(MaterialTheme.spacings.small),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text =
                                                stringResource(
                                                    SetupR.string.location_service_disabled_title,
                                                ),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                        Text(
                                            text =
                                                stringResource(
                                                    SetupR.string.location_service_disabled_summary,
                                                ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                        TextButton(onClick = onOpenLocationSettings) {
                                            Text(
                                                text =
                                                    stringResource(
                                                        SetupR.string.open_location_settings,
                                                    ),
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(SetupR.string.local_wifi_ssids_title),
                                style = MaterialTheme.typography.labelLarge,
                            )

                            if (state.localWifiSsids.isEmpty()) {
                                Text(
                                    text = stringResource(SetupR.string.local_wifi_ssids_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                FlowRow(
                                    horizontalArrangement =
                                        Arrangement.spacedBy(MaterialTheme.spacings.small),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    state.localWifiSsids.forEach { ssid ->
                                        InputChip(
                                            selected = false,
                                            onClick = {
                                                onAction(ServerAddressesAction.RemoveWifiSsid(ssid))
                                            },
                                            label = { Text(ssid) },
                                            trailingIcon = {
                                                Icon(
                                                    painter = painterResource(CoreR.drawable.ic_x),
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            },
                                        )
                                    }
                                }
                            }

                            FlowRow(
                                horizontalArrangement =
                                    Arrangement.spacedBy(MaterialTheme.spacings.small),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                OutlinedButton(onClick = onAddCurrentWifiClick) {
                                    Text(stringResource(SetupR.string.add_current_wifi))
                                }
                                OutlinedButton(onClick = { openAddSsidDialog = true }) {
                                    Text(stringResource(SetupR.string.add_wifi_ssid_manual))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (openAddDialog) {
        AddServerAddressDialog(
            onAdd = { address ->
                onAction(ServerAddressesAction.AddAddress(address))
                openAddDialog = false
            },
            onDismiss = { openAddDialog = false },
        )
    }

    if (openDeleteDialog && selectedAddress != null) {
        DeleteServerAddressDialog(
            address = selectedAddress!!.address,
            onConfirm = {
                onAction(ServerAddressesAction.DeleteAddress(selectedAddress!!.id))
                openDeleteDialog = false
            },
            onDismiss = { openDeleteDialog = false },
        )
    }

    if (openAddSsidDialog) {
        AddWifiSsidDialog(
            onAdd = { ssid ->
                onAction(ServerAddressesAction.AddWifiSsid(ssid))
                openAddSsidDialog = false
            },
            onDismiss = { openAddSsidDialog = false },
        )
    }

    val pendingAddress = state.pendingForceSaveAddress
    if (pendingAddress != null) {
        AlertDialog(
            title = { Text(stringResource(SetupR.string.unreachable_address_dialog_title)) },
            text = {
                Text(
                    stringResource(
                        SetupR.string.unreachable_address_dialog_text,
                        pendingAddress,
                        state.pendingForceSaveError.orEmpty(),
                    ),
                )
            },
            onDismissRequest = { onAction(ServerAddressesAction.DismissForceAddAddress) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAction(ServerAddressesAction.ConfirmForceAddAddress(pendingAddress))
                    },
                ) {
                    Text(stringResource(SetupR.string.save_anyway))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onAction(ServerAddressesAction.DismissForceAddAddress) },
                ) {
                    Text(stringResource(SetupR.string.cancel))
                }
            },
        )
    }

    val errorMessage = state.errorMessage
    if (errorMessage != null) {
        AlertDialog(
            title = { Text(stringResource(CoreR.string.error_loading_data)) },
            text = { Text(errorMessage) },
            onDismissRequest = { onAction(ServerAddressesAction.DismissError) },
            confirmButton = {
                TextButton(onClick = { onAction(ServerAddressesAction.DismissError) }) {
                    Text(stringResource(SetupR.string.ok))
                }
            },
        )
    }
}

@Composable
private fun AddWifiSsidDialog(onAdd: (ssid: String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(true) { focusRequester.requestFocus() }

    BaseDialog(
        title = stringResource(SetupR.string.add_wifi_ssid_dialog_title),
        onDismiss = onDismiss,
        negativeButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(SetupR.string.cancel)) }
        },
        positiveButton = {
            TextButton(
                onClick = { onAdd(text) },
                enabled = text.isNotBlank(),
            ) {
                Text(stringResource(CoreR.string.add))
            }
        },
    ) { contentPadding ->
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier =
                Modifier.padding(contentPadding).fillMaxWidth().focusRequester(focusRequester),
            placeholder = { Text(stringResource(SetupR.string.wifi_ssid_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            singleLine = true,
        )
    }
}

@PreviewScreenSizes
@Composable
private fun ServerAddressesLayoutPreview() {
    FindroidTheme {
        ServerAddressesLayout(
            state =
                ServerAddressesState(
                    currentAddressId = dummyServerAddress.id,
                    localAddressId = dummyServerAddress.id,
                    addresses = listOf(dummyServerAddress),
                    autoSwitchEnabled = true,
                    localWifiSsids = listOf("Home_5G"),
                ),
            onAction = {},
        )
    }
}
