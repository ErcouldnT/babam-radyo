package dev.erkut.babamradyo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Indirmeleri on plan servisinde yurutur.
 *
 * Daha once indirme, ekranin yasam dongusune bagliydi: kullanici uygulamadan
 * cikinca indirme sessizce iptal oluyordu. Artik servis calisir, bildirimde
 * ilerleme cubugu gosterir ve uygulama kapaliyken de surer.
 */
class DownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val SUMMARY_ID = 2000
        private const val DONE_ID_BASE = 3000

        const val ACTION_START = "dev.erkut.babamradyo.DOWNLOAD_START"
        const val ACTION_CANCEL = "dev.erkut.babamradyo.DOWNLOAD_CANCEL"
        const val EXTRA_TRACK_ID = "track_id"

        /** Suren indirmeler: track.id -> yuzde (0..100, -1 = boyut bilinmiyor). */
        private val progress = HashMap<String, Int>()

        /** Ekranlarin canli ilerlemeyi dinlemesi icin. */
        private val listeners = mutableSetOf<(String, Int) -> Unit>()

        fun addListener(l: (String, Int) -> Unit) { listeners.add(l) }
        fun removeListener(l: (String, Int) -> Unit) { listeners.remove(l) }

        fun activeProgress(): Map<String, Int> = HashMap(progress)
        fun isDownloading(id: String) = progress.containsKey(id)

        private fun notifyListeners(id: String, pct: Int) {
            listeners.toList().forEach { it(id, pct) }
        }

        fun enqueue(context: Context, track: Track) {
            if (progress.containsKey(track.id)) return
            val i = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra("id", track.id)
                putExtra("title", track.title)
                putExtra("subtitle", track.subtitle)
                putExtra("url", track.url)
                putExtra("duration", track.durationSec)
                putExtra("artwork", track.artworkUrl)
                putExtra("fileName", track.fileName)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        fun cancel(context: Context, trackId: String) {
            context.startService(
                Intent(context, DownloadService::class.java).apply {
                    action = ACTION_CANCEL
                    putExtra(EXTRA_TRACK_ID, trackId)
                }
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob())
    private val jobs = HashMap<String, Job>()
    private val titles = HashMap<String, String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                val id = intent.getStringExtra(EXTRA_TRACK_ID)
                if (id != null) cancelOne(id)
            }
            ACTION_START -> {
                val track = intent.toTrack()
                if (track != null) start(track) else stopIfIdle()
            }
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------- indirme

    private fun start(track: Track) {
        if (jobs.containsKey(track.id)) return

        progress[track.id] = 0
        titles[track.id] = track.title
        notifyListeners(track.id, 0)
        goForeground()

        jobs[track.id] = scope.launch {
            try {
                Downloads.download(applicationContext, track) { pct ->
                    progress[track.id] = pct
                    notifyListeners(track.id, pct)
                    updateNotification()
                }
                finish(track.id)
                showDone(track, getString(R.string.download_done))
            } catch (e: CancellationException) {
                finish(track.id)
            } catch (e: Exception) {
                finish(track.id)
                showDone(track, getString(R.string.download_failed))
            }
        }
    }

    private fun cancelOne(id: String) {
        jobs[id]?.cancel()
        finish(id)
    }

    private fun finish(id: String) {
        jobs.remove(id)
        progress.remove(id)
        titles.remove(id)
        notifyListeners(id, 100)
        if (jobs.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            updateNotification()
        }
    }

    private fun stopIfIdle() {
        if (jobs.isEmpty()) stopSelf()
    }

    // --------------------------------------------------------- bildirimler

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_downloads),
            NotificationManager.IMPORTANCE_LOW      // ses cikarmasin
        ).apply { setShowBadge(false) }
        (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
    }

    private fun buildNotification(): android.app.Notification {
        val count = jobs.size
        val onlyId = progress.keys.firstOrNull()
        val pct = onlyId?.let { progress[it] } ?: 0
        val title = if (count == 1) {
            titles[onlyId] ?: getString(R.string.downloading)
        } else {
            getString(R.string.downloading_count, count)
        }

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.downloading))
            .setContentText(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // Boyut bilinmiyorsa belirsiz cubuk goster.
        if (pct < 0) b.setProgress(0, 0, true)
        else b.setProgress(100, pct, false).setSubText("%$pct")

        // Tek indirme varsa bildirimden iptal edilebilsin.
        if (count == 1 && onlyId != null) {
            val cancelIntent = PendingIntent.getService(
                this, onlyId.hashCode(),
                Intent(this, DownloadService::class.java).apply {
                    action = ACTION_CANCEL
                    putExtra(EXTRA_TRACK_ID, onlyId)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(R.drawable.ic_close, getString(R.string.cancel_download), cancelIntent)
        }
        return b.build()
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(SUMMARY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(SUMMARY_ID, n)
        }
    }

    private var lastShownPct = -99
    private fun updateNotification() {
        val pct = progress.values.firstOrNull() ?: 0
        // Her yuzde degisiminde bildirim yenilemek pahali; %2'de bir yeter.
        if (jobs.size == 1 && kotlin.math.abs(pct - lastShownPct) < 2 && pct < 100) return
        lastShownPct = pct
        runCatching {
            NotificationManagerCompat.from(this).notify(SUMMARY_ID, buildNotification())
        }
    }

    private fun showDone(track: Track, text: String) {
        val openApp = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_check)
            .setContentTitle(track.title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(DONE_ID_BASE + (track.id.hashCode() and 0xFFF), n)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        progress.clear()
        super.onDestroy()
    }

    private fun Intent.toTrack(): Track? {
        val id = getStringExtra("id") ?: return null
        val url = getStringExtra("url") ?: return null
        return Track(
            id = id,
            title = getStringExtra("title").orEmpty(),
            subtitle = getStringExtra("subtitle").orEmpty(),
            url = url,
            kind = Track.Kind.ARCHIVE,
            durationSec = getIntExtra("duration", 0),
            fileName = getStringExtra("fileName").orEmpty(),
            artworkUrl = getStringExtra("artwork").orEmpty()
        )
    }
}
