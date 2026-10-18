package com.wildrift.overlay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * İZLEYİCİ CİHAZ (A8): Gelen ADPCM paketlerini çözer ve düşük gecikmeli AudioTrack ile çalar.
 * Birikim olursa eski paketler atılır; böylece ses hiçbir zaman gecikme biriktirmez.
 */
class AudioPlayer {

    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null
    @Volatile private var lastSeq = -1

    companion object {
        private const val MAX_BACKLOG = 4   // 4 x 20ms = 80ms üstünde birikirse at
        private const val KEEP_BACKLOG = 2  // atınca 40ms bırak
    }

    fun start(): Boolean {
        if (running) return true
        val t = try {
            buildTrack()
        } catch (e: Throwable) {
            null
        } ?: return false

        track = t
        queue.clear()
        lastSeq = -1
        running = true
        thread = Thread({ loop(t) }, "AudioPlay").also {
            it.isDaemon = true
            it.start()
        }
        return true
    }

    private fun buildTrack(): AudioTrack? {
        val frameBytes = AdpcmCodec.FRAME_SAMPLES * AdpcmCodec.CHANNELS * 2
        val minBuf = AudioTrack.getMinBufferSize(
            AdpcmCodec.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, frameBytes * 2)

        val tr = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AdpcmCodec.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(bufSize)
            .build()

        return if (tr.state == AudioTrack.STATE_INITIALIZED) {
            tr
        } else {
            tr.release()
            null
        }
    }

    /** DataChannel'dan gelen ham paketi kuyruğa alır (sıra dışı / tekrar eden paketleri eler). */
    fun push(packet: ByteArray) {
        if (!running) return
        val seq = AdpcmCodec.readSeq(packet)
        if (seq < 0) return
        val prev = lastSeq
        if (prev >= 0) {
            val diff = (seq - prev) and 0xFFFF
            if (diff == 0 || diff > 0x8000) return // eski veya tekrar
        }
        lastSeq = seq
        queue.offer(packet)
    }

    private fun loop(t: AudioTrack) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (e: Exception) {
        }
        try {
            t.play()
            while (running) {
                var pkt = queue.poll(40, TimeUnit.MILLISECONDS) ?: continue
                // Gecikme biriktiyse eskileri at
                if (queue.size > MAX_BACKLOG) {
                    while (queue.size > KEEP_BACKLOG) {
                        pkt = queue.poll() ?: break
                    }
                }
                val pcm = AdpcmCodec.decode(pkt) ?: continue
                t.write(pcm, 0, pcm.size)
            }
        } catch (e: Throwable) {
            // sessizce bitir
        } finally {
            try { t.pause() } catch (e: Exception) {}
            try { t.flush() } catch (e: Exception) {}
            try { t.release() } catch (e: Exception) {}
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
        track = null
        queue.clear()
    }
}
