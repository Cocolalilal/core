package com.maxrave.media3.sonos

import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.extension.now
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.logger.Logger
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.lastOrNull

/**
 * Resolves a stream URL for Sonos speakers.
 * Streams are audio-only (AAC/m4a or MP3) which are universally supported across Sonos devices.
 */
internal class SonosStreamResolver(
    private val streamRepository: StreamRepository,
    private val dataStoreManager: DataStoreManager,
) {
    internal data class ResolvedStream(
        val url: String,
        val mimeType: String,
        val contentLength: Long? = null,
        val durationSeconds: Int? = null,
    )

    suspend fun resolve(mediaId: String): ResolvedStream? {
        val videoId = mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO)
        // 1. Check cached format if not expired and accessible
        streamRepository.getNewFormat(videoId).lastOrNull()?.let { format ->
            val cachedUrl = format.audioUrl
            if (cachedUrl != null && format.expiredTime > now()) {
                val is403 = streamRepository.is403Url(cachedUrl).firstOrNull() != false
                if (!is403) {
                    Logger.d(TAG, "Resolved $videoId from cached format")
                    return ResolvedStream(
                        url = cachedUrl,
                        mimeType = normalizeMimeType(format.mimeType),
                        contentLength = format.contentLength,
                        durationSeconds = format.lengthSeconds,
                    )
                }
            }
        }

        // 2. Fallback: standard stream extraction
        val freshUrl =
            streamRepository
                .getStream(
                    dataStoreManager,
                    videoId,
                    isDownloading = false,
                    isVideo = false,
                ).lastOrNull() ?: return null
        val format = streamRepository.getNewFormat(videoId).lastOrNull()
        Logger.d(TAG, "Resolved $videoId from fresh extraction")
        return ResolvedStream(
            url = freshUrl,
            mimeType = normalizeMimeType(format?.mimeType),
            contentLength = format?.contentLength,
            durationSeconds = format?.lengthSeconds,
        )
    }

    suspend fun invalidate(mediaId: String) {
        streamRepository.invalidateFormat(mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO))
    }

    private fun normalizeMimeType(mimeType: String?): String =
        mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf { it.isNotBlank() && it.startsWith("audio/") }
            ?: DEFAULT_AUDIO_MIME_TYPE

    companion object {
        private const val TAG = "SonosStreamResolver"
        private const val DEFAULT_AUDIO_MIME_TYPE = "audio/mp4"
    }
}
