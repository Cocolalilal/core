package com.maxrave.media3.exoplayer

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import com.maxrave.domain.data.player.DjTransitionStyle
import com.maxrave.domain.data.player.GenericCastState
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericPlaybackParameters
import com.maxrave.domain.data.player.PlayerConstants
import com.maxrave.domain.data.player.PlayerError
import com.maxrave.domain.extension.isVideo
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.mediaservice.player.MediaPlayerListener
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.logger.Logger
import com.maxrave.media3.audio.BiquadFilter
import com.maxrave.media3.audio.CrossfadeFilterAudioProcessor
import com.maxrave.media3.exoplayer.CrossfadeExoPlayerAdapter.Companion.SPEED_PITCH_STEP
import com.maxrave.media3.service.mediasourcefactory.MergingMediaSourceFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

private const val TAG = "CrossfadeExoPlayerAdapter"

/**
 * ExoPlayer implementation of [MediaPlayerInterface] with crossfade support.
 *
 * Architecture mirrors [com.simpmusic.media_jvm.GstreamerPlayerAdapter]:
 * - Internal playlist management (not ExoPlayer's playlist)
 * - Multi-player instance model: each track gets its own ExoPlayer
 * - Precaching system for smooth transitions
 * - Crossfade with listener swap pattern
 * - [DelegatingForwardingPlayer] for MediaSession integration
 *
 * Key difference from GstreamerPlayerAdapter:
 * - Uses [MergingMediaSourceFactory] + ResolvingDataSource for URL resolution
 *   (instead of manually extracting URLs via StreamRepository)
 * - Each ExoPlayer gets a single [MediaItem] and auto-resolves the stream URL
 */
@SuppressLint("UnsafeOptInUsageError")
@OptIn(UnstableApi::class)
internal class CrossfadeExoPlayerAdapter(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val dataStoreManager: DataStoreManager,
    private val mediaSourceFactory: MergingMediaSourceFactory,
    private val audioAttributes: AudioAttributes,
    private val streamRepository: StreamRepository,
) : MediaPlayerInterface {
    // ========== Internal State Enum (same as GstreamerPlayerAdapter) ==========

    private enum class InternalState {
        IDLE, // No media loaded
        PREPARING, // Loading media
        READY, // Ready to play/paused
        PLAYING, // Currently playing
        PAUSED,
        ENDED, // Playback ended
        ERROR, // Error state
    }

    private fun InternalState.isInReadyState(): Boolean = this == InternalState.READY || this == InternalState.PLAYING || this == InternalState.PAUSED

    // ========== Crossfade Settings (loaded from DataStore) ==========

    init {
        coroutineScope.launch {
            dataStoreManager.crossfadeEnabled.collect { enabled ->
                crossfadeEnabled = (enabled == DataStoreManager.TRUE)
                Logger.d(TAG, "Crossfade enabled: $crossfadeEnabled")
            }
        }
        coroutineScope.launch {
            dataStoreManager.crossfadeDuration.collect { duration ->
                crossfadeDurationMs = duration
                Logger.d(TAG, "Crossfade duration: $crossfadeDurationMs ms")
            }
        }
        coroutineScope.launch {
            dataStoreManager.crossfadeDjMode.collect { enabled ->
                djCrossfadeEnabled = (enabled == DataStoreManager.TRUE)
                Logger.d(TAG, "DJ crossfade mode: $djCrossfadeEnabled")
            }
        }
        coroutineScope.launch {
            dataStoreManager.djTransitionStyle.collect { styleKey ->
                djTransitionStyle = DjTransitionStyle.fromKey(styleKey)
                Logger.d(TAG, "DJ transition style: $djTransitionStyle")
            }
        }
        coroutineScope.launch {
            dataStoreManager.djBpmMatching.collect { enabled ->
                djBpmMatching = (enabled == DataStoreManager.TRUE)
                Logger.d(TAG, "DJ BPM matching: $djBpmMatching")
            }
        }
        coroutineScope.launch {
            dataStoreManager.djTransitionOnSkip.collect { enabled ->
                djTransitionOnSkip = (enabled == DataStoreManager.TRUE)
                Logger.d(TAG, "DJ transition on skip: $djTransitionOnSkip")
            }
        }
        coroutineScope.launch {
            dataStoreManager.watchVideoInsteadOfPlayingAudio.collect { enabled ->
                watchVideoEnabled = (enabled == DataStoreManager.TRUE)
                Logger.d(TAG, "Watch video enabled: $watchVideoEnabled")
            }
        }
    }

    // ========== State Management ==========

    private val listeners = mutableListOf<MediaPlayerListener>()

    @Volatile
    private var currentPlayer: ExoPlayer? = null

    @Volatile
    private var internalState = InternalState.IDLE

    @Volatile
    private var internalPlayWhenReady = true

    @Volatile
    private var internalVolume = 1.0f

    @Volatile
    private var internalRepeatMode = PlayerConstants.REPEAT_MODE_OFF

    @Volatile
    private var internalShuffleModeEnabled = false

    @Volatile
    private var internalPlaybackSpeed = 1.0f

    @Volatile
    private var internalPlaybackPitch = 1.0f

    @Volatile
    private var internalSkipSilence = false

    // Position tracking - updated periodically, not on every query
    @Volatile
    private var cachedPosition = 0L

    @Volatile
    private var cachedDuration = 0L

    @Volatile
    private var cachedBufferedPosition = 0L

    @Volatile
    private var cachedIsLoading = false

    private var positionUpdateJob: Job? = null

    // Active Player.Listener (equivalent to BusListeners in GstreamerPlayerAdapter)
    // Only ONE listener instance, attached to ONE ExoPlayer at a time.
    // Swapped between players during crossfade.
    private var activePlayerListener: Player.Listener? = null

    // ========== Audio Focus (manual, session-scoped) — #2155 ==========
    // The multi-player swap model means audio focus must NOT be tied to any single
    // ExoPlayer: releasing the outgoing player would abandon focus, and the incoming
    // player (built with handleAudioFocus=false) never re-requests it — the root cause
    // of "music stops between tracks" (see androidx/media#2100). Instead we hold one
    // app-level AudioFocusRequest at the adapter level so focus survives every swap.

    private val duckVolumeFactor = 0.2f

    private val audioManager: AudioManager? by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    @Volatile
    private var hasAudioFocus = false

    /** True when focus was lost transiently so playback should auto-resume on regain. */
    @Volatile
    private var resumeOnFocusGain = false

    private val audioFocusListener =
        AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    // Don't fight the crossfade ramp; while crossfading it owns the volume.
                    if (!isCrossfading) currentPlayer?.volume = internalVolume
                    if (resumeOnFocusGain) {
                        resumeOnFocusGain = false
                        play()
                    }
                }

                AudioManager.AUDIOFOCUS_LOSS -> {
                    // Permanent loss (another app took over): pause and stop tracking focus.
                    resumeOnFocusGain = false
                    hasAudioFocus = false
                    pause()
                }

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    // Temporary loss (e.g. an incoming call): pause and remember to resume.
                    resumeOnFocusGain = internalState == InternalState.PLAYING
                    pause()
                }

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // Lower the volume instead of pausing (e.g. a navigation prompt).
                    // Skip during crossfade — the ramp owns volume and would override this.
                    if (!isCrossfading) currentPlayer?.volume = internalVolume * duckVolumeFactor
                }
            }
        }

    private val audioFocusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes
                    .Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            ).setOnAudioFocusChangeListener(audioFocusListener)
            .setWillPauseWhenDucked(false)
            .build()
    }

    /** Request app-level audio focus once; idempotent while focus is held. */
    private fun requestAudioFocusInternal(): Boolean {
        val am = audioManager ?: return true
        if (hasAudioFocus) return true
        val granted = am.requestAudioFocus(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        hasAudioFocus = granted
        Logger.d(TAG, "requestAudioFocus -> granted=$granted")
        return granted
    }

    private fun abandonAudioFocusInternal() {
        val am = audioManager ?: return
        if (!hasAudioFocus) return
        am.abandonAudioFocusRequest(audioFocusRequest)
        hasAudioFocus = false
        resumeOnFocusGain = false
        Logger.d(TAG, "abandonAudioFocus")
    }

    // ========== Precaching System ==========

    private data class PrecachedPlayer(
        val player: ExoPlayer,
        val mediaItem: GenericMediaItem,
        val filter: CrossfadeFilterAudioProcessor? = null,
    )

    // VideoId -> PrecachedPlayer
    private val precachedPlayers = ConcurrentHashMap<String, PrecachedPlayer>()
    private var precacheEnabled = true
    private val maxPrecacheCount = 2
    private var precacheJob: Job? = null

    // ========== Crossfade System ==========

    @Volatile
    private var crossfadeEnabled = false

    @Volatile
    private var crossfadeDurationMs = 5000

    @Volatile
    private var djCrossfadeEnabled = true

    @Volatile
    private var djTransitionStyle: DjTransitionStyle = DjTransitionStyle.SMART_AI

    @Volatile
    private var djBpmMatching: Boolean = true

    @Volatile
    private var djTransitionOnSkip: Boolean = true

    // Whether video content plays as video (watch-video setting) — the same condition
    // MergingMediaSourceFactory uses to build a merged audio+video source.
    @Volatile
    private var watchVideoEnabled = false

    @Volatile
    private var secondaryPlayer: ExoPlayer? = null

    @Volatile
    private var crossfadeJob: Job? = null

    @Volatile
    private var isCrossfading = false

    // Per-player filter references for DJ-style crossfade
    @Volatile
    private var currentPlayerFilter: CrossfadeFilterAudioProcessor? = null

    @Volatile
    private var secondaryPlayerFilter: CrossfadeFilterAudioProcessor? = null

    /** Index we're crossfading from; used when cancelling to revert localCurrentMediaItemIndex. */
    @Volatile
    private var crossfadeFromIndex = -1

    // ========== Retry on Source Error ==========
    // Track retry attempts per media item to avoid infinite retry loops
    private var retryCount = 0
    private var retryVideoId: String? = null
    private val maxRetryCount = 2

    // ========== AutoMix Metadata Cache ==========
    // videoId -> audio analysis data from Tidal (populated externally when 320kbps stream is fetched)
    private val audioMetaCache = ConcurrentHashMap<String, SongAudioMeta>()

    /**
     * Update crossfade state and notify listeners when it changes.
     */
    private fun setCrossfading(value: Boolean) {
        if (isCrossfading != value) {
            isCrossfading = value
            listeners.forEach { it.onCrossfadeStateChanged(value) }
        }
    }

    // ========== Playlist Management ==========

    private val playlist = mutableListOf<GenericMediaItem>()
    private var localCurrentMediaItemIndex = -1

    // Shuffle management
    private var shuffleIndices = mutableListOf<Int>()
    private var shuffleOrder = mutableListOf<Int>()

    // Loading management
    private var currentLoadJob: Job? = null

    // ========== ForwardingPlayer for MediaSession ==========

    // Create an initial idle ExoPlayer for MediaSession to hold
    private val initialPlayerWithFilter = createExoPlayerInstance()

    /**
     * Stable [Player] reference for MediaSession.
     * Delegates all calls to the currently active [ExoPlayer] instance.
     * Updated via [DelegatingForwardingPlayer.swapDelegate] when the active player changes.
     */
    val forwardingPlayer: DelegatingForwardingPlayer = DelegatingForwardingPlayer(initialPlayerWithFilter.player)

    init {
        currentPlayer = initialPlayerWithFilter.player
        currentPlayerFilter = initialPlayerWithFilter.filter

        // Wire up playlist navigation so ForwardingPlayer (and thus MediaSession)
        // can see the full playlist state instead of the single-item ExoPlayer state.
        // Only navigation commands are overridden — NOT getMediaItemCount/getCurrentMediaItemIndex
        // which must stay consistent with ExoPlayer's internal Timeline to avoid crashes.
        forwardingPlayer.playlistNavigationProvider =
            object : DelegatingForwardingPlayer.PlaylistNavigationProvider {
                override fun hasNextMediaItem(): Boolean = this@CrossfadeExoPlayerAdapter.hasNextMediaItem()

                override fun hasPreviousMediaItem(): Boolean = this@CrossfadeExoPlayerAdapter.hasPreviousMediaItem()

                override fun seekToNext(): Unit = this@CrossfadeExoPlayerAdapter.seekToNext()

                override fun seekToPrevious(): Unit = this@CrossfadeExoPlayerAdapter.seekToPrevious()

                override fun seekToPreviousMediaItem(): Unit = this@CrossfadeExoPlayerAdapter.seekToPreviousMediaItem()
            }
    }

    // ========== Cast Remote Routing ==========

    /**
     * While a Cast session is active this holds the session-level [Player] (the unified
     * CastPlayer wrapping [forwardingPlayer]): transport calls and position/state getters
     * are routed to it, and playback-start requests are handed to [castPlaybackRouter]
     * instead of the local ExoPlayer machinery. The playlist itself stays local — the
     * receiver only ever sees a small resolved-URL window of it.
     */
    @Volatile
    private var castRemotePlayer: Player? = null

    internal val isCastActive: Boolean
        get() = castRemotePlayer != null

    /** Set by CastHandoffManager: (playlistIndex, startPositionMs, playWhenReady) -> load on receiver. */
    internal var castPlaybackRouter: ((Int, Long, Boolean) -> Unit)? = null

    internal fun setCastActive(
        remotePlayer: Player?,
        deviceName: String?,
        deviceType: com.maxrave.domain.data.player.RemoteDeviceType = com.maxrave.domain.data.player.RemoteDeviceType.CAST,
    ) {
        if (remotePlayer != null) {
            if (castRemotePlayer === remotePlayer) return
            castRemotePlayer = remotePlayer
            Logger.w(TAG, "Cast session active on ${deviceName ?: "unknown device"} — local playback suspended")
            coroutineScope.launch {
                // Kill anything that makes local noise or wastes battery while remote.
                crossfadeJob?.cancel()
                crossfadeJob = null
                currentPlayerFilter?.enabled = false
                secondaryPlayerFilter?.enabled = false
                secondaryPlayer?.release()
                secondaryPlayer = null
                secondaryPlayerFilter = null
                setCrossfading(false)
                cancelPrecaching()
                clearAllPrecacheInternal()
                currentPlayer?.pause()
                stopPositionUpdates()
                abandonAudioFocusInternal()
                notifyEqualizerIntent(false)
            }
            listeners.forEach { it.onCastStateChanged(GenericCastState(isRemote = true, deviceName = deviceName, deviceType = deviceType)) }
        } else {
            if (castRemotePlayer == null) return
            castRemotePlayer = null
            Logger.w(TAG, "Cast session ended — back to local playback")
            listeners.forEach { it.onCastStateChanged(GenericCastState.NOT_CASTING) }
        }
    }

    internal fun notifyRemoteDeviceVolumeChanged(volumePercent: Int) {
        val volFloat = (volumePercent / 100f).coerceIn(0f, 1f)
        internalVolume = volFloat
        listeners.forEach { it.onVolumeChanged(volFloat) }
    }

    /** Remote queue advanced — keep the local playlist pointer and UI in sync. */
    internal fun notifyRemoteTransition(playlistIndex: Int) {
        if (!isCastActive || playlistIndex !in playlist.indices) return
        if (playlistIndex == localCurrentMediaItemIndex) return
        localCurrentMediaItemIndex = playlistIndex
        val item = playlist[playlistIndex]
        listeners.forEach {
            it.onMediaItemTransition(item, PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        }
    }

    internal fun notifyRemoteIsPlaying(isPlaying: Boolean) {
        if (!isCastActive) return
        internalPlayWhenReady = isPlaying
        listeners.forEach { it.onIsPlayingChanged(isPlaying) }
    }

    internal fun notifyRemotePlaybackState(playbackState: Int) {
        if (!isCastActive) return
        listeners.forEach { it.onPlaybackStateChanged(playbackState) }
    }

    // ========== ExoPlayer Instance Factory ==========

    /**
     * Result of creating an ExoPlayer instance, bundled with its per-player crossfade filter.
     */
    private data class PlayerWithFilter(
        val player: ExoPlayer,
        val filter: CrossfadeFilterAudioProcessor,
    )

    /**
     * Create a new ExoPlayer instance with a per-player [CrossfadeFilterAudioProcessor].
     *
     * Each player gets its own filter instance so the fade-out player can have
     * an independent low-pass filter while the fade-in player has a high-pass filter.
     *
     * Audio focus is NOT handled per-player: it is managed once at the adapter level
     * (see the Audio Focus section) so it survives every player swap (#2155).
     */
    private fun createExoPlayerInstance(): PlayerWithFilter {
        val crossfadeFilter = CrossfadeFilterAudioProcessor()

        val perPlayerRenderers =
            object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink =
                    DefaultAudioSink
                        .Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                        .setAudioProcessorChain(
                            DefaultAudioSink.DefaultAudioProcessorChain(
                                arrayOf(crossfadeFilter),
                                SilenceSkippingAudioProcessor(
                                    2_000_000,
                                    (20_000 / 2_000_000).toFloat(),
                                    2_000_000,
                                    0,
                                    256,
                                ),
                                SonicAudioProcessor(),
                            ),
                        ).build()
            }

        val player =
            ExoPlayer
                .Builder(context)
                .setAudioAttributes(audioAttributes, false)
                .setLoadControl(
                    DefaultLoadControl
                        .Builder()
                        .setBufferDurationsMs(
                            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS * 4,
                            DefaultLoadControl.DEFAULT_MAX_BUFFER_MS * 4,
                            0,
                            0,
                        ).build(),
                ).setWakeMode(C.WAKE_MODE_NETWORK)
                .setHandleAudioBecomingNoisy(true)
                .setSeekForwardIncrementMs(5000)
                .setSeekBackIncrementMs(5000)
                .setMediaSourceFactory(mediaSourceFactory)
                .setRenderersFactory(perPlayerRenderers)
                .build()

        return PlayerWithFilter(player, crossfadeFilter)
    }

    // ========== Playback Control ==========

    override fun play() {
        Logger.d(TAG, "play() called (state: $internalState, playWhenReady: $internalPlayWhenReady)")
        castRemotePlayer?.let { remote ->
            internalPlayWhenReady = true
            remote.play()
            return
        }
        coroutineScope.launch {
            when (internalState) {
                InternalState.READY, InternalState.ENDED, InternalState.PAUSED -> {
                    currentPlayer?.let { player ->
                        requestAudioFocusInternal()
                        player.play()
                        transitionToState(InternalState.PLAYING)
                        internalPlayWhenReady = true
                    } ?: Logger.w(TAG, "Play called but currentPlayer is null")
                }

                InternalState.PREPARING -> {
                    internalPlayWhenReady = true
                    Logger.d(TAG, "Play: During PREPARING - will auto-play when ready")
                }

                InternalState.PLAYING -> {
                    internalPlayWhenReady = true
                    cachedIsLoading = false
                }

                else -> {
                    Logger.w(TAG, "Play: Called in invalid state: $internalState")
                }
            }
        }
    }

    override fun pause() {
        Logger.d(TAG, "pause() called (state: $internalState, playWhenReady: $internalPlayWhenReady)")
        castRemotePlayer?.let { remote ->
            internalPlayWhenReady = false
            remote.pause()
            return
        }
        coroutineScope.launch {
            forwardingPlayer.suppressPlaybackEnded = false
            // Cancel any ongoing crossfade by committing the incoming track (A+1) as current.
            // Direction 1: pausing during a crossfade stays on A+1 (the track the UI already
            // shows) and freezes it in place via the when(internalState) block below — it does
            // NOT jump back to A.
            if (isCrossfading) {
                Logger.d(TAG, "Pause: committing incoming (A+1) and pausing in place")
                commitIncomingAsCurrentInternal()
            }

            when (internalState) {
                InternalState.PLAYING, InternalState.READY -> {
                    currentPlayer?.let { player ->
                        player.pause()
                        transitionToState(InternalState.PAUSED)
                        internalPlayWhenReady = false
                    }
                }

                InternalState.PREPARING -> {
                    internalPlayWhenReady = false
                }

                else -> {
                    Logger.w(TAG, "Pause: Called in invalid state: $internalState")
                }
            }
        }
    }

    override fun stop() {
        castRemotePlayer?.let { remote ->
            remote.stop()
            return
        }
        coroutineScope.launch {
            forwardingPlayer.suppressPlaybackEnded = false
            currentPlayer?.let { player ->
                Logger.d(TAG, "Stop called")
                player.stop()
                transitionToState(InternalState.IDLE)
                stopPositionUpdates()
                abandonAudioFocusInternal()
                notifyEqualizerIntent(false)
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        castRemotePlayer?.let { remote ->
            remote.seekTo(positionMs)
            cachedPosition = positionMs
            return
        }
        currentPlayer?.let { player ->
            try {
                player.seekTo(positionMs)
                cachedPosition = positionMs
            } catch (e: Exception) {
                Logger.e(TAG, "Seek exception: ${e.message}", e)
            }
        }
    }

    override fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
    ) {
        if (mediaItemIndex !in playlist.indices) return

        coroutineScope.launch {
            val shouldPlay = internalPlayWhenReady

            // Cancel any ongoing crossfade
            if (isCrossfading) {
                Logger.d(TAG, "seekTo: Cancelling crossfade")
                crossfadeJob?.cancel()
                crossfadeJob = null
                currentPlayerFilter?.enabled = false
                secondaryPlayerFilter?.enabled = false
                secondaryPlayer?.release()
                secondaryPlayer = null
                secondaryPlayerFilter = null
                setCrossfading(false)
            }

            // Cancel any ongoing load
            currentLoadJob?.cancel()

            // Load the new track
            localCurrentMediaItemIndex = mediaItemIndex
            loadAndPlayTrackInternal(mediaItemIndex, positionMs, shouldPlay)
        }
    }

    override fun seekBack() {
        val newPosition = (currentPosition - 5000).coerceAtLeast(0)
        seekTo(newPosition)
    }

    override fun seekForward() {
        val end = duration.takeIf { it > 0 } ?: cachedDuration
        val newPosition = (currentPosition + 5000).coerceAtMost(end)
        seekTo(newPosition)
    }

    override fun seekToNext() {
        coroutineScope.launch {
            // During crossfade A→A+1, "next" commits A+1 as current (Direction 1: the UI already
            // shows A+1) and then advances to A+2. Outside crossfade it advances normally.
            val wasCrossfading = isCrossfading
            if (wasCrossfading) {
                Logger.d(TAG, "seekToNext: committing incoming (A+1), then advancing to A+2")
                commitIncomingAsCurrentInternal()
            }
            if (hasNextMediaItem()) {
                val nextIndex = getNextMediaItemIndex()
                if (djTransitionOnSkip && crossfadeEnabled && !wasCrossfading && (internalState == InternalState.PLAYING || currentPlayer?.isPlaying == true) && !isCurrentTrackVideo()) {
                    Logger.d(TAG, "seekToNext: initiating intentional DJ skip transition to $nextIndex")
                    triggerCrossfadeTransition(nextIndex, isManualSkip = true)
                } else {
                    seekTo(nextIndex, 0)
                }
            } else if (wasCrossfading) {
                // A+1 was the last track — stay on it (already promoted), just refresh metadata.
                forwardingPlayer.notifyMediaItemChanged()
            }
        }
    }

    override fun seekToPrevious() {
        coroutineScope.launch {
            // During a crossfade, commit the incoming track (A+1) as current FIRST, so the
            // 3-second rule below evaluates against A+1's position/index (Direction 1).
            if (isCrossfading) {
                Logger.d(TAG, "seekToPrevious: committing incoming (A+1) first")
                commitIncomingAsCurrentInternal()
            }

            // Standard music player behavior:
            // - Position > 3s  → seek to start of current track
            // - Position <= 3s → go to previous track
            val positionThresholdMs = 3000L
            val position = currentPosition
            if (position > positionThresholdMs) {
                Logger.d(TAG, "seekToPrevious: pos=${position}ms > ${positionThresholdMs}ms — seeking to start")
                seekTo(0)
            } else if (hasPreviousMediaItem()) {
                Logger.d(TAG, "seekToPrevious: pos=${position}ms <= ${positionThresholdMs}ms — going to previous track")
                val prevIndex = getPreviousMediaItemIndex()
                seekTo(prevIndex, 0)
            } else {
                Logger.d(TAG, "seekToPrevious: No previous item, seeking to start")
                seekTo(0)
            }
        }
    }

    override fun seekToPreviousMediaItem() {
        coroutineScope.launch {
            // Mirror seekToPrevious(): commit the incoming track (A+1) as current first.
            if (isCrossfading) {
                Logger.d(TAG, "seekToPreviousMediaItem: committing incoming (A+1) first")
                commitIncomingAsCurrentInternal()
            }

            // Always advance to the previous track regardless of `cachedPosition` —
            // skips the 3-second "seek to start" rule used by seekToPrevious().
            if (hasPreviousMediaItem()) {
                val prevIndex = getPreviousMediaItemIndex()
                Logger.d(TAG, "seekToPreviousMediaItem: jumping to previous index=$prevIndex")
                seekTo(prevIndex, 0)
            } else {
                Logger.d(TAG, "seekToPreviousMediaItem: No previous item — no-op")
            }
        }
    }

    override fun prepare() {
        if (playlist.isNotEmpty() && localCurrentMediaItemIndex >= 0) {
            coroutineScope.launch {
                loadAndPlayTrackInternal(localCurrentMediaItemIndex, 0, false)
            }
        }
    }

    // ========== Media Item Management ==========

    override fun setMediaItem(mediaItem: GenericMediaItem) {
        coroutineScope.launch {
            // Cancel ongoing operations
            currentLoadJob?.cancel()
            cancelPrecaching()

            playlist.clear()
            clearAllPrecacheInternal()
            playlist.add(mediaItem)
            localCurrentMediaItemIndex = 0

            if (internalShuffleModeEnabled) {
                createShuffleOrder()
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")
            loadAndPlayTrackInternal(0, 0, internalPlayWhenReady)
        }
    }

    override fun addMediaItem(mediaItem: GenericMediaItem) {
        playlist.add(mediaItem)

        if (internalShuffleModeEnabled) {
            createShuffleOrder()
        }

        notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

        if (playlist.size - 1 - currentMediaItemIndex <= maxPrecacheCount) {
            coroutineScope.launch {
                clearPrecacheExceptCurrentInternal()
                triggerPrecachingInternal()
            }
        }
    }

    override fun addMediaItem(
        index: Int,
        mediaItem: GenericMediaItem,
    ) {
        if (index in 0..playlist.size) {
            val currentIndexBeforeInsert = localCurrentMediaItemIndex

            playlist.add(index, mediaItem)

            // Adjust current index if needed
            if (index <= localCurrentMediaItemIndex) {
                localCurrentMediaItemIndex++
            }

            // Update shuffle order if enabled
            if (internalShuffleModeEnabled) {
                if (currentIndexBeforeInsert >= 0 && index == currentIndexBeforeInsert + 1) {
                    val currentShufflePos = shuffleIndices.getOrNull(currentIndexBeforeInsert) ?: 0
                    insertIntoShuffleOrder(index, currentShufflePos)
                } else {
                    createShuffleOrder()
                }
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

            if (index - 1 - currentMediaItemIndex <= maxPrecacheCount) {
                coroutineScope.launch {
                    clearPrecacheExceptCurrentInternal()
                    triggerPrecachingInternal()
                }
            }
        }
    }

    override fun removeMediaItem(index: Int) {
        if (index !in playlist.indices) return

        coroutineScope.launch {
            val track = playlist.removeAt(index)

            // Remove from precache
            precachedPlayers.remove(track.mediaId)?.let { cached ->
                cleanupPlayerInternal(cached.player)
            }

            when {
                index < localCurrentMediaItemIndex -> {
                    localCurrentMediaItemIndex--
                    clearPrecacheExceptCurrentInternal()
                    triggerPrecachingInternal()
                }

                index == localCurrentMediaItemIndex -> {
                    if (localCurrentMediaItemIndex >= playlist.size) {
                        localCurrentMediaItemIndex = playlist.size - 1
                    }
                    if (localCurrentMediaItemIndex >= 0) {
                        loadAndPlayTrackInternal(localCurrentMediaItemIndex, 0, internalPlayWhenReady)
                    } else {
                        cleanupCurrentPlayerInternal()
                    }
                }

                else -> {
                    clearPrecacheExceptCurrentInternal()
                    triggerPrecachingInternal()
                }
            }

            if (internalShuffleModeEnabled) {
                createShuffleOrder()
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")
        }
    }

    override fun moveMediaItem(
        fromIndex: Int,
        toIndex: Int,
    ) {
        if (fromIndex !in playlist.indices || toIndex !in playlist.indices) return

        coroutineScope.launch {
            val item = playlist.removeAt(fromIndex)
            playlist.add(toIndex, item)

            // Update current index
            localCurrentMediaItemIndex =
                when {
                    localCurrentMediaItemIndex == fromIndex -> {
                        toIndex
                    }
                    fromIndex < localCurrentMediaItemIndex && toIndex >= localCurrentMediaItemIndex -> {
                        localCurrentMediaItemIndex - 1
                    }
                    fromIndex > localCurrentMediaItemIndex && toIndex <= localCurrentMediaItemIndex -> {
                        localCurrentMediaItemIndex + 1
                    }
                    else -> {
                        localCurrentMediaItemIndex
                    }
                }

            if (internalShuffleModeEnabled) {
                createShuffleOrder()
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

            clearPrecacheExceptCurrentInternal()
            triggerPrecachingInternal()
        }
    }

    override fun clearMediaItems() {
        coroutineScope.launch {
            playlist.clear()
            localCurrentMediaItemIndex = -1
            clearShuffleOrder()
            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")
            cleanupCurrentPlayerInternal()
            clearAllPrecacheInternal()
        }
    }

    override fun replaceMediaItem(
        index: Int,
        mediaItem: GenericMediaItem,
    ) {
        if (index !in playlist.indices) return

        coroutineScope.launch {
            playlist[index] = mediaItem

            precachedPlayers.remove(mediaItem.mediaId)?.let { cached ->
                cleanupPlayerInternal(cached.player)
            }

            if (internalShuffleModeEnabled) {
                createShuffleOrder()
            }

            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

            if (index == localCurrentMediaItemIndex) {
                loadAndPlayTrackInternal(index, 0, internalPlayWhenReady)
            } else {
                triggerPrecachingInternal()
            }
        }
    }

    override fun getMediaItemAt(index: Int): GenericMediaItem? = playlist.getOrNull(index)

    override fun getCurrentMediaTimeLine(): List<GenericMediaItem> =
        if (internalShuffleModeEnabled) {
            shuffleOrder.mapNotNull { shuffledIndex -> playlist.getOrNull(shuffledIndex) }
        } else {
            playlist.toList()
        }

    override fun getUnshuffledIndex(shuffledIndex: Int): Int =
        if (internalShuffleModeEnabled) {
            shuffleOrder.getOrNull(shuffledIndex) ?: -1
        } else {
            shuffledIndex
        }

    // ========== Playback State Properties ==========

    override val isPlaying: Boolean
        get() = castRemotePlayer?.isPlaying ?: (internalState == InternalState.PLAYING)

    override val currentPosition: Long
        get() = castRemotePlayer?.currentPosition ?: cachedPosition

    override val duration: Long
        get() {
            castRemotePlayer?.let { remote ->
                return remote.duration.takeIf { it > 0 } ?: 0L
            }
            return currentPlayer?.duration ?: cachedDuration
        }

    override val bufferedPosition: Long
        get() = castRemotePlayer?.bufferedPosition ?: cachedBufferedPosition

    override val bufferedPercentage: Int
        get() {
            val dur = duration
            if (dur <= 0) return 0
            return ((bufferedPosition * 100) / dur).toInt().coerceIn(0, 100)
        }

    override val currentMediaItem: GenericMediaItem?
        get() = playlist.getOrNull(localCurrentMediaItemIndex)

    override val currentMediaItemIndex: Int
        get() = localCurrentMediaItemIndex

    override val mediaItemCount: Int
        get() = playlist.size

    override val contentPosition: Long
        get() = castRemotePlayer?.contentPosition ?: cachedPosition

    override val playbackState: Int
        get() {
            // Media3 Player.STATE_* values match PlayerConstants 1:1.
            castRemotePlayer?.let { return it.playbackState }
            return when (internalState) {
                InternalState.IDLE -> PlayerConstants.STATE_IDLE
                InternalState.PREPARING -> PlayerConstants.STATE_BUFFERING
                InternalState.READY -> PlayerConstants.STATE_READY
                InternalState.PLAYING -> PlayerConstants.STATE_READY
                InternalState.ENDED -> PlayerConstants.STATE_ENDED
                InternalState.ERROR -> PlayerConstants.STATE_IDLE
                InternalState.PAUSED -> PlayerConstants.STATE_READY
            }
        }

    // ========== Navigation ==========

    override fun hasNextMediaItem(): Boolean =
        when (internalRepeatMode) {
            PlayerConstants.REPEAT_MODE_ONE -> true
            PlayerConstants.REPEAT_MODE_ALL -> true
            else -> localCurrentMediaItemIndex < playlist.size - 1
        }

    override fun hasPreviousMediaItem(): Boolean =
        when (internalRepeatMode) {
            PlayerConstants.REPEAT_MODE_ONE -> true
            PlayerConstants.REPEAT_MODE_ALL -> true
            else -> localCurrentMediaItemIndex > 0
        }

    private fun getNextMediaItemIndex(): Int =
        when (internalRepeatMode) {
            PlayerConstants.REPEAT_MODE_ONE -> {
                localCurrentMediaItemIndex
            }
            PlayerConstants.REPEAT_MODE_ALL -> {
                if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos = shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: 0
                    val nextShufflePos = (currentShufflePos + 1) % shuffleOrder.size
                    shuffleOrder.getOrNull(nextShufflePos) ?: localCurrentMediaItemIndex
                } else {
                    if (localCurrentMediaItemIndex < playlist.size - 1) {
                        localCurrentMediaItemIndex + 1
                    } else {
                        0
                    }
                }
            }
            else -> {
                if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos = shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: 0
                    val nextShufflePos = currentShufflePos + 1
                    if (nextShufflePos < shuffleOrder.size) {
                        shuffleOrder.getOrNull(nextShufflePos) ?: localCurrentMediaItemIndex
                    } else {
                        localCurrentMediaItemIndex
                    }
                } else {
                    (localCurrentMediaItemIndex + 1).coerceAtMost(playlist.size - 1)
                }
            }
        }

    private fun getPreviousMediaItemIndex(): Int =
        when (internalRepeatMode) {
            PlayerConstants.REPEAT_MODE_ONE -> {
                localCurrentMediaItemIndex
            }
            PlayerConstants.REPEAT_MODE_ALL -> {
                if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos = shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: 0
                    val prevShufflePos =
                        if (currentShufflePos > 0) {
                            currentShufflePos - 1
                        } else {
                            shuffleOrder.size - 1
                        }
                    shuffleOrder.getOrNull(prevShufflePos) ?: localCurrentMediaItemIndex
                } else {
                    if (localCurrentMediaItemIndex > 0) {
                        localCurrentMediaItemIndex - 1
                    } else {
                        playlist.size - 1
                    }
                }
            }
            else -> {
                if (internalShuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos = shuffleIndices.getOrNull(localCurrentMediaItemIndex) ?: 0
                    val prevShufflePos = currentShufflePos - 1
                    if (prevShufflePos >= 0) {
                        shuffleOrder.getOrNull(prevShufflePos) ?: localCurrentMediaItemIndex
                    } else {
                        localCurrentMediaItemIndex
                    }
                } else {
                    (localCurrentMediaItemIndex - 1).coerceAtLeast(0)
                }
            }
        }

    // ========== Playback Modes ==========

    override var shuffleModeEnabled: Boolean
        get() = internalShuffleModeEnabled
        set(value) {
            if (internalShuffleModeEnabled == value) return

            internalShuffleModeEnabled = value

            if (value) {
                createShuffleOrder()
            } else {
                clearShuffleOrder()
            }

            val mediaItemList = getShuffledMediaItemList()
            listeners.forEach { it.onShuffleModeEnabledChanged(value, mediaItemList) }
            notifyTimelineChanged("TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED")

            Logger.d(TAG, "Shuffle mode ${if (value) "enabled" else "disabled"}")
        }

    override var repeatMode: Int
        get() = internalRepeatMode
        set(value) {
            if (internalRepeatMode == value) return
            internalRepeatMode = value
            listeners.forEach { it.onRepeatModeChanged(value) }
        }

    override var playWhenReady: Boolean
        get() = internalPlayWhenReady
        set(value) {
            internalPlayWhenReady = value
            if (value) play() else pause()
        }

    override var playbackParameters: GenericPlaybackParameters
        get() = GenericPlaybackParameters(internalPlaybackSpeed, internalPlaybackPitch)
        set(value) {
            internalPlaybackSpeed = value.speed
            internalPlaybackPitch = value.pitch
            val params = PlaybackParameters(value.speed, value.pitch)
            currentPlayer?.playbackParameters = params
            // Also apply to secondary player during crossfade
            secondaryPlayer?.playbackParameters = params
            // Receiver support varies (speed only, no pitch) — best-effort while casting.
            castRemotePlayer?.let { remote ->
                runCatching { remote.playbackParameters = params }
            }
        }

    // ========== Audio Settings ==========

    override val audioSessionId: Int
        get() = currentPlayer?.audioSessionId ?: 0

    override var volume: Float
        get() = internalVolume
        set(value) {
            Logger.w(TAG, "Setting volume to $value")
            internalVolume = value.coerceIn(0f, 1f)
            castRemotePlayer?.volume = internalVolume
            currentPlayer?.volume = internalVolume
            listeners.forEach { it.onVolumeChanged(internalVolume) }
        }

    override var skipSilenceEnabled: Boolean
        get() = internalSkipSilence
        set(value) {
            internalSkipSilence = value
            currentPlayer?.skipSilenceEnabled = value
            // Also apply to secondary player during crossfade
            secondaryPlayer?.skipSilenceEnabled = value
        }

    // ========== Listener Management ==========

    override fun addListener(listener: MediaPlayerListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: MediaPlayerListener) {
        listeners.remove(listener)
    }

    // ========== Release Resources ==========

    override fun release() {
        // Cancel all ongoing jobs
        currentLoadJob?.cancel()
        precacheJob?.cancel()
        positionUpdateJob?.cancel()

        // Cancel crossfade
        crossfadeJob?.cancel()
        secondaryPlayer?.release()
        secondaryPlayer = null
        secondaryPlayerFilter = null
        currentPlayerFilter = null
        isCrossfading = false

        abandonAudioFocusInternal()
        coroutineScope.cancel()
        cleanupCurrentPlayerInternal()
        clearAllPrecacheInternal()
        listeners.clear()
    }

    // ========== Internal: State Transition ==========

    /**
     * State transition helper - mirrors GstreamerPlayerAdapter.transitionToState()
     */
    private fun propagatePlayerError(error: PlaybackException) {
        val genericError =
            PlayerError(
                errorCode =
                    when (error.errorCode) {
                        PlaybackException.ERROR_CODE_TIMEOUT -> PlayerConstants.ERROR_CODE_TIMEOUT
                        else -> error.errorCode
                    },
                errorCodeName = error.errorCodeName,
                message = error.message,
            )
        Logger.e(TAG, "Playback error: ${error.message}")
        listeners.forEach { it.onPlayerError(genericError) }
        transitionToState(InternalState.ERROR)
    }

    private fun transitionToState(newState: InternalState) {
        if (internalState == newState) {
            Logger.d(TAG, "State transition ignored: already in $newState")
            return
        }

        val oldState = internalState
        internalState = newState

        Logger.d(TAG, "State: $oldState -> $newState (playWhenReady=$internalPlayWhenReady)")

        // Update cached duration from player
        currentPlayer?.let {
            val dur = it.duration
            if (dur > 0L) {
                cachedDuration = dur
            }
        }

        // Notify listeners
        when (newState) {
            InternalState.PAUSED -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_READY) }
                listeners.forEach { it.onIsPlayingChanged(false) }
            }

            InternalState.IDLE -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_IDLE) }
                listeners.forEach { it.onIsPlayingChanged(false) }
            }

            InternalState.PREPARING -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_BUFFERING) }
            }

            InternalState.READY -> {
                if (internalPlayWhenReady) {
                    play()
                } else {
                    listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_READY) }
                    listeners.forEach { it.onIsPlayingChanged(false) }
                }
            }

            InternalState.PLAYING -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_READY) }
                listeners.forEach { it.onIsLoadingChanged(false) }
                listeners.forEach { it.onIsPlayingChanged(true) }
            }

            InternalState.ENDED -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_ENDED) }
                listeners.forEach { it.onIsPlayingChanged(false) }
            }

            InternalState.ERROR -> {
                listeners.forEach { it.onPlaybackStateChanged(PlayerConstants.STATE_IDLE) }
                listeners.forEach { it.onIsPlayingChanged(false) }
                listeners.forEach {
                    it.onPlayerError(
                        PlayerError(
                            errorCode = 403,
                            errorCodeName = "ERROR_UNKNOWN",
                            message = "Can not extract playable URL or playback error",
                        ),
                    )
                }
            }
        }
    }

    // ========== Internal: Load and Play Track ==========

    /**
     * Load and play track - mirrors GstreamerPlayerAdapter.loadAndPlayTrackInternal()
     *
     * Key difference: instead of extracting URL + creating PlayBin,
     * we create an ExoPlayer, set a MediaItem, and let MediaSourceFactory resolve the URL.
     */
    private fun loadAndPlayTrackInternal(
        index: Int,
        startPositionMs: Long,
        shouldPlay: Boolean,
    ) {
        if (index !in playlist.indices) return

        val mediaItem = playlist[index]
        val videoId = mediaItem.mediaId

        // While casting, playback starts on the receiver — never on a local ExoPlayer.
        castPlaybackRouter?.takeIf { isCastActive }?.let { router ->
            currentLoadJob?.cancel()
            listeners.forEach {
                it.onMediaItemTransition(mediaItem, PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_SEEK)
            }
            router(index, startPositionMs, shouldPlay)
            return
        }

        // Cancel previous load
        currentLoadJob?.cancel()

        currentLoadJob =
            coroutineScope.launch {
                try {
                    transitionToState(InternalState.PREPARING)

                    // Notify media item transition
                    listeners.forEach {
                        it.onMediaItemTransition(
                            mediaItem,
                            PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                        )
                    }

                    // Use precached player if available
                    val cachedPlayerEntry = precachedPlayers.remove(videoId)
                    val player: ExoPlayer
                    val playerFilter: CrossfadeFilterAudioProcessor?
                    if (cachedPlayerEntry?.player != null) {
                        Logger.d(TAG, "Using precached player for $videoId")
                        player = cachedPlayerEntry.player
                        playerFilter = cachedPlayerEntry.filter
                    } else {
                        Logger.d(TAG, "Creating new player for $videoId")
                        val pwf = createExoPlayerInstance()
                        player = pwf.player
                        playerFilter = pwf.filter
                        player.setMediaItem(mediaItem.toMedia3MediaItem())
                        player.prepare()
                    }

                    // === CAREFUL ORDER for ForwardingPlayer integration ===

                    // 1. Remove our active listener from old player
                    cleanupPlayerListenerInternal()
                    stopPositionUpdates()
                    crossfadeJob?.cancel()
                    crossfadeJob = null
                    setCrossfading(false)

                    // 2. Save old player reference
                    val oldPlayer = currentPlayer

                    // 3. Set new player as current
                    currentPlayer = player
                    currentPlayerFilter = playerFilter

                    // 4. Setup our listener on new player
                    setupPlayerListenerInternal(player)

                    // 5. Swap ForwardingPlayer delegate (moves MediaSession's listeners from old to new)
                    forwardingPlayer.swapDelegate(player)

                    // 5b. Notify MediaSession about the new media item
                    // The MediaItem was set before the swap (either during precache or above),
                    // so MediaSession's listener missed the onMediaItemTransition event.
                    // play() below will trigger onIsPlayingChanged which causes MediaSession
                    // to re-query metadata, but this explicit notify is safer and ensures
                    // the notification updates immediately even if play() is delayed.
                    forwardingPlayer.notifyMediaItemChanged()

                    // 6. NOW release old player (it has no listeners anymore)
                    if (oldPlayer != null && oldPlayer !== player) {
                        try {
                            oldPlayer.stop()
                            oldPlayer.release()
                        } catch (e: Exception) {
                            Logger.w(TAG, "Error releasing old player: ${e.message}")
                        }
                    }

                    // Audio focus is held at the adapter level (see Audio Focus section),
                    // not per-player, so it survives this swap (#2155).

                    // Apply settings
                    player.volume = internalVolume
                    player.playbackParameters = PlaybackParameters(internalPlaybackSpeed, internalPlaybackPitch)
                    player.skipSilenceEnabled = internalSkipSilence

                    // Seek if needed
                    if (startPositionMs > 0) {
                        player.seekTo(startPositionMs)
                        cachedPosition = startPositionMs
                    }

                    // Auto-play if requested
                    if (shouldPlay) {
                        requestAudioFocusInternal()
                        player.play()
                        transitionToState(InternalState.PLAYING)
                    } else {
                        player.pause()
                        transitionToState(InternalState.READY)
                    }

                    forwardingPlayer.suppressPlaybackEnded = false

                    // Start position updates
                    startPositionUpdates()

                    // Eagerly load audio metadata for auto crossfade calculations
                    // so it's available when position updates check the trigger threshold
                    if (crossfadeEnabled && crossfadeDurationMs == DataStoreManager.CROSSFADE_DURATION_AUTO) {
                        loadAudioMetaIfNeeded(videoId)
                    }

                    // Trigger precaching
                    triggerPrecachingInternal()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Logger.e(TAG, "Load track error: ${e.message}", e)
                    forwardingPlayer.suppressPlaybackEnded = false
                    transitionToState(InternalState.ERROR)
                }
            }
    }

    // ========== Internal: Player Listener Management ==========
    // Equivalent to setupPlayerListenersInternal / cleanupBusListenersInternal

    /**
     * Setup Player.Listener on the given player.
     * First removes any existing listener from the old player (like cleanupBusListenersInternal),
     * then creates and attaches a new listener to the given player.
     *
     * This is the KEY crossfade mechanism:
     * When crossfade starts, the listener is moved from old player to new player.
     * The old player's STATE_ENDED event is then ignored (no listener to handle it).
     */
    private fun setupPlayerListenerInternal(player: ExoPlayer) {
        // Clean up old listener first
        cleanupPlayerListenerInternal()

        val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_ENDED -> {
                            Logger.d(TAG, "End of stream reached")
                            if (hasNextMediaItem()) {
                                forwardingPlayer.suppressPlaybackEnded = true
                                transitionToState(InternalState.PREPARING)
                            } else {
                                transitionToState(InternalState.ENDED)
                            }
                            handleTrackEndInternal()
                        }

                        Player.STATE_READY -> {
                            // Always clear loading state when ExoPlayer is ready
                            // (handles both initial load AND mid-playback rebuffer)
                            if (cachedIsLoading && player == currentPlayer) {
                                cachedIsLoading = false
                                listeners.forEach { it.onIsLoadingChanged(false) }
                            }
                            // Duration should be available now
                            val dur = player.duration
                            if (dur > 0) cachedDuration = dur
                            // Reset retry counter on successful playback
                            retryCount = 0
                            retryVideoId = null
                        }

                        Player.STATE_BUFFERING -> {
                            // Playback is stalled waiting for data — report buffering
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (player != currentPlayer) {
                        Logger.d(TAG, "Ignoring onPlaybackStateChanged from non-current player")
                        return
                    }
                    if (isPlaying) {
                        if (internalState != InternalState.PLAYING) {
                            transitionToState(InternalState.PLAYING)
                            notifyEqualizerIntent(true)
                        }
                    } else {
                        if (internalState == InternalState.PLAYING) {
                            if (!player.playWhenReady) {
                                transitionToState(InternalState.PAUSED)
                                notifyEqualizerIntent(false)
                            }
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (player != currentPlayer) {
                        Logger.d(TAG, "Ignoring onPlayerError from non-current player")
                        return
                    }

                    // Retry for source errors (expired/invalid stream URL)
                    // ERROR_CODE_PARSING_CONTAINER_MALFORMED (3001) = server returned non-media response (e.g. HTML error page)
                    // ERROR_CODE_IO_BAD_HTTP_STATUS (2004) = HTTP 403/410 from expired URL
                    // ERROR_CODE_IO_NETWORK_CONNECTION_FAILED (2001) = connection refused
                    // ERROR_CODE_IO_FILE_NOT_FOUND (2005) = the resolver served a cache hit as a bare
                    //   media id and the cached spans were evicted (or cleared) mid-read, so
                    //   DefaultDataSource fell through to FileDataSource on a scheme-less URI. Media3
                    //   lists FileNotFoundException as non-retriable, so this layer is the only chance
                    //   to recover; reloading re-runs the resolver, which now sees an incomplete cache
                    //   and resolves a real URL.
                    val isRetryableSourceError =
                        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                            error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND

                    val currentVideoId = playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId
                    if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) {
                        // Without this the crash report is a bare FileDataSourceException with
                        // nothing tying it back to a cache decision made three layers up.
                        Logger.w(
                            TAG,
                            "Cache disappeared mid-read for $currentVideoId — the resolver had served it " +
                                "as a fully cached bare media id. Retrying to resolve a real URL.",
                        )
                    }
                    if (isRetryableSourceError && currentVideoId != null) {
                        // Reset retry count if this is a different track
                        if (retryVideoId != currentVideoId) {
                            retryVideoId = currentVideoId
                            retryCount = 0
                        }
                        if (retryCount < maxRetryCount) {
                            retryCount++
                            Logger.w(TAG, "Retryable source error (attempt $retryCount/$maxRetryCount) for $currentVideoId: ${error.errorCodeName}")
                            // Snapshot the current position so the retry resumes where playback
                            // failed instead of restarting the track from the beginning.
                            val resumePositionMs = cachedPosition.coerceAtLeast(0L)
                            coroutineScope.launch {
                                try {
                                    // Invalidate cached format so ResolvingDataSource fetches a fresh URL
                                    streamRepository.invalidateFormat(currentVideoId)
                                    streamRepository.invalidateFormat("${com.maxrave.common.MERGING_DATA_TYPE.VIDEO}$currentVideoId")
                                    // Evict from precache (it may hold a stale player)
                                    precachedPlayers.remove(currentVideoId)?.player?.release()
                                    // Reload the track at the saved position
                                    loadAndPlayTrackInternal(localCurrentMediaItemIndex, resumePositionMs, shouldPlay = true)
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    Logger.e(TAG, "Retry failed: ${e.message}", e)
                                    propagatePlayerError(error)
                                }
                            }
                            return
                        }
                        Logger.e(TAG, "Max retries ($maxRetryCount) exhausted for $currentVideoId")
                    }

                    propagatePlayerError(error)
                }

                override fun onIsLoadingChanged(isLoading: Boolean) {
                    // ExoPlayer reports isLoading=true during normal background buffer refill,
                    // not just when playback is stalled. Only propagate loading=true when
                    // playback is actually stalled (STATE_BUFFERING) AND the player intends
                    // to play. Ignore buffering events while paused to avoid showing
                    // a loading spinner when the user has explicitly paused.
                    val isPlaybackStalled = isLoading && player.playbackState == Player.STATE_BUFFERING && player.playWhenReady
                    val isCurrentPlayer = player == currentPlayer
                    Logger.d(TAG, "onIsLoadingChanged: isLoading=$isLoading, isPlaybackStalled=$isPlaybackStalled, isCurrentPlayer=$isCurrentPlayer")
                    if (cachedIsLoading != isPlaybackStalled && isCurrentPlayer) {
                        cachedIsLoading = isPlaybackStalled
                        listeners.forEach { it.onIsLoadingChanged(isPlaybackStalled) }
                    }
                }

                override fun onTracksChanged(tracks: Tracks) {
                    if (player != currentPlayer) {
                        Logger.d(TAG, "Ignoring onPlaybackStateChanged from non-current player")
                        return
                    }
                    val genericTracks = tracks.toGenericTracks()
                    listeners.forEach { it.onTracksChanged(genericTracks) }
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (player != currentPlayer) return
                    // Fires for both in-app seeks and MediaSession/notification seeks — both land on the
                    // raw ExoPlayer. seekTo() does no manual notification, so this is the single source.
                    if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                        listeners.forEach { it.onSeeked(newPosition.positionMs) }
                    }
                }

                override fun onEvents(
                    player: Player,
                    events: Player.Events,
                ) {
                    if (player != currentPlayer) {
                        Logger.d(TAG, "Ignoring onPlaybackStateChanged from non-current player")
                        return
                    }
                    val shouldBePlaying =
                        !(player.playbackState == Player.STATE_ENDED || !player.playWhenReady)
                    if (events.containsAny(
                            Player.EVENT_PLAYBACK_STATE_CHANGED,
                            Player.EVENT_PLAY_WHEN_READY_CHANGED,
                            Player.EVENT_IS_PLAYING_CHANGED,
                            Player.EVENT_POSITION_DISCONTINUITY,
                        )
                    ) {
                        if (shouldBePlaying) {
                            listeners.forEach { it.shouldOpenOrCloseEqualizerIntent(true) }
                        } else {
                            listeners.forEach { it.shouldOpenOrCloseEqualizerIntent(false) }
                        }
                    }
                }
            }

        player.addListener(listener)
        activePlayerListener = listener
    }

    /**
     * Clean up active player listener from whichever player has it.
     * During crossfade the listener is on secondaryPlayer, not currentPlayer.
     */
    private fun cleanupPlayerListenerInternal() {
        activePlayerListener?.let { listener ->
            currentPlayer?.removeListener(listener)
            secondaryPlayer?.removeListener(listener)
        }
        activePlayerListener = null
    }

    // ========== Internal: Player Cleanup ==========

    private fun cleanupPlayerInternal(player: ExoPlayer) {
        try {
            player.stop()
            player.release()
        } catch (e: Exception) {
            Logger.w(TAG, "Error cleaning up player: ${e.message}")
        }
    }

    private fun cleanupCurrentPlayerInternal() {
        stopPositionUpdates()
        cleanupPlayerListenerInternal()

        // Cancel any ongoing crossfade
        crossfadeJob?.cancel()
        crossfadeJob = null
        setCrossfading(false)

        currentPlayer?.let { cleanupPlayerInternal(it) }
        currentPlayer = null
    }

    /**
     * Abort an in-progress crossfade by committing the INCOMING track (A+1) as the new
     * current player — the mid-fade counterpart of [finalizeCrossfade]. Invoked when the
     * user interacts during a crossfade (next/prev/pause). Direction: "crossfade means we
     * have moved to A+1", so we keep A+1 and drop A.
     *
     * Mirrors [finalizeCrossfade]'s player swap: release the outgoing player (A), promote
     * the secondary player (A+1) to current. The active listener and the ForwardingPlayer
     * delegate are intentionally NOT touched — both already point at A+1 (set in
     * [triggerCrossfadeTransition]); touching them would lose the listener / detach
     * MediaSession. [localCurrentMediaItemIndex] already equals A+1, so it is kept.
     *
     * Does NOT change [internalState], restart position updates, or seek — the caller
     * decides what to do next (pause in place, advance to A+2, go to previous, ...).
     */
    private fun commitIncomingAsCurrentInternal() {
        crossfadeJob?.cancel()
        crossfadeJob = null
        stopPositionUpdates()

        // Release the outgoing player (A). Do NOT remove listeners — the active listener
        // is on the incoming player (A+1), which we are keeping (same as finalizeCrossfade).
        currentPlayer?.let { cleanupPlayerInternal(it) }

        // Promote incoming (A+1) to current.
        currentPlayer = secondaryPlayer
        currentPlayerFilter = secondaryPlayerFilter
        secondaryPlayer = null
        secondaryPlayerFilter = null

        // The incoming player was fading in: reduced volume + DJ filter on (and the
        // outgoing one carried any tempo/pitch match). Restore normal playback on A+1.
        currentPlayerFilter?.enabled = false
        currentPlayer?.volume = internalVolume
        currentPlayer?.playbackParameters = PlaybackParameters(internalPlaybackSpeed, internalPlaybackPitch)
        currentPlayer?.skipSilenceEnabled = internalSkipSilence

        setCrossfading(false)
        crossfadeFromIndex = -1
    }

    // ========== Internal: Track End ==========

    /**
     * Crossfade is skipped when the NEXT track will play as a video (video content with
     * the watch-video setting on — the same condition MergingMediaSourceFactory uses to
     * build a merged audio+video source). The merged source resolves two stream URLs and
     * is error-prone to prepare mid-fade, and a video should start from its first frame
     * instead of fading in under the outgoing song — so the transition takes the normal
     * (non-crossfade) path.
     */
    private fun isNextTrackVideo(): Boolean = watchVideoEnabled && playlist.getOrNull(getNextMediaItemIndex())?.isVideo() == true

    /** Same skip rule for the CURRENT track: a video should play out to its last frame instead of fading out under the incoming song. */
    private fun isCurrentTrackVideo(): Boolean = watchVideoEnabled && currentMediaItem?.isVideo() == true

    /**
     * Handle track end - mirrors GstreamerPlayerAdapter.handleTrackEndInternal()
     */
    private fun handleTrackEndInternal() {
        // While casting, track transitions are driven by the receiver queue.
        if (isCastActive) return
        // Check if crossfade should be used
        val shouldCrossfade =
            crossfadeEnabled &&
                hasNextMediaItem() &&
                !isCrossfading &&
                !isCurrentTrackVideo() &&
                !isNextTrackVideo()

        if (shouldCrossfade) {
            val nextIndex = getNextMediaItemIndex()
            triggerCrossfadeTransition(nextIndex)
        } else {
            // Original behavior
            when (internalRepeatMode) {
                PlayerConstants.REPEAT_MODE_ONE -> {
                    seekTo(localCurrentMediaItemIndex, 0)
                }

                PlayerConstants.REPEAT_MODE_ALL -> {
                    if (hasNextMediaItem()) {
                        seekToNext()
                    }
                }

                else -> {
                    if (localCurrentMediaItemIndex < playlist.size - 1) {
                        seekToNext()
                    } else {
                        notifyEqualizerIntent(false)
                    }
                }
            }
        }
    }

    // ========== Internal: Crossfade ==========

    /**
     * Trigger crossfade to next track.
     * Mirrors GstreamerPlayerAdapter.triggerCrossfadeTransition()
     *
     * Key mechanism: [setupPlayerListenerInternal] moves the active listener
     * from the current player to the next player. The old player's STATE_ENDED
     * event is then ignored (no listener to fire).
     */
    /**
     * Trigger crossfade to next track.
     * Incorporates downbeat phase-locking, cue-in silence trimming, and archetype selection
     * inspired by DJtransGAN (https://github.com/ChenPaulYu/DJtransGAN) and
     * FlowFusion (https://github.com/the-data-science-union/DSU-W2025-FlowFusion-Automated-Song-Transitions).
     */
    private fun triggerCrossfadeTransition(nextIndex: Int, isManualSkip: Boolean = false) {
        if (nextIndex !in playlist.indices || isCrossfading || isCastActive) return

        coroutineScope.launch {
            try {
                setCrossfading(true)
                val nextMediaItem = playlist[nextIndex]
                val nextVideoId = nextMediaItem.mediaId

                Logger.d(TAG, "Starting DJ crossfade to track $nextIndex (manualSkip=$isManualSkip)")

                val currentVideoId = playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId ?: ""

                // Lazily load AutoMix metadata from database if not cached
                loadAudioMetaIfNeeded(currentVideoId)
                loadAudioMetaIfNeeded(nextVideoId)

                val effectiveStyle = resolveEffectiveTransitionStyle(currentVideoId, nextVideoId, isManualSkip)
                val resolvedConfigDurationMs =
                    if (crossfadeDurationMs == DataStoreManager.CROSSFADE_DURATION_AUTO || djCrossfadeEnabled) {
                        resolveAutoCrossfadeDurationMs(currentVideoId, nextVideoId, effectiveStyle)
                    } else {
                        crossfadeDurationMs
                    }

                // Phase-Locking: calculate downbeat alignment so Track B drops on Beat 1 of a measure
                val currentBpm = audioMetaCache[currentVideoId]?.bpm ?: DEFAULT_TEMPO_BPM
                val safeBpm = if (currentBpm in 60..220) currentBpm else DEFAULT_TEMPO_BPM
                val beatMs = 60_000.0 / safeBpm
                val barMs = (4 * beatMs).toLong().coerceAtLeast(1000L)

                if (!isManualSkip && currentPlayer != null) {
                    val currentPos = currentPlayer?.currentPosition ?: 0L
                    val barOffset = if (currentPos > 0) currentPos % barMs else 0L
                    val delayToNextDownbeat = (barMs - barOffset).coerceIn(0L, barMs)
                    if (delayToNextDownbeat in 50L..2500L) {
                        Logger.d(TAG, "Phase-locking to downbeat: delaying ${delayToNextDownbeat}ms")
                        delay(delayToNextDownbeat)
                    }
                }

                // Get or create secondary player
                val cachedPlayerEntry = precachedPlayers.remove(nextVideoId)
                val nextPlayer: ExoPlayer
                val nextFilter: CrossfadeFilterAudioProcessor?
                if (cachedPlayerEntry?.player != null) {
                    nextPlayer = cachedPlayerEntry.player
                    nextFilter = cachedPlayerEntry.filter
                } else {
                    val pwf = createExoPlayerInstance()
                    nextPlayer = pwf.player
                    nextFilter = pwf.filter
                    nextPlayer.setMediaItem(nextMediaItem.toMedia3MediaItem())
                    nextPlayer.prepare()
                }

                // Setup secondary player
                secondaryPlayer = nextPlayer
                secondaryPlayerFilter = nextFilter
                setupPlayerListenerInternal(nextPlayer)
                // Trim leading silence (C_in cue-in) for crisp transient attack
                nextPlayer.skipSilenceEnabled = true
                nextPlayer.volume = 0f

                forwardingPlayer.swapDelegate(nextPlayer)
                requestAudioFocusInternal()
                nextPlayer.play()
                forwardingPlayer.suppressPlaybackEnded = false
                forwardingPlayer.notifyMediaItemChanged()

                val bpmSpeedRatio = if (djBpmMatching) calculateBpmSpeedRatio(currentVideoId, nextVideoId) else 1.0f
                val keyPitchRatio = if (djBpmMatching) calculateKeyPitchRatio(currentVideoId, nextVideoId) else 1.0f

                nextPlayer.playbackParameters =
                    PlaybackParameters(internalPlaybackSpeed, internalPlaybackPitch)

                crossfadeFromIndex = localCurrentMediaItemIndex
                localCurrentMediaItemIndex = nextIndex

                listeners.forEach {
                    it.onMediaItemTransition(
                        nextMediaItem,
                        PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                    )
                }

                val actualTimeRemaining =
                    if (isManualSkip) {
                        resolvedConfigDurationMs.toLong()
                    } else {
                        currentPlayer?.let { player ->
                            val dur = player.duration
                            val pos = player.currentPosition
                            val speed = internalPlaybackSpeed.coerceAtLeast(0.1f)
                            if (dur > 0 && pos >= 0) ((dur - pos) / speed).toLong() else resolvedConfigDurationMs.toLong()
                        } ?: resolvedConfigDurationMs.toLong()
                    }

                val effectiveCrossfadeDurationMs =
                    minOf(resolvedConfigDurationMs.toLong(), actualTimeRemaining)
                        .coerceAtLeast(1000L)
                        .toInt()

                Logger.d(
                    TAG,
                    "DJ Transition triggered: style=$effectiveStyle, duration=${effectiveCrossfadeDurationMs}ms, " +
                        "bpmRatio=$bpmSpeedRatio, pitchRatio=$keyPitchRatio",
                )

                performCrossfade(
                    nextIndex,
                    nextPlayer,
                    effectiveCrossfadeDurationMs,
                    effectiveStyle,
                    bpmSpeedRatio,
                    keyPitchRatio,
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.e(TAG, "Crossfade error: ${e.message}", e)
                setCrossfading(false)
                seekTo(nextIndex, 0)
            }
        }
    }

    /**
     * S-curve (sigmoid) function for DJ filter crossfade timing.
     * Keeps both tracks near full spectrum at the start and end,
     * with a steep transition in the middle — like a real DJ mixer crossfader.
     *
     * k controls steepness: higher = sharper transition.
     * k=6 gives a gentle S-curve: ~8-92% of duration covers the sweep,
     * keeping a subtle onset/ending without harsh "slam" in the middle.
     */
    private fun sigmoid(
        t: Float,
        k: Float = DJ_FILTER_SIGMOID_K,
    ): Float = 1.0f / (1.0f + exp(-k * (t - 0.5f)))

    /**
     * Exponential interpolation between two values.
     * Frequency perception is logarithmic, so this produces a natural-sounding sweep.
     */
    private fun exponentialInterpolate(
        start: Float,
        end: Float,
        t: Float,
    ): Float {
        if (start <= 0f || end <= 0f) return end
        return exp(ln(start) + (ln(end) - ln(start)) * t).toFloat()
    }

    /**
     * Perform the actual DJ crossfade animation.
     * Implements EQ separation, drop swap, and filter sweeps inspired by DJtransGAN (https://github.com/ChenPaulYu/DJtransGAN)
     * and phrase/archetype rules inspired by FlowFusion (https://github.com/the-data-science-union/DSU-W2025-FlowFusion-Automated-Song-Transitions).
     *
     * @param effectiveDurationMs The actual crossfade duration to use.
     * @param style The resolved DJ transition archetype.
     * @param targetSpeedRatio BPM-based speed ratio for outgoing track.
     * @param targetPitchRatio Key-based pitch ratio for outgoing track.
     */
    private suspend fun performCrossfade(
        nextIndex: Int,
        nextPlayer: ExoPlayer,
        effectiveDurationMs: Int,
        style: DjTransitionStyle,
        targetSpeedRatio: Float = 1.0f,
        targetPitchRatio: Float = 1.0f,
    ) {
        val steps = 50
        val delayPerStep = (effectiveDurationMs / steps).coerceAtLeast(20)
        val targetVolume = internalVolume
        val useAutoMixRamp = djBpmMatching && (targetSpeedRatio != 1.0f || targetPitchRatio != 1.0f)
        Logger.d(
            TAG,
            "Crossfade animation: ${effectiveDurationMs}ms, $steps steps, ${delayPerStep}ms/step, " +
                "style=$style, autoMix=$useAutoMixRamp (speed=$targetSpeedRatio, pitch=$targetPitchRatio)",
        )

        // Setup DJ filters based on archetype before starting animation
        when (style) {
            DjTransitionStyle.BASS_SWAP -> {
                // DJtransGAN: Outgoing track plays wide open (LPF 20kHz).
                // Incoming track has sub-bass killed (HPF 350Hz) so its melody/intro layers cleanly.
                currentPlayerFilter?.let { filter ->
                    filter.filterType = BiquadFilter.FilterType.LOW_PASS
                    filter.cutoffFrequencyHz = LPF_START_HZ
                    filter.enabled = true
                }
                secondaryPlayerFilter?.let { filter ->
                    filter.filterType = BiquadFilter.FilterType.HIGH_PASS
                    filter.cutoffFrequencyHz = DJ_BASS_KILL_HZ
                    filter.enabled = true
                }
            }
            DjTransitionStyle.FILTER_SWEEP -> {
                // Resonant sweep washout: outgoing track sweeps HPF up to 3500Hz; incoming sweeps HPF down.
                currentPlayerFilter?.let { filter ->
                    filter.filterType = BiquadFilter.FilterType.HIGH_PASS
                    filter.cutoffFrequencyHz = 20f
                    filter.enabled = true
                }
                secondaryPlayerFilter?.let { filter ->
                    filter.filterType = BiquadFilter.FilterType.HIGH_PASS
                    filter.cutoffFrequencyHz = 400f
                    filter.enabled = true
                }
            }
            DjTransitionStyle.VINYL_BRAKE -> {
                // Turntable motor brake: no frequency filter needed, driven by pitch/rate drop
                currentPlayerFilter?.enabled = false
                secondaryPlayerFilter?.enabled = false
            }
            DjTransitionStyle.SMOOTH_CROSSFADE, DjTransitionStyle.SMART_AI -> {
                currentPlayerFilter?.let { filter ->
                    filter.filterType = BiquadFilter.FilterType.LOW_PASS
                    filter.cutoffFrequencyHz = LPF_START_HZ
                    filter.enabled = true
                }
                secondaryPlayerFilter?.let { filter ->
                    filter.filterType = BiquadFilter.FilterType.HIGH_PASS
                    filter.cutoffFrequencyHz = HPF_START_HZ
                    filter.enabled = true
                }
            }
        }

        var lastOutgoingSpeed = -1f
        var lastOutgoingPitch = -1f
        val bpmRampPortion = BPM_RAMP_PORTION

        crossfadeJob?.cancel()
        crossfadeJob =
            coroutineScope.launch {
                try {
                    for (step in 0..steps) {
                        if (!isActive) break

                        val progress = step.toFloat() / steps

                        when (style) {
                            DjTransitionStyle.BASS_SWAP -> {
                                // DJtransGAN Club Bass Swap:
                                // Intro blend (0.0..0.5) with sub-bass kill and vocal mid-range ducking
                                if (progress < 0.5f) {
                                    val introProgress = progress * 2f
                                    val inAngle = (introProgress * PI / 2).toFloat()
                                    nextPlayer.volume = targetVolume * sin(inAngle)

                                    // Mid-range vocal ducking on outgoing track around 0.25..0.5
                                    val ducking = if (progress >= 0.25f) 0.82f else 1.0f
                                    currentPlayer?.volume = targetVolume * ducking

                                    secondaryPlayerFilter?.cutoffFrequencyHz = DJ_BASS_KILL_HZ
                                    currentPlayerFilter?.cutoffFrequencyHz = LPF_START_HZ
                                } else {
                                    // Downbeat Bass Swap (0.5..1.0):
                                    // Incoming HPF drops wide open to 20Hz; outgoing LPF cuts to 180Hz (muffled kick eliminated)
                                    val outroProgress = (progress - 0.5f) * 2f
                                    val outAngle = (outroProgress * PI / 2).toFloat()

                                    secondaryPlayerFilter?.cutoffFrequencyHz = 20f
                                    nextPlayer.volume = targetVolume

                                    currentPlayerFilter?.cutoffFrequencyHz =
                                        exponentialInterpolate(DJ_DROP_LPF_CUT_HZ, 40f, outroProgress)
                                    currentPlayer?.volume = targetVolume * 0.35f * cos(outAngle)
                                }
                            }

                            DjTransitionStyle.FILTER_SWEEP -> {
                                val fadeAngle = (progress * PI / 2).toFloat()
                                currentPlayer?.volume = targetVolume * cos(fadeAngle)
                                nextPlayer.volume = targetVolume * sin(fadeAngle)

                                currentPlayerFilter?.cutoffFrequencyHz =
                                    exponentialInterpolate(20f, 3500f, progress)
                                secondaryPlayerFilter?.cutoffFrequencyHz =
                                    exponentialInterpolate(400f, 20f, progress)
                            }

                            DjTransitionStyle.VINYL_BRAKE -> {
                                val brake = (1.0f - progress * progress).coerceAtLeast(0.05f)
                                val qBrakeSpeed = quantize(brake * internalPlaybackSpeed)
                                val qBrakePitch = quantize(brake * internalPlaybackPitch)

                                if (qBrakeSpeed != lastOutgoingSpeed || qBrakePitch != lastOutgoingPitch) {
                                    currentPlayer?.playbackParameters = PlaybackParameters(qBrakeSpeed, qBrakePitch)
                                    lastOutgoingSpeed = qBrakeSpeed
                                    lastOutgoingPitch = qBrakePitch
                                }

                                if (progress < 0.65f) {
                                    currentPlayer?.volume = targetVolume * (1.0f - progress * 0.4f)
                                    nextPlayer.volume = 0f
                                } else {
                                    currentPlayer?.volume = 0f
                                    nextPlayer.volume = targetVolume
                                }
                            }

                            DjTransitionStyle.SMOOTH_CROSSFADE, DjTransitionStyle.SMART_AI -> {
                                val fadeAngle = (progress * PI / 2).toFloat()
                                currentPlayer?.volume = targetVolume * cos(fadeAngle)
                                nextPlayer.volume = targetVolume * sin(fadeAngle)

                                val filterProgress = sigmoid(progress)
                                currentPlayerFilter?.cutoffFrequencyHz =
                                    exponentialInterpolate(LPF_START_HZ, LPF_END_HZ, filterProgress)
                                secondaryPlayerFilter?.cutoffFrequencyHz =
                                    exponentialInterpolate(HPF_START_HZ, HPF_END_HZ, filterProgress)
                            }
                        }

                        // AutoMix BPM matching (for non-vinyl brake transitions)
                        if (useAutoMixRamp && style != DjTransitionStyle.VINYL_BRAKE) {
                            val linearRamp = (progress / bpmRampPortion).coerceAtMost(1f)
                            val rampProgress = linearRamp * linearRamp * (3f - 2f * linearRamp)
                            val rawOutSpeed = lerp(1.0f, targetSpeedRatio, rampProgress)
                            val rawOutPitch = lerp(1.0f, targetPitchRatio, rampProgress)
                            val qOutSpeed = quantize(rawOutSpeed * internalPlaybackSpeed)
                            val qOutPitch = quantize(rawOutPitch * internalPlaybackPitch)

                            if (qOutSpeed != lastOutgoingSpeed || qOutPitch != lastOutgoingPitch) {
                                currentPlayer?.playbackParameters = PlaybackParameters(qOutSpeed, qOutPitch)
                                lastOutgoingSpeed = qOutSpeed
                                lastOutgoingPitch = qOutPitch
                            }
                        }

                        delay(delayPerStep.toLong())
                    }

                    // Transition complete
                    finalizeCrossfade(nextIndex, nextPlayer)
                } catch (e: CancellationException) {
                    Logger.d(TAG, "Crossfade cancelled")
                    currentPlayerFilter?.enabled = false
                    secondaryPlayerFilter?.enabled = false
                    currentPlayer?.playbackParameters =
                        PlaybackParameters(internalPlaybackSpeed, internalPlaybackPitch)
                    currentPlayer?.volume = internalVolume
                    nextPlayer.release()
                    secondaryPlayer = null
                    secondaryPlayerFilter = null
                    setCrossfading(false)
                }
            }
    }

    // ========== AutoMix Public API ==========

    /**
     * Audio analysis metadata for a song, populated from Tidal search response.
     */
    data class SongAudioMeta(
        val bpm: Int?,
        val key: String?,
        val keyScale: String?, // "MAJOR" or "MINOR"
    )

    /**
     * Update the audio analysis metadata cache for a song.
     * Called externally when Tidal 320kbps stream is fetched and BPM/key data is available.
     * Data is used by Auto crossfade mode for beat-quantized duration, BPM matching, and key matching.
     */
    fun updateSongAudioMeta(
        videoId: String,
        bpm: Int?,
        key: String?,
        keyScale: String?,
    ) {
        if (bpm != null || key != null) {
            audioMetaCache[videoId] = SongAudioMeta(bpm, key, keyScale)
            Logger.d(TAG, "AutoMix meta updated: videoId=$videoId, bpm=$bpm, key=$key $keyScale")
        }
    }

    // ========== AutoMix Internal Logic ==========

    /**
     * Lazily load audio analysis metadata from NewFormatEntity if not already in cache.
     * Called before AutoMix calculations to ensure metadata is available.
     */
    private suspend fun loadAudioMetaIfNeeded(videoId: String) {
        if (videoId.isBlank() || audioMetaCache.containsKey(videoId)) return
        try {
            val format = streamRepository.getNewFormat(videoId).firstOrNull()
            if (format == null) {
                Logger.d(TAG, "AutoMix meta: no NewFormatEntity found for videoId=$videoId")
                return
            }
            if (format.bpm != null || format.musicKey != null) {
                audioMetaCache[videoId] = SongAudioMeta(format.bpm, format.musicKey, format.keyScale)
                Logger.d(TAG, "AutoMix meta loaded: videoId=$videoId, bpm=${format.bpm}, key=${format.musicKey} ${format.keyScale}")
            } else {
                Logger.d(TAG, "AutoMix meta: format exists but no bpm/key data for videoId=$videoId")
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to load AutoMix meta for $videoId: ${e.message}")
        }
    }

    /**
     * Linear interpolation between two values.
     */
    private fun lerp(
        start: Float,
        end: Float,
        t: Float,
    ): Float = start + (end - start) * t

    /**
     * Quantize a speed/pitch value to the nearest [SPEED_PITCH_STEP] (2%).
     * Prevents SonicAudioProcessor from resetting on micro-adjustments that
     * cause audible popping/clicking artifacts.
     */
    private fun quantize(value: Float): Float = (Math.round(value / SPEED_PITCH_STEP) * SPEED_PITCH_STEP)

    /**
     * Resolves the effective DJ transition style based on FlowFusion and DJtransGAN archetype matrix.
     * Evaluates tempo gap and Camelot harmonic key compatibility.
     *
     * Reference:
     * - FlowFusion (UCLA DSU): https://github.com/the-data-science-union/DSU-W2025-FlowFusion-Automated-Song-Transitions
     * - DJtransGAN (ICASSP 2022): https://github.com/ChenPaulYu/DJtransGAN
     */
    private fun resolveEffectiveTransitionStyle(
        currentVideoId: String,
        nextVideoId: String,
        isManualSkip: Boolean = false,
    ): DjTransitionStyle {
        if (isManualSkip) {
            return DjTransitionStyle.VINYL_BRAKE
        }
        if (djTransitionStyle != DjTransitionStyle.SMART_AI) {
            return djTransitionStyle
        }

        val currentMeta = audioMetaCache[currentVideoId]
        val nextMeta = audioMetaCache[nextVideoId]
        val currentBpm = currentMeta?.bpm
        val nextBpm = nextMeta?.bpm

        if (currentBpm == null || nextBpm == null || currentBpm <= 0 || nextBpm <= 0) {
            return DjTransitionStyle.BASS_SWAP
        }

        var ratio = nextBpm.toDouble() / currentBpm.toDouble()
        while (ratio > 1.5) ratio /= 2.0
        while (ratio < 0.67) ratio *= 2.0
        val bpmGapPercent = abs(1.0 - ratio)

        val currentKey = currentMeta.key
        val nextKey = nextMeta.key
        val currentCamelot = if (currentKey != null) keyToCamelot(currentKey, currentMeta.keyScale) else null
        val nextCamelot = if (nextKey != null) keyToCamelot(nextKey, nextMeta.keyScale) else null
        val camelotDist = if (currentCamelot != null && nextCamelot != null) {
            camelotDistance(currentCamelot, nextCamelot)
        } else {
            1
        }

        val style = when {
            bpmGapPercent > 0.16 || camelotDist >= 5 -> DjTransitionStyle.VINYL_BRAKE
            bpmGapPercent > 0.08 || camelotDist >= 3 -> DjTransitionStyle.FILTER_SWEEP
            else -> DjTransitionStyle.BASS_SWAP
        }

        Logger.d(
            TAG,
            "resolveEffectiveTransitionStyle -> $style (bpmGap=${"%.1f".format(bpmGapPercent * 100)}%, camelotDist=$camelotDist)",
        )
        return style
    }

    /**
     * Resolves phrase-locked transition duration based on musical beat boundaries (FlowFusion).
     * Locks transitions to 16 beats (4 bars), 8 beats (2 bars), or 4 beats (1 bar) based on archetype.
     */
    private fun resolveAutoCrossfadeDurationMs(
        currentVideoId: String,
        nextVideoId: String,
        style: DjTransitionStyle,
    ): Int {
        val currentBpm = audioMetaCache[currentVideoId]?.bpm ?: DEFAULT_TEMPO_BPM
        val safeBpm = if (currentBpm in 60..220) currentBpm else DEFAULT_TEMPO_BPM
        val beatMs = 60_000.0 / safeBpm

        val duration = when (style) {
            DjTransitionStyle.BASS_SWAP, DjTransitionStyle.SMOOTH_CROSSFADE -> {
                (PHRASE_BEATS_BASS_SWAP * beatMs).toInt().coerceIn(4000, 12000)
            }
            DjTransitionStyle.FILTER_SWEEP -> {
                (PHRASE_BEATS_FILTER_SWEEP * beatMs).toInt().coerceIn(2500, 6000)
            }
            DjTransitionStyle.VINYL_BRAKE -> {
                (PHRASE_BEATS_VINYL_BRAKE * beatMs).toInt().coerceIn(1500, 3500)
            }
            DjTransitionStyle.SMART_AI -> {
                (PHRASE_BEATS_BASS_SWAP * beatMs).toInt().coerceIn(4000, 10000)
            }
        }

        Logger.d(
            TAG,
            "resolveAutoCrossfadeDurationMs: style=$style, bpm=$safeBpm, beatMs=${beatMs.toInt()}ms -> duration=${duration}ms",
        )
        return duration
    }

    /**
     * BPM gap → duration multiplier.
     * Normalizes halftime/doubletime (80 vs 160 → effectively same tempo).
     * Linear: 0% gap → 1.0x, 25% gap → 1.5x.
     */
    private fun calculateBpmGapDurationFactor(
        currentBpm: Int,
        nextBpm: Int,
    ): Double {
        if (currentBpm <= 0 || nextBpm <= 0) return 1.0
        var ratio = nextBpm.toDouble() / currentBpm.toDouble()
        while (ratio > 1.5) ratio /= 2.0
        while (ratio < 0.67) ratio *= 2.0
        val gapPercent = abs(1.0 - ratio)
        return 1.0 + gapPercent * BPM_GAP_DURATION_SCALE
    }

    /**
     * Key gap (Camelot distance) → duration multiplier.
     * Compatible keys (dist ≤ 1) need no extension; far keys need longer blend.
     */
    private fun calculateKeyGapDurationFactor(
        currentVideoId: String,
        nextVideoId: String,
    ): Double {
        val currentMeta = audioMetaCache[currentVideoId]
        val nextMeta = audioMetaCache[nextVideoId]
        val currentKey = currentMeta?.key ?: return UNKNOWN_GAP_DEFAULT_FACTOR
        val nextKey = nextMeta?.key ?: return UNKNOWN_GAP_DEFAULT_FACTOR

        // An unparseable key tells us nothing about compatibility — treat it like a missing
        // key instead of like a perfect match, otherwise it silently shortens the blend.
        val currentCamelot = keyToCamelot(currentKey, currentMeta.keyScale) ?: return UNKNOWN_GAP_DEFAULT_FACTOR
        val nextCamelot = keyToCamelot(nextKey, nextMeta.keyScale) ?: return UNKNOWN_GAP_DEFAULT_FACTOR

        val dist = camelotDistance(currentCamelot, nextCamelot)
        return when {
            dist <= 1 -> 1.0
            dist == 2 -> 1.1
            dist <= 4 -> 1.25
            else -> 1.4
        }
    }

    /**
     * Calculate the playback speed ratio to match the incoming track's BPM to the current track's BPM.
     * Returns a ratio to apply to the incoming track's speed (e.g., 1.05 = speed up 5%).
     * Handles halftime/doubletime harmonic BPM relationships (e.g., 70 BPM ≈ 140 BPM / 2).
     * Returns 1.0 if no BPM data or the ratio exceeds the safe adjustment range.
     */
    private fun calculateBpmSpeedRatio(
        currentVideoId: String,
        nextVideoId: String,
    ): Float {
        val currentMeta = audioMetaCache[currentVideoId]
        val nextMeta = audioMetaCache[nextVideoId]
        val currentBpm = currentMeta?.bpm
        val nextBpm = nextMeta?.bpm

        if (currentBpm == null || nextBpm == null) {
            Logger.d(
                TAG,
                "AutoMix BPM: missing data - current=$currentBpm (cached=${currentMeta != null}), " +
                    "next=$nextBpm (cached=${nextMeta != null})",
            )
            return 1.0f
        }
        if (currentBpm <= 0 || nextBpm <= 0) return 1.0f

        // Ratio is APPLIED to outgoing player so its effective BPM matches next track.
        //   outgoing_effective_BPM = currentBpm × ratio
        //   want outgoing_effective_BPM = nextBpm
        //   → ratio = nextBpm / currentBpm
        var ratio = nextBpm.toFloat() / currentBpm.toFloat()

        // Normalize halftime/doubletime relationships (e.g., 140/70 → 1.0, 70/140 → 1.0)
        while (ratio > 1.5f) ratio /= 2f
        while (ratio < 0.67f) ratio *= 2f

        Logger.d(TAG, "AutoMix BPM: current=$currentBpm, next=$nextBpm, ratio=${"%.4f".format(ratio)}")

        // Only apply if adjustment is within safe range (avoids unnatural artifacts)
        // Quantize to SPEED_PITCH_STEP to avoid SonicAudioProcessor artifacts
        return if (ratio in BPM_RATIO_MIN..BPM_RATIO_MAX) {
            quantize(ratio)
        } else {
            Logger.d(TAG, "AutoMix BPM: ratio ${"%.4f".format(ratio)} outside safe range [$BPM_RATIO_MIN..$BPM_RATIO_MAX], skipping")
            1.0f
        }
    }

    // ========== Camelot Wheel Key Matching ==========

    /**
     * Camelot Wheel position: number (1-12) + type (minor=A, major=B).
     * Standard DJ key compatibility system — adjacent codes are harmonically compatible.
     */
    private data class CamelotCode(
        val number: Int,
        val isMinor: Boolean,
    ) {
        override fun toString(): String = "$number${if (isMinor) "A" else "B"}"
    }

    /**
     * Map a musical key + scale to its Camelot Wheel code.
     *
     * Camelot Wheel (minor = A, major = B):
     *  1A=Abm  1B=B     |  7A=Dm   7B=F
     *  2A=Ebm  2B=F#    |  8A=Am   8B=C
     *  3A=Bbm  3B=Db    |  9A=Em   9B=G
     *  4A=Fm   4B=Ab    | 10A=Bm  10B=D
     *  5A=Cm   5B=Eb    | 11A=F#m 11B=A
     *  6A=Gm   6B=Bb    | 12A=C#m 12B=E
     */
    private fun keyToCamelot(
        key: String,
        keyScale: String?,
    ): CamelotCode? {
        val semitone = keyToSemitone(key)
        if (semitone < 0) return null

        val isMinor = keyScale?.uppercase()?.contains("MIN") == true

        // Camelot number lookup by chromatic semitone (C=0, C#=1, D=2, ... B=11)
        //                                   C  C# D  D# E  F  F# G  G# A  A# B
        val minorCamelotByPitch = intArrayOf(5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)
        val majorCamelotByPitch = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)

        val number = if (isMinor) minorCamelotByPitch[semitone] else majorCamelotByPitch[semitone]
        return CamelotCode(number, isMinor)
    }

    /**
     * Calculate the Camelot distance between two keys.
     * Considers both the circular number distance (0-6) and type difference (A/B).
     *
     * Compatible transitions (distance <= 1):
     * - Same code (8A->8A): distance 0
     * - Adjacent number, same type (8A->7A, 8A->9A): distance 1
     * - Same number, different type / relative key (8A->8B): distance 1
     *
     * Near-compatible (distance 2, same type):
     * - +-2 same type (8A->10A): 2 semitones apart (whole tone) — safe to shift
     */
    private fun camelotDistance(
        a: CamelotCode,
        b: CamelotCode,
    ): Int {
        val numberDiff = abs(a.number - b.number)
        val circularDist = minOf(numberDiff, 12 - numberDiff)
        val typeDiff = if (a.isMinor != b.isMinor) 1 else 0
        return circularDist + typeDiff
    }

    /**
     * Calculate pitch ratio using Camelot Wheel for musically correct key matching.
     *
     * Tries ±1 then ±2 semitone shifts on the outgoing track and picks the
     * smallest shift that brings Camelot distance ≤ 1 (harmonically compatible).
     * ±1 semitone is nearly imperceptible during crossfade; ±2 is a whole tone.
     * If no small shift achieves compatibility, returns 1.0 (no shift).
     */
    private fun calculateKeyPitchRatio(
        currentVideoId: String,
        nextVideoId: String,
    ): Float {
        val currentMeta = audioMetaCache[currentVideoId]
        val nextMeta = audioMetaCache[nextVideoId]
        val currentKey = currentMeta?.key
        val nextKey = nextMeta?.key

        if (currentKey == null || nextKey == null) {
            Logger.d(
                TAG,
                "AutoMix Key: missing data - currentKey=$currentKey (cached=${currentMeta != null}), " +
                    "nextKey=$nextKey (cached=${nextMeta != null})",
            )
            return 1.0f
        }

        val currentCamelot = keyToCamelot(currentKey, currentMeta.keyScale)
        val nextCamelot = keyToCamelot(nextKey, nextMeta.keyScale)

        if (currentCamelot == null || nextCamelot == null) {
            Logger.d(
                TAG,
                "AutoMix Key: unknown key format - currentKey='$currentKey' ${currentMeta.keyScale}, " +
                    "nextKey='$nextKey' ${nextMeta.keyScale}",
            )
            return 1.0f
        }

        val dist = camelotDistance(currentCamelot, nextCamelot)

        Logger.d(
            TAG,
            "AutoMix Key: current=$currentKey ${currentMeta.keyScale} ($currentCamelot), " +
                "next=$nextKey ${nextMeta.keyScale} ($nextCamelot), camelotDist=$dist",
        )

        if (dist <= 1) {
            Logger.d(TAG, "AutoMix Key: compatible (dist=$dist), no shift")
            return 1.0f
        }

        val currentSemitone = keyToSemitone(currentKey)
        if (currentSemitone < 0) return 1.0f

        val isMinor = currentCamelot.isMinor
        //                                   C  C# D  D# E  F  F# G  G# A  A# B
        val minorCamelotByPitch = intArrayOf(5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)
        val majorCamelotByPitch = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)

        for (shift in intArrayOf(-1, 1, -2, 2)) {
            val shiftedSemitone = (currentSemitone + shift + 12) % 12
            val shiftedNumber =
                if (isMinor) minorCamelotByPitch[shiftedSemitone] else majorCamelotByPitch[shiftedSemitone]
            val shiftedCamelot = CamelotCode(shiftedNumber, isMinor)
            if (camelotDistance(shiftedCamelot, nextCamelot) <= 1) {
                val pitchRatio = exp(ln(2.0) * shift.toDouble() / 12.0).toFloat()
                Logger.d(
                    TAG,
                    "AutoMix Key: shift $shift semitones ($currentCamelot→$shiftedCamelot), " +
                        "ratio=${"%.4f".format(pitchRatio)}",
                )
                return pitchRatio
            }
        }

        Logger.d(TAG, "AutoMix Key: dist=$dist, no safe shift within ±2 semitones")
        return 1.0f
    }

    /**
     * Map a musical key name to its chromatic semitone number (0-11).
     * C=0, C#/Db=1, D=2, ..., B=11. Returns -1 for unknown keys.
     *
     * Tidal spells accidentals out ("FSharp", "CSharp") instead of using symbols,
     * so the name is normalised before matching.
     */
    private fun keyToSemitone(key: String): Int {
        val normalized =
            key
                .trim()
                .replace("Sharp", "#", ignoreCase = true)
                .replace("Flat", "b", ignoreCase = true)
                .replaceFirstChar { it.uppercaseChar() }
        return when (normalized) {
            "C" -> 0
            "C#", "Db" -> 1
            "D" -> 2
            "D#", "Eb" -> 3
            "E" -> 4
            "F" -> 5
            "F#", "Gb" -> 6
            "G" -> 7
            "G#", "Ab" -> 8
            "A" -> 9
            "A#", "Bb" -> 10
            "B" -> 11
            else -> -1
        }
    }

    companion object {
        // DJ crossfade sigmoid steepness (higher = sharper S-curve transition)
        private const val DJ_FILTER_SIGMOID_K = 6f

        // DJ crossfade filter frequency bounds
        private const val LPF_START_HZ = 20000f // Low-pass starts wide open
        private const val LPF_END_HZ = 200f // Low-pass ends muffled (keeps bass thump like Pioneer DJM)
        private const val HPF_START_HZ = 2000f // High-pass starts lower — incoming track fills in faster
        private const val HPF_END_HZ = 20f // High-pass ends wide open

        // DJtransGAN EQ anchors (https://github.com/ChenPaulYu/DJtransGAN)
        private const val DJ_BASS_KILL_HZ = 350f // Cut incoming sub-bass during intro phrase
        private const val DJ_DROP_LPF_CUT_HZ = 180f // Cut outgoing bass on downbeat swap

        // FlowFusion musical phrasing in beats (https://github.com/the-data-science-union/DSU-W2025-FlowFusion-Automated-Song-Transitions)
        private const val PHRASE_BEATS_BASS_SWAP = 16 // 4 bars
        private const val PHRASE_BEATS_FILTER_SWEEP = 8 // 2 bars
        private const val PHRASE_BEATS_VINYL_BRAKE = 4 // 1 bar
        private const val DEFAULT_TEMPO_BPM = 120

        // AutoMix constants
        private const val AUTO_FALLBACK_DURATION_MS = 8000 // Default 16-beat phrase at 120 BPM
        private const val AUTO_MIN_DURATION_MS = 1500
        private const val AUTO_MAX_DURATION_MS = 12000
        private val BEAT_COUNT_OPTIONS = intArrayOf(4, 8, 16, 24, 32)
        private const val DEFAULT_BEAT_COUNT = 16
        private const val BPM_RATIO_MIN = 0.75f // Max 25% slower
        private const val BPM_RATIO_MAX = 1.25f // Max 25% faster

        // BPM gap → duration scale: 0% gap → 1.0x, 25% gap → 1.5x
        private const val BPM_GAP_DURATION_SCALE = 2.0

        // Default gap factor when BPM or key data is missing — assume moderate incompatibility
        private const val UNKNOWN_GAP_DEFAULT_FACTOR = 1.25

        // Quantization step for speed/pitch ramp (0.5% = 0.005)
        // Prevents SonicAudioProcessor from popping on micro-adjustments
        private const val SPEED_PITCH_STEP = 0.02f

        // Front-loaded BPM/pitch ramp portion (fraction of crossfade duration).
        // Outgoing tempo reaches target within the first [BPM_RAMP_PORTION] of the
        // crossfade (smoothstep S-curve) and then holds for the remainder.
        private const val BPM_RAMP_PORTION = 0.4f
    }

    /**
     * Finalize crossfade: swap players and cleanup.
     * Mirrors GstreamerPlayerAdapter.finalizeCrossfade()
     */
    private fun finalizeCrossfade(
        nextIndex: Int,
        nextPlayer: ExoPlayer,
    ) {
        Logger.d(TAG, "Crossfade complete, swapping players")

        // Cleanup old current player WITHOUT touching listeners
        // (listeners are already setup for nextPlayer via setupPlayerListenerInternal)
        stopPositionUpdates()

        // Cleanup the old current player manually
        currentPlayer?.let { oldPlayer ->
            try {
                oldPlayer.stop()
                oldPlayer.release()
            } catch (e: Exception) {
                Logger.w(TAG, "Error cleaning up old player: ${e.message}")
            }
        }

        // Disable and reset DJ filters on the new current player (no overhead during normal playback)
        secondaryPlayerFilter?.let { filter ->
            filter.enabled = false
        }
        // Old player's filter is released with the old player (GC'd)

        // Promote secondary to current
        currentPlayer = nextPlayer
        currentPlayerFilter = secondaryPlayerFilter
        secondaryPlayer = null
        secondaryPlayerFilter = null
        // localCurrentMediaItemIndex already updated in triggerCrossfadeTransition()

        // Audio focus is held at the adapter level (see Audio Focus section),
        // not per-player, so it survives this crossfade swap (#2155).

        // Ensure correct volume and playback parameters
        currentPlayer?.volume = internalVolume
        currentPlayer?.playbackParameters = PlaybackParameters(internalPlaybackSpeed, internalPlaybackPitch)
        currentPlayer?.skipSilenceEnabled = internalSkipSilence

        // Reset state
        setCrossfading(false)
        crossfadeFromIndex = -1
        transitionToState(InternalState.PLAYING)

        // Ensure MediaSession notification has correct metadata after crossfade completes.
        // setAudioAttributes() above may cause a brief audio session reset, so re-notify
        // MediaSession to guarantee the notification shows the correct track info.
        forwardingPlayer.notifyMediaItemChanged()

        // Start position tracking
        startPositionUpdates()

        // Trigger next precache
        triggerPrecachingInternal()
    }

    // ========== Internal: Position Updates ==========

    /**
     * Start position updates (periodic background task).
     * Also handles crossfade detection when position approaches end.
     */
    private fun startPositionUpdates() {
        stopPositionUpdates()

        positionUpdateJob =
            coroutineScope.launch {
                while (isActive && currentPlayer != null) {
                    try {
                        currentPlayer?.let { player ->
                            if (internalState == InternalState.PLAYING ||
                                internalState == InternalState.READY ||
                                internalState == InternalState.PAUSED
                            ) {
                                // During crossfade, show the incoming track's timeline
                                val timelinePlayer = if (isCrossfading) secondaryPlayer ?: player else player
                                val pos = timelinePlayer.currentPosition
                                val dur = timelinePlayer.duration
                                val buf = timelinePlayer.bufferedPosition

                                if (pos >= 0) cachedPosition = pos
                                if (dur > 0) cachedDuration = dur
                                if (buf >= 0) cachedBufferedPosition = buf

                                // Check if should trigger crossfade.
                                // Add a preparation buffer (3s) so that if the next track
                                // is NOT precached, URL resolution + buffering time doesn't
                                // eat into the audible crossfade window.
                                if (crossfadeEnabled &&
                                    !isCrossfading &&
                                    player.isPlaying &&
                                    dur > 0 &&
                                    pos > 0 &&
                                    !isCurrentTrackVideo() &&
                                    !isNextTrackVideo()
                                ) {
                                    // Account for playback speed: at higher speed, media time
                                    // is consumed faster, so wall-clock remaining is shorter
                                    val speed = internalPlaybackSpeed.coerceAtLeast(0.1f)
                                    val timeRemaining = ((dur - pos) / speed).toLong()
                                    val nextVideoId = playlist.getOrNull(getNextMediaItemIndex())?.mediaId ?: ""
                                    val currentVideoId = playlist.getOrNull(localCurrentMediaItemIndex)?.mediaId ?: ""
                                    val isPrecached = nextVideoId.isNotBlank() && precachedPlayers.containsKey(nextVideoId)
                                    val preparationBufferMs = if (isPrecached) 0L else 3000L

                                    if (timeRemaining in 1L..30000L) {
                                        loadAudioMetaIfNeeded(currentVideoId)
                                        loadAudioMetaIfNeeded(nextVideoId)
                                    }

                                    val effectiveStyle = resolveEffectiveTransitionStyle(currentVideoId, nextVideoId)
                                    val resolvedDurationMs =
                                        if (crossfadeDurationMs == DataStoreManager.CROSSFADE_DURATION_AUTO || djCrossfadeEnabled) {
                                            resolveAutoCrossfadeDurationMs(currentVideoId, nextVideoId, effectiveStyle)
                                        } else {
                                            crossfadeDurationMs
                                        }
                                    val triggerThreshold = resolvedDurationMs.toLong() + preparationBufferMs
                                    if (timeRemaining in 1..triggerThreshold) {
                                        if (hasNextMediaItem()) {
                                            val nextIndex = getNextMediaItemIndex()
                                            triggerCrossfadeTransition(nextIndex)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore query errors - don't log to avoid spam
                    }

                    delay(200) // Update every 200ms
                }
            }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    // ========== Internal: Precaching ==========

    /**
     * Trigger precaching - mirrors GstreamerPlayerAdapter.triggerPrecachingInternal()
     *
     * Creates ExoPlayer instances for upcoming tracks. Each player gets a MediaItem
     * and calls prepare(), which triggers URL resolution and buffering via MediaSourceFactory.
     */
    private fun triggerPrecachingInternal() {
        if (!precacheEnabled || playlist.isEmpty() || isCastActive) return

        cancelPrecaching()
        Logger.d(TAG, "Trigger precache")
        precacheJob =
            coroutineScope.launch {
                try {
                    val indicesToPrecache = mutableListOf<Int>()

                    val index = localCurrentMediaItemIndex
                    for (i in 1..maxPrecacheCount) {
                        val nextIndex =
                            when (internalRepeatMode) {
                                PlayerConstants.REPEAT_MODE_ALL -> {
                                    (index + i) % playlist.size
                                }
                                else -> {
                                    val next = index + i
                                    if (next < playlist.size) next else break
                                }
                            }

                        if (nextIndex != localCurrentMediaItemIndex &&
                            !precachedPlayers.containsKey(playlist.getOrNull(nextIndex)?.mediaId)
                        ) {
                            indicesToPrecache.add(nextIndex)
                        }
                    }

                    for (idx in indicesToPrecache) {
                        if (!isActive) break

                        val mediaItem = playlist.getOrNull(idx) ?: continue

                        try {
                            val pwf = createExoPlayerInstance()
                            pwf.player.setMediaItem(mediaItem.toMedia3MediaItem())
                            pwf.player.prepare()
                            precachedPlayers[mediaItem.mediaId] = PrecachedPlayer(pwf.player, mediaItem, pwf.filter)
                            Logger.d(TAG, "Precached player for index $idx")
                        } catch (e: Exception) {
                            Logger.e(TAG, "Precaching error for $idx: ${e.message}")
                        }

                        delay(100)
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Logger.e(TAG, "Precaching error: ${e.message}")
                    }
                }
            }
    }

    private fun cancelPrecaching() {
        precacheJob?.cancel()
        precacheJob = null
    }

    private fun clearPrecacheExceptCurrentInternal() {
        Logger.d(TAG, "Clearing precache")
        precachedPlayers.entries.removeIf { (videoId, cached) ->
            if (videoId != currentMediaItem?.mediaId) {
                cleanupPlayerInternal(cached.player)
                true
            } else {
                false
            }
        }
    }

    private fun clearAllPrecacheInternal() {
        Logger.d(TAG, "Clearing all precache")
        precachedPlayers.values.forEach { cleanupPlayerInternal(it.player) }
        precachedPlayers.clear()
    }

    // ========== Internal: Notifications ==========

    private fun notifyEqualizerIntent(shouldOpen: Boolean) {
        listeners.forEach { it.shouldOpenOrCloseEqualizerIntent(shouldOpen) }
    }

    // ========== Internal: Shuffle Management ==========
    // Mirrors GstreamerPlayerAdapter shuffle management exactly

    private fun createShuffleOrder() {
        if (playlist.isEmpty()) {
            shuffleIndices.clear()
            shuffleOrder.clear()
            return
        }

        val indices = playlist.indices.toMutableList()

        val currentIndex = localCurrentMediaItemIndex
        if (currentIndex in indices) {
            indices.removeAt(currentIndex)
        }

        indices.shuffle()

        if (currentIndex in playlist.indices) {
            indices.add(0, currentIndex)
        }

        shuffleOrder.clear()
        shuffleOrder.addAll(indices)

        shuffleIndices.clear()
        shuffleIndices.addAll(List(playlist.size) { 0 })
        shuffleOrder.forEachIndexed { shuffledPos, originalIndex ->
            shuffleIndices[originalIndex] = shuffledPos
        }

        Logger.d(TAG, "Created shuffle order: $shuffleOrder")
    }

    private fun clearShuffleOrder() {
        shuffleIndices.clear()
        shuffleOrder.clear()
        Logger.d(TAG, "Cleared shuffle order")
    }

    private fun insertIntoShuffleOrder(
        insertedOriginalIndex: Int,
        afterShufflePos: Int,
    ) {
        if (playlist.isEmpty() || insertedOriginalIndex !in playlist.indices) {
            return
        }

        for (i in shuffleOrder.indices) {
            if (shuffleOrder[i] >= insertedOriginalIndex) {
                shuffleOrder[i]++
            }
        }

        val insertPos = (afterShufflePos + 1).coerceIn(0, shuffleOrder.size)
        shuffleOrder.add(insertPos, insertedOriginalIndex)

        shuffleIndices.clear()
        shuffleIndices.addAll(List(playlist.size) { 0 })
        shuffleOrder.forEachIndexed { shuffledPos, origIndex ->
            if (origIndex < shuffleIndices.size) {
                shuffleIndices[origIndex] = shuffledPos
            }
        }

        Logger.d(
            TAG,
            "Inserted index $insertedOriginalIndex into shuffle at position $insertPos (after shuffle pos $afterShufflePos)",
        )
    }

    private fun getShuffledMediaItemList(): List<GenericMediaItem> {
        if (!internalShuffleModeEnabled || shuffleOrder.isEmpty()) {
            return playlist.toList()
        }
        return shuffleOrder.mapNotNull { playlist.getOrNull(it) }
    }

    private fun notifyTimelineChanged(reason: String) {
        val list = getShuffledMediaItemList()
        listeners.forEach { it.onTimelineChanged(list, reason) }
    }
}