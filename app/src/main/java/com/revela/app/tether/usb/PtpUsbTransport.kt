package com.revela.app.tether.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import com.revela.app.tether.TetherLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

class PtpException(message: String, val code: Int = 0) : IOException(message)

/**
 * Cliente PTP sobre USB (Still Image Class) usando transferências bulk diretas.
 * Diferente do android.mtp.MtpDevice, permite enviar operações de fabricante
 * (necessárias para disparar uma Sony em modo PC Remoto).
 */
class PtpUsbTransport(
    private val conn: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
    private val epEvent: UsbEndpoint?,
) : Closeable {

    class Response(val code: Int, val params: IntArray, val data: ByteArray) {
        val ok get() = code == PtpCodes.RC_OK
        fun requireOk(what: String): Response {
            if (!ok) throw PtpException("$what falhou: ${PtpCodes.responseName(code)}", code)
            return this
        }
    }

    private val lock = Mutex()
    private var transactionId = 0

    /** Registra cada operação no diagnóstico (desligado durante o fluxo ao vivo). */
    @Volatile var verbose = true

    /** O padrão PTP pede que o OpenSession use o número de transação 0. */
    fun resetTransactions() {
        transactionId = -1
    }
    private val buf = ByteArray(256 * 1024)
    @Volatile private var closed = false

    suspend fun request(
        op: Int,
        vararg params: Int,
        dataOut: ByteArray? = null,
        timeoutMs: Int = 10_000,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Response = lock.withLock {
        withContext(Dispatchers.IO) {
            val p = params.joinToString(",") { TetherLog.hex(it, 1) }
            try {
                val r = doRequest(op, params, dataOut, timeoutMs, onProgress)
                if (verbose) {
                    TetherLog.add("PTP ${TetherLog.hex(op)}($p) → ${PtpCodes.responseName(r.code)} · ${r.data.size} B")
                }
                r
            } catch (e: Exception) {
                TetherLog.add("PTP ${TetherLog.hex(op)}($p) ✕ ${e.message}")
                throw e
            }
        }
    }

    private fun doRequest(
        op: Int, params: IntArray, dataOut: ByteArray?, timeoutMs: Int,
        onProgress: ((Long, Long) -> Unit)?,
    ): Response {
        if (closed) throw PtpException("Conexão fechada")
        val tid = ++transactionId
        write(container(PtpCodes.TYPE_COMMAND, op, tid, params, null), timeoutMs)
        if (dataOut != null) write(container(PtpCodes.TYPE_DATA, op, tid, IntArray(0), dataOut), timeoutMs)

        var data: ByteArray? = null
        var zeroReads = 0
        while (true) {
            val n = read(timeoutMs)
            if (n == 0) {
                if (++zeroReads > 4) throw PtpException("Câmera não respondeu")
                continue
            }
            if (n < 12) throw PtpException("Pacote PTP curto ($n bytes)")
            val bb = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN)
            val len = bb.int.toLong() and 0xFFFFFFFFL
            val type = bb.short.toInt() and 0xFFFF
            val code = bb.short.toInt() and 0xFFFF
            bb.int // transaction id
            when (type) {
                PtpCodes.TYPE_DATA -> {
                    val total = (len - 12).toInt()
                    val out = ByteArray(total)
                    var got = min(n - 12, total)
                    System.arraycopy(buf, 12, out, 0, got)
                    var zeros = 0
                    while (got < total) {
                        val m = read(timeoutMs)
                        if (m == 0) {
                            if (++zeros > 4) throw PtpException("Transferência interrompida")
                            continue
                        }
                        val c = min(m, total - got)
                        System.arraycopy(buf, 0, out, got, c)
                        got += c
                        onProgress?.invoke(got.toLong(), total.toLong())
                    }
                    data = out
                }
                PtpCodes.TYPE_RESPONSE -> {
                    val np = ((min(len.toInt(), n) - 12) / 4).coerceIn(0, 5)
                    val rp = IntArray(np) { bb.int }
                    return Response(code, rp, data ?: ByteArray(0))
                }
                else -> Unit // evento perdido no endpoint bulk: ignora
            }
        }
    }

    /** Lê um evento do endpoint de interrupção (null se nada chegou no tempo). */
    fun readEvent(timeoutMs: Int): PtpEvent? {
        val ep = epEvent ?: return null
        if (closed) throw PtpException("Conexão fechada")
        val b = ByteArray(64)
        val n = conn.bulkTransfer(ep, b, b.size, timeoutMs)
        if (n < 12) return null
        val bb = ByteBuffer.wrap(b, 0, n).order(ByteOrder.LITTLE_ENDIAN)
        bb.int
        val type = bb.short.toInt() and 0xFFFF
        val code = bb.short.toInt() and 0xFFFF
        bb.int
        if (type != PtpCodes.TYPE_EVENT) return null
        val np = ((n - 12) / 4).coerceIn(0, 3)
        return PtpEvent(code, IntArray(np) { bb.int })
    }

    val hasEventEndpoint get() = epEvent != null

    private fun container(type: Int, code: Int, tid: Int, params: IntArray, payload: ByteArray?): ByteArray {
        val len = 12 + params.size * 4 + (payload?.size ?: 0)
        val bb = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(len)
        bb.putShort(type.toShort())
        bb.putShort(code.toShort())
        bb.putInt(tid)
        params.forEach { bb.putInt(it) }
        if (payload != null) bb.put(payload)
        return bb.array()
    }

    private fun write(bytes: ByteArray, timeoutMs: Int) {
        var off = 0
        val chunk = 16 * 1024
        while (off < bytes.size) {
            val len = min(chunk, bytes.size - off)
            val n = conn.bulkTransfer(epOut, bytes, off, len, timeoutMs)
            if (n < 0) throw PtpException("Falha ao enviar para a câmera (cabo desconectado?)")
            off += n
        }
        // Pacote de tamanho zero quando o envio termina exatamente no limite do pacote USB
        if (bytes.size % epOut.maxPacketSize == 0) conn.bulkTransfer(epOut, ByteArray(0), 0, timeoutMs)
    }

    private fun read(timeoutMs: Int): Int {
        val n = conn.bulkTransfer(epIn, buf, buf.size, timeoutMs)
        if (n < 0) throw PtpException("Sem resposta da câmera (tempo esgotado ou cabo desconectado)")
        return n
    }

    override fun close() {
        if (closed) return
        closed = true
        try { conn.releaseInterface(iface) } catch (_: Exception) {}
        try { conn.close() } catch (_: Exception) {}
    }
}
