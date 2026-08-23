package dev.erkut.babamradyo

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.SeekBar
import androidx.media3.common.Player
import dev.erkut.babamradyo.databinding.PlayerBarBinding

/**
 * Ekranin altindaki mini oynatici. Her iki ekran da bunu kullanir;
 * ayni [PlayerCore] ornegini dinledigi icin ekranlar arasi gecişte
 * calan parca kesintiye ugramaz.
 */
class PlayerBar(
    private val context: Context,
    private val b: PlayerBarBinding,
    /** Calan parca degistiginde listeyi tazelemek icin. */
    private val onTrackChanged: () -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private var userSeeking = false

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { render() }
        override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) {
            render(); onTrackChanged()
        }
        override fun onPlaybackStateChanged(state: Int) { render() }
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    fun attach() {
        PlayerCore.get(context).addListener(listener)

        b.btnPlayPause.setOnClickListener { PlayerCore.togglePlayPause(context) }
        b.btnPrev.setOnClickListener { PlayerCore.previous(context) }
        b.btnNext.setOnClickListener { PlayerCore.next(context) }

        b.sbPosition.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                val exo = PlayerCore.get(context)
                val dur = exo.duration
                if (dur > 0) exo.seekTo(dur * sb.progress / 1000)
            }
        })

        render()
        handler.post(ticker)
    }

    fun detach() {
        handler.removeCallbacks(ticker)
        PlayerCore.get(context).removeListener(listener)
    }

    fun render() {
        val exo = PlayerCore.get(context)
        val item = exo.currentMediaItem
        if (item == null) {
            b.root.visibility = View.GONE
            return
        }
        b.root.visibility = View.VISIBLE
        b.tvPlayerTitle.text = item.mediaMetadata.title ?: ""
        b.tvPlayerSubtitle.text = item.mediaMetadata.artist ?: ""
        b.tvPlayerTitle.isSelected = true   // marquee kaydirmayi baslat

        b.btnPlayPause.setImageResource(
            if (exo.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )

        // Kuyrugun basinda/sonunda ilgili dugmeyi soluklastir.
        b.btnPrev.isEnabled = true            // hep basa sarabilir
        b.btnNext.isEnabled = exo.hasNextMediaItem()
        b.btnNext.alpha = if (b.btnNext.isEnabled) 1f else 0.3f

        ImageLoader.load(
            b.ivPlayerArt,
            item.mediaMetadata.artworkUri?.toString().orEmpty(),
            R.drawable.ic_art_placeholder
        )

        updateProgress()
    }

    private fun updateProgress() {
        val exo = PlayerCore.get(context)
        if (exo.currentMediaItem == null) return

        val dur = exo.duration
        val live = dur <= 0 || dur == androidx.media3.common.C.TIME_UNSET

        if (live) {
            // Canli yayinda ilerleme cubugu anlamsiz.
            b.sbPosition.visibility = View.INVISIBLE
            b.tvPos.text = context.getString(R.string.live)
            b.tvDur.text = ""
        } else {
            b.sbPosition.visibility = View.VISIBLE
            b.sbPosition.max = 1000
            if (!userSeeking) {
                b.sbPosition.progress = (exo.currentPosition * 1000 / dur).toInt()
            }
            b.tvPos.text = fmt(exo.currentPosition)
            b.tvDur.text = fmt(dur)
        }
    }

    companion object {
        fun fmt(ms: Long): String {
            if (ms <= 0) return "0:00"
            val total = ms / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
            else String.format("%d:%02d", m, s)
        }

        fun fmtSeconds(sec: Int): String = fmt(sec * 1000L)
    }
}
