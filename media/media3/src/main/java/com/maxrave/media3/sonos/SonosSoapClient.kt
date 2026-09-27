package com.maxrave.media3.sonos

import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Executes UPnP SOAP actions against Sonos AVTransport, RenderingControl, and ZoneGroupTopology services.
 */
internal class SonosSoapClient {
    data class PositionInfo(
        val relTimeMs: Long,
        val durationMs: Long,
        val trackUri: String,
    )

    data class ZoneTopologyMember(
        val uuid: String,
        val coordinatorUuid: String,
        val zoneName: String,
        val ip: String,
        val baseUrl: String,
        val isCoordinator: Boolean,
        val isInvisible: Boolean,
    )

    suspend fun setAVTransportURI(
        baseUrl: String,
        streamUrl: String,
        title: String,
        artist: String,
        album: String,
        artworkUrl: String?,
        durationSeconds: Int = 0,
        mimeType: String = "audio/mp4",
        sizeBytes: Long = 0L,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val metadataXml =
                buildDidlLiteMetadata(
                    streamUrl = streamUrl,
                    title = title,
                    artist = artist,
                    album = album,
                    artworkUrl = artworkUrl,
                    durationSeconds = durationSeconds,
                    mimeType = mimeType,
                    sizeBytes = sizeBytes,
                )
            val escapedMetadata = escapeXml(metadataXml)
            val escapedUri = escapeXml(streamUrl)

            val args =
                "<InstanceID>0</InstanceID>" +
                    "<CurrentURI>$escapedUri</CurrentURI>" +
                    "<CurrentURIMetaData>$escapedMetadata</CurrentURIMetaData>"

            var result =
                executeSoap(
                    baseUrl = baseUrl,
                    endpoint = AV_TRANSPORT_CONTROL,
                    service = AV_TRANSPORT_SERVICE,
                    action = "SetAVTransportURI",
                    arguments = args,
                )

            // If SetAVTransportURI failed (e.g., speaker is a follower in a group or in TV mode),
            // promote it to standalone coordinator and retry SetAVTransportURI.
            if (result == null) {
                Logger.w(TAG, "First SetAVTransportURI failed on $baseUrl; attempting BecomeCoordinatorOfStandaloneGroup and retrying...")
                becomeCoordinatorOfStandaloneGroup(baseUrl)
                result =
                    executeSoap(
                        baseUrl = baseUrl,
                        endpoint = AV_TRANSPORT_CONTROL,
                        service = AV_TRANSPORT_SERVICE,
                        action = "SetAVTransportURI",
                        arguments = args,
                    )
            }

            // Fallback 1: Universal standard protocolInfo (without extended DLNA flags) in case firmware rejects DLNA.ORG_ flags
            if (result == null) {
                Logger.w(TAG, "SetAVTransportURI with DLNA flags failed on $baseUrl; retrying with standard UPnP protocolInfo...")
                val universalMetadata =
                    buildDidlLiteMetadata(
                        streamUrl = streamUrl,
                        title = title,
                        artist = artist,
                        album = album,
                        artworkUrl = artworkUrl,
                        durationSeconds = durationSeconds,
                        mimeType = mimeType,
                        sizeBytes = sizeBytes,
                        useUniversalProtocolInfo = true,
                    )
                val universalArgs =
                    "<InstanceID>0</InstanceID>" +
                        "<CurrentURI>$escapedUri</CurrentURI>" +
                        "<CurrentURIMetaData>${escapeXml(universalMetadata)}</CurrentURIMetaData>"
                result =
                    executeSoap(
                        baseUrl = baseUrl,
                        endpoint = AV_TRANSPORT_CONTROL,
                        service = AV_TRANSPORT_SERVICE,
                        action = "SetAVTransportURI",
                        arguments = universalArgs,
                    )
            }

            // Fallback 2: Empty metadata (official UPnP direct HTTP fallback)
            if (result == null) {
                Logger.w(TAG, "SetAVTransportURI with metadata failed on $baseUrl; retrying with empty metadata...")
                val emptyMetaArgs =
                    "<InstanceID>0</InstanceID>" +
                        "<CurrentURI>$escapedUri</CurrentURI>" +
                        "<CurrentURIMetaData></CurrentURIMetaData>"
                result =
                    executeSoap(
                        baseUrl = baseUrl,
                        endpoint = AV_TRANSPORT_CONTROL,
                        service = AV_TRANSPORT_SERVICE,
                        action = "SetAVTransportURI",
                        arguments = emptyMetaArgs,
                    )
            }
            result != null
        }

    suspend fun becomeCoordinatorOfStandaloneGroup(baseUrl: String): Boolean =
        withContext(Dispatchers.IO) {
            val args = "<InstanceID>0</InstanceID>"
            val result =
                executeSoap(
                    baseUrl = baseUrl,
                    endpoint = AV_TRANSPORT_CONTROL,
                    service = AV_TRANSPORT_SERVICE,
                    action = "BecomeCoordinatorOfStandaloneGroup",
                    arguments = args,
                )
            result != null
        }

    suspend fun play(baseUrl: String): Boolean =
        withContext(Dispatchers.IO) {
            val args = "<InstanceID>0</InstanceID><Speed>1</Speed>"
            val result =
                executeSoap(
                    baseUrl = baseUrl,
                    endpoint = AV_TRANSPORT_CONTROL,
                    service = AV_TRANSPORT_SERVICE,
                    action = "Play",
                    arguments = args,
                )
            result != null
        }

    suspend fun pause(baseUrl: String): Boolean =
        withContext(Dispatchers.IO) {
            val args = "<InstanceID>0</InstanceID>"
            val result =
                executeSoap(
                    baseUrl = baseUrl,
                    endpoint = AV_TRANSPORT_CONTROL,
                    service = AV_TRANSPORT_SERVICE,
                    action = "Pause",
                    arguments = args,
                )
            result != null
        }

    suspend fun stop(baseUrl: String): Boolean =
        withContext(Dispatchers.IO) {
            val args = "<InstanceID>0</InstanceID>"
            val result =
                executeSoap(
                    baseUrl = baseUrl,
                    endpoint = AV_TRANSPORT_CONTROL,
                    service = AV_TRANSPORT_SERVICE,
                    action = "Stop",
                    arguments = args,
                )
            result != null
        }

    suspend fun seek(
        baseUrl: String,
        positionMs: Long,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val targetTime = formatMsToRelTime(positionMs)
            val args = "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$targetTime</Target>"
            val result =
                executeSoap(
                    baseUrl = baseUrl,
                    endpoint = AV_TRANSPORT_CONTROL,
                    service = AV_TRANSPORT_SERVICE,
                    action = "Seek",
                    arguments = args,
                )
            result != null
        }

    suspend fun getPositionInfo(baseUrl: String): PositionInfo? =
        withContext(Dispatchers.IO) {
            val args = "<InstanceID>0</InstanceID>"
            val xml =
                executeSoap(
                    baseUrl = baseUrl,
                    endpoint = AV_TRANSPORT_CONTROL,
                    service = AV_TRANSPORT_SERVICE,
                    action = "GetPositionInfo",
                    arguments = args,
                ) ?: return@withContext null

            val trackDurationStr = extractTagValue(xml, "TrackDuration")
            val relTimeStr = extractTagValue(xml, "RelTime")
            val trackUri = extractTagValue(xml, "TrackURI") ?: ""

            val relTimeMs = relTimeStr?.let { parseRelTimeToMs(it) } ?: 0L
            val durationMs = trackDurationStr?.let { parseRelTimeToMs(it) } ?: 0L

            PositionInfo(
                relTimeMs = relTimeMs,
                durationMs = durationMs,
                trackUri = trackUri,
            )
        }

    suspend fun getTransportInfo(baseUrl: String): String? =
        withContext(Dispatchers.IO) {
            val args = "<InstanceID>0</InstanceID>"
            val xml =
                executeSoap(
                    baseUrl = baseUrl,
                    endpoint = AV_TRANSPORT_CONTROL,
                    service = AV_TRANSPORT_SERVICE,
                    action = "GetTransportInfo",
                    arguments = args,
                ) ?: return@withContext null

            extractTagValue(xml, "CurrentTransportState")
        }

    suspend fun getVolume(baseUrl: String): Int? =
        withContext(Dispatchers.IO) {
            val args = "<InstanceID>0</InstanceID><Channel>Master</Channel>"
            val xml =
                executeSoap(
                    baseUrl = baseUrl,
                    endpoint = RENDERING_CONTROL,
                    service = RENDERING_SERVICE,
                    action = "GetVolume",
                    arguments = args,
                ) ?: return@withContext null

            extractTagValue(xml, "CurrentVolume")?.toIntOrNull()
        }

    suspend fun setVolume(
        baseUrl: String,
        volumePercent: Int,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val clamped = volumePercent.coerceIn(0, 100)
            val args = "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$clamped</DesiredVolume>"
            val result =
                executeSoap(
                    baseUrl = baseUrl,
                    endpoint = RENDERING_CONTROL,
                    service = RENDERING_SERVICE,
                    action = "SetVolume",
                    arguments = args,
                )
            result != null
        }

    suspend fun setMute(
        baseUrl: String,
        mute: Boolean,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val muteVal = if (mute) "1" else "0"
            val args = "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredMute>$muteVal</DesiredMute>"
            val result =
                executeSoap(
                    baseUrl = baseUrl,
                    endpoint = RENDERING_CONTROL,
                    service = RENDERING_SERVICE,
                    action = "SetMute",
                    arguments = args,
                )
            result != null
        }

    /**
     * Queries Sonos `ZoneGroupTopology#GetZoneGroupState` from any speaker on the LAN.
     * Returns all zone members along with their active Group Coordinator UUID, IP, and visibility flag
     * so stereo pairs, bonded Subs/surrounds, and grouped speakers are always routed to their Coordinator.
     */
    suspend fun getZoneGroupTopology(baseUrl: String): List<ZoneTopologyMember> =
        withContext(Dispatchers.IO) {
            val xml =
                executeSoap(
                    baseUrl = baseUrl,
                    endpoint = ZONE_GROUP_TOPOLOGY_CONTROL,
                    service = ZONE_GROUP_TOPOLOGY_SERVICE,
                    action = "GetZoneGroupState",
                    arguments = "",
                ) ?: return@withContext emptyList()

            val escapedState = extractTagValue(xml, "ZoneGroupState") ?: return@withContext emptyList()
            val stateXml = unescapeXml(escapedState)

            val members = mutableListOf<ZoneTopologyMember>()
            val groupRegex = Regex("""<ZoneGroup\b[^>]*Coordinator="([^"]+)"[^>]*>([\s\S]*?)</ZoneGroup>""")
            val memberRegex = Regex("""<ZoneGroupMember\b([^>]*?)/?>""")
            val attrRegex = Regex("""(\w+)="([^"]*)"""")

            for (groupMatch in groupRegex.findAll(stateXml)) {
                val coordinatorUuid = groupMatch.groupValues[1]
                val groupContent = groupMatch.groupValues[2]

                for (memberMatch in memberRegex.findAll(groupContent)) {
                    val attrsStr = memberMatch.groupValues[1]
                    val attrs = mutableMapOf<String, String>()
                    for (attrMatch in attrRegex.findAll(attrsStr)) {
                        attrs[attrMatch.groupValues[1]] = unescapeXml(attrMatch.groupValues[2])
                    }

                    val uuid = attrs["UUID"] ?: continue
                    val zoneName = attrs["ZoneName"] ?: continue
                    val location = attrs["Location"] ?: continue
                    val invisible = attrs["Invisible"] == "1"

                    val parsedUrl = runCatching { URL(location) }.getOrNull() ?: continue
                    val ip = parsedUrl.host ?: continue
                    val port = if (parsedUrl.port > 0) parsedUrl.port else 1400
                    val memberBaseUrl = "${parsedUrl.protocol}://$ip:$port"

                    members.add(
                        ZoneTopologyMember(
                            uuid = uuid,
                            coordinatorUuid = coordinatorUuid,
                            zoneName = zoneName,
                            ip = ip,
                            baseUrl = memberBaseUrl,
                            isCoordinator = (uuid == coordinatorUuid),
                            isInvisible = invisible,
                        ),
                    )
                }
            }
            members
        }

    private fun executeSoap(
        baseUrl: String,
        endpoint: String,
        service: String,
        action: String,
        arguments: String,
    ): String? {
        val urlStr = baseUrl.trimEnd('/') + endpoint
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            connection =
                (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 4000
                    readTimeout = 4000
                    doOutput = true
                    doInput = true
                    setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                    setRequestProperty("SOAPACTION", "\"$service#$action\"")
                    setRequestProperty("Connection", "close")
                }

            val envelope =
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                    "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                    "<s:Body>" +
                    "<u:$action xmlns:u=\"$service\">" +
                    arguments +
                    "</u:$action>" +
                    "</s:Body>" +
                    "</s:Envelope>"

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use {
                it.write(envelope)
                it.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                val errorMsg = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                Logger.w(TAG, "SOAP $action failed with code $responseCode: $errorMsg")
                null
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Error executing SOAP $action on $urlStr: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun buildDidlLiteMetadata(
        streamUrl: String,
        title: String,
        artist: String,
        album: String,
        artworkUrl: String?,
        durationSeconds: Int,
        mimeType: String,
        sizeBytes: Long,
        useUniversalProtocolInfo: Boolean = false,
    ): String {
        val durationStr =
            if (durationSeconds > 0) {
                val h = durationSeconds / 3600
                val m = (durationSeconds % 3600) / 60
                val s = durationSeconds % 60
                String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
            } else {
                ""
            }

        val artTag =
            if (!artworkUrl.isNullOrBlank()) {
                "<upnp:albumArtURI>${escapeXml(artworkUrl)}</upnp:albumArtURI>"
            } else {
                ""
            }

        val durationAttr = if (durationStr.isNotBlank()) " duration=\"$durationStr\"" else ""
        val sizeAttr = if (sizeBytes > 0L) " size=\"$sizeBytes\"" else ""
        val protocolInfo =
            if (useUniversalProtocolInfo) {
                "http-get:*:$mimeType:*"
            } else {
                val dlnaProfile = if (mimeType.contains("wav")) "LPCM" else "AAC_ISO_320"
                "http-get:*:$mimeType:DLNA.ORG_PN=$dlnaProfile;DLNA.ORG_OP=11;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
            }

        return "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
            "xmlns:r=\"urn:schemas-rinconnetworks-com:metadata-1-0/\" " +
            "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">" +
            "<item id=\"1\" parentID=\"0\" restricted=\"true\">" +
            "<res protocolInfo=\"$protocolInfo\"$durationAttr$sizeAttr>${escapeXml(streamUrl)}</res>" +
            "<dc:title>${escapeXml(title)}</dc:title>" +
            "<dc:creator>${escapeXml(artist)}</dc:creator>" +
            "<upnp:artist>${escapeXml(artist)}</upnp:artist>" +
            "<upnp:album>${escapeXml(album)}</upnp:album>" +
            artTag +
            "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            "</item>" +
            "</DIDL-Lite>"
    }

    private fun escapeXml(input: String): String {
        val sb = StringBuilder()
        for (c in input) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun unescapeXml(input: String): String =
        input
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")

    private fun extractTagValue(
        xml: String,
        tagName: String,
    ): String? {
        val openTag = "<$tagName>"
        val closeTag = "</$tagName>"
        val startIdx = xml.indexOf(openTag)
        if (startIdx < 0) return null
        val endIdx = xml.indexOf(closeTag, startIdx + openTag.length)
        if (endIdx < 0) return null
        return xml.substring(startIdx + openTag.length, endIdx).trim()
    }

    fun formatMsToRelTime(positionMs: Long): String {
        val totalSeconds = (positionMs / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    fun parseRelTimeToMs(timeStr: String): Long =
        try {
            val parts = timeStr.trim().split(":")
            when (parts.size) {
                3 -> {
                    val h = parts[0].toLongOrNull() ?: 0L
                    val m = parts[1].toLongOrNull() ?: 0L
                    val s = parts[2].substringBefore('.').toLongOrNull() ?: 0L
                    (h * 3600 + m * 60 + s) * 1000L
                }
                2 -> {
                    val m = parts[0].toLongOrNull() ?: 0L
                    val s = parts[1].substringBefore('.').toLongOrNull() ?: 0L
                    (m * 60 + s) * 1000L
                }
                else -> 0L
            }
        } catch (_: Exception) {
            0L
        }

    companion object {
        private const val TAG = "SonosSoapClient"
        private const val AV_TRANSPORT_CONTROL = "/MediaRenderer/AVTransport/Control"
        private const val AV_TRANSPORT_SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val RENDERING_CONTROL = "/MediaRenderer/RenderingControl/Control"
        private const val RENDERING_SERVICE = "urn:schemas-upnp-org:service:RenderingControl:1"
        private const val ZONE_GROUP_TOPOLOGY_CONTROL = "/ZoneGroupTopology/Control"
        private const val ZONE_GROUP_TOPOLOGY_SERVICE = "urn:schemas-upnp-org:service:ZoneGroupTopology:1"
    }
}
