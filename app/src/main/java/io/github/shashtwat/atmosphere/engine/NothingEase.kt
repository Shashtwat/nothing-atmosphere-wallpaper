package io.github.shashtwat.atmosphere.engine

import android.view.animation.Interpolator
import kotlin.math.pow

/**
 * libblurwallpaper's own `PathInterpolator` (0x26868): the cubic Bezier (0,0) (x1,y1) (x2,y2) (1,1)
 * sampled at t = 0, 0.002, 0.004 ... accumulated in float while t < 1 (501 points, last x ~= 0.99998),
 * each point from the Bernstein terms with powf; lookup is a binary search on x (`x[mid] > v -> hi`)
 * and linear interpolation with no clamp. Android's PathInterpolator approximates the same curve
 * adaptively and is off by up to 0.003, so this replaces it.
 */
class NothingEase(x1: Float, y1: Float, x2: Float, y2: Float) : Interpolator {
    private val xs: FloatArray
    private val ys: FloatArray

    init {
        val px = ArrayList<Float>(512); val py = ArrayList<Float>(512)
        var t = 0f
        while (t < 1f) {
            val u = 1f - t
            px += 3f * u.pow(2) * t * x1 + 3f * u * t.pow(2) * x2 + t.pow(3)
            py += 3f * u.pow(2) * t * y1 + 3f * u * t.pow(2) * y2 + t.pow(3)
            t += 0.002f
        }
        xs = px.toFloatArray(); ys = py.toFloatArray()
    }

    override fun getInterpolation(v: Float): Float {
        var lo = 0
        var hi = xs.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (xs[mid] > v) hi = mid else lo = mid
        }
        val dx = xs[hi] - xs[lo]
        if (dx == 0f) return ys[lo]
        return ys[lo] + (v - xs[lo]) / dx * (ys[hi] - ys[lo])
    }
}
