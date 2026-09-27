package com.maxrave.media3.sonos

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.maxrave.domain.data.player.SonosDevice
import com.maxrave.domain.mediaservice.sonos.SonosController
import com.maxrave.logger.Logger
import com.maxrave.media3.exoplayer.CrossfadeExoPlayerAdapter
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

/**
 * Manages playback handoff, state synchronization, and transport control between Replay
 * and remote Sonos speakers on the local Wi-Fi network.
 */
@UnstableApi
internal class SonosHandoffManager(
    private val context: Context,
    private val adapter: CrossfadeExoPlayerAdapter,
    private val streamResolver: SonosStreamResolver,
    private val coroutineScope: CoroutineScope,
) : SonosController {
    private val soapClient = SonosSoapClient()
    private val discovery = SonosDiscovery(context, coroutineScope, soapClient)
    private val audioServer = SonosAudioServer(context, streamResolver, coroutineScope)

    override val devices: StateFlow<List<SonosDevice>> = discovery.devices
    override val isScanning: StateFlow<Boolean> = discovery.isScanning

    private val _connectedDevice = MutableStateFlow<SonosDevice?>(null)
    override val connectedDevice: StateFlow<SonosDevice?> = _connectedDevice.asStateFlow()

    private val _volume = MutableStateFlow(0.5f)
    override val volume: StateFlow<Float> = _volume.asStateFlow()

    private var pollJob: Job? = null
    private var volumeJob: Job? = null
    private var playTrackJob: Job? = null

    @Volatile
    private var suppressPollingUntilMs: Long = 0L

    @Volatile
    private var currentLoadedVideoId: String? = null

    val isConnected: Boolean
        get() = _connectedDevice.value != null

    private val sonosPlayer: SonosPlayer =
        SonosPlayer(
            basePlayer = adapter.forwardingPlayer,
            onPlayAction = {
                _connectedDevice.value?.let { dev ->
                    coroutineScope.launch {
                        suppressPollingUntilMs = System.currentTimeMillis() + 3500L
                        sonosPlayer.remoteIsPlaying = true
                        adapter.notifyRemoteIsPlaying(true)

                        val transport = withContext(Dispatchers.IO) { soapClient.getTransportInfo(dev.baseUrl) }
                        val currentMedia = adapter.currentMediaItem
                        val isTrackLoaded =
                            currentLoadedVideoId != null &&
                                currentLoadedVideoId == currentMedia?.mediaId &&
                                !transport.equals("STOPPED", ignoreCase = true) &&
                                !transport.equals("NO_MEDIA_PRESENT", ignoreCase = true)

                        if (!isTrackLoaded) {
                            Logger.d(TAG, "onPlayAction: track not loaded on Sonos (state=$transport), re-triggering playTrack()")
                            playTrack(adapter.currentMediaItemIndex, sonosPlayer.remotePositionMs, true)
                        } else {
                            val playOk = withContext(Dispatchers.IO) { soapClient.play(dev.baseUrl) }
                            if (!playOk) {
                                Logger.w(TAG, "soapClient.play() returned false, re-triggering playTrack()")
                                playTrack(adapter.currentMediaItemIndex, sonosPlayer.remotePositionMs, true)
                            }
                        }
                    }
                }
            },
            onPauseAction = {
                _connectedDevice.value?.let { dev ->
                    coroutineScope.launch {
                        suppressPollingUntilMs = System.currentTimeMillis() + 3500L
                        sonosPlayer.remoteIsPlaying = false
                        adapter.notifyRemoteIsPlaying(false)
                        withContext(Dispatchers.IO) {
                            soapClient.pause(dev.baseUrl)
                        }
                    }
                }
            },
            onStopAction = {
                _connectedDevice.value?.let { dev ->
                    coroutineScope.launch {
                        suppressPollingUntilMs = System.currentTimeMillis() + 3500L
                        sonosPlayer.remoteIsPlaying = false
                        adapter.notifyRemoteIsPlaying(false)
                        withContext(Dispatchers.IO) {
                            soapClient.stop(dev.baseUrl)
                        }
                    }
                }
            },
            onSeekAction = { positionMs ->
                seekSonos(positionMs)
            },
            onVolumeAction = { vol ->
                setVolume(vol)
            },
            onSeekToNextAction = {
                adapter.seekToNext()
            },
            onSeekToPreviousAction = {
                adapter.seekToPrevious()
            },
        )

    fun start() {
        Logger.d(TAG, "SonosHandoffManager initialized")
    }

    override fun startDiscovery() {
        discovery.startDiscovery()
    }

    override fun stopDiscovery() {
        discovery.stopDiscovery()
    }

    override fun refresh() {
        discovery.refresh()
    }

    override fun connect(device: SonosDevice) {
        if (_connectedDevice.value?.id == device.id) return

        coroutineScope.launch {
            try {
                Logger.i(TAG, "Connecting to Sonos device: ${device.name} (${device.ip})")
                _connectedDevice.value = device

                // Start local audio server for rock-solid local Wi-Fi playback
                audioServer.start()

                // Snapshot current local playback state BEFORE activating cast
                val startIndex = adapter.currentMediaItemIndex
                val startPositionMs = adapter.currentPosition
                val playWhenReady = adapter.isPlaying || adapter.playWhenReady
                val initialDurationMs = adapter.duration.takeIf { it > 0 } ?: 0L

                // Seed remote player duration and position immediately so UI never sees 0L
                sonosPlayer.remotePositionMs = startPositionMs
                sonosPlayer.remoteDurationMs = initialDurationMs
                sonosPlayer.remoteIsPlaying = playWhenReady

                // Activate remote player routing on adapter
                val displayDeviceName = "${device.name} (Sonos)"
                adapter.setCastActive(sonosPlayer, displayDeviceName, com.maxrave.domain.data.player.RemoteDeviceType.SONOS)

                // Wire up adapter's playback router so skips, next, previous, and queue taps land here
                adapter.castPlaybackRouter = { index, positionMs, pwr ->
                    playTrack(index, positionMs, pwr)
                }

                // Query initial volume from speaker
                val remoteVol = withContext(Dispatchers.IO) { soapClient.getVolume(device.baseUrl) }
                if (remoteVol != null) {
                    val volFloat = remoteVol / 100f
                    _volume.value = volFloat
                    sonosPlayer.remoteVolume = volFloat
                    adapter.notifyRemoteDeviceVolumeChanged(remoteVol)
                }

                startPolling(device)

                // Hand off current track to Sonos
                if (startIndex >= 0 && startIndex < adapter.mediaItemCount) {
                    playTrack(startIndex, startPositionMs, playWhenReady)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.e(TAG, "Failed to connect to Sonos speaker: ${e.message}", e)
                disconnect()
            }
        }
    }

    override fun disconnect() {
        val current = _connectedDevice.value ?: return
        Logger.i(TAG, "Disconnecting from Sonos: ${current.name}")
        _connectedDevice.value = null
        currentLoadedVideoId = null

        stopPolling()
        playTrackJob?.cancel()
        playTrackJob = null

        val resumeIndex = adapter.currentMediaItemIndex
        val resumePositionMs = sonosPlayer.remotePositionMs

        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                soapClient.stop(current.baseUrl)
            }
        }

        audioServer.stop()

        // Restore local ExoPlayer
        adapter.setCastActive(null, null)
        if (resumeIndex >= 0 && resumeIndex < adapter.mediaItemCount) {
            Logger.d(TAG, "Resuming local playback at index $resumeIndex, ${resumePositionMs}ms")
            adapter.seekTo(resumeIndex, resumePositionMs)
        }
    }

    override fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        _volume.value = clamped
        sonosPlayer.remoteVolume = clamped
        adapter.notifyRemoteDeviceVolumeChanged((clamped * 100).toInt())

        val device = _connectedDevice.value ?: return
        volumeJob?.cancel()
        volumeJob =
            coroutineScope.launch {
                delay(50) // Small debounce for smooth continuous slider dragging
                val volumePercent = (clamped * 100).toInt()
                withContext(Dispatchers.IO) {
                    soapClient.setVolume(device.baseUrl, volumePercent)
                }
            }
    }

    private fun seekSonos(positionMs: Long) {
        val device = _connectedDevice.value ?: return
        suppressPollingUntilMs = System.currentTimeMillis() + 3000L
        sonosPlayer.remotePositionMs = positionMs

        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                soapClient.seek(device.baseUrl, positionMs)
            }
        }
    }

    private fun playTrack(
        index: Int,
        startPositionMs: Long,
        playWhenReady: Boolean,
    ) {
        val device = _connectedDevice.value ?: return
        playTrackJob?.cancel()
        playTrackJob =
            coroutineScope.launch {
                try {
                    suppressPollingUntilMs = System.currentTimeMillis() + 4000L
                    val mediaItem = adapter.getMediaItemAt(index) ?: return@launch
                    val videoId = mediaItem.mediaId
                    Logger.d(TAG, "playTrack: preparing $videoId (index=$index, pos=${startPositionMs}ms)")

                    // Snapshot duration from local player or cached remote player before switch
                    val initialDurationMs: Long =
                        adapter.duration.takeIf { it > 0 }
                            ?: sonosPlayer.remoteDurationMs.takeIf { it > 0 }
                            ?: 0L

                    sonosPlayer.remotePositionMs = startPositionMs
                    if (initialDurationMs > 0L) {
                        sonosPlayer.remoteDurationMs = initialDurationMs
                    }
                    sonosPlayer.remoteIsPlaying = playWhenReady
                    adapter.notifyRemoteIsPlaying(playWhenReady)
                    adapter.notifyRemoteTransition(index)

                    // Prepare track (pre-downloads fast chunks & transmuxes to faststart M4A)
                    val prepared = audioServer.prepareTrack(videoId)
                    val extension = prepared?.extension ?: "m4a"
                    val streamMime = prepared?.mimeType ?: "audio/mp4"
                    val streamSize = prepared?.file?.length() ?: 0L

                    val streamUrl = audioServer.getStreamUrl(videoId, extension, "")
                    val artworkUrl = audioServer.getArtworkUrl(videoId, mediaItem.metadata.artworkUri)

                    val title = mediaItem.metadata.title ?: "Unknown Title"
                    val artist = mediaItem.metadata.artist ?: "Unknown Artist"
                    val album = mediaItem.metadata.albumTitle ?: ""
                    val durationSec =
                        prepared?.durationSeconds?.takeIf { it > 0 }
                            ?: ((sonosPlayer.remoteDurationMs / 1000).toInt().takeIf { it > 0 })
                            ?: 0

                    if (durationSec > 0 && sonosPlayer.remoteDurationMs <= 0L) {
                        sonosPlayer.remoteDurationMs = durationSec * 1000L
                    }

                    Logger.i(TAG, "Sending track to Sonos: $title by $artist ($streamUrl)")
                    val setUriSuccess =
                        withContext(Dispatchers.IO) {
                            soapClient.setAVTransportURI(
                                baseUrl = device.baseUrl,
                                streamUrl = streamUrl,
                                title = title,
                                artist = artist,
                                album = album,
                                artworkUrl = artworkUrl,
                                durationSeconds = durationSec,
                                mimeType = streamMime,
                                sizeBytes = streamSize,
                            )
                        }

                    if (!setUriSuccess) {
                        Logger.e(TAG, "Failed to set AVTransport URI on Sonos for $videoId")
                        return@launch
                    }

                    currentLoadedVideoId = videoId

                    if (startPositionMs > 1000L) {
                        withContext(Dispatchers.IO) {
                            soapClient.seek(device.baseUrl, startPositionMs)
                        }
                    }

                    if (playWhenReady) {
                        val playOk =
                            withContext(Dispatchers.IO) {
                                soapClient.play(device.baseUrl)
                            }
                        if (!playOk) {
                            Logger.w(TAG, "Initial soapClient.play() returned false, retrying after 300ms...")
                            delay(300)
                            withContext(Dispatchers.IO) { soapClient.play(device.baseUrl) }
                        }
                        sonosPlayer.remoteIsPlaying = true
                        adapter.notifyRemoteIsPlaying(true)
                    }

                    adapter.notifyRemotePlaybackState(Player.STATE_READY)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Logger.e(TAG, "Error starting playback on Sonos: ${e.message}", e)
                }
            }
    }

    private fun startPolling(device: SonosDevice) {
        pollJob?.cancel()
        pollJob =
            coroutineScope.launch {
                var consecutiveTrackEndCount = 0
                var volumePollCycle = 0

                while (isActive && _connectedDevice.value?.id == device.id) {
                    delay(POLL_INTERVAL_MS)

                    // Periodically poll speaker hardware volume in case adjusted via physical buttons
                    volumePollCycle++
                    if (volumePollCycle % 3 == 0 && volumeJob?.isActive != true) {
                        try {
                            val speakerVol = withContext(Dispatchers.IO) { soapClient.getVolume(device.baseUrl) }
                            if (speakerVol != null && volumeJob?.isActive != true) {
                                val volFloat = speakerVol / 100f
                                if (kotlin.math.abs(volFloat - _volume.value) >= 0.02f) {
                                    _volume.value = volFloat
                                    sonosPlayer.remoteVolume = volFloat
                                    adapter.notifyRemoteDeviceVolumeChanged(speakerVol)
                                }
                            }
                        } catch (e: Exception) {
                            Logger.w(TAG, "Error polling Sonos volume: ${e.message}")
                        }
                    }

                    // Skip polling position and state right after user actions so UI doesn't jump
                    if (System.currentTimeMillis() < suppressPollingUntilMs) {
                        continue
                    }

                    try {
                        val posInfo =
                            withContext(Dispatchers.IO) {
                                soapClient.getPositionInfo(device.baseUrl)
                            }
                        if (posInfo != null) {
                            sonosPlayer.remotePositionMs = posInfo.relTimeMs
                            if (posInfo.durationMs > 0L) {
                                sonosPlayer.remoteDurationMs = posInfo.durationMs
                            }
                        }

                        val transportState =
                            withContext(Dispatchers.IO) {
                                soapClient.getTransportInfo(device.baseUrl)
                            }

                        if (transportState != null) {
                            when {
                                transportState.equals("PLAYING", ignoreCase = true) -> {
                                    if (!sonosPlayer.remoteIsPlaying) {
                                        sonosPlayer.remoteIsPlaying = true
                                        adapter.notifyRemoteIsPlaying(true)
                                    }
                                }
                                transportState.equals("PAUSED_PLAYBACK", ignoreCase = true) -> {
                                    if (sonosPlayer.remoteIsPlaying) {
                                        sonosPlayer.remoteIsPlaying = false
                                        adapter.notifyRemoteIsPlaying(false)
                                    }
                                }
                                transportState.equals("TRANSITIONING", ignoreCase = true) -> {
                                    // Keep current playing state during transition/buffering
                                }
                                transportState.equals("STOPPED", ignoreCase = true) -> {
                                    // Only mark stopped if not in transition
                                    if (sonosPlayer.remoteIsPlaying && sonosPlayer.remotePositionMs <= 5000L) {
                                        sonosPlayer.remoteIsPlaying = false
                                        adapter.notifyRemoteIsPlaying(false)
                                    }
                                }
                            }

                            // Auto-advance when track ends on Sonos
                            val isEnded =
                                transportState.equals("STOPPED", ignoreCase = true) ||
                                    (
                                        transportState.equals("TRANSITIONING", ignoreCase = true) &&
                                            sonosPlayer.remoteDurationMs > 0 &&
                                            sonosPlayer.remotePositionMs >= sonosPlayer.remoteDurationMs - 2000L
                                    )

                            if (isEnded && sonosPlayer.remotePositionMs > 5000L) {
                                consecutiveTrackEndCount++
                                if (consecutiveTrackEndCount >= 2) {
                                    consecutiveTrackEndCount = 0
                                    Logger.i(TAG, "Track ended on Sonos, advancing to next queue item")
                                    if (adapter.hasNextMediaItem()) {
                                        adapter.seekToNext()
                                    } else {
                                        adapter.notifyRemotePlaybackState(Player.STATE_ENDED)
                                    }
                                }
                            } else {
                                consecutiveTrackEndCount = 0
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Logger.d(TAG, "Polling update error: ${e.message}")
                    }
                }
            }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    companion object {
        private const val TAG = "SonosHandoffManager"
        private const val POLL_INTERVAL_MS = 1000L
    }
}
