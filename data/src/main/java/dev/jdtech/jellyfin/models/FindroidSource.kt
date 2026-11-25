package dev.jdtech.jellyfin.models

import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.io.File
import java.util.UUID
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo

data class FindroidSource(
    val id: String,
    val name: String,
    val type: FindroidSourceType,
    val path: String,
    val size: Long,
    val mediaStreams: List<FindroidMediaStream>,
    val downloadId: Long? = null,
    val playSessionId: String? = null,
    val isTranscoding: Boolean = false,
    val bitrate: Int? = null,
    val defaultAudioStreamIndex: Int? = null,
)

suspend fun MediaSourceInfo.toFindroidSource(
    jellyfinRepository: JellyfinRepository,
    itemId: UUID,
    includePath: Boolean = false,
    playSessionId: String? = null,
    maxBitrate: Int? = null,
): FindroidSource {
    val useTranscoding =
        includePath &&
            maxBitrate != null &&
            maxBitrate > 0 &&
            !supportsDirectPlay &&
            !transcodingUrl.isNullOrBlank()
    val path =
        if (useTranscoding) {
            val url = transcodingUrl!!
            if (url.startsWith("http://") || url.startsWith("https://")) {
                url
            } else {
                jellyfinRepository.getBaseUrl().trimEnd('/') + "/" + url.trimStart('/')
            }
        } else {
            when (protocol) {
                MediaProtocol.FILE -> {
                    try {
                        if (includePath) {
                            jellyfinRepository.getStreamUrl(itemId, id.orEmpty())
                        } else {
                            ""
                        }
                    } catch (_: Exception) {
                        ""
                    }
                }
                MediaProtocol.HTTP -> this.path.orEmpty()
                else -> ""
            }
        }
    return FindroidSource(
        id = id.orEmpty(),
        name = name.orEmpty(),
        type = FindroidSourceType.REMOTE,
        path = path,
        size = size ?: 0,
        mediaStreams =
            mediaStreams?.map { it.toFindroidMediaStream(jellyfinRepository) } ?: emptyList(),
        playSessionId = playSessionId,
        isTranscoding = useTranscoding,
        bitrate = bitrate,
        defaultAudioStreamIndex = defaultAudioStreamIndex,
    )
}

suspend fun FindroidSourceDto.toFindroidSource(
    serverDatabaseDao: ServerDatabaseDao,
): FindroidSource {
    return FindroidSource(
        id = id,
        name = name,
        type = type,
        path = path,
        size = File(path).length(),
        mediaStreams =
            serverDatabaseDao.getMediaStreamsBySourceId(id).map { it.toFindroidMediaStream() },
        downloadId = downloadId,
    )
}

enum class FindroidSourceType {
    REMOTE,
    LOCAL,
}
