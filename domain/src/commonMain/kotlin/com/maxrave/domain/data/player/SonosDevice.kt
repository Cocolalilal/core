package com.maxrave.domain.data.player

/**
 * Representation of a discovered Sonos speaker on the local network.
 */
data class SonosDevice(
    val id: String,
    val name: String,
    val model: String = "Sonos",
    val ip: String,
    val port: Int = 1400,
    val baseUrl: String = "http://$ip:$port",
    val isCoordinator: Boolean = true,
    val groupMembers: List<String> = emptyList(),
)
