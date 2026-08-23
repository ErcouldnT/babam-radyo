package dev.erkut.babamradyo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Tum ag islemleri. Sadece Android'in kendi HttpURLConnection + org.json siniflari
 * kullanilir; hicbir 3. parti kutuphane, API anahtari veya hesap gerekmez.
 *
 * Kaynaklar:
 *  - radio-browser.info : acik, anahtarsiz canli radyo dizini
 *  - archive.org        : acik, anahtarsiz ses arsivi (indirilebilir mp3)
 */
object Api {

    private const val UA = "BabamRadyo/1.0 (Android)"

    /** radio-browser dagitik calisir; biri cevap vermezse digerine gecilir. */
    private val RADIO_MIRRORS = listOf(
        "https://de1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info",
        "https://at1.api.radio-browser.info"
    )

    // ---------------------------------------------------------------- http

    private fun httpGet(urlStr: String, timeoutMs: Int = 15000): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw java.io.IOException("HTTP ${conn.responseCode}")
            }
            return conn.inputStream.bufferedReader().use(BufferedReader::readText)
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    // --------------------------------------------------------------- radyo

    /**
     * Canli radyo istasyonlarini getirir.
     * [query] bossa Turkiye'nin en cok dinlenen istasyonlari listelenir.
     */
    suspend fun radioStations(query: String): List<Track> = withContext(Dispatchers.IO) {
        val path = if (query.isBlank()) {
            "/json/stations/search?countrycode=TR&limit=120&hidebroken=true" +
                "&order=clickcount&reverse=true"
        } else {
            "/json/stations/search?name=${enc(query)}&limit=120&hidebroken=true" +
                "&order=clickcount&reverse=true"
        }

        var lastError: Exception? = null
        for (mirror in RADIO_MIRRORS) {
            try {
                val arr = JSONArray(httpGet(mirror + path))
                val out = ArrayList<Track>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val url = o.optString("url_resolved").ifBlank { o.optString("url") }
                    if (url.isBlank()) continue
                    val name = o.optString("name").trim()
                    if (name.isBlank()) continue

                    val bits = ArrayList<String>()
                    o.optString("tags").split(",").firstOrNull { it.isNotBlank() }
                        ?.let { bits.add(it.trim().replaceFirstChar(Char::uppercase)) }
                    o.optString("codec").takeIf { it.isNotBlank() }?.let { bits.add(it) }
                    o.optInt("bitrate").takeIf { it > 0 }?.let { bits.add("$it kbps") }

                    out.add(
                        Track(
                            id = o.optString("stationuuid").ifBlank { url },
                            title = name,
                            subtitle = bits.joinToString(" · ").ifBlank { "Canlı yayın" },
                            url = url,
                            kind = Track.Kind.RADIO
                        )
                    )
                }
                // Ayni istasyon birden fazla kez kayitli olabiliyor, isme gore tekille.
                return@withContext out.distinctBy { it.title.lowercase() }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: java.io.IOException("Radyo listesi alınamadı")
    }

    // -------------------------------------------------------------- arsiv

    /**
     * archive.org ses arsivinde arama yapar.
     *
     * Onemli: arsivin serbest metin aramasi (varsayilan `text:` alani) cok
     * alakasiz sonuc donduruyor - "turkish folk" aramasi Arapca Kuran
     * kayitlarini getiriyordu. Bu yuzden arama bilerek `title`, `creator` ve
     * `subject` alanlariyla sinirlandirildi; siralama da bu alakali kume
     * icinde indirme sayisina gore yapiliyor.
     */
    suspend fun archiveSearch(query: String): List<ArchiveItem> = withContext(Dispatchers.IO) {
        val safe = sanitize(query)
        if (safe.isBlank()) return@withContext emptyList()

        val q = enc(
            "mediatype:(audio) AND " +
                "(title:($safe) OR creator:($safe) OR subject:($safe))"
        )
        val url = "https://archive.org/advancedsearch.php?q=$q" +
            "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&fl%5B%5D=year" +
            "&sort%5B%5D=${enc("downloads desc")}&rows=60&page=1&output=json"

        val docs = JSONObject(httpGet(url, 25000))
            .getJSONObject("response").getJSONArray("docs")

        val out = ArrayList<ArchiveItem>(docs.length())
        for (i in 0 until docs.length()) {
            val o = docs.getJSONObject(i)
            val id = o.optString("identifier")
            if (id.isBlank()) continue
            out.add(
                ArchiveItem(
                    identifier = id,
                    title = flatten(o.opt("title")).ifBlank { id },
                    creator = flatten(o.opt("creator")),
                    year = o.optString("year")
                )
            )
        }
        out
    }

    /** Bir arsiv kaydinin icindeki calinabilir mp3 parcalarini listeler. */
    suspend fun archiveTracks(item: ArchiveItem): List<Track> = withContext(Dispatchers.IO) {
        val md = JSONObject(httpGet("https://archive.org/metadata/${enc(item.identifier)}", 25000))
        val files = md.optJSONArray("files") ?: return@withContext emptyList()

        val out = ArrayList<Track>()
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val name = f.optString("name")
            if (!name.lowercase().endsWith(".mp3")) continue

            // Arsivde hem "title" hem dosya adi klasor yolunu icerebiliyor
            // ("Album Adi/Sarki.mp3"); listede sadece sarki adi gosterilsin.
            val title = f.optString("title")
                .ifBlank { name.substringBeforeLast('.').replace('_', ' ') }
                .substringAfterLast('/')
                .trim()
            out.add(
                Track(
                    id = "${item.identifier}/$name",
                    title = title,
                    subtitle = listOf(item.creator, item.title)
                        .filter { it.isNotBlank() }.joinToString(" · ")
                        .ifBlank { "archive.org" },
                    url = "https://archive.org/download/${enc(item.identifier)}/${encPath(name)}",
                    kind = Track.Kind.ARCHIVE,
                    durationSec = f.optString("length").toSecondsOrZero(),
                    sizeBytes = f.optString("size").toLongOrNull() ?: 0L,
                    fileName = name
                )
            )
        }
        out
    }

    // ------------------------------------------------------------ yardimci

    /**
     * Kullanicinin yazdigi metinden Lucene sorgusunu bozacak karakterleri
     * temizler; harf, rakam, bosluk ve kesme isareti kalir.
     */
    private fun sanitize(query: String): String =
        query.map { if (it.isLetterOrDigit() || it == ' ' || it == '\'') it else ' ' }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Dosya adindaki bosluk vb. karakterleri kacisla, '/' korunur. */
    private fun encPath(name: String): String =
        name.split("/").joinToString("/") { enc(it).replace("+", "%20") }

    /** archive.org bazi alanlari string, bazen dizi olarak dondurur. */
    private fun flatten(v: Any?): String = when (v) {
        null -> ""
        is JSONArray -> (0 until v.length()).joinToString(", ") { v.optString(it) }
        else -> v.toString()
    }

    /** "182.5" ya da "3:02.5" formatini saniyeye cevirir. */
    private fun String.toSecondsOrZero(): Int {
        if (isBlank()) return 0
        return if (contains(":")) {
            split(":").fold(0.0) { acc, p -> acc * 60 + (p.toDoubleOrNull() ?: 0.0) }.toInt()
        } else {
            toDoubleOrNull()?.toInt() ?: 0
        }
    }
}
