package me.echeung.moemoekyun.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.RatingCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import java.text.ParseException
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import me.echeung.moemoekyun.BuildConfig
import me.echeung.moemoekyun.R
import me.echeung.moemoekyun.client.RadioClient
import me.echeung.moemoekyun.client.api.library.Jpop
import me.echeung.moemoekyun.client.api.library.Kpop
import me.echeung.moemoekyun.client.api.socket.Socket
import me.echeung.moemoekyun.client.api.socket.response.UpdateResponse
import me.echeung.moemoekyun.client.auth.AuthUtil
import me.echeung.moemoekyun.client.stream.Stream
import me.echeung.moemoekyun.service.notification.MusicNotifier
import me.echeung.moemoekyun.ui.activity.auth.AuthActivityUtil
import me.echeung.moemoekyun.util.AlbumArtUtil
import me.echeung.moemoekyun.util.PreferenceUtil
import me.echeung.moemoekyun.util.SongActionsUtil
import me.echeung.moemoekyun.util.ext.connectivityManager
import me.echeung.moemoekyun.util.ext.launchIO
import me.echeung.moemoekyun.util.ext.launchUI
import me.echeung.moemoekyun.util.ext.toast
import me.echeung.moemoekyun.util.system.TimeUtil
import me.echeung.moemoekyun.viewmodel.RadioViewModel
import org.koin.android.ext.android.inject

@OptIn(ExperimentalCoroutinesApi::class)
class RadioService : Service() {

    private val scope = CoroutineScope(Job() + Dispatchers.Main)

    private val radioClient: RadioClient by inject()
    private val stream: Stream by inject()
    private val socket: Socket by inject()

    private val albumArtUtil: AlbumArtUtil by inject()
    private val authUtil: AuthUtil by inject()
    private val preferenceUtil: PreferenceUtil by inject()

    private val radioViewModel: RadioViewModel by inject()

    private val binder = ServiceBinder()

    private var notifier = MusicNotifier(this, albumArtUtil)

    @Volatile
    var mediaSession: MediaSessionCompat? = null
        private set
    private val mediaSessionLock = Any()

    private var intentReceiver: BroadcastReceiver? = null
    private var receiverRegistered = false

    val isStreamStarted: Boolean
        get() = stream.isStarted

    val isPlaying: Boolean
        get() = stream.isPlaying

    override fun onBind(intent: Intent): IBinder? = binder

    override fun onCreate() {
        initBroadcastReceiver()
        initNetworkStateCallback()
        initMediaSession()

        merge(preferenceUtil.shouldPreferRomaji().asFlow(), preferenceUtil.shouldShowLockscreenAlbumArt().asFlow())
            .onEach { updateMediaSession() }
            .launchIn(scope)

        albumArtUtil.channel.asFlow()
            .onEach { updateMediaSession() }
            .launchIn(scope)

        stream.channel.asFlow()
            .onEach {
                when (it) {
                    Stream.State.PLAY -> {
                        radioViewModel.isPlaying = true

                        updateNotification()
                    }
                    Stream.State.PAUSE -> {
                        radioViewModel.isPlaying = false

                        updateNotification()
                    }
                    Stream.State.STOP -> {
                        stopForeground(true)
                        stopSelf()

                        preferenceUtil.sleepTimer().delete()
                        radioViewModel.isPlaying = false
                    }
                }

                updateMediaSessionPlaybackState()
            }
            .launchIn(scope)

        socket.channel.asFlow()
            .onEach {
                when (it) {
                    is Socket.SocketResponse -> it.info?.let { data -> onSocketReceive(data) }
                    is Socket.SocketError -> onSocketFailure()
                }
            }
            .launchIn(scope)

        socket.connect()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startID: Int): Int {
        handleIntent(intent)

        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent) {
        if (!isPlaying) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        stream.stop()

        socket.disconnect()

        if (receiverRegistered) {
            unregisterReceiver(intentReceiver)
            receiverRegistered = false
        }

        destroyMediaSession()

        super.onDestroy()
    }

    private fun onSocketReceive(info: UpdateResponse.Details?) {
        radioViewModel.listeners = info!!.listeners
        radioViewModel.setRequester(info.requester)
        radioViewModel.event = info.event

        // TODO: get queue info
//        radioViewModel.queueSize = info.queue.inQueue
//        radioViewModel.inQueueByUser = info.queue.inQueueByUser
//        radioViewModel.queuePosition = info.queue.inQueueBeforeUser

        var startTime: Calendar? = null
        try {
            startTime = TimeUtil.toCalendar(info.startTime!!)
        } catch (e: ParseException) {
            Log.e(TAG, e.message ?: "Error parsing time")
        }

        // Check if current song is favorited
        if (info.song != null && authUtil.isAuthenticated) {
            launchIO {
                val favoritedSongIds = radioClient.api.isFavorite(listOf(info.song.id))
                if (info.song.id in favoritedSongIds && radioViewModel.currentSong?.id == info.song.id) {
                    radioViewModel.isFavorited = true
                }
            }
        }

        radioViewModel.setCurrentSong(info.song, startTime)
        radioViewModel.lastSong = info.lastPlayed!![0]
        radioViewModel.secondLastSong = info.lastPlayed[1]

        albumArtUtil.updateAlbumArt(info.song)

        updateMediaSession()
        updateNotification()
    }

    private fun onSocketFailure() {
        radioViewModel.reset()
        updateNotification()
    }

    private fun updateMediaSession() {
        val currentSong = radioViewModel.currentSong

        if (currentSong == null) {
            mediaSession!!.setMetadata(null)
            return
        }

        val metaData = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentSong.titleString)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentSong.artistsString)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentSong.albumsString)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, (currentSong.duration * MILLISECONDS_IN_SECOND).toLong())

        if (preferenceUtil.shouldShowLockscreenAlbumArt().get()) {
            val albumArt = albumArtUtil.currentAlbumArt
            if (albumArt != null && !albumArtUtil.isDefaultAlbumArt) {
                metaData.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
                updateNotification()
            }
        }

        synchronized(mediaSessionLock) {
            mediaSession!!.setMetadata(metaData.build())
            updateMediaSessionPlaybackState()
        }
    }

    private fun updateMediaSessionPlaybackState() {
        // Play/pause state
        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(MEDIA_SESSION_ACTIONS)
            .setState(
                if (isStreamStarted) {
                    if (isPlaying) {
                        PlaybackStateCompat.STATE_PLAYING
                    } else {
                        PlaybackStateCompat.STATE_PAUSED
                    }
                } else {
                    PlaybackStateCompat.STATE_STOPPED
                },
                radioViewModel.currentSongProgress,
                1f
            )

        // Favorite action
        if (authUtil.isAuthenticated) {
            val currentSong = radioViewModel.currentSong
            val favoriteIcon = if (currentSong == null || !currentSong.favorite) {
                R.drawable.ic_star_border_24dp
            } else {
                R.drawable.ic_star_24dp
            }

            stateBuilder.addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(
                    TOGGLE_FAVORITE, getString(R.string.favorite), favoriteIcon
                )
                    .build()
            )
        }

        if (mediaSession!!.isActive) {
            mediaSession!!.setPlaybackState(stateBuilder.build())
        }
    }

    private fun updateNotification() {
        if (isStreamStarted) {
            notifier.update(radioViewModel.currentSong, authUtil.isAuthenticated)
        } else {
            stopForeground(true)
        }
    }

    private fun handleIntent(intent: Intent?): Boolean {
        if (intent == null) return true

        when (intent.action) {
            PLAY_PAUSE -> stream.toggle()

            STOP -> stream.stop()

            TOGGLE_FAVORITE -> favoriteCurrentSong()

            UPDATE, SongActionsUtil.REQUEST_EVENT -> socket.update()

            TIMER_STOP -> stream.fadeOut()

            // Pause when headphones unplugged
            AudioManager.ACTION_AUDIO_BECOMING_NOISY -> if (preferenceUtil.shouldPauseOnNoisy()) {
                stream.pause()
            }

            // Headphone media button action
            Intent.ACTION_MEDIA_BUTTON -> {
                val extras = intent.extras ?: return false

                val keyEvent = extras.get(Intent.EXTRA_KEY_EVENT) as KeyEvent?
                if (keyEvent == null || keyEvent.action != KeyEvent.ACTION_DOWN) {
                    return false
                }

                when (keyEvent.keyCode) {
                    KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> stream.toggle()
                    KeyEvent.KEYCODE_MEDIA_PLAY -> stream.play()
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> stream.pause()
                    KeyEvent.KEYCODE_MEDIA_STOP -> stream.stop()
                    KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        // Do nothing
                    }
                }
            }

            AuthActivityUtil.AUTH_EVENT -> {
                socket.reconnect()
                if (!authUtil.isAuthenticated) {
                    radioViewModel.isFavorited = false
                    updateNotification()
                }
            }
        }

        updateNotification()
        return true
    }

    private fun initMediaSession() {
        synchronized(mediaSessionLock) {
            mediaSession = MediaSessionCompat(this, APP_PACKAGE_NAME, null, null)
            mediaSession!!.setRatingType(RatingCompat.RATING_HEART)
            mediaSession!!.setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    stream.play()
                }

                override fun onPause() {
                    stream.pause()
                }

                override fun onStop() {
                    stream.stop()
                }

                override fun onSkipToNext() {}

                override fun onSkipToPrevious() {}

                override fun onSeekTo(pos: Long) {}

                override fun onSetRating(rating: RatingCompat?) {
                    favoriteCurrentSong()
                }

                override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                    return handleIntent(mediaButtonEvent)
                }

                override fun onCustomAction(action: String?, extras: Bundle?) {
                    when (action) {
                        TOGGLE_FAVORITE -> {
                            favoriteCurrentSong()
                            updateMediaSessionPlaybackState()
                        }

                        else -> Log.d(TAG, "Unsupported action: " + action!!)
                    }
                }

                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    if (!query.isNullOrEmpty()) {
                        when (query.toLowerCase()) {
                            "jpop", "j-pop" -> onPlayFromMediaId(LIBRARY_JPOP, extras)
                            "kpop", "k-pop" -> onPlayFromMediaId(LIBRARY_KPOP, extras)
                        }
                    }

                    if (!isPlaying) {
                        stream.play()
                    }
                }

                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    super.onPlayFromMediaId(mediaId, extras)

                    // Handles changing library mode via Android Auto
                    when (mediaId) {
                        LIBRARY_JPOP -> radioClient.changeLibrary(Jpop.NAME)

                        LIBRARY_KPOP -> radioClient.changeLibrary(Kpop.NAME)
                    }
                }
            })

            mediaSession!!.isActive = true
        }
    }

    private fun destroyMediaSession() {
        synchronized(mediaSessionLock) {
            mediaSession?.isActive = false
            mediaSession?.release()
        }
    }

    private fun initBroadcastReceiver() {
        intentReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                handleIntent(intent)
            }
        }

        val intentFilter = IntentFilter().apply {
            addAction(PLAY_PAUSE)
            addAction(STOP)
            addAction(TOGGLE_FAVORITE)
            addAction(UPDATE)
            addAction(SongActionsUtil.REQUEST_EVENT)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(Intent.ACTION_MEDIA_BUTTON)
            addAction(AuthActivityUtil.AUTH_EVENT)
        }

        registerReceiver(intentReceiver, intentFilter)
        receiverRegistered = true
    }

    private fun initNetworkStateCallback() {
        val builder = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)

        val callback: ConnectivityManager.NetworkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                socket.reconnect()
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                socket.disconnect()
            }
        }

        connectivityManager.registerNetworkCallback(builder.build(), callback)
    }

    private fun favoriteCurrentSong() {
        val song = radioViewModel.currentSong ?: return

        val songId = song.id
        if (songId == -1) return

        if (!authUtil.isAuthenticated) {
            toast(R.string.login_required)
            return
        }

        val isCurrentlyFavorite = song.favorite

        launchIO {
            try {
                radioClient.api.toggleFavorite(songId)

                val currentSong = radioViewModel.currentSong
                if (currentSong!!.id == songId) {
                    radioViewModel.isFavorited = !isCurrentlyFavorite
                }
                song.favorite = !isCurrentlyFavorite

                launchUI {
                    val favIntent = Intent(SongActionsUtil.FAVORITE_EVENT)
                    sendBroadcast(favIntent)

                    updateNotification()
                    updateMediaSessionPlaybackState()
                }
            } catch (e: Exception) {
                launchUI { toast(e.message) }
            }
        }
    }

    inner class ServiceBinder : Binder() {
        val service: RadioService
            get() = this@RadioService
    }

    companion object {
        private val TAG = RadioService::class.java.simpleName

        private const val APP_PACKAGE_NAME = BuildConfig.APPLICATION_ID
        private const val MILLISECONDS_IN_SECOND = 1000

        const val PLAY_PAUSE = "$APP_PACKAGE_NAME.play_pause"
        const val STOP = "$APP_PACKAGE_NAME.stop"
        const val TOGGLE_FAVORITE = "$APP_PACKAGE_NAME.toggle_favorite"
        const val LIBRARY_JPOP = "$APP_PACKAGE_NAME.library_jpop"
        const val LIBRARY_KPOP = "$APP_PACKAGE_NAME.library_kpop"
        const val UPDATE = "$APP_PACKAGE_NAME.update"
        const val TIMER_STOP = "$APP_PACKAGE_NAME.timer_stop"

        private const val MEDIA_SESSION_ACTIONS = (
            PlaybackStateCompat.ACTION_PLAY
                or PlaybackStateCompat.ACTION_PAUSE
                or PlaybackStateCompat.ACTION_PLAY_PAUSE
                or PlaybackStateCompat.ACTION_STOP
                or PlaybackStateCompat.ACTION_SET_RATING
            )
    }
}
