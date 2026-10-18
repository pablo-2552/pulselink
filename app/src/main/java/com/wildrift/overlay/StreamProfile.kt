package com.wildrift.overlay

/**
 * Yayın profilleri. TÜM profiller aynı 19.5:9 en-boy oranını kullanır, bu yüzden
 * pencere/boşluk/yerleşim hiçbir profilde değişmez.
 *
 * Yakalama her zaman en yüksek profilde (1080x2340) yapılır; seçilen profil
 * kodlayıcıda ölçeklenerek elde edilir. Böylece profil değişince ekran yakalama
 * (VirtualDisplay) yeniden kurulmaz, geçiş anında ve takılmasız olur.
 */
object StreamProfile {
    const val CAPTURE_SHORT = 1080
    const val CAPTURE_LONG = 2340

    const val AUTO_MAX_FPS = 60

    /** Yakalama çözünürlüğüne göre küçültme oranı. */
    fun scaleFor(res: String): Double = when (res) {
        "1080p" -> 1.0   // 1080x2340
        "540p" -> 2.0    // 540x1170
        else -> 1.5      // 720x1560
    }

    fun shortSide(res: String): Int = when (res) {
        "1080p" -> 1080
        "540p" -> 540
        else -> 720
    }

    /** 60 FPS için hedef bitrate (bps). Düşük FPS'te orantılı azaltılır. */
    private fun baseBitrate60(res: String): Int = when (res) {
        "1080p" -> 10_000_000
        "540p" -> 3_000_000
        else -> 6_000_000
    }

    fun maxBitrate(res: String, fps: Int): Int {
        val base = baseBitrate60(res).toDouble()
        val factor = when {
            fps >= 60 -> 1.0
            fps >= 45 -> 0.85
            else -> 0.68
        }
        return (base * factor).toInt()
    }

    /**
     * Manuel modda kalite dalgalanmasını (karıncalanma) önlemek için alt sınır tavana yakın tutulur
     * ama %100 değil; ağ gerçekten yetmezse kuyruk birikmesin diye biraz pay bırakılır.
     */
    fun manualMinBitrate(res: String, fps: Int): Int = (maxBitrate(res, fps) * 0.70).toInt()

    /** Otomatik modda alt sınır düşük bırakılır, uygulama kendisi ayarlar. */
    fun autoMinBitrate(res: String): Int = when (res) {
        "1080p" -> 1_500_000
        "540p" -> 500_000
        else -> 900_000
    }

    /** İlk bağlantıda bant genişliği tahmininin hızlı yükselmesi için başlangıç değeri. */
    fun startBitrate(res: String, fps: Int): Int = (maxBitrate(res, fps) * 0.6).toInt()

    fun normalizeFps(fps: Int): Int = when {
        fps <= 0 -> 60
        fps > 120 -> 60
        else -> fps
    }
}
