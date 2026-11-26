package dev.jdtech.jellyfin.player.local.domain

import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import dev.jdtech.jellyfin.models.FindroidChapter
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.FindroidSources
import dev.jdtech.jellyfin.player.core.domain.models.ExternalSubtitle
import dev.jdtech.jellyfin.player.core.domain.models.PlayerAudioStream
import dev.jdtech.jellyfin.player.core.domain.models.PlayerChapter
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.core.domain.models.TrickplayInfo
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import javax.inject.Inject
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import timber.log.Timber

class PlaylistManager @Inject internal constructor(private val repository: JellyfinRepository) {
    private var startItem: FindroidItem? = null
    private var items: List<FindroidItem> = emptyList()
    private val playerItems: MutableList<PlayerItem> = mutableListOf()
    var currentItemIndex: Int = 0
    var maxBitrate: Int = 0

    suspend fun getInitialItem(
        itemId: UUID,
        itemKind: BaseItemKind,
        mediaSourceIndex: Int? = null,
        startFromBeginning: Boolean = false,
    ): PlayerItem? {
        Timber.d("Retrieving initial player item")

        val initialItem =
            when (itemKind) {
                BaseItemKind.MOVIE -> {
                    val movie = repository.getMovie(itemId)

                    items = listOf(movie)
                    movie
                }
                BaseItemKind.SERIES -> {
                    val nextUpEpisode = repository.getNextUp(itemId).firstOrNull()

                    val season =
                        if (nextUpEpisode != null) {
                            repository.getSeason(nextUpEpisode.seasonId)
                        } else {
                            val seasons = repository.getSeasons(itemId)
                            if (seasons.isEmpty()) {
                                return null
                            }
                            seasons.first()
                        }

                    val episodes =
                        repository
                            .getEpisodes(
                                seriesId = itemId,
                                seasonId = season.id,
                                fields = listOf(ItemFields.CHAPTERS, ItemFields.TRICKPLAY),
                            )
                            .filter { !it.missing }

                    if (episodes.isEmpty()) {
                        return null
                    }

                    val episode = nextUpEpisode ?: episodes.first()

                    items = episodes
                    episode
                }
                BaseItemKind.SEASON -> {
                    val season = repository.getSeason(itemId)
                    val episodes =
                        repository
                            .getEpisodes(
                                seriesId = season.seriesId,
                                seasonId = season.id,
                                fields = listOf(ItemFields.CHAPTERS, ItemFields.TRICKPLAY),
                            )
                            .filter { !it.missing }

                    if (episodes.isEmpty()) {
                        return null
                    }

                    val episode = episodes.first()

                    items = episodes
                    episode
                }
                BaseItemKind.EPISODE -> {
                    val episode = repository.getEpisode(itemId)

                    val episodes =
                        repository
                            .getEpisodes(
                                seriesId = episode.seriesId,
                                seasonId = episode.seasonId,
                                fields = listOf(ItemFields.CHAPTERS, ItemFields.TRICKPLAY),
                            )
                            .filter { !it.missing }

                    items = episodes
                    episode
                }
                else -> null
            }

        if (initialItem == null) {
            return null
        }

        startItem = initialItem

        currentItemIndex = items.indexOfFirst { it.id == initialItem.id }

        val playbackPosition =
            if (!startFromBeginning) initialItem.playbackPositionTicks.div(10000) else 0
        val playerItem = initialItem.toPlayerItem(mediaSourceIndex, playbackPosition)
        playerItems.add(playerItem)

        return playerItem
    }

    suspend fun getPreviousPlayerItem(): PlayerItem? {
        Timber.d("Retrieving previous player item")

        val itemIndex = currentItemIndex - 1
        val playerItem =
            when (startItem) {
                is FindroidMovie -> null
                is FindroidEpisode -> {
                    if (currentItemIndex == 0) {
                        null
                    } else {
                        val item = items[itemIndex]
                        if (playerItems.firstOrNull { it.itemId == item.id } == null) {
                            try {
                                item.toPlayerItem(null, 0L)
                            } catch (e: Exception) {
                                Timber.e("Failed to retrieve previous player item: $e")
                                null
                            }
                        } else {
                            null
                        }
                    }
                }
                else -> null
            }

        if (playerItem != null) {
            playerItems.add(playerItem)
        }

        return playerItem
    }

    suspend fun getNextPlayerItem(): PlayerItem? {
        Timber.d("Retrieving next player item")

        val itemIndex = currentItemIndex + 1
        val playerItem =
            when (startItem) {
                is FindroidMovie -> null
                is FindroidEpisode -> {
                    if (currentItemIndex == items.lastIndex) {
                        null
                    } else {
                        val item = items[itemIndex]
                        if (playerItems.firstOrNull { it.itemId == item.id } == null) {
                            try {
                                item.toPlayerItem(null, 0L)
                            } catch (e: Exception) {
                                Timber.e("Failed to retrieve next player item: $e")
                                null
                            }
                        } else {
                            null
                        }
                    }
                }
                else -> null
            }

        if (playerItem != null) {
            playerItems.add(playerItem)
        }

        return playerItem
    }

    fun setCurrentMediaItemIndex(itemId: UUID) {
        currentItemIndex = items.indexOfFirst { it.id == itemId }
    }

    suspend fun reloadCurrentItem(
        itemId: UUID,
        mediaSourceId: String,
        playbackPosition: Long,
        audioStreamIndex: Int? = null,
    ): PlayerItem? {
        val item = items.firstOrNull { it.id == itemId } ?: return null
        val reloadedItem =
            item.toPlayerItem(
                mediaSourceIndex = null,
                playbackPosition = playbackPosition,
                mediaSourceId = mediaSourceId,
                audioStreamIndex = audioStreamIndex,
            )
        playerItems.clear()
        playerItems.add(reloadedItem)
        return reloadedItem
    }

    private fun selectMediaSource(
        mediaSources: List<FindroidSource>,
        mediaSourceId: String?,
        mediaSourceIndex: Int?,
    ): FindroidSource {
        return if (mediaSourceId != null) {
            val remote = mediaSources.firstOrNull {
                it.id == mediaSourceId && it.type == FindroidSourceType.REMOTE
            }
            val any = mediaSources.firstOrNull { it.id == mediaSourceId }
            remote ?: any ?: mediaSources[0]
        } else if (mediaSourceIndex == null) {
            mediaSources.firstOrNull { it.type == FindroidSourceType.LOCAL } ?: mediaSources[0]
        } else {
            mediaSources[mediaSourceIndex]
        }
    }

    private suspend fun FindroidItem.toPlayerItem(
        mediaSourceIndex: Int?,
        playbackPosition: Long,
        mediaSourceId: String? = null,
        audioStreamIndex: Int? = null,
    ): PlayerItem {
        Timber.d("Converting FindroidItem ${this.id} to PlayerItem")

        val effectiveMaxBitrate = maxBitrate.takeIf { it > 0 }
        val initialSourceId =
            when {
                mediaSourceId != null -> mediaSourceId
                effectiveMaxBitrate != null && this is FindroidSources -> {
                    if (mediaSourceIndex != null) {
                        sources.getOrNull(mediaSourceIndex)?.id?.ifBlank { null }
                    } else if (sources.none { it.type == FindroidSourceType.LOCAL }) {
                        sources.firstOrNull { it.id.isNotBlank() }?.id
                    } else {
                        null
                    }
                }
                else -> null
            }

        var mediaSources =
            repository.getMediaSources(
                id,
                includePath = true,
                maxBitrate = effectiveMaxBitrate,
                audioStreamIndex = audioStreamIndex,
                mediaSourceId = initialSourceId,
            )
        var mediaSource = selectMediaSource(mediaSources, initialSourceId, mediaSourceIndex)

        if (
            effectiveMaxBitrate != null &&
                initialSourceId == null &&
                mediaSource.isTranscoding &&
                mediaSource.id.isNotBlank()
        ) {
            mediaSources =
                repository.getMediaSources(
                    id,
                    includePath = true,
                    maxBitrate = effectiveMaxBitrate,
                    audioStreamIndex = audioStreamIndex,
                    mediaSourceId = mediaSource.id,
                )
            mediaSource = selectMediaSource(mediaSources, mediaSource.id, mediaSourceIndex)
        }
        val externalSubtitles =
            mediaSource.mediaStreams
                .filter { mediaStream ->
                    val isExternalDelivery =
                        if (mediaSource.isTranscoding) {
                            mediaStream.isExternal ||
                                mediaStream.deliveryMethod == SubtitleDeliveryMethod.EXTERNAL
                        } else {
                            mediaStream.isExternal
                        }
                    isExternalDelivery &&
                        mediaStream.type == MediaStreamType.SUBTITLE &&
                        !mediaStream.path.isNullOrBlank()
                }
                .map { mediaStream ->
                    val subtitleTitle =
                        mediaStream.displayTitle?.ifBlank { mediaStream.title } ?: mediaStream.title
                    ExternalSubtitle(
                        subtitleTitle,
                        mediaStream.language,
                        mediaStream.path!!.toUri(),
                        when (mediaStream.codec.lowercase()) {
                            "subrip" -> MimeTypes.APPLICATION_SUBRIP
                            "srt" -> MimeTypes.APPLICATION_SUBRIP
                            "webvtt" -> MimeTypes.TEXT_VTT
                            "vtt" -> MimeTypes.TEXT_VTT
                            "ass" -> MimeTypes.TEXT_SSA
                            "ssa" -> MimeTypes.TEXT_SSA
                            "ttml" -> MimeTypes.APPLICATION_TTML
                            else -> MimeTypes.TEXT_UNKNOWN
                        },
                    )
                }
        val audioStreams =
            mediaSource.mediaStreams
                .filter { it.type == MediaStreamType.AUDIO && it.index >= 0 }
                .map { stream ->
                    val fallbackTitle =
                        listOfNotNull(
                                stream.language.ifBlank { null },
                                stream.codec.ifBlank { null }?.uppercase(),
                                stream.channelLayout?.ifBlank { null },
                            )
                            .joinToString(" - ")
                    val displayTitle = stream.displayTitle?.ifBlank { null }
                    PlayerAudioStream(
                        index = stream.index,
                        displayTitle = displayTitle ?: stream.title.ifBlank { fallbackTitle },
                        language = stream.language,
                        codec = stream.codec,
                    )
                }
        val trickplayInfo =
            when (this) {
                is FindroidSources -> {
                    this.trickplayInfo?.get(mediaSource.id)?.let {
                        TrickplayInfo(
                            width = it.width,
                            height = it.height,
                            tileWidth = it.tileWidth,
                            tileHeight = it.tileHeight,
                            thumbnailCount = it.thumbnailCount,
                            interval = it.interval,
                            bandwidth = it.bandwidth,
                        )
                    }
                }
                else -> null
            }
        return PlayerItem(
            name = name,
            itemId = id,
            mediaSourceId = mediaSource.id,
            mediaSourceUri = mediaSource.path,
            playbackPosition = playbackPosition,
            parentIndexNumber = if (this is FindroidEpisode) parentIndexNumber else null,
            indexNumber = if (this is FindroidEpisode) indexNumber else null,
            indexNumberEnd = if (this is FindroidEpisode) indexNumberEnd else null,
            externalSubtitles = externalSubtitles,
            chapters = chapters.toPlayerChapters(),
            trickplayInfo = trickplayInfo,
            playSessionId = mediaSource.playSessionId,
            isTranscoding = mediaSource.isTranscoding,
            isLocal = mediaSource.type == FindroidSourceType.LOCAL,
            sourceBitrate = mediaSource.bitrate,
            audioStreams = audioStreams,
            selectedAudioStreamIndex = audioStreamIndex ?: mediaSource.defaultAudioStreamIndex,
        )
    }

    private fun List<FindroidChapter>.toPlayerChapters(): List<PlayerChapter> {
        return this.map { chapter ->
            PlayerChapter(startPosition = chapter.startPosition, name = chapter.name)
        }
    }
}
