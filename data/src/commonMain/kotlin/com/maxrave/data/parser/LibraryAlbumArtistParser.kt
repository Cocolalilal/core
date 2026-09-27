package com.maxrave.data.parser

import com.maxrave.domain.data.model.searchResult.albums.AlbumsResult
import com.maxrave.domain.data.model.searchResult.artists.ArtistsResult
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.kotlinytmusicscraper.models.GridRenderer
import com.maxrave.kotlinytmusicscraper.models.MusicResponsiveListItemRenderer
import com.maxrave.kotlinytmusicscraper.models.MusicTwoRowItemRenderer
import com.maxrave.kotlinytmusicscraper.models.response.BrowseResponse

/**
 * Parses the album and artist items returned by YouTube Music account library endpoints
 * (`FEmusic_liked_albums`, `FEmusic_library_corpus_track_artists`, `FEmusic_library_corpus_artists`)
 * into domain models rendered by the Library screen.
 */

private fun String.toHighResThumbnail(): String =
    Regex("([=-][whs])\\d+").replace(this, "$1544")

private fun List<Thumbnail>.toHighResThumbnails(): List<Thumbnail> =
    map { it.copy(url = it.url.toHighResThumbnail(), width = 544, height = 544) }

private fun MusicTwoRowItemRenderer.toAlbumsResult(): AlbumsResult? {
    val browseId = navigationEndpoint.browseEndpoint?.browseId ?: return null
    val subtitleRuns = subtitle?.runs.orEmpty()
    val artists =
        subtitleRuns
            .mapNotNull { run ->
                val endpoint = run.navigationEndpoint?.browseEndpoint ?: return@mapNotNull null
                if (endpoint.isArtistEndpoint) {
                    Artist(id = endpoint.browseId, name = run.text)
                } else {
                    null
                }
            }
    val year =
        subtitleRuns
            .lastOrNull()
            ?.text
            ?.takeIf { it.length == 4 }
            ?.toIntOrNull()
            ?.toString()
            ?: ""
    return AlbumsResult(
        artists = artists,
        browseId = browseId,
        category = "",
        duration = "",
        isExplicit = false,
        resultType = "ALBUM",
        thumbnails =
            thumbnailRenderer
                ?.musicThumbnailRenderer
                ?.thumbnail
                ?.thumbnails
                ?.toListThumbnail()
                ?.toHighResThumbnails()
                ?: listOf(),
        title = title.runs?.firstOrNull()?.text ?: "",
        type = "ALBUM",
        year = year,
    )
}

private fun MusicResponsiveListItemRenderer.toAlbumsResult(): AlbumsResult? {
    val browseId =
        navigationEndpoint?.browseEndpoint?.browseId
            ?: flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.navigationEndpoint?.browseEndpoint?.browseId
            ?: return null
    val title =
        flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text
            ?: ""
    val subtitleRuns =
        flexColumns.getOrNull(1)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs.orEmpty()
    val artists =
        subtitleRuns.mapNotNull { run ->
            val endpoint = run.navigationEndpoint?.browseEndpoint
            if (endpoint?.isArtistEndpoint == true) {
                Artist(id = endpoint.browseId, name = run.text)
            } else {
                null
            }
        }.ifEmpty {
            subtitleRuns.firstOrNull()?.text?.let { listOf(Artist(id = null, name = it)) } ?: emptyList()
        }
    val year =
        subtitleRuns.mapNotNull { it.text.trim() }.firstOrNull { it.length == 4 && it.all { c -> c.isDigit() } } ?: ""

    return AlbumsResult(
        artists = artists,
        browseId = browseId,
        category = "",
        duration = "",
        isExplicit = badges?.any { it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE" } == true,
        resultType = "ALBUM",
        thumbnails =
            thumbnail
                ?.musicThumbnailRenderer
                ?.thumbnail
                ?.thumbnails
                ?.toListThumbnail()
                ?.toHighResThumbnails()
                ?: listOf(),
        title = title,
        type = "ALBUM",
        year = year,
    )
}

private fun MusicTwoRowItemRenderer.toArtistsResult(): ArtistsResult? {
    val browseId = navigationEndpoint.browseEndpoint?.browseId ?: return null
    return ArtistsResult(
        artist = title.runs?.firstOrNull()?.text ?: "",
        browseId = browseId,
        category = "",
        radioId = "",
        resultType = "ARTIST",
        shuffleId = "",
        thumbnails =
            thumbnailRenderer
                ?.musicThumbnailRenderer
                ?.thumbnail
                ?.thumbnails
                ?.toListThumbnail()
                ?.toHighResThumbnails()
                ?: listOf(),
    )
}

private fun MusicResponsiveListItemRenderer.toArtistsResult(): ArtistsResult? {
    val browseId =
        navigationEndpoint?.browseEndpoint?.browseId
            ?: flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.navigationEndpoint?.browseEndpoint?.browseId
            ?: return null
    val artistName =
        flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text
            ?: ""
    return ArtistsResult(
        artist = artistName,
        browseId = browseId,
        category = "",
        radioId = "",
        resultType = "ARTIST",
        shuffleId = "",
        thumbnails =
            thumbnail
                ?.musicThumbnailRenderer
                ?.thumbnail
                ?.thumbnails
                ?.toListThumbnail()
                ?.toHighResThumbnails()
                ?: listOf(),
    )
}

internal fun parseLibraryBrowseResponseForArtists(data: BrowseResponse): List<ArtistsResult> {
    val list = mutableListOf<ArtistsResult>()
    val tabs = data.contents?.singleColumnBrowseResultsRenderer?.tabs
        ?: data.contents?.twoColumnBrowseResultsRenderer?.tabs
        ?: emptyList()

    val sectionContents = tabs.flatMap { tab ->
        tab.tabRenderer?.content?.sectionListRenderer?.contents.orEmpty()
    } + listOfNotNull(data.contents?.sectionListRenderer?.contents).flatten()

    for (content in sectionContents) {
        content.gridRenderer?.items?.forEach { item ->
            item.musicTwoRowItemRenderer?.toArtistsResult()?.let { list.add(it) }
        }
        content.musicShelfRenderer?.contents?.forEach { shelfContent ->
            shelfContent.musicResponsiveListItemRenderer?.toArtistsResult()?.let { list.add(it) }
        }
        content.musicCarouselShelfRenderer?.contents?.forEach { carouselContent ->
            carouselContent.musicTwoRowItemRenderer?.toArtistsResult()?.let { list.add(it) }
            carouselContent.musicResponsiveListItemRenderer?.toArtistsResult()?.let { list.add(it) }
        }
    }

    data.continuationContents?.gridContinuation?.items?.forEach { item ->
        item.musicTwoRowItemRenderer?.toArtistsResult()?.let { list.add(it) }
    }
    data.continuationContents?.musicShelfContinuation?.contents?.forEach { shelfContent ->
        shelfContent.musicResponsiveListItemRenderer?.toArtistsResult()?.let { list.add(it) }
    }
    data.onResponseReceivedActions?.forEach { action ->
        action.appendContinuationItemsAction?.continuationItems?.forEach { item ->
            item.musicResponsiveListItemRenderer?.toArtistsResult()?.let { list.add(it) }
        }
    }

    return list.distinctBy { it.browseId }
}

internal fun parseLibraryBrowseResponseForAlbums(data: BrowseResponse): List<AlbumsResult> {
    val list = mutableListOf<AlbumsResult>()
    val tabs = data.contents?.singleColumnBrowseResultsRenderer?.tabs
        ?: data.contents?.twoColumnBrowseResultsRenderer?.tabs
        ?: emptyList()

    val sectionContents = tabs.flatMap { tab ->
        tab.tabRenderer?.content?.sectionListRenderer?.contents.orEmpty()
    } + listOfNotNull(data.contents?.sectionListRenderer?.contents).flatten()

    for (content in sectionContents) {
        content.gridRenderer?.items?.forEach { item ->
            item.musicTwoRowItemRenderer?.toAlbumsResult()?.let { list.add(it) }
        }
        content.musicShelfRenderer?.contents?.forEach { shelfContent ->
            shelfContent.musicResponsiveListItemRenderer?.toAlbumsResult()?.let { list.add(it) }
        }
        content.musicCarouselShelfRenderer?.contents?.forEach { carouselContent ->
            carouselContent.musicTwoRowItemRenderer?.toAlbumsResult()?.let { list.add(it) }
            carouselContent.musicResponsiveListItemRenderer?.toAlbumsResult()?.let { list.add(it) }
        }
    }

    data.continuationContents?.gridContinuation?.items?.forEach { item ->
        item.musicTwoRowItemRenderer?.toAlbumsResult()?.let { list.add(it) }
    }
    data.continuationContents?.musicShelfContinuation?.contents?.forEach { shelfContent ->
        shelfContent.musicResponsiveListItemRenderer?.toAlbumsResult()?.let { list.add(it) }
    }
    data.onResponseReceivedActions?.forEach { action ->
        action.appendContinuationItemsAction?.continuationItems?.forEach { item ->
            item.musicResponsiveListItemRenderer?.toAlbumsResult()?.let { list.add(it) }
        }
    }

    return list.distinctBy { it.browseId }
}

internal fun parseLibraryAlbums(input: List<GridRenderer.Item>): List<AlbumsResult> {
    val list = mutableListOf<AlbumsResult>()
    if (input.isNotEmpty()) {
        for (i in input.indices) {
            input[i].musicTwoRowItemRenderer?.toAlbumsResult()?.let { list.add(it) }
        }
    }
    return list
}

internal fun parseNextLibraryAlbums(input: List<MusicTwoRowItemRenderer>): List<AlbumsResult> =
    input.mapNotNull { it.toAlbumsResult() }

internal fun parseLibraryArtists(input: List<GridRenderer.Item>): List<ArtistsResult> {
    val list = mutableListOf<ArtistsResult>()
    if (input.isNotEmpty()) {
        for (i in input.indices) {
            input[i].musicTwoRowItemRenderer?.toArtistsResult()?.let { list.add(it) }
        }
    }
    return list
}

internal fun parseNextLibraryArtists(input: List<MusicTwoRowItemRenderer>): List<ArtistsResult> =
    input.mapNotNull { it.toArtistsResult() }
