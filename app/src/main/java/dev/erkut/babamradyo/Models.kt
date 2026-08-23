package dev.erkut.babamradyo

/**
 * Uygulamadaki her calinabilir sey bir Track'tir:
 * canli radyo yayini, arsivden gelen bir sarki ya da telefona inmis bir mp3.
 */
data class Track(
    val id: String,
    val title: String,
    val subtitle: String,
    val url: String,
    val kind: Kind,
    val durationSec: Int = 0,
    val sizeBytes: Long = 0L,
    /** Arsiv parcalarinda indirme dosya adini uretmek icin kullanilir. */
    val fileName: String = ""
) {
    enum class Kind { RADIO, ARCHIVE, LOCAL }

    val isLive: Boolean get() = kind == Kind.RADIO
    val canDownload: Boolean get() = kind == Track.Kind.ARCHIVE
}

/** archive.org arama sonucundaki bir kayit/albüm. */
data class ArchiveItem(
    val identifier: String,
    val title: String,
    val creator: String,
    val year: String
)
