package com.maxrave.domain.data.model.pinned

import kotlinx.serialization.Serializable

@Serializable
data class PinnedItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val thumbnailUrl: String? = null,
    val type: PinnedType,
    val targetId: String,
)

@Serializable
enum class PinnedType {
    FAVORITE_SONGS,
    DOWNLOADED_SONGS,
    MOST_PLAYED,
    PLAYLIST,
    ALBUM,
    ARTIST,
    SONG,
}
