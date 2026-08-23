package dev.erkut.babamradyo

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Ekran kapaliyken de calmayi surdurmek ve bildirim/kilit ekrani
 * kontrollerini gostermek icin gereken servis.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        session = MediaSession.Builder(this, PlayerCore.get(this))
            .setSessionActivity(openApp)
            .build()

        // Kritik: onGetSession() oturumu yalnizca *baglanan bir controller'a*
        // verir. Arayuz ExoPlayer'i dogrudan kullandigi ve hicbir zaman
        // MediaController baglamadigi icin oturum servise hic kaydolmuyordu;
        // bu yuzden ne medya bildirimi postalaniyor ne de servis on plana
        // geciyordu (yani kilit ekrani kontrolleri ve ekran kapaliyken
        // calma calismiyordu). addSession() bunu acikca kaydeder.
        session?.let { addSession(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Kullanici uygulamayi son gorevlerden atarsa: calmiyorsa tamamen kapan. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.let { removeSession(it) }
        session?.run {
            player.release()
            release()
        }
        session = null
        PlayerCore.release()
        super.onDestroy()
    }
}
