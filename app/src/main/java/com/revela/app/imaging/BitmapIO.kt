package com.revela.app.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

object BitmapIO {

    private val RAW_EXT = setOf("arw", "srf", "sr2", "cr2", "cr3", "nef", "nrw", "raf", "rw2", "orf", "dng", "pef", "srw")
    private val IMAGE_EXT = setOf("jpg", "jpeg", "heif", "heic", "hif", "png", "webp") + RAW_EXT

    fun extension(name: String) = name.substringAfterLast('.', "").lowercase()
    fun isRaw(name: String) = extension(name) in RAW_EXT
    fun isImage(name: String) = extension(name) in IMAGE_EXT

    /** Decodifica reduzindo para no máximo [maxSide] px no lado maior, já corrigindo a orientação EXIF. */
    fun decodeSampled(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = BitmapFactory.decodeFile(file.path, opts) ?: return null
        val longSide = max(bmp.width, bmp.height)
        if (longSide > maxSide) {
            val s = maxSide.toFloat() / longSide
            val scaled = Bitmap.createScaledBitmap(
                bmp, max(1, (bmp.width * s).roundToInt()), max(1, (bmp.height * s).roundToInt()), true,
            )
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        return applyExifOrientation(bmp, file)
    }

    private fun applyExifOrientation(bmp: Bitmap, file: File): Bitmap {
        val orientation = try {
            ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    /**
     * Arquivos RAW (ARW, CR2, NEF, RAF…) carregam um JPEG de pré-visualização embutido.
     * Procuramos todos os JPEGs dentro do arquivo e devolvemos o de maior resolução.
     */
    fun extractLargestJpeg(bytes: ByteArray): ByteArray? {
        var bestStart = -1
        var bestEnd = -1
        var bestArea = 0L
        var candidates = 0
        var i = 0
        val n = bytes.size
        while (i < n - 3 && candidates < 48) {
            if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xD8.toByte() && bytes[i + 2] == 0xFF.toByte()) {
                candidates++
                val end = jpegEnd(bytes, i)
                if (end > i) {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, i, end - i, o)
                    val area = o.outWidth.toLong() * o.outHeight.toLong()
                    if (area > bestArea) {
                        bestArea = area; bestStart = i; bestEnd = end
                    }
                    i = end
                    continue
                }
            }
            i++
        }
        return if (bestStart >= 0) bytes.copyOfRange(bestStart, bestEnd) else null
    }

    /** Percorre os segmentos do JPEG a partir do SOI e devolve a posição logo após o EOI (ou -1). */
    fun jpegEnd(b: ByteArray, start: Int): Int {
        val n = b.size
        var i = start + 2
        while (i + 1 < n) {
            if (b[i] != 0xFF.toByte()) return -1
            val marker = b[i + 1].toInt() and 0xFF
            when {
                marker == 0xFF -> { i++; continue }
                marker == 0xD9 -> return i + 2
                marker in 0xD0..0xD7 || marker == 0x01 -> { i += 2; continue }
            }
            if (i + 3 >= n) return -1
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (len < 2) return -1
            i += 2 + len
            if (marker == 0xDA) {
                // Dados entrópicos: avança até achar um marcador que não seja byte-stuffing nem RSTn
                while (i + 1 < n) {
                    if (b[i] == 0xFF.toByte()) {
                        val m = b[i + 1].toInt() and 0xFF
                        if (m == 0x00 || m in 0xD0..0xD7) { i += 2; continue }
                        if (m == 0xFF) { i++; continue }
                        break
                    }
                    i++
                }
            }
        }
        return -1
    }
}
