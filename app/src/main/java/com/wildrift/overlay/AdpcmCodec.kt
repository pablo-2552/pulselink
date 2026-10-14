package com.wildrift.overlay

/**
 * IMA-ADPCM ses sıkıştırma (16 bit PCM -> 4 bit, yaklaşık 4:1).
 *
 * Paket biçimi (kayıp pakette sonraki paket bozulmasın diye her paket kendi başlangıç durumunu taşır):
 *   [0..1]  seq (uint16, little endian)
 *   her kanal için: [pred int16 LE][index uint8][rezerve uint8]
 *   her kanal için: N/2 bayt nibble verisi (N = kanal başına örnek sayısı, çift olmalı)
 */
object AdpcmCodec {

    const val SAMPLE_RATE = 48000
    const val CHANNELS = 2
    const val FRAME_SAMPLES = 960 // kanal başına 20 ms @ 48 kHz

    private val STEP = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230,
        253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963,
        1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327,
        3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487,
        12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    )
    private val INDEX_ADJ = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)

    const val PACKET_SIZE = 2 + CHANNELS * (4 + FRAME_SAMPLES / 2)

    /** Kanal başına kodlayıcı durumu (paketler arası taşınır). */
    class Encoder {
        private val pred = IntArray(CHANNELS)
        private val index = IntArray(CHANNELS)
        private var seq = 0

        /** pcm: interleaved stereo, uzunluk = FRAME_SAMPLES * CHANNELS. */
        fun encode(pcm: ShortArray): ByteArray {
            val out = ByteArray(PACKET_SIZE)
            out[0] = (seq and 0xFF).toByte()
            out[1] = ((seq shr 8) and 0xFF).toByte()
            seq = (seq + 1) and 0xFFFF

            var pos = 2
            for (ch in 0 until CHANNELS) {
                out[pos] = (pred[ch] and 0xFF).toByte()
                out[pos + 1] = ((pred[ch] shr 8) and 0xFF).toByte()
                out[pos + 2] = index[ch].toByte()
                out[pos + 3] = 0
                pos += 4
            }
            for (ch in 0 until CHANNELS) {
                var i = 0
                while (i < FRAME_SAMPLES) {
                    val lo = encodeSample(ch, pcm[i * CHANNELS + ch].toInt())
                    val hi = encodeSample(ch, pcm[(i + 1) * CHANNELS + ch].toInt())
                    out[pos++] = (lo or (hi shl 4)).toByte()
                    i += 2
                }
            }
            return out
        }

        private fun encodeSample(ch: Int, sample: Int): Int {
            var p = pred[ch]
            var idx = index[ch]
            var step = STEP[idx]
            var diff = sample - p
            var code = 0
            if (diff < 0) {
                code = 8
                diff = -diff
            }
            var vpdiff = step shr 3
            if (diff >= step) { code = code or 4; diff -= step; vpdiff += step }
            step = step shr 1
            if (diff >= step) { code = code or 2; diff -= step; vpdiff += step }
            step = step shr 1
            if (diff >= step) { code = code or 1; vpdiff += step }

            p += if ((code and 8) != 0) -vpdiff else vpdiff
            if (p > 32767) p = 32767
            if (p < -32768) p = -32768
            idx += INDEX_ADJ[code and 7]
            if (idx < 0) idx = 0
            if (idx > 88) idx = 88
            pred[ch] = p
            index[ch] = idx
            return code
        }
    }

    /** Paketin sıra numarasını okur, geçersizse -1. */
    fun readSeq(packet: ByteArray): Int {
        if (packet.size != PACKET_SIZE) return -1
        return (packet[0].toInt() and 0xFF) or ((packet[1].toInt() and 0xFF) shl 8)
    }

    /** Paketi interleaved stereo PCM'e çözer. Geçersizse null. */
    fun decode(packet: ByteArray): ShortArray? {
        if (packet.size != PACKET_SIZE) return null
        val out = ShortArray(FRAME_SAMPLES * CHANNELS)
        val pred = IntArray(CHANNELS)
        val index = IntArray(CHANNELS)
        var pos = 2
        for (ch in 0 until CHANNELS) {
            pred[ch] = ((packet[pos].toInt() and 0xFF) or (packet[pos + 1].toInt() shl 8)).toShort().toInt()
            index[ch] = (packet[pos + 2].toInt() and 0xFF).coerceIn(0, 88)
            pos += 4
        }
        for (ch in 0 until CHANNELS) {
            var i = 0
            while (i < FRAME_SAMPLES) {
                val b = packet[pos++].toInt() and 0xFF
                out[i * CHANNELS + ch] = decodeNibble(pred, index, ch, b and 0x0F)
                out[(i + 1) * CHANNELS + ch] = decodeNibble(pred, index, ch, (b shr 4) and 0x0F)
                i += 2
            }
        }
        return out
    }

    private fun decodeNibble(pred: IntArray, index: IntArray, ch: Int, code: Int): Short {
        var idx = index[ch]
        val step = STEP[idx]
        var vpdiff = step shr 3
        if ((code and 4) != 0) vpdiff += step
        if ((code and 2) != 0) vpdiff += step shr 1
        if ((code and 1) != 0) vpdiff += step shr 2
        var p = pred[ch] + if ((code and 8) != 0) -vpdiff else vpdiff
        if (p > 32767) p = 32767
        if (p < -32768) p = -32768
        idx += INDEX_ADJ[code and 7]
        if (idx < 0) idx = 0
        if (idx > 88) idx = 88
        pred[ch] = p
        index[ch] = idx
        return p.toShort()
    }
}
