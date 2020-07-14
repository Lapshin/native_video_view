package cl.ceisufro.native_video_view

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.SurfaceView
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import cl.ceisufro.native_video_view.NativeVideoViewPlugin.Companion.CREATED
import cl.ceisufro.native_video_view.NativeVideoViewPlugin.Companion.DESTROYED
import cl.ceisufro.native_video_view.NativeVideoViewPlugin.Companion.PAUSED
import cl.ceisufro.native_video_view.NativeVideoViewPlugin.Companion.RESUMED
import cl.ceisufro.native_video_view.NativeVideoViewPlugin.Companion.STARTED
import cl.ceisufro.native_video_view.NativeVideoViewPlugin.Companion.STOPPED
import com.google.android.exoplayer2.ExoPlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import io.flutter.plugin.platform.PlatformView
import java.util.*
import java.util.concurrent.atomic.AtomicInteger


class ExoPlayerController(private val id: Int,
                          activityState: AtomicInteger,
                          private val registrar: PluginRegistry.Registrar)
    : Application.ActivityLifecycleCallbacks,
        MethodChannel.MethodCallHandler,
        PlatformView,
        Player.EventListener {
    private val methodChannel: MethodChannel = MethodChannel(registrar.messenger(), "native_video_view_$id")
    private val registrarActivityHashCode: Int
    private val constraintLayout: ConstraintLayout
    private val surfaceView: SurfaceView
    private val exoPlayer: SimpleExoPlayer
    private var dataSource: String? = null
    private var disposed: Boolean = false
    private var configured: Boolean = false
    private var playerState: PlayerState = PlayerState.NOT_INITIALIZED
    private var currentPosition: Long = 0

    init {
        this.methodChannel.setMethodCallHandler(this)
        this.registrarActivityHashCode = registrar.activity().hashCode()
        this.constraintLayout = LayoutInflater.from(registrar.activity())
                .inflate(R.layout.exoplayer_layout, null) as ConstraintLayout
        this.surfaceView = constraintLayout.findViewById(R.id.exo_player_surface_view)
        val trackSelector = DefaultTrackSelector(registrar.activity())
        this.exoPlayer = SimpleExoPlayer.Builder(registrar.activity())
                .setTrackSelector(trackSelector)
                .build()
        when (activityState.get()) {
            STOPPED -> {
                stopPlayback()
            }
            PAUSED -> {
                pausePlayback()
            }
            RESUMED -> {
                // Not implemented
            }
            STARTED -> {
                // Not implemented
            }
            CREATED -> {
                configurePlayer()
            }
            DESTROYED -> {
                // Not implemented
            }
            else -> throw IllegalArgumentException(
                    "Cannot interpret " + activityState.get() + " as an activity state")
        }
        registrar.activity().application.registerActivityLifecycleCallbacks(this)
    }

    override fun getView(): View {
        return constraintLayout
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        methodChannel.setMethodCallHandler(null)
        this.destroyVideoView()
        registrar.activity().application.unregisterActivityLifecycleCallbacks(this)
        Log.d("VIDEO. NVV", "Disposed view $id")
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "player#setVideoSource" -> {
                val videoPath: String? = call.argument("videoSource")
                val sourceType: String? = call.argument("sourceType")
                if (videoPath != null) {
                    if (sourceType.equals("VideoSourceType.asset")
                            || sourceType.equals("VideoSourceType.file")) {
                        initVideo("file://$videoPath")
                    } else {
                        initVideo(videoPath)
                    }
                }
                result.success(null)
            }
            "player#start" -> {
                startPlayback()
                result.success(null)
            }
            "player#pause" -> {
                pausePlayback()
                result.success(null)
            }
            "player#stop" -> {
                stopPlayback()
                result.success(null)
            }
            "player#currentPosition" -> {
                val arguments = HashMap<String, Any>()
                arguments["currentPosition"] = exoPlayer.currentPosition
                result.success(arguments)
            }
            "player#isPlaying" -> {
                val arguments = HashMap<String, Any>()
                arguments["isPlaying"] = exoPlayer.isPlaying
                result.success(arguments)
            }
            "player#seekTo" -> {
                val position: Int? = call.argument("position")
                if (position != null)
                    exoPlayer.seekTo(position.toLong())
                result.success(null)
            }
        }
    }

    override fun onActivityCreated(activity: Activity?, p1: Bundle?) {
        this.configurePlayer()
    }

    override fun onActivityStarted(activity: Activity?) {
        // Not implemented
    }

    override fun onActivityResumed(activity: Activity?) {
        // Not implemented
    }

    override fun onActivityPaused(activity: Activity?) {
        if (disposed || activity.hashCode() != registrarActivityHashCode) return
        this.pausePlayback()
    }

    override fun onActivityStopped(activity: Activity?) {
        if (disposed || activity.hashCode() != registrarActivityHashCode) return
        this.stopPlayback()
    }

    override fun onActivityDestroyed(activity: Activity?) {
        if (disposed || activity.hashCode() != registrarActivityHashCode) return
        this.destroyVideoView()
    }

    override fun onActivitySaveInstanceState(activity: Activity?, p1: Bundle?) {
        // Not implemented
    }

    private fun configurePlayer() {
        try {
            exoPlayer.addListener(this)
            exoPlayer.setVideoSurfaceView(surfaceView)
            configured = true
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    private fun initVideo(dataSource: String?) {
        if (!configured) this.configurePlayer()
        if (dataSource != null) {
            val uri = Uri.parse(dataSource)
            val dataSourceFactory = getDataSourceFactory(uri)
            val mediaSource = ProgressiveMediaSource
                    .Factory(dataSourceFactory, DefaultExtractorsFactory())
                    .createMediaSource(uri)
            this.exoPlayer.playWhenReady = true
            this.exoPlayer.prepare(mediaSource)
            this.dataSource = dataSource
        }
    }

    private fun getDataSourceFactory(uri: Uri): DataSource.Factory {
        val scheme: String? = uri.scheme
        return if (scheme != null && (scheme == "http" || scheme == "https")) {
            DefaultHttpDataSourceFactory(
                    "ExoPlayer",
                    null,
                    DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS,
                    DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS,
                    true)
        } else {
            DefaultDataSourceFactory(registrar.activity(), "ExoPlayer")
        }
    }

    private fun startPlayback() {
        if (playerState != PlayerState.PLAYING && dataSource != null) {
            if (playerState != PlayerState.NOT_INITIALIZED) {
                exoPlayer.playWhenReady = true
                playerState = PlayerState.PLAYING
            } else {
                playerState = PlayerState.PLAY_WHEN_READY
                initVideo(dataSource)
            }
        }
    }

    private fun pausePlayback() {
        exoPlayer.stop()
        playerState = PlayerState.PAUSED
    }

    private fun stopPlayback() {
        exoPlayer.stop(true)
        playerState = PlayerState.NOT_INITIALIZED
    }

    private fun destroyVideoView() {
        exoPlayer.stop(true)
        exoPlayer.removeListener(this)
        exoPlayer.release()
        configured = false
    }

    private fun notifyPlayerPrepared() {
        val arguments = HashMap<String, Any>()
        val videoFormat = exoPlayer.videoFormat
        if (videoFormat != null) {
            arguments["height"] = videoFormat.height
            arguments["width"] = videoFormat.width
            arguments["duration"] = exoPlayer.duration
        }
        playerState = PlayerState.PREPARED
        methodChannel.invokeMethod("player#onPrepared", arguments)
    }

    override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
//            currentPosition = exoPlayer.getDuration() - 100
            Log.d("EXOPLAYER", "AAAAAAAAa ${exoPlayer.getCurrentPosition()} ${exoPlayer.getDuration()}")
            initVideo(this.dataSource)
//            startPlayback()
//            exoPlayer.seekTo(currentPosition)
            methodChannel.invokeMethod("player#onCompletion", null)
        } else if (playbackState == Player.STATE_READY) {
            Log.d("EXOPLAYER", "Iam ready ${playerState} ${PlayerState.PLAY_WHEN_READY} ${currentPosition} ${exoPlayer.getCurrentPosition()} ${exoPlayer.getDuration()}")
            if (playerState == PlayerState.PLAY_WHEN_READY)
                this.startPlayback()
            else
                notifyPlayerPrepared()
        }
    }

    override fun onPlayerError(error: ExoPlaybackException) {
        dataSource = null
        playerState = PlayerState.NOT_INITIALIZED
        val arguments = HashMap<String, Any>()
        arguments["what"] = error.type
        arguments["extra"] = error.message ?: ""
        methodChannel.invokeMethod("player#onError", arguments)
    }
}