package dev.erkut.babamradyo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import dev.erkut.babamradyo.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private enum class Tab { RADIO, SEARCH, DOWNLOADS }

    private lateinit var b: ActivityMainBinding
    private lateinit var playerBar: PlayerBar

    private var tab = Tab.RADIO
    private var radioTracks: List<Track> = emptyList()
    private var localTracks: List<Track> = emptyList()

    private val downloadedIds = HashSet<String>()

    private val trackAdapter: TrackAdapter by lazy {
        TrackAdapter(
            emptyList(), downloadedIds,
            onPlay = { pos -> playCurrentList(pos) },
            onDownload = { t -> startDownload(t) },
            onDelete = { t -> confirmDelete(t) }
        )
    }
    private val archiveAdapter: ArchiveAdapter by lazy {
        ArchiveAdapter(emptyList()) { item -> openArchiveItem(item) }
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        playerBar = PlayerBar(this, b.player) { refreshPlayingMarker() }

        b.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_radio -> switchTab(Tab.RADIO)
                R.id.nav_search -> switchTab(Tab.SEARCH)
                R.id.nav_downloads -> switchTab(Tab.DOWNLOADS)
            }
            true
        }

        b.btnSearch.setOnClickListener { doSearch() }
        b.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }

        b.btnFm.setOnClickListener { openFmRadio() }

        askNotificationPermission()
        refreshDownloadedIds()
        switchTab(Tab.RADIO)
    }

    override fun onStart() {
        super.onStart()
        playerBar.attach()
    }

    override fun onResume() {
        super.onResume()
        refreshDownloadedIds()
        if (tab == Tab.DOWNLOADS) loadDownloads()
        refreshPlayingMarker()
    }

    override fun onStop() {
        playerBar.detach()
        super.onStop()
    }

    // ------------------------------------------------------------- sekmeler

    private fun switchTab(t: Tab) {
        tab = t
        hideKeyboard()
        when (t) {
            Tab.RADIO -> {
                b.searchRow.visibility = View.VISIBLE
                b.etSearch.setText("")
                b.etSearch.hint = getString(R.string.search_hint_radio)
                b.list.adapter = trackAdapter
                if (radioTracks.isEmpty()) loadRadio("") else showTracks(radioTracks)
            }
            Tab.SEARCH -> {
                b.searchRow.visibility = View.VISIBLE
                b.etSearch.setText("")
                b.etSearch.hint = getString(R.string.search_hint_music)
                b.list.adapter = archiveAdapter
                archiveAdapter.submit(emptyList())
                showEmpty(getString(R.string.empty_music))
            }
            Tab.DOWNLOADS -> {
                b.searchRow.visibility = View.GONE
                b.list.adapter = trackAdapter
                loadDownloads()
            }
        }
    }

    private fun doSearch() {
        hideKeyboard()
        val q = b.etSearch.text.toString().trim()
        when (tab) {
            Tab.RADIO -> loadRadio(q)
            Tab.SEARCH -> {
                if (q.isBlank()) {
                    showEmpty(getString(R.string.empty_music))
                    return
                }
                loadArchive(q)
            }
            Tab.DOWNLOADS -> Unit
        }
    }

    // ------------------------------------------------------------- yukleme

    private fun loadRadio(query: String) {
        showLoading()
        lifecycleScope.launch {
            try {
                radioTracks = Api.radioStations(query)
                if (tab == Tab.RADIO) {
                    if (radioTracks.isEmpty()) showEmpty(getString(R.string.empty_radio))
                    else showTracks(radioTracks)
                }
            } catch (e: Exception) {
                if (tab == Tab.RADIO) showEmpty(getString(R.string.error_network))
            }
        }
    }

    private fun loadArchive(query: String) {
        showLoading()
        lifecycleScope.launch {
            try {
                val results = Api.archiveSearch(query)
                if (tab != Tab.SEARCH) return@launch
                archiveAdapter.submit(results)
                if (results.isEmpty()) showEmpty("“$query” için sonuç bulunamadı.")
                else showList()
            } catch (e: Exception) {
                if (tab == Tab.SEARCH) showEmpty(getString(R.string.error_network))
            }
        }
    }

    private fun loadDownloads() {
        localTracks = Downloads.list(this)
        if (localTracks.isEmpty()) showEmpty(getString(R.string.empty_downloads))
        else showTracks(localTracks)
    }

    // ------------------------------------------------------------- oynatma

    private fun currentList(): List<Track> = when (tab) {
        Tab.RADIO -> radioTracks
        Tab.DOWNLOADS -> localTracks
        Tab.SEARCH -> emptyList()
    }

    private fun playCurrentList(pos: Int) {
        val list = currentList()
        if (pos !in list.indices) return
        PlayerCore.play(this, list, pos)
        playerBar.render()
        refreshPlayingMarker()
    }

    private fun refreshPlayingMarker() {
        trackAdapter.setPlaying(PlayerCore.currentId())
    }

    private fun openArchiveItem(item: ArchiveItem) {
        startActivity(
            Intent(this, TrackListActivity::class.java)
                .putExtra(TrackListActivity.EXTRA_ID, item.identifier)
                .putExtra(TrackListActivity.EXTRA_TITLE, item.title)
                .putExtra(TrackListActivity.EXTRA_CREATOR, item.creator)
                .putExtra(TrackListActivity.EXTRA_YEAR, item.year)
        )
    }

    // ------------------------------------------------------------- indirme

    private fun refreshDownloadedIds() {
        downloadedIds.clear()
        Downloads.list(this).forEach { downloadedIds.add(it.id) }
    }

    private fun startDownload(track: Track) {
        trackAdapter.setProgress(track.id, 0)
        lifecycleScope.launch {
            try {
                Downloads.download(this@MainActivity, track) { pct ->
                    runOnUiThread { trackAdapter.setProgress(track.id, pct) }
                }
                trackAdapter.markDownloaded(track.id)
                toast(getString(R.string.download_done))
            } catch (e: Exception) {
                trackAdapter.setProgress(track.id, 100)
                toast("${getString(R.string.download_failed)}: ${e.message ?: ""}")
            }
        }
    }

    private fun confirmDelete(track: Track) {
        AlertDialog.Builder(this)
            .setTitle(track.title)
            .setMessage("Bu şarkıyı telefondan silmek istiyor musun?")
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton(R.string.delete) { _, _ ->
                Downloads.delete(this, track)
                downloadedIds.remove(track.id)
                toast(getString(R.string.deleted))
                loadDownloads()
            }
            .show()
    }

    // ------------------------------------------------------------ FM radyo

    private fun openFmRadio() {
        if (Fm.open(this)) {
            toast(getString(R.string.fm_headset_hint))
        } else {
            AlertDialog.Builder(this)
                .setTitle(R.string.fm_radio)
                .setMessage(R.string.fm_not_found)
                .setPositiveButton("Tamam") { _, _ ->
                    b.bottomNav.selectedItemId = R.id.nav_radio
                }
                .show()
        }
    }

    // ------------------------------------------------------------ yardimci

    private fun showLoading() {
        b.progress.visibility = View.VISIBLE
        b.tvEmpty.visibility = View.GONE
        b.list.visibility = View.GONE
    }

    private fun showList() {
        b.progress.visibility = View.GONE
        b.tvEmpty.visibility = View.GONE
        b.list.visibility = View.VISIBLE
    }

    private fun showEmpty(msg: String) {
        b.progress.visibility = View.GONE
        b.list.visibility = View.GONE
        b.tvEmpty.visibility = View.VISIBLE
        b.tvEmpty.text = msg
    }

    private fun showTracks(list: List<Track>) {
        trackAdapter.submit(list)
        refreshPlayingMarker()
        showList()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(b.etSearch.windowToken, 0)
        b.etSearch.clearFocus()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
