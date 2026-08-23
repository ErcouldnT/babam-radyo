package dev.erkut.babamradyo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import dev.erkut.babamradyo.databinding.ItemArchiveBinding
import dev.erkut.babamradyo.databinding.ItemTrackBinding

/** Parca listesi: radyo istasyonlari, arsiv parcalari ve indirilenler. */
class TrackAdapter(
    private var items: List<Track>,
    private val downloadedIds: MutableSet<String>,
    private val onPlay: (Int) -> Unit,
    private val onDownload: (Track) -> Unit,
    private val onDelete: (Track) -> Unit
) : RecyclerView.Adapter<TrackAdapter.VH>() {

    /** track.id -> yuzde (0..100), indirme surerken dolu olur. */
    private val progress = HashMap<String, Int>()
    private var playingId: String? = null

    class VH(val b: ItemTrackBinding) : RecyclerView.ViewHolder(b.root)

    fun submit(list: List<Track>) {
        items = list
        notifyDataSetChanged()
    }

    fun setPlaying(id: String?) {
        if (playingId == id) return
        playingId = id
        notifyDataSetChanged()
    }

    fun setProgress(id: String, pct: Int) {
        if (pct >= 100) progress.remove(id) else progress[id] = pct
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) notifyItemChanged(i)
    }

    fun markDownloaded(id: String) {
        downloadedIds.add(id)
        progress.remove(id)
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) notifyItemChanged(i)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val t = items[position]
        val ctx = h.b.root.context

        h.b.tvTitle.text = t.title
        h.b.tvSubtitle.text = buildString {
            append(t.subtitle)
            if (t.durationSec > 0) {
                if (isNotEmpty()) append("  ·  ")
                append(PlayerBar.fmtSeconds(t.durationSec))
            }
        }

        h.b.ivNowPlaying.visibility =
            if (t.id == playingId) View.VISIBLE else View.INVISIBLE

        h.b.root.setOnClickListener { onPlay(h.bindingAdapterPosition) }

        val pct = progress[t.id]
        when {
            // Indirme suruyor
            pct != null -> {
                h.b.btnAction.visibility = View.GONE
                h.b.tvProgress.visibility = View.VISIBLE
                h.b.tvProgress.text = if (pct < 0) "…" else "%$pct"
            }
            // Cevrimdisi kutuphanedeki parca -> silinebilir
            t.kind == Track.Kind.LOCAL -> {
                h.b.tvProgress.visibility = View.GONE
                h.b.btnAction.visibility = View.VISIBLE
                h.b.btnAction.setImageResource(R.drawable.ic_delete)
                h.b.btnAction.contentDescription = ctx.getString(R.string.delete)
                h.b.btnAction.setOnClickListener { onDelete(t) }
            }
            // Arsiv parcasi, zaten inmis
            t.canDownload && t.id in downloadedIds -> {
                h.b.tvProgress.visibility = View.GONE
                h.b.btnAction.visibility = View.VISIBLE
                h.b.btnAction.setImageResource(R.drawable.ic_check)
                h.b.btnAction.contentDescription = ctx.getString(R.string.downloaded)
                h.b.btnAction.setOnClickListener { }
            }
            // Arsiv parcasi, indirilebilir
            t.canDownload -> {
                h.b.tvProgress.visibility = View.GONE
                h.b.btnAction.visibility = View.VISIBLE
                h.b.btnAction.setImageResource(R.drawable.ic_download)
                h.b.btnAction.contentDescription = ctx.getString(R.string.download)
                h.b.btnAction.setOnClickListener { onDownload(t) }
            }
            // Canli radyo -> indirilemez
            else -> {
                h.b.tvProgress.visibility = View.GONE
                h.b.btnAction.visibility = View.GONE
            }
        }
    }
}

/** archive.org arama sonuclari (albüm / kayit dizeyi). */
class ArchiveAdapter(
    private var items: List<ArchiveItem>,
    private val onOpen: (ArchiveItem) -> Unit
) : RecyclerView.Adapter<ArchiveAdapter.VH>() {

    class VH(val b: ItemArchiveBinding) : RecyclerView.ViewHolder(b.root)

    fun submit(list: List<ArchiveItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemArchiveBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val a = items[position]
        h.b.tvTitle.text = a.title
        // Arsivde sanatci/yil alanlari cogu zaman bos; ikinci satir bos
        // gorunmesin diye kaynagi yaziyoruz.
        h.b.tvSubtitle.text = listOf(a.creator, a.year)
            .filter { it.isNotBlank() }.joinToString(" · ")
            .ifBlank { "archive.org kaydı" }
        h.b.root.setOnClickListener { onOpen(a) }
    }
}
