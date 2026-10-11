package com.revela.app.tether

import android.content.Context
import android.hardware.usb.UsbDevice
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    }

    fun selectPreset(id: String?) {
        _presetId.value = id
        app.getSharedPreferences("tether", Context.MODE_PRIVATE).edit().putString("presetId", id).apply()
    }

    fun connectUsb(device: UsbDevice) = start("USB") { status ->
        status("Pedindo permissão USB…")
        if (!UsbAccess.ensurePermission(app, device)) throw IllegalStateException("Permissão USB negada")
        UsbPtpCamera.open(app, device, status)
    }

    fun connectWifi() = start("Wi-Fi") { status -> SonyWifiCamera.connect(app, status) }

    private fun start(via: String, factory: suspend (status: (String) -> Unit) -> CameraLink) {
        disconnect()
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
    }
}
