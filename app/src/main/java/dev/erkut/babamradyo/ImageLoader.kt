package dev.erkut.babamradyo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Listelerdeki istasyon logosu / albüm kapagi icin minik gorsel yukleyici.
 *
 * Glide veya Coil eklemek yerine HttpURLConnection + BitmapFactory ile
 * yaziidi; boylece projede 3. parti gorsel kutuphanesi olmuyor.
 * Bellekte LruCache tutulur, disk onbellegi yoktur.
 */
object ImageLoader {

    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    /** Uygulamaya ayrilan belligin sekizde biri kadar onbellek. */
    private val cache = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024) / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    /** Basarisiz URL'leri tekrar tekrar denememek icin. */
    private val failed = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * [url] gorselini [view] icine yukler. Liste geri donusumunde yanlis
     * satira gorsel dusmemesi icin view'in tag'i kontrol edilir.
     */
    fun load(view: ImageView, url: String, placeholder: Int) {
        view.setTag(R.id.tag_image_url, url)

        if (url.isBlank() || url in failed) {
            view.setImageResource(placeholder)
            return
        }

        cache.get(url)?.let {
            view.setImageBitmap(it)
            return
        }

        view.setImageResource(placeholder)
        pool.execute {
            val bmp = fetch(url)
            if (bmp == null) {
                failed.add(url)
                return@execute
            }
            cache.put(url, bmp)
            main.post {
                // Satir baska bir ogeye tekrar kullanildiysa gorseli koyma.
                if (view.getTag(R.id.tag_image_url) == url) view.setImageBitmap(bmp)
            }
        }
    }

    private fun fetch(url: String): Bitmap? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "BabamRadyo/1.1 (Android)")
        }
        try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.use { input ->
                // Liste satirlari kucuk; tam cozunurlukte bitmap tutmaya gerek yok.
                val bytes = input.readBytes()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 128)
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    private fun sampleSize(w: Int, h: Int, target: Int): Int {
        var sample = 1
        var size = maxOf(w, h)
        while (size / 2 >= target) {
            size /= 2
            sample *= 2
        }
        return sample
    }
}
