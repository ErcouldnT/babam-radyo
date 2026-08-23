package dev.erkut.babamradyo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kucuk kalici ayarlar: favori istasyonlar ve en son dinlenen parca.
 * SharedPreferences yeterli; ayri bir veritabanina gerek yok.
 */
object Prefs {

    private const val FILE = "babam_radyo"
    private const val KEY_FAVS = "favorites"
    private const val KEY_LAST = "last_track"

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ------------------------------------------------------------- favoriler

    fun favorites(ctx: Context): List<Track> {
        val raw = sp(ctx).getString(KEY_FAVS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getJSONObject(it).toTrack() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun favoriteIds(ctx: Context): Set<String> =
        favorites(ctx).map { it.id }.toHashSet()

    fun isFavorite(ctx: Context, track: Track): Boolean =
        favorites(ctx).any { it.id == track.id }

    /** Favoriye ekler ya da cikarir; yeni durumu doner (true = favoride). */
    fun toggleFavorite(ctx: Context, track: Track): Boolean {
        val current = favorites(ctx).toMutableList()
        val idx = current.indexOfFirst { it.id == track.id }
        val nowFavorite: Boolean
        if (idx >= 0) {
            current.removeAt(idx)
            nowFavorite = false
        } else {
            current.add(track)
            nowFavorite = true
        }
        val arr = JSONArray()
        current.forEach { arr.put(it.toJson()) }
        sp(ctx).edit().putString(KEY_FAVS, arr.toString()).apply()
        return nowFavorite
    }

    // -------------------------------------------------- en son dinlenen parca

    fun saveLast(ctx: Context, track: Track) {
        sp(ctx).edit().putString(KEY_LAST, track.toJson().toString()).apply()
    }

    fun lastTrack(ctx: Context): Track? {
        val raw = sp(ctx).getString(KEY_LAST, null) ?: return null
        return try {
            JSONObject(raw).toTrack()
        } catch (e: Exception) {
            null
        }
    }

    // -------------------------------------------------------------- donusum

    private fun Track.toJson() = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("subtitle", subtitle)
        put("url", url)
        put("kind", kind.name)
        put("duration", durationSec)
        put("artwork", artworkUrl)
    }

    private fun JSONObject.toTrack() = Track(
        id = optString("id"),
        title = optString("title"),
        subtitle = optString("subtitle"),
        url = optString("url"),
        kind = runCatching { Track.Kind.valueOf(optString("kind")) }
            .getOrDefault(Track.Kind.RADIO),
        durationSec = optInt("duration"),
        artworkUrl = optString("artwork")
    )
}
