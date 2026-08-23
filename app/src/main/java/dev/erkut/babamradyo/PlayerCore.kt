package dev.erkut.babamradyo

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Tek bir ExoPlayer ornegi. Hem ekranlar hem de bildirim/kilit ekrani
 * kontrollerini saglayan [PlaybackService] ayni ornegi kullanir.
 */
object PlayerCore {

    /**
     * Yeniden baglanma denemesi ust siniri. Backoff 20 saniyede sabitlendigi
     * icin bu ~7 dakikalik bir kesintiyi tolere eder. Ayrica ag geri gelir
     * gelmez [networkCallback] beklemeden yeniden dener.
     */
    private const val MAX_RETRY = 30
    private const val MAX_BACKOFF_MS = 20_000L

    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Kullanici calmayi istiyor mu? Bildirimdeki duraklat dugmesi ExoPlayer'a
     * dogrudan gittigi icin bu bayrak dinleyiciden guncellenir; boylece
     * "kullanici duraklatti" ile "yayin koptu" ayirt edilebilir.
     */
    private var wantsToPlay = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Ag dinleyicisini kaldirabilmek icin saklanan uygulama baglami. */
    private var appContext: Context? = null

    /** Calan kuyruk; parcanin canli yayin olup olmadigini bilmek icin tutulur. */
    private var queue: List<Track> = emptyList()

    private var retryCount = 0
    private var pendingRetry: Runnable? = null

    /** Arayuze gosterilecek hata/durum mesajlari. */
    var onMessage: ((String) -> Unit)? = null

    fun get(context: Context): ExoPlayer {
        player?.let { return it }
        val app = context.applicationContext
        appContext = app
        return ExoPlayer.Builder(app)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)   // kulaklik cikinca duraklat
            .build()
            .also {
                player = it
                it.addListener(RecoveryListener(app))
            }
    }

    // ------------------------------------------------------------- durum

    fun currentId(): String? = player?.currentMediaItem?.mediaId

    fun isPlaying(): Boolean = player?.isPlaying == true

    fun currentTrack(): Track? {
        val exo = player ?: return null
        val i = exo.currentMediaItemIndex
        return queue.getOrNull(i)
    }

    fun hasNext(): Boolean = player?.hasNextMediaItem() == true
    fun hasPrevious(): Boolean = player?.hasPreviousMediaItem() == true

    // ---------------------------------------------------------- oynatma

    /**
     * [list] listesini kuyruga alir ve [index] numarali parcadan baslatir.
     * Boylece bir albümün icinde "siradaki sarki" kendiliginden calar.
     */
    fun play(context: Context, list: List<Track>, index: Int) {
        if (list.isEmpty()) return
        val exo = get(context)
        startPlaybackService(context)
        registerNetworkWatcher(context)

        cancelRetry()
        wantsToPlay = true
        queue = list
        exo.setMediaItems(list.map { it.toMediaItem() }, index, C.TIME_UNSET)
        exo.repeatMode = Player.REPEAT_MODE_OFF
        exo.prepare()
        exo.playWhenReady = true

        list.getOrNull(index)?.let { Prefs.saveLast(context, it) }
    }

    fun togglePlayPause(context: Context) {
        val exo = get(context)
        if (exo.isPlaying) {
            exo.pause()
        } else {
            startPlaybackService(context)
            registerNetworkWatcher(context)
            cancelRetry()
            wantsToPlay = true
            if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
            exo.play()
        }
    }

    fun next(context: Context) {
        val exo = get(context)
        if (exo.hasNextMediaItem()) {
            exo.seekToNextMediaItem()
            exo.play()
        }
    }

    fun previous(context: Context) {
        val exo = get(context)
        // Parcanin basindan sonraysak once basa sar - alisildik davranis.
        if (exo.currentPosition > 3000 && exo.duration > 0) {
            exo.seekTo(0)
        } else if (exo.hasPreviousMediaItem()) {
            exo.seekToPreviousMediaItem()
            exo.play()
        } else {
            exo.seekTo(0)
        }
    }

    /**
     * Uygulama yeniden acildiginda en son dinlenen parcayi oynaticiya
     * yerlestirir; kendiliginden calmaz, sadece "devam et" icin hazir durur.
     */
    fun restoreLast(context: Context) {
        val exo = get(context)
        if (exo.currentMediaItem != null) return
        val last = Prefs.lastTrack(context) ?: return
        queue = listOf(last)
        // prepare() cagrilmaz: baglanti ancak kullanici cal'a basinca kurulur.
        exo.setMediaItems(listOf(last.toMediaItem()), 0, C.TIME_UNSET)
    }

    fun stop() {
        cancelRetry()
        queue = emptyList()
        player?.let {
            it.stop()
            it.clearMediaItems()
        }
    }

    fun release() {
        cancelRetry()
        wantsToPlay = false
        appContext?.let { unregisterNetworkWatcher(it) }
        player?.release()
        player = null
    }

    // ------------------------------------------- kopan yayini geri baglama

    /**
     * Canli yayin kopunca ExoPlayer durur ve kendiliginden geri gelmez.
     * Artan bekleme sureleriyle yeniden baglanmayi dener.
     */
    private class RecoveryListener(private val app: Context) : Player.Listener {

        override fun onPlayerError(error: PlaybackException) {
            // Kullanici zaten duraklattiysa geri baglanmaya calisma.
            if (!wantsToPlay) return

            val track = currentTrack()
            if (retryCount >= MAX_RETRY) {
                onMessage?.invoke(
                    app.getString(R.string.stream_failed, track?.title ?: "")
                )
                retryCount = 0
                return
            }
            retryCount++
            val delayMs = minOf(2000L * retryCount, MAX_BACKOFF_MS)
            // Her denemede bildirim yagmuru olmasin; sadece ilkinde uyar.
            if (retryCount == 1) onMessage?.invoke(app.getString(R.string.reconnecting))

            scheduleRetry(delayMs)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            wantsToPlay = playWhenReady
            if (!playWhenReady) cancelRetry()
        }

        override fun onPlaybackStateChanged(state: Int) {
            // Yayin geri geldi; sayaci sifirla.
            if (state == Player.STATE_READY) retryCount = 0
        }

        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            retryCount = 0
            currentTrack()?.let { Prefs.saveLast(app, it) }
        }
    }

    private fun scheduleRetry(delayMs: Long) {
        pendingRetry?.let { handler.removeCallbacks(it) }
        pendingRetry = Runnable {
            player?.let {
                it.prepare()
                it.play()
            }
        }.also { handler.postDelayed(it, delayMs) }
    }

    /**
     * Ag geri gelir gelmez beklemeden yeniden baglan. Backoff tek basina
     * yetmiyor: uzun bir kesintide denemeler tukenip muzik geri gelmiyordu.
     */
    private fun registerNetworkWatcher(context: Context) {
        if (networkCallback != null) return
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handler.post {
                    val exo = player ?: return@post
                    if (!wantsToPlay) return@post
                    if (exo.playbackState == Player.STATE_IDLE || exo.playerError != null) {
                        retryCount = 0
                        scheduleRetry(500L)
                    }
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(networkCallback!!)
        } catch (e: Exception) {
            networkCallback = null
        }
    }

    private fun unregisterNetworkWatcher(context: Context) {
        val cb = networkCallback ?: return
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        try {
            cm?.unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            // zaten kayitli degil
        }
        networkCallback = null
    }

    private fun cancelRetry() {
        pendingRetry?.let { handler.removeCallbacks(it) }
        pendingRetry = null
        retryCount = 0
    }

    // ------------------------------------------------------------ yardimci

    private fun startPlaybackService(context: Context) {
        context.applicationContext.startService(
            Intent(context.applicationContext, PlaybackService::class.java)
        )
    }

    private fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setUri(url)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(subtitle)
                .setIsPlayable(true)
                .apply {
                    // Bildirim ve kilit ekraninda kapak gorseli cikmasi icin.
                    if (artworkUrl.isNotBlank()) setArtworkUri(Uri.parse(artworkUrl))
                }
                .build()
        )
        .build()
}
