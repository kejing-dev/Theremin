package io.embodit.theremin.domain.play

import io.embodit.theremin.domain.model.Vec3
import kotlin.math.PI
import kotlin.math.abs

/**
 * 1-euro filter (Casiez et al. 2012) on a point: a low-pass whose cut-off rises with speed, so a still hand
 * is steady and a moving hand lags little. Design `instrument.filtering`: minCutoff 1 Hz, beta 10 s/m,
 * dCutoff 1 Hz.
 */
class OneEuro(var minCutoff: Double = 1.0, var beta: Double = 10.0, var dCutoff: Double = 1.0, var skipRepeats: Boolean = true) {
    private var x: DoubleArray? = null
    private var lastRaw: Vec3? = null
    private val dx = DoubleArray(3)
    private var lastNs = 0L
    private var lastRealNs = 0L
    private var cutoff = minCutoff

    fun reset() {
        x = null
        lastRaw = null
        dx.fill(0.0)
    }

    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }

    /**
     * [skipRepeats] (v0.1.4 sound fix): the audio thread asks every 4 ms but the hand tracker gives a new point at
     * 30 - 90 Hz. Feeding the same point again and again made the filter see stop-go motion (a speed spike at
     * every new point, then zero) and the pitch moved in small bursts at the tracking rate: a buzz like radio
     * interference. A repeated point now only carries on the low-pass at the last cut-off; the speed is measured
     * between real points, over the time between them.
     */
    fun filter(p: Vec3, nowNs: Long): Vec3 {
        val prev = x
        val raw = doubleArrayOf(p.x.toDouble(), p.y.toDouble(), p.z.toDouble())
        if (prev == null) {
            x = raw
            lastNs = nowNs
            lastRealNs = nowNs
            lastRaw = p
            return p
        }
        val dt = ((nowNs - lastNs) / 1e9).coerceIn(1e-4, 0.1)
        lastNs = nowNs
        if (skipRepeats && p == lastRaw) {
            val a = alpha(cutoff, dt)
            for (k in 0..2) prev[k] += a * (raw[k] - prev[k])
            return Vec3(prev[0].toFloat(), prev[1].toFloat(), prev[2].toFloat())
        }
        lastRaw = p
        val dtReal = if (skipRepeats) ((nowNs - lastRealNs) / 1e9).coerceIn(1e-4, 0.1) else dt
        lastRealNs = nowNs
        val ad = alpha(dCutoff, dtReal)
        var speed = 0.0
        for (k in 0..2) {
            val d = (raw[k] - prev[k]) / dtReal
            dx[k] += ad * (d - dx[k])
            speed += dx[k] * dx[k]
        }
        speed = kotlin.math.sqrt(speed)
        cutoff = minCutoff + beta * speed
        val a = alpha(cutoff, dt)
        for (k in 0..2) prev[k] += a * (raw[k] - prev[k])
        return Vec3(prev[0].toFloat(), prev[1].toFloat(), prev[2].toFloat())
    }

    companion object {
        fun close(a: Double, b: Double, eps: Double) = abs(a - b) <= eps
    }
}
