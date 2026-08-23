package dev.erkut.babamradyo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import dev.erkut.babamradyo.databinding.ItemArchiveBinding
import dev.erkut.babamradyo.databinding.ItemHeaderBinding
import dev.erkut.babamradyo.databinding.ItemTrackBinding

/** Liste satiri: ya bir bolum basligi ya da bir parca. */
sealed class Row {
    /** [onClick] doluysa baslik tiklanabilir bir eylem satiri olur. */
    data class Header(val title: String, val onClick: (() -> Unit)? = null) : Row()
    data class Item(val track: Track) : Row()
}

/** Parca listesi: radyo istasyonlari, arsiv parcalari ve indirilenler. */
class TrackAdapter(
    private val onPlay: (Track) -> Unit,
    private val onDownload: (Track) -> Unit,
    private val onCancelDownload: (Track) -> Unit,
    private val onDelete: (Track) -> Unit,
    private val onToggleFavorite: (Track) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_TRACK = 1
    }

    private var rows: List<Row> = emptyList()
    private val progress = HashMap<String, Int>()
    private var downloadedIds: Set<String> = emptySet()
    private var favoriteIds: Set<String> = emptySet()
    private var playingId: String? = null

    class HeaderVH(val b: ItemHeaderBinding) : RecyclerView.ViewHolder(b.root)
    class TrackVH(val b: ItemTrackBinding) : RecyclerView.ViewHolder(b.root)

    fun submit(newRows: List<Row>) {
        rows = newRows
        notifyDataSetChanged()
    }

    fun setDownloaded(ids: Set<String>) {
        downloadedIds = ids
        notifyDataSetChanged()
    }

    fun setFavorites(ids: Set<String>) {
        favoriteIds = ids
        notifyDataSetChanged()
    }

    fun setPlaying(id: String?) {
        if (playingId == id) return
        playingId = id
        notifyDataSetChanged()
    }

    fun setProgress(id: String, pct: Int) {
        if (pct >= 100 || pct < 0) progress.remove(id) else progress[id] = pct
        notifyRow(id)
    }

    fun startProgress(id: String) {
        progress[id] = 0
        notifyRow(id)
    }

    fun markDownloaded(id: String) {
        downloadedIds = downloadedIds + id
        progress.remove(id)
        notifyRow(id)
    }

    private fun notifyRow(id: String) {
        val i = rows.indexOfFirst { it is Row.Item && it.track.id == id }
        if (i >= 0) notifyItemChanged(i)
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) =
        if (rows[position] is Row.Header) TYPE_HEADER else TYPE_TRACK

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) HeaderVH(ItemHeaderBinding.inflate(inf, parent, false))
        else TrackVH(ItemTrackBinding.inflate(inf, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderVH).b.tvHeader.apply {
                text = row.title
                isClickable = row.onClick != null
                setOnClickListener(row.onClick?.let { cb -> View.OnClickListener { cb() } })
                setBackgroundResource(
                    if (row.onClick != null) R.drawable.header_clickable else 0
                )
            }
            is Row.Item -> bindTrack(holder as TrackVH, row.track)
        }
    }

    private fun bindTrack(h: TrackVH, t: Track) {
        val ctx = h.b.root.context

        h.b.tvTitle.text = t.title
        h.b.tvSubtitle.text = buildString {
            append(t.subtitle)
            if (t.durationSec > 0) {
                if (isNotEmpty()) append("  ·  ")
                append(PlayerBar.fmtSeconds(t.durationSec))
            }
        }

        ImageLoader.load(h.b.ivArt, t.artworkUrl, R.drawable.ic_art_placeholder)

        h.b.ivNowPlaying.visibility =
            if (t.id == playingId) View.VISIBLE else View.INVISIBLE

        h.b.root.setOnClickListener { onPlay(t) }

        val pct = progress[t.id]
        when {
            // Indirme suruyor -> yuzde + iptal dugmesi
            pct != null -> {
                h.b.btnAction.visibility = View.GONE
                h.b.tvProgress.visibility = View.VISIBLE
                h.b.tvProgress.text = "%$pct"
                h.b.btnCancel.visibility = View.VISIBLE
                h.b.btnCancel.setOnClickListener { onCancelDownload(t) }
            }
            // Canli radyo -> favori yildizi
            t.kind == Track.Kind.RADIO -> {
                hideProgress(h)
                val fav = t.id in favoriteIds
                h.b.btnAction.visibility = View.VISIBLE
                h.b.btnAction.setImageResource(
                    if (fav) R.drawable.ic_star_filled else R.drawable.ic_star_outline
                )
                h.b.btnAction.contentDescription =
                    ctx.getString(if (fav) R.string.favorite_remove else R.string.favorite_add)
                h.b.btnAction.setOnClickListener { onToggleFavorite(t) }
            }
            // Cevrimdisi kutuphanedeki parca -> silinebilir
            t.kind == Track.Kind.LOCAL -> {
                hideProgress(h)
                h.b.btnAction.visibility = View.VISIBLE
                h.b.btnAction.setImageResource(R.drawable.ic_delete)
                h.b.btnAction.contentDescription = ctx.getString(R.string.delete)
                h.b.btnAction.setOnClickListener { onDelete(t) }
            }
            // Arsiv parcasi, zaten inmis
            t.id in downloadedIds -> {
                hideProgress(h)
                h.b.btnAction.visibility = View.VISIBLE
                h.b.btnAction.setImageResource(R.drawable.ic_check)
                h.b.btnAction.contentDescription = ctx.getString(R.string.downloaded)
                h.b.btnAction.setOnClickListener(null)
            }
            // Arsiv parcasi, indirilebilir
            else -> {
                hideProgress(h)
                h.b.btnAction.visibility = View.VISIBLE
                h.b.btnAction.setImageResource(R.drawable.ic_download)
                h.b.btnAction.contentDescription = ctx.getString(R.string.download)
                h.b.btnAction.setOnClickListener { onDownload(t) }
            }
        }
    }

    private fun hideProgress(h: TrackVH) {
        h.b.tvProgress.visibility = View.GONE
        h.b.btnCancel.visibility = View.GONE
    }
}

/** archive.org arama sonuclari (albüm / kayit dizeyi). */
class ArchiveAdapter(
    private val onOpen: (ArchiveItem) -> Unit
) : RecyclerView.Adapter<ArchiveAdapter.VH>() {

    private var items: List<ArchiveItem> = emptyList()

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
        ImageLoader.load(h.b.ivArt, a.artworkUrl, R.drawable.ic_art_placeholder)
        h.b.root.setOnClickListener { onOpen(a) }
    }
}
