package org.webrtc;

/**
 * Kodlayıcı (S26) ayarı. Değer, kodlayıcı her (yeniden) oluşturulduğunda okunur.
 * true  = "HIZLI": B-kare (ileriyi tahmin eden kare) kapalı, gerçek zamanlı öncelik, düşük gecikme ipucu.
 * false = "VARSAYILAN": cihazın kodlayıcısı hiç dokunulmadan, kendi varsayılanlarıyla çalışır.
 */
public final class EncoderTuning {
    public static volatile boolean lowLatency = true;

    private EncoderTuning() {}
}
