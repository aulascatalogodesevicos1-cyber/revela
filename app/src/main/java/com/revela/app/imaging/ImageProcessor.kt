package com.revela.app.imaging

import android.graphics.Bitmap
import com.revela.app.model.EditParams
import com.revela.app.model.HslColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Motor de revelação não destrutivo. Processa em CPU, em paralelo por faixas de linhas.
 *
 * Ordem do pipeline (parecida com a do Lightroom):
 * geometria → balanço de branco + exposição (linear) → desembaçar → claridade →
 * tons (pretos/brancos/sombras/realces/contraste + curva) → curvas R/G/B →
 * HSL / vibração / saturação → P&B → gradação de cor → vinheta → granulação →
 * nitidez / redução de ruído.
 */
object ImageProcessor {

    private val SRGB_TO_LINEAR = FloatArray(256) { i ->
        val c = i / 255f
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }
    private const val ENC = 4096
    private val LINEAR_TO_SRGB = FloatArray(ENC + 1) { i ->
        val c = i / ENC.toFloat()
        if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f
    }

    private fun encode(v: Float): Float {
        val x = if (v <= 0f) 0f else if (v >= 1f) 1f else v
        return LINEAR_TO_SRGB[(x * ENC).toInt()]
    }

    suspend fun render(src: Bitmap, p: EditParams): Bitmap = withContext(Dispatchers.Default) {
        val geo = Geometry.apply(src, p)
        val w = geo.width
        val h = geo.height
        val input = IntArray(w * h)
        geo.getPixels(input, 0, w, 0, 0, w, h)

        val blur = if (p.clarity != 0f) lumaBlurPixels(geo) else null
        if (geo !== src) geo.recycle()
        ensureActive()

        val ctx = Ctx(p, w, h)
        var out = IntArray(w * h)
        val dst = out
        parallelRows(h) { y0, y1, job -> processRows(input, dst, blur, w, y0, y1, ctx, job) }
        ensureActive()

        val k = p.sharpen / 100f * 1.2f - p.noiseReduction / 100f * 0.75f
        if (k != 0f) {
            val sharp = IntArray(w * h)
            val srcPx = out
            parallelRows(h) { y0, y1, job -> detailRows(srcPx, sharp, w, h, y0, y1, k, job) }
            out = sharp
        }
        ensureActive()
        Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    // ---------------------------------------------------------------------------------------

    private suspend fun parallelRows(h: Int, block: (Int, Int, Job) -> Unit) = coroutineScope {
        val n = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val step = (h + n - 1) / n
        val job = coroutineContext.job
        (0 until n).map { k ->
            async {
                val y0 = k * step
                val y1 = min(h, y0 + step)
                if (y0 < y1) block(y0, y1, job)
            }
        }.awaitAll()
    }

    /** Desfoque grande e barato (reduz 16x e amplia com filtro) usado pela Claridade. */
    private fun lumaBlurPixels(bmp: Bitmap): IntArray {
        val w = bmp.width
        val h = bmp.height
        val small = Bitmap.createScaledBitmap(bmp, max(1, w / 16), max(1, h / 16), true)
        val big = Bitmap.createScaledBitmap(small, w, h, true)
        val px = IntArray(w * h)
        big.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== bmp) small.recycle()
        if (big !== bmp) big.recycle()
        return px
    }

    private class Ctx(p: EditParams, w: Int, h: Int) {
        val rMul: Float
        val gMul: Float
        val bMul: Float

        init {
            val t = p.temperature / 100f
            val m = p.tint / 100f
            var r = 1f + 0.35f * t
            var g = 1f - 0.25f * m
            var b = 1f - 0.35f * t
            val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val ev = 2f.pow(p.exposure)
            r = r / lum * ev; g = g / lum * ev; b = b / lum * ev
            rMul = r; gMul = g; bMul = b
        }

        val dehaze = p.dehaze / 100f
        val clarity = p.clarity / 100f * 1.1f

        val tone: FloatArray = ImageProcessor.buildToneLut(p)
        val curveR = Curves.buildLut(p.curveR)
        val curveG = Curves.buildLut(p.curveG)
        val curveB = Curves.buildLut(p.curveB)

        val sat = 1f + p.saturation / 100f
        val vib = p.vibrance / 100f
        val bw = p.blackAndWhite
        val hslActive = p.hsl.any { !it.isZero }
        val needColor = hslActive || sat != 1f || vib != 0f || bw
        val hueShift = FloatArray(360)
        val satMul = FloatArray(360) { 1f }
        val lumAdd = FloatArray(360)

        init {
            if (hslActive) {
                val centers = HslColor.entries.map { it.hue } + 360f
                for (d in 0 until 360) {
                    var k = 0
                    while (k < 7 && d >= centers[k + 1]) k++
                    val c0 = centers[k]
                    val c1 = centers[k + 1]
                    var t = (d - c0) / (c1 - c0)
                    t = t * t * (3f - 2f * t)
                    val a = p.hsl[k]
                    val b = p.hsl[(k + 1) % 8]
                    hueShift[d] = (a.hue + (b.hue - a.hue) * t) * 0.3f
                    satMul[d] = 1f + (a.sat + (b.sat - a.sat) * t) / 100f
                    lumAdd[d] = (a.lum + (b.lum - a.lum) * t) / 100f * 0.3f
                }
            }
        }

        val split = p.shadowSat > 0f || p.highlightSat > 0f
        val shTint = tintOffsets(p.shadowHue, p.shadowSat)
        val hiTint = tintOffsets(p.highlightHue, p.highlightSat)

        val vignette = p.vignette / 100f
        val grain = p.grain / 100f * 0.16f
        val cx = w / 2f
        val cy = h / 2f

        private fun tintOffsets(hue: Float, amount: Float): FloatArray {
            val c = FloatArray(3)
            ImageProcessor.hslToRgb(hue, 1f, 0.5f, c)
            val y = 0.299f * c[0] + 0.587f * c[1] + 0.114f * c[2]
            val k = amount / 100f * 0.35f
            return floatArrayOf((c[0] - y) * k, (c[1] - y) * k, (c[2] - y) * k)
        }
    }

    private const val TONE = 1024

    private fun buildToneLut(p: EditParams): FloatArray {
        val bl = p.blacks / 100f
        val wh = p.whites / 100f
        val sh = p.shadows / 100f
        val hi = p.highlights / 100f
        val c = p.contrast / 100f
        val k = if (c >= 0f) 1f + c * 1.2f else 1f + c * 0.6f
        val master = Curves.buildLut(p.curveRgb)
        return FloatArray(TONE + 1) { i ->
            var v = i / TONE.toFloat()
            val inv = 1f - v
            v += bl * 0.25f * inv * inv * inv
            v += wh * 0.25f * v * v * v
            v += sh * 0.35f * v * inv * inv * 6.75f
            v += hi * 0.35f * v * v * inv * 6.75f
            v = v.coerceIn(0f, 1f)
            if (k != 1f) {
                v = if (v < 0.5f) 0.5f * (2f * v).pow(k) else 1f - 0.5f * (2f * (1f - v)).pow(k)
            }
            if (master != null) v = Curves.lookup(master, v)
            v
        }
    }

    private fun lut(t: FloatArray, v: Float): Float {
        val x = if (v <= 0f) 0f else if (v >= 1f) 1f else v
        return t[(x * (t.size - 1)).toInt()]
    }

    private fun processRows(
        src: IntArray, dst: IntArray, blur: IntArray?, w: Int,
        y0: Int, y1: Int, c: Ctx, job: Job,
    ) {
        val tmp = FloatArray(3)
        val invCx = 1f / c.cx
        val invCy = 1f / c.cy
        for (y in y0 until y1) {
            if (!job.isActive) return
            val row = y * w
            val dy = (y - c.cy) * invCy
            for (x in 0 until w) {
                val i = row + x
                val px = src[i]
                val ri = (px shr 16) and 0xFF
                val gi = (px shr 8) and 0xFF
                val bi = px and 0xFF

                // Balanço de branco + exposição em espaço linear
                var r = SRGB_TO_LINEAR[ri] * c.rMul
                var g = SRGB_TO_LINEAR[gi] * c.gMul
                var b = SRGB_TO_LINEAR[bi] * c.bMul

                // Desembaçar (remove/adiciona véu atmosférico)
                if (c.dehaze != 0f) {
                    if (c.dehaze > 0f) {
                        val a = c.dehaze * 0.06f
                        val inv = 1f / (1f - a)
                        r = (r - a) * inv; g = (g - a) * inv; b = (b - a) * inv
                    } else {
                        val a = -c.dehaze * 0.25f
                        r += (0.7f - r) * a; g += (0.7f - g) * a; b += (0.7f - b) * a
                    }
                }

                r = encode(r); g = encode(g); b = encode(b)

                // Claridade: contraste local nos meios-tons
                if (blur != null) {
                    val ls = (0.299f * ri + 0.587f * gi + 0.114f * bi) / 255f
                    val bp = blur[i]
                    val lb = (0.299f * ((bp shr 16) and 0xFF) + 0.587f * ((bp shr 8) and 0xFF) +
                        0.114f * (bp and 0xFF)) / 255f
                    val l = 0.299f * r + 0.587f * g + 0.114f * b
                    val d = (ls - lb) * c.clarity * (4f * l * (1f - l)).coerceIn(0f, 1f)
                    r += d; g += d; b += d
                }

                // Tons
                r = lut(c.tone, r); g = lut(c.tone, g); b = lut(c.tone, b)
                if (c.curveR != null) r = Curves.lookup(c.curveR, r)
                if (c.curveG != null) g = Curves.lookup(c.curveG, g)
                if (c.curveB != null) b = Curves.lookup(c.curveB, b)

                // Cor
                if (c.needColor) {
                    rgbToHsl(r, g, b, tmp)
                    var hh = tmp[0]
                    var s = tmp[1]
                    var l = tmp[2]
                    if (c.hslActive) {
                        var di = hh.toInt()
                        if (di < 0) di = 0 else if (di > 359) di = 359
                        l += c.lumAdd[di] * s
                        hh += c.hueShift[di]
                        if (hh < 0f) hh += 360f else if (hh >= 360f) hh -= 360f
                        s *= c.satMul[di]
                    }
                    if (!c.bw) {
                        if (c.vib != 0f) s *= 1f + c.vib * (1f - s)
                        s *= c.sat
                    }
                    s = s.coerceIn(0f, 1f)
                    l = l.coerceIn(0f, 1f)
                    hslToRgb(hh, s, l, tmp)
                    r = tmp[0]; g = tmp[1]; b = tmp[2]
                    if (c.bw) {
                        val yv = 0.299f * r + 0.587f * g + 0.114f * b
                        r = yv; g = yv; b = yv
                    }
                }

                // Gradação de cor
                if (c.split) {
                    val yv = (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0f, 1f)
                    val ws = (1f - yv) * (1f - yv)
                    val wh = yv * yv
                    r += c.shTint[0] * ws + c.hiTint[0] * wh
                    g += c.shTint[1] * ws + c.hiTint[1] * wh
                    b += c.shTint[2] * ws + c.hiTint[2] * wh
                }

                // Vinheta
                if (c.vignette != 0f) {
                    val dx = (x - c.cx) * invCx
                    var m = ((dx * dx + dy * dy) * 0.5f - 0.1f) / 0.9f
                    m = m.coerceIn(0f, 1f)
                    m = m * m * (3f - 2f * m)
                    if (c.vignette < 0f) {
                        val f = 1f + c.vignette * m * 0.85f
                        r *= f; g *= f; b *= f
                    } else {
                        val a = c.vignette * m * 0.7f
                        r += (1f - r) * a; g += (1f - g) * a; b += (1f - b) * a
                    }
                }

                // Granulação (determinística: igual em toda renderização)
                if (c.grain > 0f) {
                    var hsh = x * 374761393 + y * 668265263
                    hsh = (hsh xor (hsh ushr 13)) * 1274126177
                    hsh = hsh xor (hsh ushr 16)
                    val n = ((hsh and 0xFFFF) / 65535f - 0.5f) * c.grain
                    r += n; g += n; b += n
                }

                dst[i] = (0xFF shl 24) or (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
            }
        }
    }

    private fun to8(v: Float): Int {
        val i = (v * 255f + 0.5f).toInt()
        return if (i < 0) 0 else if (i > 255) 255 else i
    }

    /** Nitidez (k>0) ou suavização/redução de ruído (k<0) com máscara 3x3. */
    private fun detailRows(src: IntArray, dst: IntArray, w: Int, h: Int, y0: Int, y1: Int, k: Float, job: Job) {
        for (y in y0 until y1) {
            if (!job.isActive) return
            val ym = if (y > 0) y - 1 else 0
            val yp = if (y < h - 1) y + 1 else h - 1
            for (x in 0 until w) {
                val xm = if (x > 0) x - 1 else 0
                val xp = if (x < w - 1) x + 1 else w - 1
                val r0 = ym * w
                val r1 = y * w
                val r2 = yp * w
                val a0 = src[r0 + xm]; val a1 = src[r0 + x]; val a2 = src[r0 + xp]
                val b0 = src[r1 + xm]; val b1 = src[r1 + x]; val b2 = src[r1 + xp]
                val c0 = src[r2 + xm]; val c1 = src[r2 + x]; val c2 = src[r2 + xp]
                val sr = ch(a0, 16) + ch(a1, 16) + ch(a2, 16) + ch(b0, 16) + ch(b1, 16) + ch(b2, 16) +
                    ch(c0, 16) + ch(c1, 16) + ch(c2, 16)
                val sg = ch(a0, 8) + ch(a1, 8) + ch(a2, 8) + ch(b0, 8) + ch(b1, 8) + ch(b2, 8) +
                    ch(c0, 8) + ch(c1, 8) + ch(c2, 8)
                val sb = ch(a0, 0) + ch(a1, 0) + ch(a2, 0) + ch(b0, 0) + ch(b1, 0) + ch(b2, 0) +
                    ch(c0, 0) + ch(c1, 0) + ch(c2, 0)
                val c = src[y * w + x]
                val cr = (c shr 16) and 0xFF
                val cg = (c shr 8) and 0xFF
                val cb = c and 0xFF
                val r = cr + k * (cr - sr / 9f)
                val g = cg + k * (cg - sg / 9f)
                val b = cb + k * (cb - sb / 9f)
                dst[y * w + x] = (0xFF shl 24) or (clamp255(r) shl 16) or (clamp255(g) shl 8) or clamp255(b)
            }
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun ch(p: Int, shift: Int): Int = (p shr shift) and 0xFF

    private fun clamp255(v: Float): Int {
        val i = (v + 0.5f).toInt()
        return if (i < 0) 0 else if (i > 255) 255 else i
    }

    // ---------------------------------------------------------------- conversões de cor

    fun rgbToHsl(r: Float, g: Float, b: Float, out: FloatArray) {
        val rr = r.coerceIn(0f, 1f)
        val gg = g.coerceIn(0f, 1f)
        val bb = b.coerceIn(0f, 1f)
        val mx = max(rr, max(gg, bb))
        val mn = min(rr, min(gg, bb))
        val l = (mx + mn) / 2f
        if (mx == mn) {
            out[0] = 0f; out[1] = 0f; out[2] = l
            return
        }
        val d = mx - mn
        val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
        var h = when (mx) {
            rr -> (gg - bb) / d + (if (gg < bb) 6f else 0f)
            gg -> (bb - rr) / d + 2f
            else -> (rr - gg) / d + 4f
        }
        h *= 60f
        out[0] = h; out[1] = s; out[2] = l
    }

    fun hslToRgb(h: Float, s: Float, l: Float, out: FloatArray) {
        if (s <= 0f) {
            out[0] = l; out[1] = l; out[2] = l
            return
        }
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        val hk = h / 360f
        out[0] = hue2rgb(p, q, hk + 1f / 3f)
        out[1] = hue2rgb(p, q, hk)
        out[2] = hue2rgb(p, q, hk - 1f / 3f)
    }

    private fun hue2rgb(p: Float, q: Float, tIn: Float): Float {
        var t = tIn
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }
}
