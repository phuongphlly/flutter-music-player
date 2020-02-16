package tech.soit.quiet.player

import android.content.Context
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.audio.AudioAttributes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tech.soit.quiet.MusicPlayerServicePlugin
import tech.soit.quiet.MusicPlayerSession
import tech.soit.quiet.MusicSessionCallback
import tech.soit.quiet.ext.mapPlaybackState
import tech.soit.quiet.ext.playbackError
import tech.soit.quiet.ext.toMediaSource
import tech.soit.quiet.service.ShimMusicSessionCallback
import tech.soit.quiet.utils.log

class MusicPlayerSessionImpl constructor(private val context: Context) : MusicPlayerSession.Stub(),
    CoroutineScope by MainScope() {

    companion object {
        private val audioAttribute = AudioAttributes.Builder()
            .setContentType(C.CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

    }

    // Wrap a SimpleExoPlayer with a decorator to handle audio focus for us.
    private val player: ExoPlayer by lazy {
        ExoPlayerFactory.newSimpleInstance(context).apply {
            setAudioAttributes(audioAttribute, true)
            addListener(ExoPlayerEventListener())
        }
    }

    @Suppress("JoinDeclarationAndAssignment")
    internal val servicePlugin: MusicPlayerServicePlugin

    private val shimSessionCallback = ShimMusicSessionCallback()

    private var playMode: Int = -1

    private lateinit var playQueue: PlayQueue

    var metadata: MusicMetadata? = null
        private set

    private var next: MusicMetadata? = null
    private var previous: MusicMetadata? = null


    private suspend fun performPlay(metadata: MusicMetadata?) {
        this.metadata = metadata
        if (metadata == null) {
            player.stop()
            return
        }
        player.prepare(metadata.toMediaSource(context, servicePlugin))
        player.playWhenReady = true

        previous = servicePlugin.getNextMusic(playQueue, metadata, playMode)
        next = servicePlugin.getPreviousMusic(playQueue, metadata, playMode)
        invalidateMetadata()
    }


    override fun skipToNext() {
        skipTo { it.getNext(metadata, PlayMode.Sequence) }
    }


    override fun skipToPrevious() {
        skipTo { it.getPrevious(metadata, PlayMode.Sequence) }
    }

    private fun skipTo(call: suspend (PlayQueue) -> MusicMetadata?) {
        player.stop()
        val queue = playQueue
        launch {
            val next = runCatching { call(queue) }.getOrNull()
            performPlay(next)
        }
    }

    override fun play() {
        player.playWhenReady = true
    }


    override fun pause() {
        player.playWhenReady = false
    }

    override fun setPlayQueue(queue: PlayQueue) {
        playQueue = queue
        shimSessionCallback.onPlayQueueChanged(queue)
    }

    override fun seekTo(pos: Long) {
        player.seekTo(pos)
    }


    override fun removeCallback(callback: MusicSessionCallback) {
        shimSessionCallback.removeCallback(callback)
    }

    override fun stop() {
        player.stop()
    }

    override fun addCallback(callback: MusicSessionCallback) {
        shimSessionCallback.addCallback(callback)
    }

    override fun playFromMediaId(mediaId: String) {
        skipTo { it.getByMediaId(mediaId)/* TODO 向 service plugin 继续请求*/ }
    }


    override fun setPlayMode(playMode: Int) {
        this.playMode = playMode
        shimSessionCallback.onPlayModeChanged(playMode)
    }

    private var playbackStateBackup: PlaybackState =
        PlaybackState(State.None, 0, 0, 1F, null, System.currentTimeMillis())


    val playbackState get() = playbackStateBackup

    private fun invalidatePlaybackState() {
        val playerError = player.playbackError()
        val state = playerError?.let { State.Error } ?: player.mapPlaybackState()
        val playbackState = PlaybackState(
            state = state,
            position = player.currentPosition,
            bufferedPosition = player.bufferedPosition,
            speed = player.playbackParameters.speed,
            error = playerError,
            updateTime = System.currentTimeMillis()
        )
        log { playbackState }
        this.playbackStateBackup = playbackState
        shimSessionCallback.onPlaybackStateChanged(playbackState)
    }

    private fun invalidateMetadata() {
        shimSessionCallback.onMetadataChanged(metadata, previous, next)
    }


    override fun destroy() {
        cancel()
    }


    private inner class ExoPlayerEventListener : Player.EventListener {

        override fun onLoadingChanged(isLoading: Boolean) {
        }

        override fun onPlayerError(error: ExoPlaybackException?) {
            invalidatePlaybackState()
        }

        override fun onPositionDiscontinuity(reason: Int) {
            invalidatePlaybackState()
        }

        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackStateInt: Int) {
            invalidatePlaybackState()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters?) {
            invalidatePlaybackState()
        }
    }


    init {
        servicePlugin = MusicPlayerServicePlugin.startServiceIsolate(context, this)
    }

}
