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
import androidx.core.widget.doAfterTextChanged
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

    /** Sunucudan gelen ham istasyon listesi (favori ayrimi yapilmamis). */
    private var allStations: List<Track> = emptyList()

    /** Ekranda gorunen sirayla parcalar; calma kuyrugu bundan kurulur. */
    private var queue: List<Track> = emptyList()

    /** Radyo sekmesinde aktif arama metni; bossa tam Turkiye listesi. */
    private var radioQuery = ""

    /** Indirme ilerlemesini servisten dinler. */
    private val downloadListener: (String, Int) -> Unit = { id, pct ->
        runOnUiThread {
            if (pct >= 100) {
                trackAdapter.setProgress(id, 100)
                trackAdapter.setDownloaded(
                    Downloads.list(this).map { it.id }.toSet()
                )
            } else {
                trackAdapter.setProgress(id, pct)
            }
        }
    }

    private val trackAdapter: TrackAdapter by lazy {
        TrackAdapter(
            onPlay = { t -> playFromQueue(t) },
            onDownload = { t -> startDownload(t) },
            onCancelDownload = { t -> cancelDownload(t) },
            onDelete = { t -> confirmDelete(t) },
            onToggleFavorite = { t -> toggleFavorite(t) }
        )
    }
    private val archiveAdapter: ArchiveAdapter by lazy {
        ArchiveAdapter { item -> openArchiveItem(item) }
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
        b.btnClear.setOnClickListener { clearSearch() }
        b.etSearch.doAfterTextChanged { updateClearButton() }

        // Zaten acik olan sekmeye tekrar dokunmak listeyi sifirlar:
        // arama sonucundan tam listeye donmenin en dogal yolu.
        b.bottomNav.setOnItemReselectedListener { item ->
            if (item.itemId == R.id.nav_radio && radioQuery.isNotEmpty()) clearSearch()
        }
        b.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }

        b.btnFm.setOnClickListener { openFmRadio() }
        b.btnSleep.setOnClickListener { showSleepTimerDialog() }

        askNotificationPermission()

        // En son dinlenen parcayi oynaticiya yerlestir (kendiliginden calmaz).
        PlayerCore.restoreLast(this)

        switchTab(Tab.RADIO)
    }

    override fun onStart() {
        super.onStart()
        playerBar.attach()
        // Yayin koptugunda / geri geldiginde kullaniciya haber ver.
        PlayerCore.onMessage = { msg -> runOnUiThread { toast(msg) } }
        DownloadService.addListener(downloadListener)
        // Ekran kapaliyken suren indirmelerin durumunu yakala.
        DownloadService.activeProgress().forEach { (id, pct) ->
            trackAdapter.setProgress(id, pct)
        }
    }

    override fun onResume() {
        super.onResume()
        trackAdapter.setDownloaded(Downloads.list(this).map { it.id }.toSet())
        trackAdapter.setFavorites(Prefs.favoriteIds(this))
        if (tab == Tab.DOWNLOADS) loadDownloads()
        refreshPlayingMarker()
    }

    override fun onStop() {
        playerBar.detach()
        PlayerCore.onMessage = null
        DownloadService.removeListener(downloadListener)
        super.onStop()
    }

    // ------------------------------------------------------------- sekmeler

    private fun switchTab(t: Tab) {
        tab = t
        hideKeyboard()
        trackAdapter.setFavorites(Prefs.favoriteIds(this))
        when (t) {
            Tab.RADIO -> {
                b.searchRow.visibility = View.VISIBLE
                b.etSearch.setText(radioQuery)
                b.etSearch.hint = getString(R.string.search_hint_radio)
                b.list.adapter = trackAdapter
                if (allStations.isEmpty()) loadRadio(radioQuery) else showStations()
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
                if (q.isBlank()) showEmpty(getString(R.string.empty_music))
                else loadArchive(q)
            }
            Tab.DOWNLOADS -> Unit
        }
    }

    // ------------------------------------------------------------- yukleme

    private fun loadRadio(query: String) {
        radioQuery = query
        updateClearButton()
        showLoading()
        lifecycleScope.launch {
            try {
                allStations = Api.radioStations(query)
                if (tab == Tab.RADIO) showStations()
            } catch (e: Exception) {
                if (tab == Tab.RADIO) {
                    // Internet yoksa bile favoriler gosterilebilsin.
                    val favs = Prefs.favorites(this@MainActivity)
                    if (favs.isEmpty()) showEmpty(getString(R.string.error_network))
                    else {
                        allStations = emptyList()
                        showStations()
                        toast(getString(R.string.error_network))
                    }
                }
            }
        }
    }

    /** Favoriler ustte ayri bir bolumde, kalan istasyonlar altta. */
    private fun showStations() {
        val favs = Prefs.favorites(this).filter { it.kind == Track.Kind.RADIO }
        val favIds = favs.map { it.id }.toSet()

        val rows = ArrayList<Row>()
        val flat = ArrayList<Track>()

        if (radioQuery.isNotEmpty()) {
            // Arama modu: favori bolumu gosterilmez, bunun yerine tam listeye
            // donmek icin tiklanabilir bir satir en ustte durur.
            rows.add(Row.Header("← " + getString(R.string.show_all_stations)) { clearSearch() })
            rows.add(Row.Header(getString(R.string.search_results)))
            allStations.forEach { rows.add(Row.Item(it)); flat.add(it) }
        } else {
            val rest = allStations.filter { it.id !in favIds }
            if (favs.isNotEmpty()) {
                rows.add(Row.Header(getString(R.string.section_favorites)))
                favs.forEach { rows.add(Row.Item(it)); flat.add(it) }
                if (rest.isNotEmpty()) {
                    rows.add(Row.Header(getString(R.string.section_all_stations)))
                }
            }
            rest.forEach { rows.add(Row.Item(it)); flat.add(it) }
        }

        queue = flat
        if (rows.isEmpty()) {
            showEmpty(getString(R.string.empty_radio))
        } else {
            trackAdapter.setFavorites(favIds)
            trackAdapter.submit(rows)
            refreshPlayingMarker()
            showList()
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
        val list = Downloads.list(this)
        queue = list
        trackAdapter.setDownloaded(list.map { it.id }.toSet())
        if (list.isEmpty()) {
            showEmpty(getString(R.string.empty_downloads))
        } else {
            trackAdapter.submit(list.map { Row.Item(it) })
            refreshPlayingMarker()
            showList()
        }
    }

    // ------------------------------------------------------------- oynatma

    private fun playFromQueue(track: Track) {
        val index = queue.indexOfFirst { it.id == track.id }
        if (index < 0) return
        PlayerCore.play(this, queue, index)
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

    // ------------------------------------------------------------ favoriler

    private fun toggleFavorite(track: Track) {
        val nowFavorite = Prefs.toggleFavorite(this, track)
        toast(getString(if (nowFavorite) R.string.favorited else R.string.unfavorited))
        if (tab == Tab.RADIO) showStations()
    }

    // ------------------------------------------------------------- indirme

    private fun startDownload(track: Track) {
        trackAdapter.startProgress(track.id)
        DownloadService.enqueue(this, track)
    }

    private fun cancelDownload(track: Track) {
        DownloadService.cancel(this, track.id)
        trackAdapter.setProgress(track.id, 100)
        toast(getString(R.string.download_cancelled))
    }

    private fun confirmDelete(track: Track) {
        AlertDialog.Builder(this)
            .setTitle(track.title)
            .setMessage("Bu şarkıyı telefondan silmek istiyor musun?")
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton(R.string.delete) { _, _ ->
                Downloads.delete(this, track)
                toast(getString(R.string.deleted))
                loadDownloads()
            }
            .show()
    }

    // ------------------------------------------------------ uyku zamanlayici

    private fun showSleepTimerDialog() {
        val options = intArrayOf(15, 30, 45, 60, 90)
        val labels = ArrayList<String>()
        if (SleepTimer.isRunning) {
            labels.add(getString(R.string.sleep_timer_off))
        }
        options.forEach { labels.add("$it dakika") }

        val title = if (SleepTimer.isRunning) {
            getString(R.string.sleep_timer_running, SleepTimer.remainingText())
        } else {
            getString(R.string.sleep_timer)
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (SleepTimer.isRunning && which == 0) {
                    SleepTimer.cancel()
                    toast(getString(R.string.sleep_timer_cancelled))
                    return@setItems
                }
                val idx = if (SleepTimer.isRunning) which - 1 else which
                val minutes = options[idx]
                SleepTimer.start(this, minutes) {
                    runOnUiThread { toast(getString(R.string.sleep_timer_finished)) }
                }
                toast(getString(R.string.sleep_timer_set, "$minutes dakika"))
            }
            .setNegativeButton("Vazgeç", null)
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

    /** Aramayi sifirlar: radyo sekmesinde tam Turkiye listesine doner. */
    private fun clearSearch() {
        b.etSearch.setText("")
        hideKeyboard()
        when (tab) {
            Tab.RADIO -> loadRadio("")
            Tab.SEARCH -> {
                archiveAdapter.submit(emptyList())
                showEmpty(getString(R.string.empty_music))
                updateClearButton()
            }
            Tab.DOWNLOADS -> Unit
        }
    }

    private fun updateClearButton() {
        val hasText = b.etSearch.text?.isNotEmpty() == true
        val searching = tab == Tab.RADIO && radioQuery.isNotEmpty()
        b.btnClear.visibility = if (hasText || searching) View.VISIBLE else View.GONE
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
