package com.revela.app.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.revela.app.model.EditParams
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Girar, espelhar, endireitar e recortar por proporção. */
object Geometry {

    fun apply(src: Bitmap, p: EditParams): Bitmap {
        if (!p.hasGeometry) return src
        var bmp = src

        if (p.rotation % 360 != 0 || p.flipH) {
            val m = Matrix()
            if (p.flipH) m.postScale(-1f, 1f)
            m.postRotate(p.rotation.toFloat())
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }

        if (p.straighten != 0f) {
            val w = bmp.width
            val h = bmp.height
            val a = Math.toRadians(abs(p.straighten).toDouble())
            val ratio = max(w, h).toDouble() / min(w, h).toDouble()
            // Maior retângulo de mesma proporção inscrito na imagem girada
            val s = 1.0 / (cos(a) + ratio * sin(a))
            val ow = max(1, (w * s).roundToInt())
            val oh = max(1, (h * s).roundToInt())
            val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.translate(ow / 2f, oh / 2f)
            c.rotate(p.straighten)
            c.drawBitmap(bmp, -w / 2f, -h / 2f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            bmp = out
        }

        if (p.cropAspect > 0f) {
            val w = bmp.width
            val h = bmp.height
            val cur = w.toFloat() / h
            val target = p.cropAspect
            if (abs(cur - target) > 0.005f) {
                val (cw, ch) = if (cur > target) {
                    (h * target).roundToInt() to h
                } else {
                    w to (w / target).roundToInt()
                }
                val x = (w - cw) / 2
                val y = (h - ch) / 2
                bmp = Bitmap.createBitmap(bmp, x, y, max(1, cw), max(1, ch))
            }
        }
        return bmp
    }
}
