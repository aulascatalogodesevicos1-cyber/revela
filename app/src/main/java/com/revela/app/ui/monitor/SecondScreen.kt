package com.revela.app.ui.monitor

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.Grid3x3
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.revela.app.tether.TetherManager
import com.revela.app.tether.TetherStatus
import com.revela.app.ui.theme.Accent
import com.revela.app.ui.theme.Danger
import com.revela.app.ui.theme.Live
import com.revela.app.ui.theme.TextDim
import kotlinx.coroutines.delay

/** Guias de enquadramento para redes sociais. */
private enum class FrameGuide(val label: String, val ratio: Float) {
    NONE("Sem guia", 0f), VERTICAL("9:16", 9f / 16f), SQUARE("1:1", 1f), PORTRAIT("4:5", 4f / 5f),
}

/**
 * Segunda tela: o celular vira um monitor ao vivo da câmera (ZV-E10 em PC Remoto via USB),
 * com grade, guias de corte, espelhamento, brilho máximo, disparo e gravação.
 */
@Composable
fun SecondScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val status by TetherManager.status.collectAsStateWithLifecycle()
    val frame by TetherManager.liveFrame.collectAsStateWithLifecycle()
    val fps by TetherManager.liveFps.collectAsStateWithLifecycle()
    val recording by TetherManager.recording.collectAsStateWithLifecycle()

    var showGrid by rememberSaveable { mutableStateOf(true) }
    var mirror by rememberSaveable { mutableStateOf(false) }
    var guide by rememberSaveable { mutableStateOf(FrameGuide.NONE) }
    var maxBrightness by rememberSaveable { mutableStateOf(true) }
    var controlsVisible by remember { mutableStateOf(true) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var recStart by remember { mutableLongStateOf(0L) }
    var recElapsed by remember { mutableLongStateOf(0L) }

    val connected = status is TetherStatus.Connected
    val camName = (status as? TetherStatus.Connected)?.camera

    // Liga o fluxo ao vivo só enquanto esta tela existe
    DisposableEffect(Unit) {
        TetherManager.startLiveView()
        onDispose { TetherManager.stopLiveView() }
    }

    // Tela cheia, sempre acesa e (opcional) com brilho máximo
    val view = LocalView.current
    DisposableEffect(maxBrightness) {
        val window = (ctx as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        window?.let {
            val lp = it.attributes
            lp.screenBrightness = if (maxBrightness) 1f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            it.attributes = lp
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
            window?.let {
                val lp = it.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                it.attributes = lp
            }
        }
    }

    // Esconde os controles depois de 4 s sem toque
    LaunchedEffect(lastTouch, controlsVisible) {
        if (controlsVisible) {
            delay(4000)
            controlsVisible = false
        }
    }

    // Cronômetro de gravação
    LaunchedEffect(recording) {
        if (recording) {
            recStart = System.currentTimeMillis()
            while (true) {
                recElapsed = System.currentTimeMillis() - recStart
                delay(250)
            }
        } else {
            recElapsed = 0L
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    controlsVisible = !controlsVisible
                    lastTouch = System.currentTimeMillis()
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        val f = frame
        if (f != null && connected) {
            val aspect = f.width.toFloat() / f.height
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val boxAspect = maxWidth / maxHeight
                val (w, h) = if (boxAspect > aspect) (maxHeight * aspect) to maxHeight else maxWidth to (maxWidth / aspect)
                Box(Modifier.size(w, h)) {
                    Image(
                        f.asImageBitmap(), contentDescription = "Imagem ao vivo da câmera",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { scaleX = if (mirror) -1f else 1f },
                    )
                    Overlays(showGrid, guide)
                    if (recording) {
                        Box(Modifier.fillMaxSize().border(3.dp, Danger))
                    }
                }
            }
        } else {
            WaitingState(
                connected = connected,
                onConnect = { TetherManager.connectFirstUsb() },
                status = status,
            )
        }

        // ---------------------------------------------------------------- topo
        AnimatedVisibility(
            visible = controlsVisible || !connected,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .safeDrawingPadding()
                    .background(Color(0x66000000))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = Color.White) }
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (connected) Live else TextDim))
                Spacer(Modifier.width(8.dp))
                Text(camName ?: "Sem câmera", color = Color.White, style = MaterialTheme.typography.labelMedium)
                if (connected) {
                    Spacer(Modifier.width(10.dp))
                    Text("$fps fps", color = TextDim, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.weight(1f))
                if (recording) {
                    RecBadge(recElapsed)
                    Spacer(Modifier.width(8.dp))
                }
            }
        }

        // ---------------------------------------------------------------- base
        AnimatedVisibility(
            visible = (controlsVisible && connected),
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .safeDrawingPadding()
                    .background(Color(0x66000000))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToggleChip("Grade", showGrid, Icons.Outlined.Grid3x3) { showGrid = !showGrid; lastTouch = System.currentTimeMillis() }
                    ToggleChip("Espelhar", mirror, Icons.Outlined.Flip) { mirror = !mirror; lastTouch = System.currentTimeMillis() }
                    ToggleChip("Brilho máx.", maxBrightness, Icons.Outlined.LightMode) { maxBrightness = !maxBrightness; lastTouch = System.currentTimeMillis() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FrameGuide.entries.forEach { g ->
                        ToggleChip(g.label, guide == g, null) { guide = g; lastTouch = System.currentTimeMillis() }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundButton(
                        label = "Foto", size = 62, ring = Color.White, fill = Accent,
                        onClick = { TetherManager.trigger(); lastTouch = System.currentTimeMillis() },
                    )
                    if (TetherManager.movieSupported) {
                        Spacer(Modifier.width(36.dp))
                        RoundButton(
                            label = if (recording) "Parar" else "REC", size = 62, ring = Color.White,
                            fill = Danger, square = recording,
                            onClick = { TetherManager.toggleRecording(); lastTouch = System.currentTimeMillis() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Overlays(showGrid: Boolean, guide: FrameGuide) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (showGrid) {
            val c = Color(0x88FFFFFF)
            for (k in 1..2) {
                drawLine(c, Offset(w * k / 3f, 0f), Offset(w * k / 3f, h), 1.5f)
                drawLine(c, Offset(0f, h * k / 3f), Offset(w, h * k / 3f), 1.5f)
            }
            // cruz central
            val cx = w / 2f
            val cy = h / 2f
            drawLine(c, Offset(cx - 14f, cy), Offset(cx + 14f, cy), 1.5f)
            drawLine(c, Offset(cx, cy - 14f), Offset(cx, cy + 14f), 1.5f)
        }
        if (guide != FrameGuide.NONE) {
            val r = guide.ratio
            val (gw, gh) = if (w / h > r) (h * r) to h else w to (w / r)
            val left = (w - gw) / 2f
            val top = (h - gh) / 2f
            val shade = Color(0x99000000)
            // escurece o que fica fora do corte
            drawRect(shade, Offset(0f, 0f), Size(left, h))
            drawRect(shade, Offset(left + gw, 0f), Size(w - left - gw, h))
            drawRect(shade, Offset(left, 0f), Size(gw, top))
            drawRect(shade, Offset(left, top + gh), Size(gw, h - top - gh))
            drawRect(Accent, Offset(left, top), Size(gw, gh), style = Stroke(2f))
        }
    }
}

@Composable
private fun ToggleChip(label: String, on: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (on) Accent.copy(alpha = 0.22f) else Color(0x33FFFFFF))
            .border(1.dp, if (on) Accent else Color.Transparent, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (on) Accent else Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = if (on) Accent else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RoundButton(label: String, size: Int, ring: Color, fill: Color, square: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size.dp)
                .clip(CircleShape)
                .border(3.dp, ring, CircleShape)
                .clickable(onClick = onClick)
                .padding(if (square) 18.dp else 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(if (square) RoundedCornerShape(4.dp) else CircleShape)
                    .background(fill),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RecBadge(elapsedMs: Long) {
    val s = elapsedMs / 1000
    val text = "%02d:%02d".format(s / 60, s % 60)
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(Danger).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White))
        Spacer(Modifier.width(6.dp))
        Text("REC $text", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun WaitingState(connected: Boolean, onConnect: () -> Unit, status: TetherStatus) {
    Column(
        Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            when {
                connected && !TetherManager.liveViewSupported -> "Esta câmera não envia imagem ao vivo por este modo"
                connected -> "Recebendo imagem ao vivo…"
                status is TetherStatus.Connecting -> status.message
                status is TetherStatus.Failed -> status.message
                else -> "Conecte a câmera para usar como monitor"
            },
            color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
        )
        if (!connected && status !is TetherStatus.Connecting) {
            Spacer(Modifier.height(10.dp))
            Text(
                "ZV-E10: MENU › Rede › Função PC Remoto › PC Remoto = Lig., método USB. " +
                    "Ligue o cabo USB-C e toque abaixo.",
                color = TextDim, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            OutlinedButton(onClick = onConnect) {
                Icon(Icons.Outlined.Usb, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Conectar pelo cabo USB")
            }
        }
    }
}
