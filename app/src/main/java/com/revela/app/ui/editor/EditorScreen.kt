package com.revela.app.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RotateLeft
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.revela.app.data.PhotoRepository
import com.revela.app.imaging.ExportSize
import com.revela.app.model.BuiltInPresets
import com.revela.app.model.CurveChannel
import com.revela.app.model.EditParams
import com.revela.app.model.HslAdjust
import com.revela.app.model.HslColor
import com.revela.app.model.IDENTITY_CURVE
import com.revela.app.model.Preset
import com.revela.app.ui.components.ParamSlider
import com.revela.app.ui.components.ToneCurveEditor
import com.revela.app.ui.theme.Accent
import com.revela.app.ui.theme.Surface1
import com.revela.app.ui.theme.Surface2
import com.revela.app.ui.theme.TextDim

enum class EditorTab(val label: String, val icon: ImageVector) {
    PRESETS("Presets", Icons.Outlined.AutoAwesome),
    LIGHT("Luz", Icons.Outlined.WbSunny),
    COLOR("Cor", Icons.Outlined.Palette),
    HSL("Mistura", Icons.Outlined.ColorLens),
    CURVE("Curva", Icons.Outlined.ShowChart),
    EFFECTS("Efeitos", Icons.Outlined.BlurOn),
    DETAIL("Detalhe", Icons.Outlined.Grain),
    CROP("Corte", Icons.Outlined.Crop),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(onBack: () -> Unit) {
    val vm: EditorViewModel = viewModel()
    val params by vm.params.collectAsStateWithLifecycle()
    val rendered by vm.rendered.collectAsStateWithLifecycle()
    val original by vm.original.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    val canRedo by vm.canRedo.collectAsStateWithLifecycle()
    val photos by PhotoRepository.instance.photos.collectAsStateWithLifecycle()
    val photo = photos.firstOrNull { it.id == vm.photoId }

    var tab by rememberSaveable { mutableStateOf(EditorTab.LIGHT) }
    var showBefore by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var savePresetDialog by remember { mutableStateOf(false) }
    var exportDialog by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(photo?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
                },
                actions = {
                    IconButton(onClick = vm::undo, enabled = canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Desfazer") }
                    IconButton(onClick = vm::redo, enabled = canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Refazer") }
                    // Segurar para ver o original
                    Box(
                        Modifier
                            .size(48.dp)
                            .pointerInput(Unit) {
                                detectTapGestures(onPress = {
                                    showBefore = true
                                    tryAwaitRelease()
                                    showBefore = false
                                })
                            },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Compare, "Antes/depois", tint = if (showBefore) Accent else Color.White) }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, "Mais") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Exportar para a galeria") }, onClick = { menuOpen = false; exportDialog = true })
                            DropdownMenuItem(text = { Text("Salvar como preset") }, onClick = { menuOpen = false; savePresetDialog = true })
                            DropdownMenuItem(text = { Text("Copiar ajustes") }, onClick = { menuOpen = false; vm.copySettings() })
                            DropdownMenuItem(text = { Text("Colar ajustes") }, onClick = { menuOpen = false; vm.pasteSettings() })
                            DropdownMenuItem(text = { Text("Redefinir tudo") }, onClick = { menuOpen = false; vm.reset() })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Color.Black,
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // ---------------------------------------------------------------- imagem
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            showBefore = true
                            tryAwaitRelease()
                            showBefore = false
                        })
                    },
                contentAlignment = Alignment.Center,
            ) {
                val shown: Bitmap? = if (showBefore) original else (rendered ?: original)
                if (shown != null) {
                    Image(
                        shown.asImageBitmap(), contentDescription = null,
                        modifier = Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit,
                    )
                }
                if (showBefore) {
                    Label("Original", Modifier.align(Alignment.TopStart).padding(14.dp))
                }
                if (busy || shown == null) {
                    CircularProgressIndicator(
                        Modifier.align(Alignment.TopEnd).padding(14.dp).size(18.dp),
                        strokeWidth = 2.dp, color = Accent,
                    )
                }
            }

            // ---------------------------------------------------------------- painel
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Surface1)
                    .navigationBarsPadding(),
            ) {
                Box(Modifier.fillMaxWidth().height(262.dp)) {
                    when (tab) {
                        EditorTab.PRESETS -> PresetsPanel(vm, onSaveNew = { savePresetDialog = true })
                        EditorTab.LIGHT -> LightPanel(params, vm)
                        EditorTab.COLOR -> ColorPanel(params, vm)
                        EditorTab.HSL -> HslPanel(params, vm)
                        EditorTab.CURVE -> CurvePanel(params, vm)
                        EditorTab.EFFECTS -> EffectsPanel(params, vm)
                        EditorTab.DETAIL -> DetailPanel(params, vm)
                        EditorTab.CROP -> CropPanel(params, vm)
                    }
                }
                LazyRow(
                    Modifier.fillMaxWidth().background(Color.Black),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    items(EditorTab.entries) { t ->
                        val sel = t == tab
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { tab = t }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(t.icon, null, tint = if (sel) Accent else TextDim, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.height(3.dp))
                            Text(t.label, style = MaterialTheme.typography.labelSmall, color = if (sel) Accent else TextDim)
                        }
                    }
                }
            }
        }
    }

    if (savePresetDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { savePresetDialog = false },
            title = { Text("Salvar preset") },
            text = {
                Column {
                    Text(
                        "Salva todos os ajustes atuais (menos corte e rotação). Ele aparece nos presets e na captura vinculada.",
                        color = TextDim, style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Nome") })
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.savePreset(name); savePresetDialog = false }) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { savePresetDialog = false }) { Text("Cancelar") } },
        )
    }

    if (exportDialog) {
        AlertDialog(
            onDismissRequest = { exportDialog = false },
            title = { Text("Exportar JPEG") },
            text = {
                Column {
                    ExportSize.entries.forEach { s ->
                        Text(
                            s.label,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { exportDialog = false; vm.export(s) }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { exportDialog = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x99000000))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun PanelColumn(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) { content() }
}

// ============================================================================ painéis

@Composable
private fun PresetsPanel(vm: EditorViewModel, onSaveNew: () -> Unit) {
    val user by PhotoRepository.instance.userPresets.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<Preset?>(null) }
    val all = BuiltInPresets.all + user
    Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
        Text(
            "Toque para aplicar · segure um preset seu para apagar",
            color = TextDim, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(
                    Modifier.width(96.dp).clickable(onClick = onSaveNew),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Accent.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Add, null, tint = Accent) }
                    Spacer(Modifier.height(6.dp))
                    Text("Salvar atual", style = MaterialTheme.typography.labelSmall, color = Accent)
                }
            }
            items(all, key = { it.id }) { preset ->
                val preview by produceState<Bitmap?>(null, preset.id, vm.photoId) { value = vm.presetPreview(preset) }
                Column(
                    Modifier
                        .width(96.dp)
                        .pointerInput(preset.id) {
                            detectTapGestures(
                                onTap = { vm.applyPreset(preset) },
                                onLongPress = { if (!preset.builtIn) confirmDelete = preset },
                            )
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)).background(Surface2)) {
                        preview?.let {
                            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(preset.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Apagar \"${p.name}\"?") },
            confirmButton = { TextButton(onClick = { vm.deletePreset(p); confirmDelete = null }) { Text("Apagar") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun LightPanel(p: EditParams, vm: EditorViewModel) = PanelColumn {
    ParamSlider("Exposição", p.exposure, -5f..5f, { v -> vm.update { it.copy(exposure = v) } }, vm::commit, decimals = 2)
    ParamSlider("Contraste", p.contrast, -100f..100f, { v -> vm.update { it.copy(contrast = v) } }, vm::commit)
    ParamSlider("Realces", p.highlights, -100f..100f, { v -> vm.update { it.copy(highlights = v) } }, vm::commit)
    ParamSlider("Sombras", p.shadows, -100f..100f, { v -> vm.update { it.copy(shadows = v) } }, vm::commit)
    ParamSlider("Brancos", p.whites, -100f..100f, { v -> vm.update { it.copy(whites = v) } }, vm::commit)
    ParamSlider("Pretos", p.blacks, -100f..100f, { v -> vm.update { it.copy(blacks = v) } }, vm::commit)
}

@Composable
private fun ColorPanel(p: EditParams, vm: EditorViewModel) = PanelColumn {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Preto e branco", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = p.blackAndWhite,
            onCheckedChange = { c -> vm.set(p.copy(blackAndWhite = c)) },
            colors = SwitchDefaults.colors(checkedTrackColor = Accent),
        )
    }
    ParamSlider(
        "Temperatura", p.temperature, -100f..100f, { v -> vm.update { it.copy(temperature = v) } }, vm::commit,
        gradient = listOf(Color(0xFF3E7BFA), Color(0xFFDDDDDD), Color(0xFFF2B33D)),
    )
    ParamSlider(
        "Tonalidade", p.tint, -100f..100f, { v -> vm.update { it.copy(tint = v) } }, vm::commit,
        gradient = listOf(Color(0xFF3FBF5A), Color(0xFFDDDDDD), Color(0xFFD64FC0)),
    )
    if (!p.blackAndWhite) {
        ParamSlider("Vibração", p.vibrance, -100f..100f, { v -> vm.update { it.copy(vibrance = v) } }, vm::commit)
        ParamSlider("Saturação", p.saturation, -100f..100f, { v -> vm.update { it.copy(saturation = v) } }, vm::commit)
    }
    SectionTitle("Gradação de cor")
    ParamSlider(
        "Sombras · matiz", p.shadowHue, 0f..360f, { v -> vm.update { it.copy(shadowHue = v) } }, vm::commit,
        default = 220f, signed = false, gradient = hueGradient,
    )
    ParamSlider("Sombras · intensidade", p.shadowSat, 0f..100f, { v -> vm.update { it.copy(shadowSat = v) } }, vm::commit, signed = false)
    ParamSlider(
        "Realces · matiz", p.highlightHue, 0f..360f, { v -> vm.update { it.copy(highlightHue = v) } }, vm::commit,
        default = 40f, signed = false, gradient = hueGradient,
    )
    ParamSlider("Realces · intensidade", p.highlightSat, 0f..100f, { v -> vm.update { it.copy(highlightSat = v) } }, vm::commit, signed = false)
}

private val hueGradient = listOf(
    Color(0xFFFF4040), Color(0xFFFFD640), Color(0xFF40FF6A), Color(0xFF40E0FF),
    Color(0xFF4060FF), Color(0xFFD040FF), Color(0xFFFF4040),
)

private enum class HslMode(val label: String) { HUE("Matiz"), SAT("Saturação"), LUM("Luminância") }

@Composable
private fun HslPanel(p: EditParams, vm: EditorViewModel) {
    var mode by rememberSaveable { mutableStateOf(HslMode.SAT) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HslMode.entries.forEach { m ->
                FilterChip(
                    selected = m == mode, onClick = { mode = m }, label = { Text(m.label) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent.copy(alpha = 0.2f), selectedLabelColor = Accent),
                )
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            HslColor.entries.forEachIndexed { i, c ->
                val adj = p.hsl[i]
                val value = when (mode) {
                    HslMode.HUE -> adj.hue
                    HslMode.SAT -> adj.sat
                    HslMode.LUM -> adj.lum
                }
                ParamSlider(
                    c.label, value, -100f..100f,
                    onChange = { v ->
                        vm.update { cur ->
                            val a = cur.hsl[i]
                            val na: HslAdjust = when (mode) {
                                HslMode.HUE -> a.copy(hue = v)
                                HslMode.SAT -> a.copy(sat = v)
                                HslMode.LUM -> a.copy(lum = v)
                            }
                            cur.withHsl(i, na)
                        }
                    },
                    onCommit = vm::commit,
                    gradient = listOf(Color(c.swatch).copy(alpha = 0.35f), Color(c.swatch)),
                )
            }
        }
    }
}

@Composable
private fun CurvePanel(p: EditParams, vm: EditorViewModel) {
    var channel by rememberSaveable { mutableStateOf(CurveChannel.RGB) }
    val color = when (channel) {
        CurveChannel.RGB -> Color.White
        CurveChannel.RED -> Color(0xFFFF5A5A)
        CurveChannel.GREEN -> Color(0xFF4ADE80)
        CurveChannel.BLUE -> Color(0xFF60A5FA)
    }
    Row(Modifier.fillMaxSize().padding(12.dp)) {
        ToneCurveEditor(
            points = p.curve(channel),
            color = color,
            onChange = { pts -> vm.update { it.withCurve(channel, pts) } },
            onCommit = vm::commit,
            modifier = Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true).background(Color(0xFF111114)),
        )
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CurveChannel.entries.forEach { ch ->
                FilterChip(
                    selected = ch == channel, onClick = { channel = ch }, label = { Text(ch.label) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent.copy(alpha = 0.2f), selectedLabelColor = Accent),
                )
            }
            TextButton(onClick = { vm.set(p.withCurve(channel, IDENTITY_CURVE)) }) { Text("Zerar") }
        }
    }
}

@Composable
private fun EffectsPanel(p: EditParams, vm: EditorViewModel) = PanelColumn {
    ParamSlider("Claridade", p.clarity, -100f..100f, { v -> vm.update { it.copy(clarity = v) } }, vm::commit)
    ParamSlider("Desembaçar", p.dehaze, -100f..100f, { v -> vm.update { it.copy(dehaze = v) } }, vm::commit)
    ParamSlider("Vinheta", p.vignette, -100f..100f, { v -> vm.update { it.copy(vignette = v) } }, vm::commit)
    ParamSlider("Granulação", p.grain, 0f..100f, { v -> vm.update { it.copy(grain = v) } }, vm::commit, signed = false)
}

@Composable
private fun DetailPanel(p: EditParams, vm: EditorViewModel) = PanelColumn {
    ParamSlider("Nitidez", p.sharpen, 0f..100f, { v -> vm.update { it.copy(sharpen = v) } }, vm::commit, signed = false)
    ParamSlider("Redução de ruído", p.noiseReduction, 0f..100f, { v -> vm.update { it.copy(noiseReduction = v) } }, vm::commit, signed = false)
    Text(
        "A prévia é reduzida; a nitidez final aparece melhor na exportação em tamanho grande.",
        color = TextDim, style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(16.dp),
    )
}

private val ASPECTS = listOf(
    "Original" to 0f, "1:1" to 1f, "4:5" to 4f / 5f, "5:4" to 5f / 4f,
    "3:2" to 3f / 2f, "2:3" to 2f / 3f, "16:9" to 16f / 9f, "9:16" to 9f / 16f,
)

@Composable
private fun CropPanel(p: EditParams, vm: EditorViewModel) = PanelColumn {
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.set(p.copy(rotation = (p.rotation + 270) % 360)) }) {
            Icon(Icons.Outlined.RotateLeft, "Girar à esquerda")
        }
        OutlinedButton(onClick = { vm.set(p.copy(rotation = (p.rotation + 90) % 360)) }) {
            Icon(Icons.Outlined.RotateRight, "Girar à direita")
        }
        OutlinedButton(onClick = { vm.set(p.copy(flipH = !p.flipH)) }) {
            Icon(Icons.Outlined.Flip, "Espelhar")
        }
    }
    Spacer(Modifier.height(8.dp))
    ParamSlider("Endireitar", p.straighten, -45f..45f, { v -> vm.update { it.copy(straighten = v) } }, vm::commit, decimals = 1)
    SectionTitle("Proporção")
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(ASPECTS) { (label, ratio) ->
            FilterChip(
                selected = p.cropAspect == ratio,
                onClick = { vm.set(p.copy(cropAspect = ratio)) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent.copy(alpha = 0.2f), selectedLabelColor = Accent),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = TextDim,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
    )
}
