package com.revela.app.tether.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.revela.app.imaging.BitmapIO
import com.revela.app.tether.CameraLink
import com.revela.app.tether.CapturedFile
import com.revela.app.tether.TetherLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Câmera via cabo USB.
 *
 * - **Sony (modo PC Remoto)**: usa as extensões SDIO da Sony. O app consegue disparar
 *   e cada foto (feita pelo app ou no botão da câmera) chega pelo handle 0xFFFFC001.
 * - **Outras / Sony em modo MTP**: PTP genérico — detecta objetos novos no cartão por
 *   evento ObjectAdded ou por varredura periódica e baixa automaticamente.
 */
class UsbPtpCamera private constructor(
    private val t: PtpUsbTransport,
    private val info: DeviceInfo,
    private val sonyRemote: Boolean,
) : CameraLink {

    override val displayName: String =
        listOf(info.manufacturer, info.model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Câmera USB" }

    override val canTrigger: Boolean =
        sonyRemote || info.supports(PtpCodes.OC_INITIATE_CAPTURE)

    private val downloadLock = Mutex()

    companion object {
        suspend fun open(ctx: Context, device: UsbDevice, status: (String) -> Unit): UsbPtpCamera =
            withContext(Dispatchers.IO) {
                val mgr = ctx.getSystemService(UsbManager::class.java)
                TetherLog.add("Dispositivo: ${UsbAccess.describe(device)}")
                val iface = findPtpInterface(device) ?: throw IOException(
                    "A câmera não expôs uma interface PTP. Na ZV-E10: Rede › Função PC Remoto › " +
                        "PC Remoto = Lig. e Método Conexão = USB.",
                )
                TetherLog.add("Interface PTP #${iface.id}: ${iface.endpointCount} endpoints")
                var epIn: UsbEndpoint? = null
                var epOut: UsbEndpoint? = null
                var epInt: UsbEndpoint? = null
                for (i in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(i)
                    when {
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_IN -> epIn = ep
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_OUT -> epOut = ep
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_INT && ep.direction == UsbConstants.USB_DIR_IN -> epInt = ep
                    }
                }
                if (epIn == null || epOut == null) throw IOException("Endpoints USB da câmera não encontrados")

                val conn = mgr.openDevice(device) ?: throw IOException("Sem permissão para acessar a câmera USB")
                var claimed = false
                for (attempt in 1..3) {
                    if (conn.claimInterface(iface, true)) { claimed = true; break }
                    TetherLog.add("claimInterface falhou (tentativa $attempt)")
                    delay(400)
                }
                if (!claimed) {
                    conn.close()
                    throw IOException(
                        "Outro app está usando a câmera. Feche apps de fotos/arquivos (ou o \"Transferência de arquivos\" " +
                            "do Android), desconecte e conecte o cabo de novo.",
                    )
                }
                val t = PtpUsbTransport(conn, iface, epIn, epOut, epInt)
                try {
                    status("Abrindo sessão PTP…")
                    openSession(t)

                    var info = DeviceInfo.parse(t.request(PtpCodes.OC_GET_DEVICE_INFO).requireOk("Ler câmera").data)
                    TetherLog.add(
                        "Câmera: ${info.manufacturer} ${info.model} · ext \"${info.vendorExtension}\" · " +
                            "${info.operations.size} operações · SDIO=${info.supports(PtpCodes.SONY_SDIO_CONNECT)}",
                    )
                    val sony = device.vendorId == PtpCodes.VENDOR_SONY && info.supports(PtpCodes.SONY_SDIO_CONNECT)
                    if (device.vendorId == PtpCodes.VENDOR_SONY && !sony) {
                        TetherLog.add("Sony sem SDIO: provavelmente em modo MTP/Mass Storage, não em PC Remoto")
                    }
                    if (sony) {
                        status("Conectando no modo PC Remoto da Sony…")
                        sonyHandshake(t)
                        info = DeviceInfo.parse(t.request(PtpCodes.OC_GET_DEVICE_INFO).requireOk("Ler câmera").data)
                    }
                    TetherLog.add("Conectado (${if (sony) "Sony PC Remoto" else "PTP genérico"})")
                    t.verbose = false
                    UsbPtpCamera(t, info, sony)
                } catch (e: Exception) {
                    TetherLog.add("Falhou: ${e.message}")
                    t.close()
                    throw e
                }
            }

        private fun findPtpInterface(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val itf = device.getInterface(i)
                if (itf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE) return itf
            }
            // Algumas câmeras em modo MTP se anunciam como "vendor specific" com 3 endpoints
            for (i in 0 until device.interfaceCount) {
                val itf = device.getInterface(i)
                if (itf.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC && itf.endpointCount == 3) return itf
            }
            return null
        }

        /**
         * Abre a sessão PTP com o número de transação 0 (exigido pelo padrão e por algumas Sony).
         * Se a câmera ficou com uma sessão presa de uma tentativa anterior, fecha e abre de novo.
         */
        private suspend fun openSession(t: PtpUsbTransport) {
            t.resetTransactions()
            val first = t.request(PtpCodes.OC_OPEN_SESSION, 1)
            if (first.ok || first.code == PtpCodes.RC_SESSION_ALREADY_OPEN) return
            TetherLog.add("Sessão recusada; fechando e tentando de novo")
            runCatching { t.request(PtpCodes.OC_CLOSE_SESSION) }
            delay(300)
            t.resetTransactions()
            val second = t.request(PtpCodes.OC_OPEN_SESSION, 1)
            if (!second.ok && second.code != PtpCodes.RC_SESSION_ALREADY_OPEN) second.requireOk("Abrir sessão")
        }

        /** Sequência de conexão SDIO (mesma usada pelo Imaging Edge e pelo libgphoto2). */
        private suspend fun sonyHandshake(t: PtpUsbTransport) {
            sdio(t, 1)
            sdio(t, 2)
            // Versão do protocolo SDIO: 0xC8 (clássica) ou 0x12C (mais nova)
            var ext = t.request(PtpCodes.SONY_GET_SDIO_EXT_DEVICE_INFO, 0xC8)
            if (!ext.ok) ext = t.request(PtpCodes.SONY_GET_SDIO_EXT_DEVICE_INFO, 0x12C)
            if (!ext.ok) TetherLog.add("Aviso: ExtDeviceInfo não respondeu OK, seguindo mesmo assim")
            sdio(t, 3)
            delay(500)
        }

        /** Passo SDIO com novas tentativas: a câmera responde "ocupada" enquanto troca de modo. */
        private suspend fun sdio(t: PtpUsbTransport, step: Int) {
            repeat(5) { attempt ->
                val r = t.request(PtpCodes.SONY_SDIO_CONNECT, step, 0, 0)
                if (r.ok) return
                if (attempt == 4) r.requireOk("Sony SDIO $step")
                delay(300)
            }
        }
    }

    // ------------------------------------------------------------------ disparo

    override suspend fun trigger() {
        if (sonyRemote) {
            sonyControl(PtpCodes.SONY_PROP_AUTOFOCUS, 2) // meio-clique (foco)
            delay(250)
            sonyControl(PtpCodes.SONY_PROP_CAPTURE, 2)   // dispara
            delay(150)
            sonyControl(PtpCodes.SONY_PROP_CAPTURE, 1)   // solta
            sonyControl(PtpCodes.SONY_PROP_AUTOFOCUS, 1)
        } else {
            t.request(PtpCodes.OC_INITIATE_CAPTURE, 0, 0, timeoutMs = 20_000).requireOk("Disparo")
        }
    }

    // ------------------------------------------------------------------ segunda tela

    override val supportsLiveView: Boolean get() = sonyRemote
    override val supportsMovie: Boolean get() = sonyRemote

    /**
     * Na Sony em PC Remoto, o objeto 0xFFFFC002 devolve o quadro ao vivo atual:
     * um cabeçalho da Sony seguido de um JPEG. Recortamos só o JPEG.
     */
    override suspend fun liveViewFrame(): ByteArray? {
        if (!sonyRemote) return null
        val r = t.request(PtpCodes.OC_GET_OBJECT, PtpCodes.SONY_LIVEVIEW_HANDLE, timeoutMs = 3000)
        if (!r.ok) return null
        val d = r.data
        var start = -1
        for (i in 0 until d.size - 2) {
            if (d[i] == 0xFF.toByte() && d[i + 1] == 0xD8.toByte() && d[i + 2] == 0xFF.toByte()) { start = i; break }
        }
        if (start < 0) return null
        val end = BitmapIO.jpegEnd(d, start)
        return if (end > start) d.copyOfRange(start, end) else d.copyOfRange(start, d.size)
    }

    override suspend fun setRecording(on: Boolean) {
        if (!sonyRemote) return
        sonyControl(PtpCodes.SONY_PROP_MOVIE, if (on) 2 else 1)
    }

    private suspend fun sonyControl(prop: Int, value: Int) {
        val data = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
        t.request(PtpCodes.SONY_SET_CONTROL_DEVICE_B, prop, dataOut = data).requireOk("Controle Sony")
    }

    // ------------------------------------------------------------------ recepção

    override suspend fun listen(onInfo: (String) -> Unit, onCapture: suspend (CapturedFile) -> Unit) {
        if (sonyRemote) listenSony(onInfo, onCapture) else listenGeneric(onInfo, onCapture)
    }

    private suspend fun listenSony(onInfo: (String) -> Unit, onCapture: suspend (CapturedFile) -> Unit) =
        coroutineScope {
            // Eventos (rápido) + varredura (garantia caso o evento não chegue)
            launch(Dispatchers.IO) {
                while (isActive) {
                    val ev = t.readEvent(500) ?: continue
                    if (ev.code == PtpCodes.SONY_EC_OBJECT_ADDED || ev.code == PtpCodes.EC_OBJECT_ADDED) {
                        drainSony(onInfo, onCapture)
                    }
                }
            }
            while (isActive) {
                drainSony(onInfo, onCapture)
                delay(700)
            }
        }

    /** Baixa todas as fotos pendentes na memória da câmera Sony. */
    private suspend fun drainSony(onInfo: (String) -> Unit, onCapture: suspend (CapturedFile) -> Unit) =
        downloadLock.withLock {
            repeat(8) {
                val r = t.request(PtpCodes.OC_GET_OBJECT_INFO, PtpCodes.SONY_OBJECT_IN_MEMORY_HANDLE)
                if (!r.ok || r.data.size < 53) return@withLock
                val oi = ObjectInfo.parse(r.data)
                if (oi.compressedSize <= 0) return@withLock
                val name = oi.fileName.ifBlank { "Sony_${System.currentTimeMillis()}.jpg" }
                onInfo("Recebendo $name…")
                val d = t.request(
                    PtpCodes.OC_GET_OBJECT, PtpCodes.SONY_OBJECT_IN_MEMORY_HANDLE, timeoutMs = 30_000,
                    onProgress = { got, total -> onInfo("Recebendo $name · ${got * 100 / total}%") },
                )
                if (!d.ok) return@withLock
                onCapture(CapturedFile(name, d.data))
            }
        }

    private suspend fun listenGeneric(onInfo: (String) -> Unit, onCapture: suspend (CapturedFile) -> Unit) =
        coroutineScope {
            val known = HashSet<Int>()
            known.addAll(allHandles())
            onInfo("Aguardando novas fotos no cartão…")

            if (t.hasEventEndpoint) {
                launch(Dispatchers.IO) {
                    while (isActive) {
                        val ev = t.readEvent(500) ?: continue
                        if (ev.code == PtpCodes.EC_OBJECT_ADDED && ev.params.isNotEmpty()) {
                            downloadIfNew(ev.params[0], known, onInfo, onCapture)
                        }
                    }
                }
            }
            while (isActive) {
                delay(2000)
                val handles = allHandles()
                for (h in handles) if (h !in known) downloadIfNew(h, known, onInfo, onCapture)
            }
        }

    private suspend fun allHandles(): List<Int> {
        val r = t.request(PtpCodes.OC_GET_OBJECT_HANDLES, 0xFFFFFFFF.toInt(), 0, 0, timeoutMs = 20_000)
        if (!r.ok) return emptyList()
        return PtpReader(r.data).u32Array().toList()
    }

    private suspend fun downloadIfNew(
        handle: Int, known: MutableSet<Int>,
        onInfo: (String) -> Unit, onCapture: suspend (CapturedFile) -> Unit,
    ) = downloadLock.withLock {
        if (!known.add(handle)) return@withLock
        val r = t.request(PtpCodes.OC_GET_OBJECT_INFO, handle)
        if (!r.ok) return@withLock
        val oi = ObjectInfo.parse(r.data)
        if (oi.isFolder || !BitmapIO.isImage(oi.fileName)) return@withLock
        onInfo("Recebendo ${oi.fileName}…")
        val d = t.request(
            PtpCodes.OC_GET_OBJECT, handle, timeoutMs = 30_000,
            onProgress = { got, total -> onInfo("Recebendo ${oi.fileName} · ${got * 100 / total}%") },
        )
        if (d.ok) onCapture(CapturedFile(oi.fileName, d.data))
    }

    override fun close() {
        t.close()
    }
}
