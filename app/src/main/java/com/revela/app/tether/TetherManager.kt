package com.revela.app.tether

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.io.IOException
import com.revela.app.data.PhotoRepository
import com.revela.app.model.BuiltInPresets
import com.revela.app.tether.usb.UsbAccess
import com.revela.app.tether.usb.UsbPtpCamera
import com.revela.app.tether.wifi.SonyWifiCamera
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sessão de captura vinculada: mantém a conexão com a câmera, recebe as fotos,
 * aplica o preset escolhido e coloca tudo na biblioteca.
 */
object TetherManager {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow<TetherStatus>(TetherStatus.Disconnected)
    val status: StateFlow<TetherStatus> = _status.asStateFlow()

    /** Mensagem curta de atividade ("Recebendo DSC0123.ARW · 40%"). */
    private val _activity = MutableStateFlow<String?>(null)
    val activity: StateFlow<String?> = _activity.asStateFlow()

    /** Fotos desta sessão, da mais nova para a mais antiga. */
    private val _sessionCaptures = MutableStateFlow<List<String>>(emptyList())
    val sessionCaptures: StateFlow<List<String>> = _sessionCaptures.asStateFlow()

    /** Preset aplicado automaticamente em cada foto recebida (null = sem preset). */
    private val _presetId = MutableStateFlow<String?>(null)
    val presetId: StateFlow<String?> = _presetId.asStateFlow()

    private var link: CameraLink? = null
    private var job: Job? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
        val prefs = app.getSharedPreferences("tether", Context.MODE_PRIVATE)
        _presetId.value = prefs.getString("presetId", null)
        watchUsbEvents()
    }

    fun selectPreset(id: String?) {
        _presetId.value = id
        app.getSharedPreferences("tether", Context.MODE_PRIVATE).edit().putString("presetId", id).apply()
    }

    /**
     * Conecta por USB. Se a câmera ainda não estiver em modo PC Remoto (aparece sem a
     * interface de câmera, ou nem aparece), espera até [WAIT_PTP_MS] ela trocar de modo.
     */
    fun connectUsb(device: UsbDevice?) = start("USB") { status ->
        var dev = device?.takeIf { UsbAccess.hasPtp(it) }
        if (dev == null) {
            if (device != null) TetherLog.add("Sem interface de câmera: ${UsbAccess.describe(device)}")
            TetherLog.add("Aguardando a câmera aparecer em modo PC Remoto (até ${WAIT_PTP_MS / 1000}s)…")
            dev = waitForPtpDevice { secs ->
                status(
                    if (device != null) "Câmera detectada, esperando o modo PC Remoto… ${secs}s\nOlhe a tela da câmera."
                    else "Esperando a câmera no cabo USB… ${secs}s",
                )
            } ?: throw IOException(
                if (device != null || UsbAccess.allDevices(app).isNotEmpty())
                    "A câmera está no cabo, mas não entrou em PC Remoto. Ligue a câmera, deixe-a na tela de " +
                        "fotografar (fora do menu) e confira Rede › Função PC Remoto › PC Remoto = Lig., método USB."
                else
                    "O celular não está vendo a câmera no USB. Confira se a câmera está ligada, se o cabo " +
                        "passa dados e se o OTG está ativo no celular.",
            )
            TetherLog.add("Câmera em PC Remoto apareceu: ${UsbAccess.describe(dev)}")
        }
        status("Pedindo permissão USB…")
        TetherLog.add("Permissão USB: ${if (UsbAccess.hasPermission(app, dev)) "já concedida" else "pedindo…"}")
        val granted = withTimeoutOrNull(60_000) { UsbAccess.ensurePermission(app, dev) }
        TetherLog.add("Permissão USB: ${if (granted == true) "OK" else "negada/sem resposta"}")
        if (granted != true) {
            throw IllegalStateException("Permissão USB não concedida. Toque em Cabo USB de novo e aceite o aviso.")
        }
        UsbPtpCamera.open(app, dev, status)
    }

    private suspend fun waitForPtpDevice(onTick: (Int) -> Unit): UsbDevice? {
        val start = System.currentTimeMillis()
        var lastSeen = ""
        while (System.currentTimeMillis() - start < WAIT_PTP_MS) {
            val all = UsbAccess.allDevices(app)
            val seen = all.joinToString(" | ") { UsbAccess.describe(it) }.ifEmpty { "nenhum" }
            if (seen != lastSeen) {
                TetherLog.add("USB agora: $seen")
                lastSeen = seen
            }
            all.firstOrNull { UsbAccess.hasPtp(it) }?.let { return it }
            onTick(((WAIT_PTP_MS - (System.currentTimeMillis() - start)) / 1000).toInt())
            delay(500)
        }
        return null
    }

    /** Toque em "Cabo USB": usa a câmera que estiver no cabo, ou espera ela aparecer. */
    fun connectFirstUsb() {
        val all = UsbAccess.allDevices(app)
        TetherLog.add("Dispositivos USB vistos pelo Android: ${all.size}")
        all.forEach { TetherLog.add(" • ${UsbAccess.describe(it)}") }
        connectUsb(all.firstOrNull { UsbAccess.hasPtp(it) } ?: UsbAccess.cameras(app).firstOrNull())
    }

    private const val WAIT_PTP_MS = 45_000L

    /** Registra no diagnóstico quando algo é ligado/desligado do USB. */
    private fun watchUsbEvents() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val dev = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                val what = if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) "desconectado" else "conectado"
                TetherLog.add("USB $what: ${dev?.let { UsbAccess.describe(it) } ?: "?"}")
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    fun connectWifi() = start("Wi-Fi") { status -> SonyWifiCamera.connect(app, status) }

    private fun start(via: String, factory: suspend (status: (String) -> Unit) -> CameraLink) {
        disconnect()
        TetherLog.add("—— Nova conexão via $via (Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}) ——")
        job = scope.launch {
            _status.value = TetherStatus.Connecting("Conectando via $via…")
            var l: CameraLink? = null
            try {
                l = factory { msg -> _status.value = TetherStatus.Connecting(msg) }
                link = l
                _status.value = TetherStatus.Connected(l.displayName, via, l.canTrigger)
                _activity.value = if (l.canTrigger) "Pronto para disparar" else "Aguardando fotos da câmera…"
                l.listen(onInfo = { _activity.value = it }, onCapture = { handleCapture(it) })
                _status.value = TetherStatus.Disconnected
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TetherLog.add("Erro: ${e.javaClass.simpleName}: ${e.message}")
                _status.value = TetherStatus.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                l?.close()
                if (link === l) link = null
            }
        }
    }

    fun trigger() {
        val l = link ?: return
        scope.launch {
            try {
                _activity.value = "Disparando…"
                l.trigger()
                _activity.value = "Aguardando a foto…"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TetherLog.add("Falha no disparo: ${e.message}")
                _activity.value = "Falha no disparo: ${e.message}"
            }
        }
    }

    private suspend fun handleCapture(file: CapturedFile) {
        val repo = PhotoRepository.instance
        val preset = _presetId.value?.let { id ->
            (BuiltInPresets.all + repo.userPresets.value).firstOrNull { it.id == id }
        }
        val result = repo.addCaptured(file.name, file.bytes, preset)
        if (result == null) {
            _activity.value = "Não foi possível ler ${file.name}"
            return
        }
        val (photo, isNew) = result
        if (isNew) _sessionCaptures.update { listOf(photo.id) + it }
        _activity.value = "${file.name} recebida" + (preset?.let { " · ${it.name}" } ?: "")
    }

    // ------------------------------------------------------------------ segunda tela

    /** Último quadro ao vivo recebido da câmera. */
    private val _liveFrame = MutableStateFlow<Bitmap?>(null)
    val liveFrame: StateFlow<Bitmap?> = _liveFrame.asStateFlow()

    private val _liveFps = MutableStateFlow(0)
    val liveFps: StateFlow<Int> = _liveFps.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    val liveViewSupported: Boolean get() = link?.supportsLiveView == true
    val movieSupported: Boolean get() = link?.supportsMovie == true

    private var liveJob: Job? = null

    /** Começa a puxar quadros ao vivo enquanto a Segunda tela estiver aberta. */
    fun startLiveView() {
        if (liveJob?.isActive == true) return
        liveJob = scope.launch {
            var frames = 0
            var windowStart = System.currentTimeMillis()
            var failures = 0
            while (isActive) {
                val l = link
                if (l == null || !l.supportsLiveView) {
                    _liveFps.value = 0
                    delay(400)
                    continue
                }
                try {
                    val jpeg = l.liveViewFrame()
                    val bmp = jpeg?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    if (bmp != null) {
                        _liveFrame.value = bmp
                        frames++
                        failures = 0
                    } else {
                        delay(30)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (++failures > 20) delay(500) else delay(60)
                }
                val now = System.currentTimeMillis()
                if (now - windowStart >= 1000) {
                    _liveFps.value = (frames * 1000 / (now - windowStart)).toInt()
                    frames = 0
                    windowStart = now
                }
            }
        }
    }

    fun stopLiveView() {
        liveJob?.cancel()
        liveJob = null
        _liveFps.value = 0
    }

    fun toggleRecording() {
        val l = link ?: return
        val target = !_recording.value
        scope.launch {
            try {
                l.setRecording(target)
                _recording.value = target
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _activity.value = "Falha ao ${if (target) "iniciar" else "parar"} gravação: ${e.message}"
            }
        }
    }

    fun clearSession() {
        _sessionCaptures.value = emptyList()
    }

    fun disconnect() {
        job?.cancel()
        job = null
        link?.close()
        link = null
        _status.value = TetherStatus.Disconnected
        _activity.value = null
        _liveFrame.value = null
        _recording.value = false
    }
}
