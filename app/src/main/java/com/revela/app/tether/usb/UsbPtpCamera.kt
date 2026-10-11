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
                val iface = findPtpInterface(device) ?: throw IOException(
                    "A câmera não expôs uma interface PTP. Na Sony, ative Menu › Rede/USB › " +
                        "Conexão USB: \"PC Remoto\" (ou \"MTP\" para só importar).",
                )
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
                if (!conn.claimInterface(iface, true)) {
                    conn.close()
                    throw IOException("Não foi possível assumir a interface USB (outro app está usando a câmera?)")
                }
                val t = PtpUsbTransport(conn, iface, epIn, epOut, epInt)
                try {
                    status("Abrindo sessão PTP…")
                    val open = t.request(PtpCodes.OC_OPEN_SESSION, 1)
                    if (!open.ok && open.code != PtpCodes.RC_SESSION_ALREADY_OPEN) open.requireOk("Abrir sessão")

                    var info = DeviceInfo.parse(t.request(PtpCodes.OC_GET_DEVICE_INFO).requireOk("Ler câmera").data)
                    val sony = device.vendorId == PtpCodes.VENDOR_SONY && info.supports(PtpCodes.SONY_SDIO_CONNECT)
                    if (sony) {
                        status("Conectando no modo PC Remoto da Sony…")
                        sonyHandshake(t)
                        info = DeviceInfo.parse(t.request(PtpCodes.OC_GET_DEVICE_INFO).requireOk("Ler câmera").data)
                    }
                    UsbPtpCamera(t, info, sony)
                } catch (e: Exception) {
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

        /** Sequência de conexão SDIO (mesma usada pelo Imaging Edge e pelo libgphoto2). */
        private suspend fun sonyHandshake(t: PtpUsbTransport) {
            t.request(PtpCodes.SONY_SDIO_CONNECT, 1, 0, 0).requireOk("Sony SDIO 1")
            t.request(PtpCodes.SONY_SDIO_CONNECT, 2, 0, 0).requireOk("Sony SDIO 2")
            // Versão do protocolo: modelos novos aceitam 0x12C, antigos 0xC8
            val ext = t.request(PtpCodes.SONY_GET_SDIO_EXT_DEVICE_INFO, 0x12C)
            if (!ext.ok) t.request(PtpCodes.SONY_GET_SDIO_EXT_DEVICE_INFO, 0xC8)
            t.request(PtpCodes.SONY_SDIO_CONNECT, 3, 0, 0).requireOk("Sony SDIO 3")
            delay(300)
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
