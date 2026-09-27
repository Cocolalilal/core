package com.maxrave.media3.sonos

import android.content.Context
import android.net.wifi.WifiManager
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Embedded HTTP 1.1 / DLNA server that serves canonical faststart `.m4a` (or `.wav`) audio files
 * to Sonos speakers over the local Wi-Fi network.
 *
 * Responsibilities:
 * 1. Downloads YouTube's DASH `fMP4` (`itag=140/141`) using high-speed parallel HTTP Range chunks
 *    to bypass CDN bandwidth throttling.
 * 2. Losslessly flattens `fMP4` fragments (`moof`/`trun` + `mdat`) via [Fmp4ToM4aConverter] into a
 *    canonical faststart `.m4a` (`[ftyp][moov (stsd, stts, stsc, stsz, stco)][mdat]`) so Sonos
 *    hardware can immediately parse the full sample table and seek to any timestamp.
 * 3. Serves `HEAD`, `GET` (`200 OK`), and `Range` (`206 Partial Content`) requests from disk via
 *    [RandomAccessFile] with sub-millisecond latency and DLNA byte-seek headers (`DLNA.ORG_OP=01`).
 */
internal class SonosAudioServer(
    private val context: Context,
    private val streamResolver: SonosStreamResolver,
    private val coroutineScope: CoroutineScope,
) {
    internal data class PreparedTrack(
        val file: File,
        val mimeType: String,
        val extension: String,
        val durationSeconds: Int?,
    )

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val preparationJobs = ConcurrentHashMap<String, Deferred<PreparedTrack?>>()
    private val cacheDir: File by lazy {
        File(context.cacheDir, "sonos_audio_cache").apply { mkdirs() }
    }

    @Volatile
    var serverPort: Int = 0
        private set

    val isRunning: Boolean
        get() = serverSocket?.isClosed == false && serverJob?.isActive == true

    fun start(): Int {
        if (isRunning) return serverPort

        try {
            val socket =
                ServerSocket(0).apply {
                    reuseAddress = true
                }
            serverSocket = socket
            serverPort = socket.localPort

            serverJob =
                coroutineScope.launch(Dispatchers.IO) {
                    Logger.i(TAG, "Sonos audio proxy server started on port $serverPort")
                    while (isActive && !socket.isClosed) {
                        try {
                            val clientSocket = socket.accept()
                            launch(Dispatchers.IO) {
                                handleClient(clientSocket)
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException || socket.isClosed) break
                            Logger.w(TAG, "Accept error: ${e.message}")
                        }
                    }
                }
            return serverPort
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to start Sonos audio server: ${e.message}", e)
            return 0
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
        serverPort = 0
        preparationJobs.values.forEach { it.cancel() }
        preparationJobs.clear()
        Logger.i(TAG, "Sonos audio proxy server stopped")
    }

    /**
     * Pre-downloads and converts the track for [videoId] into a Sonos-ready faststart `.m4a` (or `.wav`) file.
     * Concurrent calls for the same [videoId] share the same in-flight job.
     */
    suspend fun prepareTrack(videoId: String): PreparedTrack? =
        withContext(Dispatchers.IO) {
            // Check existing cached files first
            val cachedM4a = File(cacheDir, "$videoId.m4a")
            if (cachedM4a.exists() && cachedM4a.length() > 1024) {
                cachedM4a.setLastModified(System.currentTimeMillis())
                return@withContext PreparedTrack(cachedM4a, "audio/mp4", "m4a", null)
            }
            val cachedWav = File(cacheDir, "$videoId.wav")
            if (cachedWav.exists() && cachedWav.length() > 1024) {
                cachedWav.setLastModified(System.currentTimeMillis())
                return@withContext PreparedTrack(cachedWav, "audio/wav", "wav", null)
            }

            val existingJob = preparationJobs[videoId]
            if (existingJob != null && existingJob.isActive) {
                return@withContext existingJob.await()
            }

            val deferred =
                coroutineScope.async(Dispatchers.IO) {
                    try {
                        downloadAndPrepareTrackInternal(videoId)
                    } finally {
                        preparationJobs.remove(videoId)
                    }
                }
            preparationJobs[videoId] = deferred
            deferred.await()
        }

    fun getStreamUrl(
        videoId: String,
        extension: String = "m4a",
        fallbackUrl: String,
    ): String {
        val ip = getLocalWifiIpAddress()
        return if (ip != null && isRunning && serverPort > 0) {
            "http://$ip:$serverPort/sonos/stream/$videoId.$extension"
        } else {
            fallbackUrl
        }
    }

    fun getArtworkUrl(
        videoId: String,
        originalArtworkUrl: String?,
    ): String? {
        val ip = getLocalWifiIpAddress()
        return if (ip != null && isRunning && serverPort > 0 && !originalArtworkUrl.isNullOrBlank()) {
            "http://$ip:$serverPort/sonos/artwork/$videoId.jpg"
        } else {
            originalArtworkUrl
        }
    }

    fun getLocalWifiIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()

            // 1. First priority: wlan interface with a site-local IPv4 address (192.168.x.x, 10.x.x.x, 172.16-31.x.x)
            val wlanIp =
                interfaces
                    .filter { it.isUp && !it.isLoopback && it.name.contains("wlan", ignoreCase = true) }
                    .flatMap { it.inetAddresses.toList() }
                    .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
                    ?.hostAddress
            if (wlanIp != null) return wlanIp

            // 2. Second priority: any non-virtual, non-cellular interface with a site-local IPv4 address
            val lanIp =
                interfaces
                    .filter {
                        it.isUp && !it.isLoopback &&
                            !it.name.startsWith("rmnet", ignoreCase = true) &&
                            !it.name.startsWith("tun", ignoreCase = true) &&
                            !it.name.startsWith("p2p", ignoreCase = true)
                    }.flatMap { it.inetAddresses.toList() }
                    .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
                    ?.hostAddress
            if (lanIp != null) return lanIp

            // 3. Third priority: WifiManager connection info (for legacy Android)
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                return String.format(
                    Locale.US,
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff,
                )
            }

            // 4. Fallback: any active non-loopback IPv4 address
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress
                        if (host != null && !host.startsWith("127.")) {
                            return host
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        return null
    }

    private suspend fun handleClient(client: Socket) =
        withContext(Dispatchers.IO) {
            try {
                client.soTimeout = 20_000
                client.tcpNoDelay = true
                val input = client.getInputStream()
                val output = client.getOutputStream()

                // Read HTTP request line and headers using a single unbuffered byte-line reader
                // (prevents BufferedReader over-read hang)
                val requestLine = readAsciiLine(input) ?: return@withContext
                val parts = requestLine.split(" ")
                if (parts.size < 2) return@withContext

                val method = parts[0].uppercase(Locale.US)
                val path = parts[1]

                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readAsciiLine(input) ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        headers[line.substring(0, colon).trim().lowercase(Locale.US)] =
                            line.substring(colon + 1).trim()
                    }
                }

                Logger.d(TAG, "Sonos HTTP $method $path (Range: ${headers["range"] ?: "none"})")

                if (path.startsWith("/sonos/stream/")) {
                    val fileName = path.removePrefix("/sonos/stream/").substringBefore('?')
                    val rawId = fileName.substringBeforeLast('.')
                    handleStreamRequest(rawId, method, headers, output)
                } else if (path.startsWith("/sonos/artwork/")) {
                    val rawId = path.removePrefix("/sonos/artwork/").substringBefore('.')
                    handleArtworkRequest(rawId, output)
                } else {
                    sendHttpError(output, 404, "Not Found")
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Logger.d(TAG, "Client socket finished: ${e.message}")
                }
            } finally {
                try {
                    client.close()
                } catch (_: Exception) {
                }
            }
        }

    private suspend fun handleStreamRequest(
        videoId: String,
        method: String,
        headers: Map<String, String>,
        output: OutputStream,
    ) {
        val prepared = prepareTrack(videoId)
        if (prepared == null || !prepared.file.exists()) {
            Logger.e(TAG, "Failed to prepare track $videoId for Sonos HTTP request")
            sendHttpError(output, 404, "Stream Not Found")
            return
        }

        val file = prepared.file
        val totalLength = file.length()
        val contentType = prepared.mimeType
        val rangeHeader = headers["range"]

        var startByte = 0L
        var endByte = totalLength - 1L
        var isPartial = false

        if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=", ignoreCase = true)) {
            val rangeSpec = rangeHeader.substringAfter('=').substringBefore(',').trim()
            val dashIdx = rangeSpec.indexOf('-')
            if (dashIdx >= 0) {
                val startStr = rangeSpec.substring(0, dashIdx).trim()
                val endStr = rangeSpec.substring(dashIdx + 1).trim()
                if (startStr.isNotEmpty()) {
                    startByte = startStr.toLongOrNull()?.coerceIn(0L, totalLength - 1L) ?: 0L
                    if (endStr.isNotEmpty()) {
                        endByte = endStr.toLongOrNull()?.coerceIn(startByte, totalLength - 1L) ?: (totalLength - 1L)
                    }
                } else if (endStr.isNotEmpty()) {
                    // Suffix range bytes=-500
                    val suffixLen = endStr.toLongOrNull() ?: 0L
                    startByte = (totalLength - suffixLen).coerceIn(0L, totalLength - 1L)
                }
                isPartial = true
            }
        }

        val contentLength = (endByte - startByte + 1L).coerceAtLeast(0L)
        val dlnaProfile = if (prepared.extension == "wav") "LPCM" else "AAC_ISO_320"

        val headerBuilder = StringBuilder()
        if (isPartial) {
            headerBuilder.append("HTTP/1.1 206 Partial Content\r\n")
            headerBuilder.append("Content-Range: bytes $startByte-$endByte/$totalLength\r\n")
        } else {
            headerBuilder.append("HTTP/1.1 200 OK\r\n")
        }
        headerBuilder.append("Content-Type: $contentType\r\n")
        headerBuilder.append("Content-Length: $contentLength\r\n")
        headerBuilder.append("Accept-Ranges: bytes\r\n")
        headerBuilder.append("transferMode.dlna.org: Streaming\r\n")
        headerBuilder.append(
            "contentFeatures.dlna.org: DLNA.ORG_PN=$dlnaProfile;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n",
        )
        headerBuilder.append("Cache-Control: no-cache\r\n")
        headerBuilder.append("Connection: close\r\n\r\n")

        output.write(headerBuilder.toString().toByteArray(Charsets.US_ASCII))
        output.flush()

        if (method == "HEAD" || contentLength == 0L) {
            return
        }

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(startByte)
            val buffer = ByteArray(32_768)
            var remaining = contentLength
            while (remaining > 0L) {
                val toRead = min(buffer.size.toLong(), remaining).toInt()
                val read = raf.read(buffer, 0, toRead)
                if (read <= 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
            output.flush()
        }
    }

    private suspend fun downloadAndPrepareTrackInternal(videoId: String): PreparedTrack? {
        trimCacheIfNeeded()

        var resolved = streamResolver.resolve(videoId) ?: return null
        var rawBytes = downloadStreamFast(resolved.url, resolved.contentLength)
        if (rawBytes == null) {
            Logger.w(TAG, "First download attempt failed for $videoId, invalidating & retrying...")
            streamResolver.invalidate(videoId)
            resolved = streamResolver.resolve(videoId) ?: return null
            rawBytes = downloadStreamFast(resolved.url, resolved.contentLength) ?: return null
        }

        // 1. Try lossless fMP4 -> faststart M4A conversion first
        val m4aBytes = Fmp4ToM4aConverter.convertFmp4ToStandardM4a(rawBytes)
        if (m4aBytes != null && m4aBytes.size > 1024) {
            val outM4a = File(cacheDir, "$videoId.m4a")
            val tempFile = File(cacheDir, "$videoId.m4a.tmp")
            tempFile.writeBytes(m4aBytes)
            tempFile.renameTo(outM4a)
            Logger.i(TAG, "Prepared faststart M4A for $videoId (${outM4a.length()} bytes)")
            return PreparedTrack(outM4a, "audio/mp4", "m4a", resolved.durationSeconds)
        }

        // 2. Fallback: if upstream was WebM/Opus, decode to 16-bit PCM WAV for Sonos compatibility
        val rawTempFile = File(cacheDir, "$videoId.raw.tmp")
        val outWav = File(cacheDir, "$videoId.wav")
        val wavTempFile = File(cacheDir, "$videoId.wav.tmp")
        try {
            rawTempFile.writeBytes(rawBytes)
            if (Fmp4ToM4aConverter.decodeToWavFile(rawTempFile, wavTempFile)) {
                wavTempFile.renameTo(outWav)
                Logger.i(TAG, "Prepared decoded WAV fallback for $videoId (${outWav.length()} bytes)")
                return PreparedTrack(outWav, "audio/wav", "wav", resolved.durationSeconds)
            }
        } finally {
            runCatching { rawTempFile.delete() }
            runCatching { wavTempFile.delete() }
        }

        return null
    }

    /**
     * Downloads a YouTube stream using parallel ranged requests (`Range: bytes=start-end`)
     * so YouTube's CDN serves at full bandwidth instead of throttling un-ranged streams.
     */
    private suspend fun downloadStreamFast(
        urlStr: String,
        knownContentLength: Long?,
    ): ByteArray? =
        coroutineScope {
            try {
                // Strip any pre-appended &range=0-... query parameter so our HTTP Range header controls chunking
                val cleanUrl = urlStr.replace(Regex("[&?]range=[^&]+"), "")
                val totalLen = knownContentLength?.takeIf { it > 0 } ?: probeContentLength(cleanUrl)

                if (totalLen <= 0L) {
                    // Fallback single download
                    return@coroutineScope downloadByteRange(cleanUrl, null)
                }

                val chunkSize = 768 * 1024L // 768 KB per parallel chunk
                val ranges = mutableListOf<LongRange>()
                var start = 0L
                while (start < totalLen) {
                    val end = min(start + chunkSize - 1L, totalLen - 1L)
                    ranges.add(start..end)
                    start = end + 1L
                }

                val chunks =
                    ranges
                        .map { range ->
                            async(Dispatchers.IO) {
                                downloadByteRange(cleanUrl, "bytes=${range.first}-${range.last}")
                            }
                        }.awaitAll()

                if (chunks.any { it == null }) {
                    return@coroutineScope downloadByteRange(cleanUrl, "bytes=0-${totalLen - 1}")
                }

                val output = ByteArrayOutputStream(totalLen.toInt())
                for (chunk in chunks) {
                    if (chunk != null) output.write(chunk)
                }
                output.toByteArray()
            } catch (e: Exception) {
                Logger.e(TAG, "downloadStreamFast error: ${e.message}", e)
                null
            }
        }

    private fun probeContentLength(urlStr: String): Long {
        var conn: HttpURLConnection? = null
        return try {
            conn = openUpstreamConnection(urlStr, "bytes=0-1")
            val code = conn.responseCode
            if (code == 206) {
                val contentRange = conn.getHeaderField("Content-Range") // e.g. bytes 0-1/3145728
                contentRange?.substringAfter('/')?.trim()?.toLongOrNull() ?: -1L
            } else if (code == 200) {
                conn.contentLengthLong
            } else {
                -1L
            }
        } catch (_: Exception) {
            -1L
        } finally {
            conn?.disconnect()
        }
    }

    private fun downloadByteRange(
        urlStr: String,
        rangeHeader: String?,
    ): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = openUpstreamConnection(urlStr, rangeHeader)
            val code = conn.responseCode
            if (code !in 200..299) {
                Logger.w(TAG, "HTTP $code when downloading chunk ($rangeHeader)")
                return null
            }
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream(if (conn.contentLength > 0) conn.contentLength else 262_144)
                val buf = ByteArray(16_384)
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    out.write(buf, 0, read)
                }
                out.toByteArray()
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Chunk download failed ($rangeHeader): ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun trimCacheIfNeeded() {
        try {
            val files = cacheDir.listFiles() ?: return
            if (files.size > MAX_CACHED_TRACKS) {
                files
                    .sortedBy { it.lastModified() }
                    .take(files.size - MAX_CACHED_TRACKS)
                    .forEach { runCatching { it.delete() } }
            }
        } catch (_: Exception) {
        }
    }

    private fun handleArtworkRequest(
        videoId: String,
        output: OutputStream,
    ) {
        val artworkUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        var connection: HttpURLConnection? = null
        try {
            connection =
                (URL(artworkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                }
            val contentType = connection.contentType ?: "image/jpeg"
            val contentLength = connection.contentLengthLong

            val headers =
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $contentType\r\n" +
                    (if (contentLength > 0) "Content-Length: $contentLength\r\n" else "") +
                    "Connection: close\r\n\r\n"

            output.write(headers.toByteArray(Charsets.US_ASCII))
            output.flush()

            connection.inputStream.use { input ->
                val buffer = ByteArray(16_384)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
                output.flush()
            }
        } catch (e: Exception) {
            Logger.d(TAG, "Artwork request finished: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun openUpstreamConnection(
        urlStr: String,
        rangeHeader: String?,
    ): HttpURLConnection =
        (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 10000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "com.google.android.youtube/19.29.37 (Linux; U; Android 14) gzip")
            if (!rangeHeader.isNullOrBlank()) {
                setRequestProperty("Range", rangeHeader)
            }
        }

    /**
     * Reads a single CRLF/LF terminated line from [input] without buffering past the newline.
     */
    private fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder(80)
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (sb.isEmpty()) null else sb.toString()
            }
            if (b == '\n'.code) {
                break
            }
            if (b != '\r'.code) {
                sb.append(b.toChar())
            }
        }
        return sb.toString()
    }

    private fun sendHttpError(
        output: OutputStream,
        code: Int,
        message: String,
    ) {
        try {
            val response = "HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            output.write(response.toByteArray(Charsets.US_ASCII))
            output.flush()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "SonosAudioServer"
        private const val MAX_CACHED_TRACKS = 8
    }
}
