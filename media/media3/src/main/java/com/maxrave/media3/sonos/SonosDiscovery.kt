package com.maxrave.media3.sonos

import android.content.Context
import android.net.wifi.WifiManager
import com.maxrave.domain.data.player.SonosDevice
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Discovers Sonos speakers on the local Wi-Fi network using SSDP (Simple Service Discovery Protocol)
 * combined with Sonos `ZoneGroupTopology#GetZoneGroupState` to:
 * 1. Discover all rooms in the household even if some speakers miss UDP multicast packets.
 * 2. Filter out `Invisible="1"` bonded satellites (Subs, surrounds, stereo pair right-channel slaves).
 * 3. Route stereo pairs and grouped zones to their active Coordinator IP/baseUrl.
 */
internal class SonosDiscovery(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val soapClient: SonosSoapClient = SonosSoapClient(),
) {
    private val _devices = MutableStateFlow<List<SonosDevice>>(emptyList())
    val devices: StateFlow<List<SonosDevice>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var scanJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun startDiscovery() {
        if (_isScanning.value) return
        scanJob?.cancel()
        scanJob =
            coroutineScope.launch {
                scan()
            }
    }

    fun stopDiscovery() {
        scanJob?.cancel()
        scanJob = null
        releaseMulticastLock()
        _isScanning.value = false
    }

    fun refresh() {
        stopDiscovery()
        startDiscovery()
    }

    private suspend fun scan() =
        withContext(Dispatchers.IO) {
            _isScanning.value = true
            acquireMulticastLock()
            val discoveredMap = LinkedHashMap<String, SonosDevice>()
            var topologyFetched = false

            // Keep previously discovered visible devices initially
            _devices.value.forEach { discoveredMap[it.id] = it }

            var socket: DatagramSocket? = null
            try {
                socket =
                    DatagramSocket(null).apply {
                        reuseAddress = true
                        soTimeout = SOCKET_TIMEOUT_MS
                        bind(InetSocketAddress(0))
                    }

                val ssdpMessage = buildSsdpSearchMessage()
                val multicastAddress = InetAddress.getByName(SSDP_IP)
                val packet =
                    DatagramPacket(
                        ssdpMessage.toByteArray(Charsets.UTF_8),
                        ssdpMessage.length,
                        multicastAddress,
                        SSDP_PORT,
                    )

                // Send discovery packet twice with a small gap to overcome UDP packet loss
                socket.send(packet)
                delay(100)
                socket.send(packet)

                val receiveBuffer = ByteArray(4096)
                val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                val startTime = System.currentTimeMillis()

                while (isActive && (System.currentTimeMillis() - startTime < SCAN_DURATION_MS)) {
                    try {
                        socket.receive(receivePacket)
                        val response = String(receivePacket.data, 0, receivePacket.length, Charsets.UTF_8)
                        val location = extractHeader(response, "LOCATION")
                        val server = extractHeader(response, "SERVER")
                        val st = extractHeader(response, "ST")
                        val usn = extractHeader(response, "USN")

                        val isSonos =
                            server?.contains("Sonos", ignoreCase = true) == true ||
                                st?.contains("ZonePlayer", ignoreCase = true) == true ||
                                usn?.contains("ZonePlayer", ignoreCase = true) == true ||
                                location?.contains("1400") == true

                        if (isSonos && location != null) {
                            val device = parseDeviceDescription(location)
                            if (device != null) {
                                // Query ZoneGroupTopology once from the first responding speaker
                                if (!topologyFetched) {
                                    topologyFetched = true
                                    val topologyDevices = resolveFromZoneGroupTopology(device.baseUrl)
                                    if (topologyDevices.isNotEmpty()) {
                                        discoveredMap.clear()
                                        for (td in topologyDevices) {
                                            discoveredMap[td.id] = td
                                        }
                                        _devices.value = discoveredMap.values.toList()
                                        Logger.i(TAG, "Populated ${topologyDevices.size} Sonos zones via ZoneGroupTopology")
                                    }
                                }

                                if (!topologyFetched || discoveredMap.isEmpty()) {
                                    // Deduplicate by room name, preferring coordinator
                                    val existingSameRoom = discoveredMap.values.firstOrNull { it.name.equals(device.name, ignoreCase = true) }
                                    if (existingSameRoom == null) {
                                        discoveredMap[device.id] = device
                                        _devices.value = discoveredMap.values.toList()
                                    }
                                }
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        // Continue listening until SCAN_DURATION_MS completes
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Logger.w(TAG, "Error receiving SSDP packet: ${e.message}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "SSDP scan error: ${e.message}", e)
            } finally {
                socket?.close()
                releaseMulticastLock()
                _isScanning.value = false
            }
        }

    private suspend fun resolveFromZoneGroupTopology(anySpeakerBaseUrl: String): List<SonosDevice> {
        val members = soapClient.getZoneGroupTopology(anySpeakerBaseUrl)
        if (members.isEmpty()) return emptyList()

        val byUuid = members.associateBy { it.uuid }
        val visibleMembers = members.filter { !it.isInvisible }
        val result = mutableListOf<SonosDevice>()
        val seenZoneNames = mutableSetOf<String>()

        // Sort so coordinators come first when deduplicating rooms
        for (member in visibleMembers.sortedByDescending { it.isCoordinator }) {
            val normalizedRoom = member.zoneName.trim().lowercase()
            if (!seenZoneNames.add(normalizedRoom)) continue

            // If this visible room is paired/grouped with a coordinator of the same room name, use coordinator
            val coordinator = byUuid[member.coordinatorUuid]
            val targetMember =
                if (coordinator != null && coordinator.zoneName.equals(member.zoneName, ignoreCase = true)) {
                    coordinator
                } else {
                    member
                }

            val desc = parseDeviceDescription("${targetMember.baseUrl}/xml/device_description.xml")
            val modelName = desc?.model ?: "Sonos Speaker"

            result.add(
                SonosDevice(
                    id = "uuid:${targetMember.uuid}",
                    name = member.zoneName,
                    model = modelName,
                    ip = targetMember.ip,
                    port = 1400,
                    baseUrl = targetMember.baseUrl,
                    isCoordinator = targetMember.isCoordinator,
                ),
            )
        }
        return result
    }

    private fun buildSsdpSearchMessage(): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_IP:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n"

    private fun extractHeader(
        response: String,
        headerName: String,
    ): String? {
        val lines = response.lines()
        for (line in lines) {
            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                val key = line.substring(0, colonIndex).trim()
                if (key.equals(headerName, ignoreCase = true)) {
                    return line.substring(colonIndex + 1).trim()
                }
            }
        }
        return null
    }

    private fun parseDeviceDescription(locationUrl: String): SonosDevice? =
        try {
            val url = URL(locationUrl)
            val ip = url.host
            val port = if (url.port > 0) url.port else 1400
            val conn = url.openConnection()
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val xml = conn.getInputStream().bufferedReader().use { it.readText() }

            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var friendlyName: String? = null
            var modelName: String? = null
            var roomName: String? = null
            var udn: String? = null
            var currentTag = ""

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text.trim()
                        if (text.isNotEmpty()) {
                            when (currentTag.lowercase()) {
                                "friendlyname" -> if (friendlyName == null) friendlyName = text
                                "modelname" -> if (modelName == null) modelName = text
                                "roomname" -> if (roomName == null) roomName = text
                                "udn" -> if (udn == null) udn = text
                            }
                        }
                    }
                }
                eventType = parser.next()
            }

            val deviceName = roomName ?: friendlyName ?: "Sonos Speaker"
            val id = udn ?: "uuid:rincon_${ip.replace('.', '_')}"
            val model = modelName ?: "Sonos"

            SonosDevice(
                id = id,
                name = deviceName,
                model = model,
                ip = ip,
                port = port,
                baseUrl = "http://$ip:$port",
                isCoordinator = true,
            )
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to parse device description from $locationUrl: ${e.message}")
            null
        }

    private fun acquireMulticastLock() {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock =
                wifiManager?.createMulticastLock("ReplaySonosMulticastLock")?.apply {
                    setReferenceCounted(true)
                    acquire()
                }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to acquire multicast lock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
            multicastLock = null
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to release multicast lock: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "SonosDiscovery"
        private const val SSDP_IP = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SOCKET_TIMEOUT_MS = 1000
        private const val SCAN_DURATION_MS = 4000L
    }
}
