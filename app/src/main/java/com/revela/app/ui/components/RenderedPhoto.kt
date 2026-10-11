package com.revela.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.revela.app.data.Photo
import com.revela.app.data.PhotoRepository
import com.revela.app.ui.theme.Surface2

/** Mostra a foto já com os ajustes aplicados (renderizada e em cache). */
@Composable
fun RenderedPhoto(
    photo: Photo,
    maxSide: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val bmp by produceState<Bitmap?>(null, photo.id, photo.rev, maxSide) {
        value = PhotoRepository.instance.render(photo.id, maxSide)
    }
    val b = bmp
    if (b != null) {
        Image(b.asImageBitmap(), contentDescription = photo.name, modifier = modifier, contentScale = contentScale)
    } else {
        Box(modifier.background(Surface2))
    }
}
