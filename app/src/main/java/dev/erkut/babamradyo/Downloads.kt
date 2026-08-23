package dev.erkut.babamradyo

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cevrimdisi dinleme icin mp3 indirme ve indirilenler kutuphanesi.
 *
 * Dosyalar uygulamaya ozel klasore yazilir
 * (Android/data/dev.erkut.babamradyo/files/Music), bu yuzden hicbir
 * depolama izni istenmez ve uygulama silinince dosyalar da temizlenir.
 */
object Downloads {

    private const val INDEX = "index.json"

    fun dir(ctx: Context): File =
        (ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: ctx.filesDir)
            .also { if (!it.exists()) it.mkdirs() }

    // ------------------------------------------------------------ kütüphane

    /** Indirilmis parcalar; en yeni en ustte. */
    fun list(ctx: Context): List<Track> {
        val d = dir(ctx)
        return readIndex(ctx).mapNotNull { e ->
            val f = File(d, e.optString("file"))
            if (!f.exists()) return@mapNotNull null
            Track(
                id = e.optString("id"),
                // Eski kayitlarda baslik klasor yolunu icerebiliyor.
                title = e.optString("title").substringAfterLast('/').trim(),
                subtitle = e.optString("subtitle"),
                url = f.toURI().toString(),
                kind = Track.Kind.LOCAL,
                durationSec = e.optInt("duration"),
                sizeBytes = f.length(),
                fileName = f.name
            )
        }.reversed()
    }

    fun isDownloaded(ctx: Context, track: Track): Boolean =
        readIndex(ctx).any {
            it.optString("id") == track.id && File(dir(ctx), it.optString("file")).exists()
        }

    fun delete(ctx: Context, track: Track): Boolean {
        val d = dir(ctx)
        val kept = JSONArray()
        var removed = false
        readIndex(ctx).forEach { e ->
            if (e.optString("id") == track.id) {
                File(d, e.optString("file")).delete()
                removed = true
            } else {
                kept.put(e)
            }
        }
        if (removed) writeIndex(ctx, kept)
        return removed
    }

    // ------------------------------------------------------------- indirme

    /**
     * [track] parcasini indirir. [onProgress] 0..100 arasi yuzde alir,
     * toplam boyut bilinmiyorsa -1 gelir.
     */
    suspend fun download(
        ctx: Context,
        track: Track,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val d = dir(ctx)
        val target = File(d, safeName(track))
        val part = File(d, target.name + ".part")

        val conn = (URL(track.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "BabamRadyo/1.0 (Android)")
        }

        try {
            if (conn.responseCode !in 200..299) {
                throw java.io.IOException("HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong
            var done = 0L
            var lastPct = -1

            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = ((done * 100) / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        } else {
                            onProgress(-1)
                        }
                    }
                }
            }

            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw java.io.IOException("Dosya kaydedilemedi")

            addToIndex(ctx, track, target.name)
            onProgress(100)
            target
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------- dahili

    private fun safeName(track: Track): String {
        val base = track.title.ifBlank { "parca" }
            .replace(Regex("[^\\p{L}\\p{N} ._-]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(60)
            .ifBlank { "parca" }
        // Ayni isimli farkli parcalar birbirini ezmesin diye kimlikten kisa bir ek.
        val suffix = Integer.toHexString(track.id.hashCode()).takeLast(6)
        return "$base-$suffix.mp3"
    }

    private fun indexFile(ctx: Context) = File(dir(ctx), INDEX)

    private fun readIndex(ctx: Context): List<JSONObject> {
        val f = indexFile(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeIndex(ctx: Context, arr: JSONArray) {
        indexFile(ctx).writeText(arr.toString())
    }

    private fun addToIndex(ctx: Context, track: Track, fileName: String) {
        val arr = JSONArray()
        // Ayni parca tekrar indirildiyse eski kaydi dusur.
        readIndex(ctx).filter { it.optString("id") != track.id }.forEach { arr.put(it) }
        arr.put(
            JSONObject().apply {
                put("id", track.id)
                put("title", track.title)
                put("subtitle", track.subtitle)
                put("duration", track.durationSec)
                put("file", fileName)
            }
        )
        writeIndex(ctx, arr)
    }
}
