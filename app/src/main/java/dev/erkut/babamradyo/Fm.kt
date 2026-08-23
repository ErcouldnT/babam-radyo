package dev.erkut.babamradyo

import android.content.Context
import android.content.Intent

/**
 * Telefonda kurulu **dahili** FM radyo uygulamasini bulup acar.
 *
 * Not: Gercek FM radyo telefonun icindeki FM anten cipiyle calisir ve
 * anten gorevi kablolu kulaklik kablosu tarafindan yapilir. Bu yuzden
 * uygulama disaridan FM yayini "indiremiyor"; sadece cihazin kendi
 * FM uygulamasini acabiliyor. Cip yoksa internet radyosu kullanilir.
 */
object Fm {

    private val CANDIDATES = listOf(
        "com.android.fmradio",           // Oppo / ColorOS ve AOSP tabanli
        "com.oppo.fmradio",
        "com.coloros.fmradio",
        "com.oplus.fmradio",
        "com.caf.fmradio",               // Qualcomm referans uygulamasi
        "com.miui.fm",
        "com.sec.android.app.fm",        // Samsung
        "com.motorola.fmplayer",
        "com.huawei.android.FMRadio",
        "com.lge.fmradio"
    )

    /** Bulunursa FM uygulamasini acar ve true doner. */
    fun open(context: Context): Boolean {
        val pm = context.packageManager
        for (pkg in CANDIDATES) {
            val intent = pm.getLaunchIntentForPackage(pkg) ?: continue
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                false
            }
        }
        return false
    }
}
