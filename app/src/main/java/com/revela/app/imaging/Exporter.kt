package com.revela.app.imaging

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.revela.app.data.PhotoRepository
import com.revela.app.model.EditParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportSize(val label: String, val maxSide: Int) {
    WEB("2048 px · redes sociais", 2048),
    LARGE("4096 px · alta", 4096),
    FULL("Máxima (até 6000 px)", 6000),
}

object Exporter {

    /** Renderiza a foto com os ajustes e salva em Galeria › Pictures/Revela. */
    suspend fun export(ctx: Context, photoId: String, params: EditParams, size: ExportSize, quality: Int = 92): Uri? =
        withContext(Dispatchers.IO) {
            val repo = PhotoRepository.instance
            val base = BitmapIO.decodeSampled(repo.imageFile(photoId), size.maxSide) ?: return@withContext null
            val out = ImageProcessor.render(base, params)
            base.recycle()

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Revela_$stamp.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Revela")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: return@withContext null
            try {
                resolver.openOutputStream(uri)?.use { out.compress(Bitmap.CompressFormat.JPEG, quality, it) }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                null
            } finally {
                out.recycle()
            }
        }
}
