package com.revela.app.ui.hub

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.revela.app.data.Photo
import com.revela.app.data.PhotoRepository
import com.revela.app.tether.TetherManager
import com.revela.app.tether.TetherStatus
import com.revela.app.ui.components.RenderedPhoto
import com.revela.app.ui.theme.Accent
import com.revela.app.ui.theme.Live
import com.revela.app.ui.theme.Surface1
import com.revela.app.ui.theme.Surface2
import com.revela.app.ui.theme.TextDim
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HubScreen(onOpenTether: () -> Unit, onOpenPhoto: (String) -> Unit) {
    val repo = PhotoRepository.instance
    val photos by repo.photos.collectAsStateWithLifecycle()
    val status by TetherManager.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var toDelete by remember { mutableStateOf<Photo?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            snackbar.showSnackbar("Importando ${uris.size} foto(s)…")
            val n = repo.importUris(uris)
            snackbar.showSnackbar("$n foto(s) importada(s)")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Revela") },
                actions = {
                    TextButton(onClick = { picker.launch(arrayOf("image/*")) }) {
                        Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Importar")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                TetherCard(status = status, onClick = onOpenTether)
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp, start = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.PhotoLibrary, null, tint = TextDim, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Biblioteca", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    Text("${photos.size}", color = TextDim, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (photos.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyLibrary(onImport = { picker.launch(arrayOf("image/*")) })
                }
            }
            items(photos, key = { it.id }) { photo ->
                Box(
                    Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .combinedClickable(
                            onClick = { onOpenPhoto(photo.id) },
                            onLongClick = { toDelete = photo },
                        ),
                ) {
                    RenderedPhoto(photo, maxSide = 360, modifier = Modifier.fillMaxSize())
                    if (photo.origin == "tether") {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(5.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Accent),
                        )
                    }
                }
            }
        }
    }

    toDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Apagar foto?") },
            text = { Text("${p.name} e seus ajustes serão removidos do app. Fotos já exportadas não são afetadas.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repo.delete(p.id) }
                    toDelete = null
                }) { Text("Apagar") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun TetherCard(status: TetherStatus, onClick: () -> Unit) {
    val (subtitle, dot) = when (status) {
        is TetherStatus.Connected -> "Conectada: ${status.camera} · ${status.via}" to Live
        is TetherStatus.Connecting -> status.message to Accent
        is TetherStatus.Failed -> "Falhou: ${status.message}" to Color(0xFFFF5C5C)
        TetherStatus.Disconnected -> "Conecte sua câmera por cabo USB ou Wi-Fi e veja cada clique na hora" to TextDim
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2A2116), Surface1, Surface2)))
            .border(1.dp, Accent.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.CameraAlt, null, tint = Accent)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Captura vinculada", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        subtitle, color = TextDim, style = MaterialTheme.typography.labelMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = TextDim)
        }
    }
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Nenhuma foto ainda", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Importe fotos (JPEG, HEIF ou RAW) ou use a captura vinculada.",
            color = TextDim, style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = onImport) { Text("Importar fotos") }
    }
}
