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
    private val downloadedIds = HashSet<String>()

    private val adapter: TrackAdapter by lazy {
        TrackAdapter(
            emptyList(), downloadedIds,
            onPlay = { pos ->
                if (pos in tracks.indices) {
                    PlayerCore.play(this, tracks, pos)
                    playerBar.render()
                    adapter.setPlaying(PlayerCore.currentId())
                }
            },
            onDownload = { t -> startDownload(t) },
            onDelete = { }
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

        refreshDownloadedIds()
        load(item)
    }

    override fun onStart() {
        super.onStart()
        playerBar.attach()
    }

    override fun onStop() {
        playerBar.detach()
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
                    adapter.submit(tracks)
                    adapter.setPlaying(PlayerCore.currentId())
                }
            } catch (e: Exception) {
                b.progress.visibility = View.GONE
                b.tvEmpty.visibility = View.VISIBLE
                b.tvEmpty.setText(R.string.error_network)
            }
        }
    }

    private fun refreshDownloadedIds() {
        downloadedIds.clear()
        Downloads.list(this).forEach { downloadedIds.add(it.id) }
    }

    private fun startDownload(track: Track) {
        adapter.setProgress(track.id, 0)
        lifecycleScope.launch {
            try {
                Downloads.download(this@TrackListActivity, track) { pct ->
                    runOnUiThread { adapter.setProgress(track.id, pct) }
                }
                adapter.markDownloaded(track.id)
                Toast.makeText(
                    this@TrackListActivity,
                    R.string.download_done, Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                adapter.setProgress(track.id, 100)
                Toast.makeText(
                    this@TrackListActivity,
                    "${getString(R.string.download_failed)}: ${e.message ?: ""}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
