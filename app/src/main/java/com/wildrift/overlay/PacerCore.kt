package com.wildrift.overlay

import java.util.ArrayDeque

/**
 * Kare düzenleyicinin (frame pacer) saf mantığı (Android'e bağlı değil, test edilebilir).
 *
 * Ağdan kareler düzensiz aralıklarla gelir (jitter). Bu sınıf kareleri küçük bir kuyrukta bekletip
 * sabit bir ritimle (tahmini kare aralığı) bırakır. Bedel: yaklaşık [depth] kare kadar ek gecikme.
 * Kareler HER ZAMAN geliş sırasıyla gösterilir (sıra asla değişmez).
 */
class PacerCore<T : Any>(private val onDrop: (T) -> Unit) {

    /** Kaç kare bekletileceği (1 veya 2). */
    var depth: Int = 1

    private val queue = ArrayDeque<T>()
    private var emaIntervalNs = 16_666_667.0
    private var lastArrivalNs = 0L
    private var idleTicks = 0

    /** Sıradaki tikin zamanı (ns). */
    var nextTickNs = 0L
        private set

    /** Tik zinciri çalışıyor mu. */
    var running = false
        private set

    val intervalNs: Long get() = emaIntervalNs.toLong()

    fun reset() {
        while (queue.isNotEmpty()) onDrop(queue.removeFirst())
        running = false
        lastArrivalNs = 0L
        idleTicks = 0
    }

    /** Kare geldi. Yeni bir tik planlanmalıysa tik zamanını (ns), gerekmiyorsa -1 döner. */
    fun onArrival(frame: T, nowNs: Long): Long {
        if (lastArrivalNs != 0L) {
            val dt = (nowNs - lastArrivalNs).toDouble()
            if (dt in 3e6..120e6) emaIntervalNs += 0.1 * (dt - emaIntervalNs)
        }
        lastArrivalNs = nowNs

        queue.addLast(frame)
        while (queue.size > depth + 2) onDrop(queue.removeFirst())

        if (!running) {
            running = true
            idleTicks = 0
            nextTickNs = nowNs + (depth * emaIntervalNs).toLong()
            return nextTickNs
        }
        return -1L
    }

    /** Tik zamanı geldi: gösterilecek kareyi (yoksa null) döner. Sonraki tik [nextTickNs]'de. */
    fun onTick(nowNs: Long): T? {
        // Gecikme birikmişse en eskileri atla (en yeni kareler korunur)
        while (queue.size > depth + 1) onDrop(queue.removeFirst())

        val frame = if (queue.isEmpty()) null else queue.removeFirst()
        if (frame == null) {
            idleTicks++
            if (idleTicks > 4) running = false // yayın durdu/sakin ekran: zinciri bırak
        } else {
            idleTicks = 0
        }

        val step = emaIntervalNs.toLong()
        nextTickNs += step
        if (nextTickNs < nowNs - step) nextTickNs = nowNs + step // işlemci aksadıysa ritmi yeniden kur
        return frame
    }
}
