package com.wildrift.overlay

import android.media.MediaCodecList

/** İzleyici cihazın H.264 donanım çözücüsünün hangi çözünürlükleri kaldırdığını kontrol eder. */
object DeviceCaps {

    private fun isSoftware(name: String): Boolean {
        return name.startsWith("OMX.google.") || name.startsWith("c2.android.")
    }

    fun canDecodeAvc(width: Int, height: Int, fps: Int): Boolean {
        return try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                if (isSoftware(info.name)) continue
                if (!info.supportedTypes.any { it.equals("video/avc", ignoreCase = true) }) continue
                val vc = info.getCapabilitiesForType("video/avc").videoCapabilities ?: continue
                val f = fps.toDouble()
                val ok = try {
                    vc.areSizeAndRateSupported(width, height, f) ||
                        vc.areSizeAndRateSupported(height, width, f)
                } catch (e: Exception) {
                    false
                }
                if (ok) return true
            }
            false
        } catch (e: Exception) {
            // Emin olunamazsa kullanıcıyı kısıtlama
            true
        }
    }

    /** Cihazın çözebildiği en yüksek profili döner ("1080p", "720p" veya "540p"). */
    fun maxSupportedProfile(fps: Int): String {
        val f = if (fps <= 0) 30 else minOf(fps, 30) // 30 FPS'te destek varsa profil izin verilir
        return when {
            canDecodeAvc(StreamProfile.CAPTURE_LONG, StreamProfile.CAPTURE_SHORT, f) -> "1080p"
            canDecodeAvc(1560, 720, f) -> "720p"
            else -> "540p"
        }
    }

    fun isProfileSupported(res: String, fps: Int): Boolean {
        val f = if (fps <= 0) 30 else minOf(fps, 30)
        return when (res) {
            "1080p" -> canDecodeAvc(2340, 1080, f)
            "720p" -> canDecodeAvc(1560, 720, f)
            else -> true
        }
    }
}
