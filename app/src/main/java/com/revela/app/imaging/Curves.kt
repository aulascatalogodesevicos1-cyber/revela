package com.revela.app.imaging

import com.revela.app.model.CurvePoint
import kotlin.math.max
import kotlin.math.sqrt

/** Curva de tons com interpolação cúbica monotônica (Fritsch–Carlson), sem "overshoot". */
object Curves {

    fun isIdentity(points: List<CurvePoint>): Boolean =
        points.size == 2 &&
            points[0].x == 0f && points[0].y == 0f &&
            points[1].x == 1f && points[1].y == 1f

    /** Gera uma tabela com [size]+1 entradas mapeando 0..1 -> 0..1. Retorna null se for identidade. */
    fun buildLut(points: List<CurvePoint>, size: Int = 1024): FloatArray? {
        if (points.size < 2 || isIdentity(points)) return null
        val pts = points.sortedBy { it.x }
        val n = pts.size
        val xs = FloatArray(n) { pts[it].x }
        val ys = FloatArray(n) { pts[it].y }

        val d = FloatArray(n - 1) { i -> (ys[i + 1] - ys[i]) / max(1e-6f, xs[i + 1] - xs[i]) }
        val m = FloatArray(n)
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) {
            m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        }
        for (i in 0 until n - 1) {
            if (d[i] == 0f) {
                m[i] = 0f; m[i + 1] = 0f
            } else {
                val a = m[i] / d[i]
                val b = m[i + 1] / d[i]
                val s = a * a + b * b
                if (s > 9f) {
                    val t = 3f / sqrt(s)
                    m[i] = t * a * d[i]
                    m[i + 1] = t * b * d[i]
                }
            }
        }

        val lut = FloatArray(size + 1)
        var k = 0
        for (i in 0..size) {
            val x = i / size.toFloat()
            lut[i] = when {
                x <= xs[0] -> ys[0]
                x >= xs[n - 1] -> ys[n - 1]
                else -> {
                    while (k < n - 2 && x > xs[k + 1]) k++
                    val h = xs[k + 1] - xs[k]
                    val t = (x - xs[k]) / h
                    val t2 = t * t
                    val t3 = t2 * t
                    val h00 = 2 * t3 - 3 * t2 + 1
                    val h10 = t3 - 2 * t2 + t
                    val h01 = -2 * t3 + 3 * t2
                    val h11 = t3 - t2
                    h00 * ys[k] + h10 * h * m[k] + h01 * ys[k + 1] + h11 * h * m[k + 1]
                }
            }.coerceIn(0f, 1f)
        }
        return lut
    }

    fun lookup(lut: FloatArray, v: Float): Float {
        val size = lut.size - 1
        val x = if (v <= 0f) 0f else if (v >= 1f) 1f else v
        val f = x * size
        val i = f.toInt()
        if (i >= size) return lut[size]
        val t = f - i
        return lut[i] + (lut[i + 1] - lut[i]) * t
    }
}
