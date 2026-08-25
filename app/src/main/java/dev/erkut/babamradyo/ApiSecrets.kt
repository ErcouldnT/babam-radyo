package dev.erkut.babamradyo

/**
 * Backend adresi ve gizli anahtar.
 *
 * Degerler depoya girmeyen `api.properties` dosyasindan derleme sirasinda
 * BuildConfig'e gomulur ve orada XOR ile karistirilmis halde durur.
 *
 * Dikkat: bu gercek bir sir saklama yontemi degildir. APK'ya sahip olan
 * biri anahtari cikarabilir; XOR yalnizca `strings` ile bakan birini
 * engeller. Asil koruma, anahtarin sizmasi halinde sunucuda
 * degistirilebilmesidir.
 */
object ApiSecrets {

    val baseUrl: String = BuildConfig.API_BASE_URL.trimEnd('/')

    val token: String by lazy { deobfuscate(BuildConfig.API_TOKEN_OBFUSCATED) }

    /** api.properties doldurulmadiysa YouTube araması sessizce devre disi kalir. */
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && token.isNotBlank()

    private fun deobfuscate(hex: String): String {
        if (hex.isBlank()) return ""
        val key = BuildConfig.API_OBFUSCATION_KEY
        if (key.isEmpty()) return ""
        return try {
            val bytes = ByteArray(hex.length / 2) { i ->
                val v = hex.substring(i * 2, i * 2 + 2).toInt(16)
                (v xor key[i % key.length].code).toByte()
            }
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}
