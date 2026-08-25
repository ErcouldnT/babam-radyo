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
    val fileName: String = "",
    /** Istasyon logosu ya da albüm kapagi; yoksa bos. */
    val artworkUrl: String = "",
    /**
     * Indirmede kullanilacak adres. YouTube'da calma ve indirme farkli
     * adreslerden gelir: calmada ileri sarilabilen kaynak akis, indirmede
     * mp3'e cevrilmis hali. Bos ise [url] kullanilir.
     */
    val downloadUrl: String = ""
) {
    enum class Kind { RADIO, ARCHIVE, LOCAL, YOUTUBE }

    val isLive: Boolean get() = kind == Kind.RADIO

    val canDownload: Boolean
        get() = kind == Kind.ARCHIVE || kind == Kind.YOUTUBE

    /** Indirme icin gercek adres. */
    val effectiveDownloadUrl: String get() = downloadUrl.ifBlank { url }
}

/** archive.org arama sonucundaki bir kayit/albüm. */
data class ArchiveItem(
    val identifier: String,
    val title: String,
    val creator: String,
    val year: String
) {
    /** Arsivin her kayit icin urettigi kapak gorseli. */
    val artworkUrl: String get() = "https://archive.org/services/img/$identifier"
}
