package dev.erkut.babamradyo

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Uyku zamanlayicisi: sure dolunca calmayi duraklatir.
 * Gece radyo dinlerken uyuyakalinca telefonun sabaha kadar calmamasi icin.
 */
object SleepTimer {

    private val handler = Handler(Looper.getMainLooper())
    private var endsAtMs = 0L
    private var task: Runnable? = null

    /** Zamanlayici calisiyorsa kalan sure (ms), yoksa 0. */
    fun remainingMs(): Long =
        if (endsAtMs == 0L) 0L else maxOf(0L, endsAtMs - SystemClock.elapsedRealtime())

    val isRunning: Boolean get() = remainingMs() > 0

    fun start(context: Context, minutes: Int, onFinished: () -> Unit) {
        cancel()
        val app = context.applicationContext
        endsAtMs = SystemClock.elapsedRealtime() + minutes * 60_000L
        task = Runnable {
            PlayerCore.get(app).pause()
            endsAtMs = 0L
            task = null
            onFinished()
        }.also { handler.postDelayed(it, minutes * 60_000L) }
    }

    fun cancel() {
        task?.let { handler.removeCallbacks(it) }
        task = null
        endsAtMs = 0L
    }

    /** "23 dakika" gibi okunabilir kalan sure. */
    fun remainingText(): String {
        val mins = (remainingMs() + 59_999L) / 60_000L
        return "$mins dakika"
    }
}
