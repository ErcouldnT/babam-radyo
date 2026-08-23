package dev.erkut.babamradyo

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import dev.erkut.babamradyo.databinding.ActivityTracklistBinding
import kotlinx.coroutines.launch

/** Bir archive.org kaydinin icindeki sarkilar. */
class TrackListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_CREATOR = "creator"
        const val EXTRA_YEAR = "year"
    }

    private lateinit var b: ActivityTracklistBinding
    private lateinit var playerBar: PlayerBar

    private var tracks: List<Track> = emptyList()

    private val downloadListener: (String, Int) -> Unit = { id, pct ->
        runOnUiThread {
            if (pct >= 100) {
                adapter.setProgress(id, 100)
                adapter.setDownloaded(Downloads.list(this).map { it.id }.toSet())
            } else {
                adapter.setProgress(id, pct)
            }
        }
    }

    private val adapter: TrackAdapter by lazy {
        TrackAdapter(
            onPlay = { t ->
                val i = tracks.indexOfFirst { it.id == t.id }
                if (i >= 0) {
                    PlayerCore.play(this, tracks, i)
                    playerBar.render()
                    adapter.setPlaying(PlayerCore.currentId())
                }
            },
            onDownload = { t -> startDownload(t) },
            onCancelDownload = { t -> cancelDownload(t) },
            onDelete = { },
            onToggleFavorite = { }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTracklistBinding.inflate(layoutInflater)
        setContentView(b.root)

        val item = ArchiveItem(
            identifier = intent.getStringExtra(EXTRA_ID).orEmpty(),
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            creator = intent.getStringExtra(EXTRA_CREATOR).orEmpty(),
            year = intent.getStringExtra(EXTRA_YEAR).orEmpty()
        )

        b.tvHeader.text = item.title
        b.btnBack.setOnClickListener { finish() }

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        b.list.adapter = adapter

        playerBar = PlayerBar(this, b.player) { adapter.setPlaying(PlayerCore.currentId()) }

        adapter.setDownloaded(Downloads.list(this).map { it.id }.toSet())
        load(item)
    }

    override fun onStart() {
        super.onStart()
        playerBar.attach()
        PlayerCore.onMessage = { msg -> runOnUiThread { toast(msg) } }
        DownloadService.addListener(downloadListener)
        DownloadService.activeProgress().forEach { (id, pct) -> adapter.setProgress(id, pct) }
    }

    override fun onStop() {
        playerBar.detach()
        PlayerCore.onMessage = null
        DownloadService.removeListener(downloadListener)
        super.onStop()
    }

    private fun load(item: ArchiveItem) {
        b.progress.visibility = View.VISIBLE
        b.list.visibility = View.GONE
        lifecycleScope.launch {
            try {
                tracks = Api.archiveTracks(item)
                b.progress.visibility = View.GONE
                if (tracks.isEmpty()) {
                    b.tvEmpty.visibility = View.VISIBLE
                    b.tvEmpty.setText(R.string.no_tracks)
                } else {
                    b.list.visibility = View.VISIBLE
                    adapter.submit(tracks.map { Row.Item(it) })
                    adapter.setPlaying(PlayerCore.currentId())
                }
            } catch (e: Exception) {
                b.progress.visibility = View.GONE
                b.tvEmpty.visibility = View.VISIBLE
                b.tvEmpty.setText(R.string.error_network)
            }
        }
    }

    private fun startDownload(track: Track) {
        adapter.startProgress(track.id)
        DownloadService.enqueue(this, track)
    }

    private fun cancelDownload(track: Track) {
        DownloadService.cancel(this, track.id)
        adapter.setProgress(track.id, 100)
        toast(getString(R.string.download_cancelled))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
