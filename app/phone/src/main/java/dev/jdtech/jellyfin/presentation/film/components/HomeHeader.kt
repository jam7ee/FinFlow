package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.network.AddressKind
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.LocalOfflineMode
import kotlin.math.abs
import kotlin.math.exp
import kotlinx.coroutines.launch

@Composable
fun HomeHeader(
    serverName: String,
    isLoading: Boolean,
    isError: Boolean,
    onServerClick: () -> Unit,
    onErrorClick: () -> Unit,
    onRetryClick: () -> Unit,
    onSearchClick: () -> Unit,
    onUserClick: () -> Unit,
    modifier: Modifier = Modifier,
    nextToggleTargetKind: AddressKind? = null,
    onQuickToggleAddress: () -> Unit = {},
) {
    val isOfflineMode = LocalOfflineMode.current

    Row(
        modifier = modifier.fillMaxWidth().height(56.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Surface(
            onClick = onServerClick,
            modifier = Modifier.fillMaxHeight().weight(1f, fill = false),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = MaterialTheme.spacings.medium),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_logo),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = Color.Unspecified,
                )
                Text(
                    text = serverName,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.animateContentSize(),
                )
            }
        }

        Spacer(Modifier.width(MaterialTheme.spacings.medium))

        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium)) {
            AnimatedVisibility(visible = isError, enter = fadeIn(), exit = fadeOut()) {
                Surface(
                    onClick = onErrorClick,
                    modifier = Modifier.fillMaxHeight().aspectRatio(1f),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Box {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_alert_circle),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }

            AnimatedVisibility(visible = isLoading || isError, enter = fadeIn(), exit = fadeOut()) {
                Surface(
                    onClick = onRetryClick,
                    modifier = Modifier.fillMaxHeight().aspectRatio(1f),
                    enabled = !isLoading,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Box {
                        when {
                            isError -> {
                                Icon(
                                    painter = painterResource(CoreR.drawable.ic_rotate_ccw),
                                    contentDescription = null,
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            }
                            isLoading -> {
                                Box(modifier = Modifier.size(32.dp).align(Alignment.Center)) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                    }
                }
            }

            if (!isOfflineMode) {
                Surface(
                    onClick = onSearchClick,
                    modifier = Modifier.fillMaxHeight().aspectRatio(1f),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_search),
                            contentDescription = null,
                        )
                    }
                }
            }

            PullToSwitchSettingsButton(
                nextToggleTargetKind = nextToggleTargetKind,
                onClick = onUserClick,
                onPullToggle = onQuickToggleAddress,
            )
        }
    }
}

@Composable
private fun PullToSwitchSettingsButton(
    nextToggleTargetKind: AddressKind?,
    onClick: () -> Unit,
    onPullToggle: () -> Unit,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val interactionSource = remember { MutableInteractionSource() }

    val latestOnClick by rememberUpdatedState(onClick)
    val latestOnPullToggle by rememberUpdatedState(onPullToggle)

    val maxPullPx = with(density) { 82.dp.toPx() }
    val thresholdPx = with(density) { 46.dp.toPx() }
    val dampingPx = with(density) { 64.dp.toPx() }

    val pullOffset = remember { Animatable(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val progress = (pullOffset.value / thresholdPx).coerceIn(0f, 1.35f)
    val isArmed = pullOffset.value >= thresholdPx

    val containerColor by
        animateColorAsState(
            targetValue =
                if (isArmed) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            label = "pullSwitchContainerColor",
        )
    val targetIconTint by
        animateColorAsState(
            targetValue =
                if (isArmed) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            label = "pullSwitchIconColor",
        )

    val targetIconRes =
        when (nextToggleTargetKind) {
            AddressKind.LOCAL -> CoreR.drawable.ic_wifi
            AddressKind.EXTERNAL -> CoreR.drawable.ic_globe
            else -> CoreR.drawable.ic_arrow_down_up
        }

    val hintText =
        when {
            isArmed && nextToggleTargetKind == AddressKind.LOCAL ->
                stringResource(CoreR.string.release_to_switch_local)
            isArmed && nextToggleTargetKind == AddressKind.EXTERNAL ->
                stringResource(CoreR.string.release_to_switch_external)
            isArmed -> stringResource(CoreR.string.release_to_switch_address)
            nextToggleTargetKind == AddressKind.LOCAL ->
                stringResource(CoreR.string.switch_to_local)
            nextToggleTargetKind == AddressKind.EXTERNAL ->
                stringResource(CoreR.string.switch_to_external)
            else -> stringResource(CoreR.string.switch_address)
        }

    // Reserve exact 56.dp x 56.dp layout slot in the header Row; let the animated capsule overflow
    // downwards.
    Box(
        modifier =
            Modifier.size(56.dp).semantics {
                role = Role.Button
                this.onClick {
                    latestOnClick()
                    true
                }
            },
        contentAlignment = Alignment.TopEnd,
    ) {
        Column(
            modifier = Modifier.wrapContentSize(align = Alignment.TopEnd, unbounded = true),
            horizontalAlignment = Alignment.End,
        ) {
            val extraHeightDp = with(density) { pullOffset.value.toDp() }
            Surface(
                modifier =
                    Modifier.width(56.dp)
                        .height(56.dp + extraHeightDp)
                        .clip(CircleShape)
                        .indication(interactionSource, ripple())
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val press = PressInteraction.Press(down.position)
                                scope.launch { interactionSource.emit(press) }

                                var totalDx = 0f
                                var totalDy = 0f
                                var pullStarted = false
                                var canceledByOtherAxis = false
                                var wasArmed = false
                                var wasCancelled = false
                                val touchSlop = viewConfiguration.touchSlop

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change =
                                        event.changes.firstOrNull { it.id == down.id }
                                            ?: event.changes.firstOrNull()
                                    if (change == null) {
                                        wasCancelled = true
                                        break
                                    }
                                    if (!change.pressed) {
                                        if (
                                            change.isConsumed ||
                                                event.type != PointerEventType.Release
                                        ) {
                                            wasCancelled = true
                                        }
                                        break
                                    }

                                    val delta = change.positionChange()
                                    totalDx += delta.x
                                    totalDy += delta.y

                                    if (!pullStarted && !canceledByOtherAxis) {
                                        if (totalDy > touchSlop && totalDy > abs(totalDx)) {
                                            pullStarted = true
                                            isDragging = true
                                            scope.launch {
                                                interactionSource.emit(
                                                    PressInteraction.Cancel(press),
                                                )
                                            }
                                        } else if (
                                            abs(totalDx) > touchSlop || totalDy < -touchSlop
                                        ) {
                                            canceledByOtherAxis = true
                                        }
                                    }

                                    if (pullStarted) {
                                        change.consume()
                                        val rawPull = totalDy.coerceAtLeast(0f)
                                        val visualPull =
                                            maxPullPx * (1f - exp(-rawPull / dampingPx))
                                        scope.launch { pullOffset.snapTo(visualPull) }

                                        val nowArmed = visualPull >= thresholdPx
                                        if (nowArmed != wasArmed) {
                                            wasArmed = nowArmed
                                            haptic.performHapticFeedback(
                                                HapticFeedbackType.LongPress,
                                            )
                                        }
                                    }
                                }

                                if (pullStarted) {
                                    isDragging = false
                                    if (wasArmed && !wasCancelled) {
                                        latestOnPullToggle()
                                    }
                                    scope.launch {
                                        pullOffset.animateTo(
                                            targetValue = 0f,
                                            animationSpec =
                                                spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessMediumLow,
                                                ),
                                        )
                                    }
                                } else {
                                    scope.launch {
                                        interactionSource.emit(PressInteraction.Release(press))
                                    }
                                    if (!canceledByOtherAxis && !wasCancelled) {
                                        latestOnClick()
                                    }
                                }
                            }
                        },
                shape = CircleShape,
                color = containerColor,
                shadowElevation = if (pullOffset.value > 2f) 4.dp else 0.dp,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Top settings/user icon shrinks and dims smoothly while pulling down
                    Box(
                        modifier =
                            Modifier.size(56.dp).align(Alignment.TopCenter).graphicsLayer {
                                val t = progress.coerceIn(0f, 1f)
                                val scale = 1f - 0.18f * t
                                scaleX = scale
                                scaleY = scale
                                alpha = 1f - 0.55f * t
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_user),
                            contentDescription = null,
                        )
                    }

                    // Bottom revealed LAN/External target icon scales in as the capsule stretches
                    if (pullOffset.value > 4f) {
                        Box(
                            modifier =
                                Modifier.size(56.dp).align(Alignment.BottomCenter).graphicsLayer {
                                    val t = progress.coerceIn(0f, 1f)
                                    val scale = 0.45f + 0.65f * t
                                    scaleX = scale
                                    scaleY = scale
                                    alpha = t
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(targetIconRes),
                                contentDescription = null,
                                tint = targetIconTint,
                            )
                        }
                    }
                }
            }

            if (isDragging || pullOffset.value > 8f) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color =
                        if (isArmed) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                    shadowElevation = 3.dp,
                    modifier =
                        Modifier.graphicsLayer { alpha = (progress * 1.4f).coerceIn(0f, 1f) },
                ) {
                    Text(
                        text = hintText,
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (isArmed) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
@Preview(showBackground = true)
private fun HomeHeaderLoadingPreview() {
    FindroidTheme {
        HomeHeader(
            serverName = "Jellyfin",
            isLoading = true,
            isError = false,
            onServerClick = {},
            onErrorClick = {},
            onRetryClick = {},
            onSearchClick = {},
            onUserClick = {},
        )
    }
}

@Composable
@Preview(showBackground = true)
private fun HomeHeaderErrorPreview() {
    FindroidTheme {
        HomeHeader(
            serverName = "Jellyfin",
            isLoading = false,
            isError = true,
            onServerClick = {},
            onErrorClick = {},
            onRetryClick = {},
            onSearchClick = {},
            onUserClick = {},
        )
    }
}
