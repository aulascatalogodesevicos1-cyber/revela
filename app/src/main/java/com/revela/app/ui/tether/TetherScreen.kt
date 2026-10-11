package com.revela.app.ui.tether

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.revela.app.data.PhotoRepository
import com.revela.app.model.BuiltInPresets
import com.revela.app.tether.TetherManager
import com.revela.app.tether.TetherStatus
import com.revela.app.tether.usb.UsbAccess
import com.revela.app.ui.components.RenderedPhoto
import com.revela.app.ui.theme.Accent
import com.revela.app.ui.theme.Danger
import com.revela.app.ui.theme.Live
import com.revela.app.ui.theme.Surface1
import com.revela.app.ui.theme.Surface2
import com.revela.app.ui.theme.TextDim
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TetherScreen(onBack: () -> Unit, onOpenPhoto: (String) -> Unit) {
    val ctx = LocalContext.current
    val repo = PhotoRepository.instance
    val status by TetherManager.status.collectAsStateWithLifecycle()
    val activity by TetherManager.activity.collectAsStateWithLifecycle()
    val captureIds by TetherManager.sessionCaptures.collectAsStateWithLifecycle()
    val presetId by TetherManager.presetId.collectAsStateWithLifecycle()
    val photos by repo.photos.collectAsStateWithLifecycle()
    val userPresets by repo.userPresets.collectAsStateWithLifecycle()
    val presets = BuiltInPresets.all + userPresets
    val captures = captureIds.mapNotNull { id -> photos.firstOrNull { it.id == id } }
    val latest = captures.firstOrNull()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Tela sempre acesa durante a sessão
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val connected = status is TetherStatus.Connected
    val canTrigger = (status as? TetherStatus.Connected)?.canTrigger == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Captura vinculada") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
                },
                actions = {
                    if (status !is TetherStatus.Disconnected) {
                        IconButton(onClick = { TetherManager.disconnect() }) {
                            Icon(Icons.Outlined.LinkOff, "Desconectar")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).navigationBarsPadding()) {
            // ---------------------------------------------------------------- conexão
            ConnectionBar(
                status = status,
                activity = activity,
                onUsb = {
                    val cams = UsbAccess.cameras(ctx)
                    if (cams.isEmpty()) {
                        scope.launch {
                            snackbar.showSnackbar(
                                "Nenhuma câmera no USB. Na ZV-E10: Rede › Função PC Remoto › PC Remoto = Lig., método USB.",
                            )
                        }
                    } else {
                        TetherManager.connectUsb(cams.first())
                    }
                },
                onWifi = { TetherManager.connectWifi() },
            )

            // ---------------------------------------------------------------- preset
            PresetPicker(
                presets = presets.map { it.id to it.name },
                selectedId = presetId,
                onSelect = { TetherManager.selectPreset(it) },
            )

            // ---------------------------------------------------------------- última foto
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
                    .clickable(enabled = latest != null) { latest?.let { onOpenPhoto(it.id) } },
                contentAlignment = Alignment.Center,
            ) {
                if (latest != null) {
                    RenderedPhoto(latest, maxSide = 1600, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    Text(
                        latest.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(10.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x99000000))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                } else {
                    EmptyHint(connected)
                }
            }

            // ---------------------------------------------------------------- filmstrip
            if (captures.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(captures, key = { it.id }) { p ->
                        RenderedPhoto(
                            p, maxSide = 240,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .then(
                                    if (p.id == latest?.id) Modifier.border(2.dp, Accent, RoundedCornerShape(6.dp))
                                    else Modifier,
                                )
                                .clickable { onOpenPhoto(p.id) },
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(12.dp))
            }

            // ---------------------------------------------------------------- obturador
            Box(Modifier.fillMaxWidth().padding(bottom = 16.dp), contentAlignment = Alignment.Center) {
                ShutterButton(enabled = canTrigger, onClick = { TetherManager.trigger() })
            }
        }
    }
}

@Composable
private fun ConnectionBar(status: TetherStatus, activity: String?, onUsb: () -> Unit, onWifi: () -> Unit) {
    val (title, color) = when (status) {
        is TetherStatus.Connected -> "${status.camera} · ${status.via}" to Live
        is TetherStatus.Connecting -> status.message to Accent
        is TetherStatus.Failed -> status.message to Danger
        TetherStatus.Disconnected -> "Nenhuma câmera conectada" to TextDim
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(10.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (activity != null && status is TetherStatus.Connected) {
            Spacer(Modifier.height(4.dp))
            Text(activity, color = TextDim, style = MaterialTheme.typography.labelMedium)
        }
        if (status !is TetherStatus.Connected && status !is TetherStatus.Connecting) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onUsb, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Usb, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Cabo USB")
                }
                OutlinedButton(onClick = onWifi, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Wifi, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Wi-Fi Sony")
                }
            }
        }
    }
}

@Composable
private fun PresetPicker(presets: List<Pair<String, String>>, selectedId: String?, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selectedName = presets.firstOrNull { it.first == selectedId }?.second
    Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Surface2)
                .clickable { open = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = if (selectedName != null) Accent else TextDim, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Preset na captura", style = MaterialTheme.typography.labelSmall, color = TextDim)
                Text(selectedName ?: "Nenhum (foto original)", style = MaterialTheme.typography.bodyMedium)
            }
            Text("Trocar", color = Accent, style = MaterialTheme.typography.labelMedium)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Nenhum (foto original)") }, onClick = { onSelect(null); open = false })
            presets.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name, color = if (id == selectedId) Accent else Color.Unspecified) },
                    onClick = { onSelect(id); open = false },
                )
            }
        }
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    val ring = if (enabled) Color.White else Color(0xFF4A4A50)
    Box(
        Modifier
            .size(76.dp)
            .clip(CircleShape)
            .border(4.dp, ring, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(if (enabled) Accent else Color(0xFF2A2A2F)))
    }
}

@Composable
private fun EmptyHint(connected: Boolean) {
    Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (connected) "Faça um clique — a foto aparece aqui em segundos."
            else "Como conectar",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        if (!connected) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Sony ZV-E10 (cabo USB):\n" +
                    "1. MENU › Rede › Conexão Smartphone › Conexão Smartphone = Desl.\n" +
                    "2. MENU › Rede › Função PC Remoto › PC Remoto = Lig.\n" +
                    "3. Método Conexão PC Remoto = USB · Destino salv. imagem = Só PC (ou PC+Câmera)\n" +
                    "4. Ligue com cabo USB-C ↔ USB-C e toque em Cabo USB.\n\n" +
                    "Wi-Fi Sony: só modelos antigos com \"Smart Remote\" (a6000, a7 III…). A ZV-E10 usa o cabo.\n\n" +
                    "O preset escolhido acima já é aplicado em cada foto que chegar.",
                color = TextDim,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
