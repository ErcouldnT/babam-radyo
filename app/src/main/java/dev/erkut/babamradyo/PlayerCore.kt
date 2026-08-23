package dev.erkut.babamradyo

import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Tek bir ExoPlayer ornegi. Hem ekranlar hem de bildirim/kilit ekrani
 * kontrollerini saglayan [PlaybackService] ayni ornegi kullanir.
 */
object PlayerCore {

    private var player: ExoPlayer? = null

    fun get(context: Context): ExoPlayer {
        player?.let { return it }
        val app = context.applicationContext
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
            .also { player = it }
    }

    /** Su an calan parcanin kimligi (Track.id), yoksa null. */
    fun currentId(): String? = player?.currentMediaItem?.mediaId

    fun isPlaying(): Boolean = player?.isPlaying == true

    /**
     * [list] listesini kuyruga alir ve [index] numarali parcadan baslatir.
     * Boylece bir albümün icinde "siradaki sarki" kendiliginden calar.
     */
    fun play(context: Context, list: List<Track>, index: Int) {
        if (list.isEmpty()) return
        val exo = get(context)

        // Servisi ayaga kaldir: bildirim ve arka planda calma bunun sayesinde calisir.
        context.applicationContext.startService(
            Intent(context.applicationContext, PlaybackService::class.java)
        )

        exo.setMediaItems(list.map { it.toMediaItem() }, index, C.TIME_UNSET)
        exo.repeatMode = Player.REPEAT_MODE_OFF
        exo.prepare()
        exo.playWhenReady = true
    }

    fun togglePlayPause(context: Context) {
        val exo = get(context)
        if (exo.isPlaying) exo.pause()
        else {
            if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
            exo.play()
        }
    }

    fun stop() {
        player?.let {
            it.stop()
            it.clearMediaItems()
        }
    }

    fun release() {
        player?.release()
        player = null
    }

    private fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setUri(url)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(subtitle)
                .setIsPlayable(true)
                .build()
        )
        .build()
}
