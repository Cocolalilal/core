package com.maxrave.domain.mediaservice.sonos

import com.maxrave.domain.data.player.SonosDevice
import kotlinx.coroutines.flow.StateFlow

/**
 * Controller interface for discovering and managing Sonos speakers on the local network.
 */
interface SonosController {
    val devices: StateFlow<List<SonosDevice>>
    val isScanning: StateFlow<Boolean>
    val connectedDevice: StateFlow<SonosDevice?>
    val volume: StateFlow<Float> // 0f..1f

    fun startDiscovery()
    fun stopDiscovery()
    fun refresh()
    fun connect(device: SonosDevice)
    fun disconnect()
    fun setVolume(volume: Float)
}
