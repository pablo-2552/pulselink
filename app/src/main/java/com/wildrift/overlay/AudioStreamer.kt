package com.wildrift.overlay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import org.webrtc.DataChannel
import java.nio.ByteBuffer

/**
 * YAYIN CİHAZI (S26): Telefonun çaldığı sesi (oyun/medya) yakalar, IMA-ADPCM ile sıkıştırır ve
 * güvenilmez (yeniden gönderimsiz) bir DataChannel üzerinden gönderir.
 * Yeniden gönderim olmadığı için ses asla gecikme biriktirmez; kayıp paket sadece kısa bir kırpılmadır.
 */
class AudioStreamer(
    private val projection: MediaProjection,
    private val channel: DataChannel
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    /** false dönerse ses başlatılamadı (izin yok, Android < 10 vb.). */
    fun start(): Boolean {
        if (running) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val record = try {
            buildRecord()
        } catch (t: Throwable) {
            null
        } ?: return false

        running = true
        thread = Thread({ loop(record) }, "AudioCapture").also {
            it.isDaemon = true
            it.start()
        }
        return true
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildRecord(): AudioRecord? {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AdpcmCodec.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val frameBytes = AdpcmCodec.FRAME_SAMPLES * AdpcmCodec.CHANNELS * 2
        val minBuf = AudioRecord.getMinBufferSize(
            AdpcmCodec.SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )

        val rec = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf, frameBytes * 4))
            .setAudioPlaybackCaptureConfig(config)
            .build()

        return if (rec.state == AudioRecord.STATE_INITIALIZED) {
            rec
        } else {
            rec.release()
            null
        }
    }

    private fun loop(record: AudioRecord) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (e: Exception) {
        }

        val encoder = AdpcmCodec.Encoder()
        val pcm = ShortArray(AdpcmCodec.FRAME_SAMPLES * AdpcmCodec.CHANNELS)
        var filled = 0
        var silentFrames = 0

        try {
            record.startRecording()
            while (running) {
                val n = record.read(pcm, filled, pcm.size - filled, AudioRecord.READ_BLOCKING)
                if (n < 0) break
                filled += n
                if (filled < pcm.size) continue
                filled = 0

                var silent = true
                for (s in pcm) {
                    if (s.toInt() != 0) {
                        silent = false
                        break
                    }
                }
                silentFrames = if (silent) silentFrames + 1 else 0

                val packet = encoder.encode(pcm)
                // Uzun süre tam sessizlikte (oyun sessiz) veri göndermeyip bant genişliği tasarrufu
                if (silentFrames > 5) continue

                if (channel.state() != DataChannel.State.OPEN) continue
                // Kanal tıkanıksa eski sesi biriktirme, at (gecikme birikmesin)
                if (channel.bufferedAmount() > 24 * 1024) continue
                channel.send(DataChannel.Buffer(ByteBuffer.wrap(packet), true))
            }
        } catch (t: Throwable) {
            // sessizce bitir; yayın videosu etkilenmez
        } finally {
            try { record.stop() } catch (e: Exception) {}
            try { record.release() } catch (e: Exception) {}
            running = false
        }
    }

    fun stop() {
        running = false
        try {
            thread?.join(400)
        } catch (e: Exception) {
        }
        thread = null
    }
}
