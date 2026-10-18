package com.wildrift.overlay

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import kotlin.math.sqrt

/**
 * İzleyici cihazda, çözülen kareler ile ekran arasına girer.
 *  - depth = 0: kareler anında ekrana verilir (en düşük gecikme, varsayılan).
 *  - depth = 1/2: kareler 1-2 kare bekletilip sabit ritimle bırakılır (daha akıcı, biraz daha gecikme).
 * Ayrıca "ekranda gösterilen kareler arası en uzun boşluk" ölçülür (HUD'daki G değeri).
 */
class FramePacer(private val target: VideoSink) : VideoSink {

    private val thread = HandlerThread("FramePacer", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
    private val handler = Handler(thread.looper)
    private val core = PacerCore<VideoFrame> { it.release() }
    private val tickRunnable = Runnable { onTick() }

    @Volatile private var depthValue = 0

    private val statLock = Any()
    private var lastShownNs = 0L
    private var gapCount = 0
    private var gapSum = 0.0
    private var gapSumSq = 0.0
    private var gapMaxMs = 0.0

    fun setDepth(depth: Int) {
        val d = depth.coerceIn(0, 2)
        if (d == depthValue) return
        depthValue = d
        handler.post {
            core.reset()
            if (d > 0) core.depth = d
        }
    }

    override fun onFrame(frame: VideoFrame) {
        if (depthValue <= 0) {
            recordShown()
            target.onFrame(frame)
            return
        }
        frame.retain()
        handler.post {
            val tickAt = core.onArrival(frame, System.nanoTime())
            if (tickAt >= 0) scheduleTick(tickAt)
        }
    }

    private fun onTick() {
        val frame = core.onTick(System.nanoTime())
        if (frame != null) {
            recordShown()
            target.onFrame(frame)
            frame.release()
        }
        if (core.running) scheduleTick(core.nextTickNs)
    }

    private fun scheduleTick(atNs: Long) {
        val delayMs = ((atNs - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, delayMs)
    }

    private fun recordShown() {
        val now = System.nanoTime()
        synchronized(statLock) {
            if (lastShownNs != 0L) {
                val gapMs = (now - lastShownNs) / 1e6
                if (gapMs < 1000.0) { // uzun duraklamalar (statik ekran) istatistiği bozmasın
                    gapCount++
                    gapSum += gapMs
                    gapSumSq += gapMs * gapMs
                    if (gapMs > gapMaxMs) gapMaxMs = gapMs
                }
            }
            lastShownNs = now
        }
    }

    /** Son ölçümden beri (en uzun boşluk ms, standart sapma ms). Veri yoksa (-1, -1). Sayaçları sıfırlar. */
    fun snapshotGaps(): Pair<Int, Int> {
        synchronized(statLock) {
            val result = if (gapCount >= 3) {
                val mean = gapSum / gapCount
                val variance = (gapSumSq / gapCount - mean * mean).coerceAtLeast(0.0)
                Pair(gapMaxMs.toInt(), sqrt(variance).toInt())
            } else {
                Pair(-1, -1)
            }
            gapCount = 0
            gapSum = 0.0
            gapSumSq = 0.0
            gapMaxMs = 0.0
            return result
        }
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        handler.post { core.reset() }
        thread.quitSafely()
    }
}
