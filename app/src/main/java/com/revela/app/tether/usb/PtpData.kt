package com.revela.app.tether.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Leitor de datasets PTP (little-endian). */
class PtpReader(bytes: ByteArray) {
    private val bb: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val remaining get() = bb.remaining()

    fun u8(): Int = bb.get().toInt() and 0xFF
    fun u16(): Int = bb.short.toInt() and 0xFFFF
    fun u32(): Long = bb.int.toLong() and 0xFFFFFFFFL
    fun i32(): Int = bb.int

    fun skip(n: Int) {
        bb.position(bb.position() + n)
    }

    fun string(): String {
        if (bb.remaining() < 1) return ""
        val len = u8()
        if (len == 0) return ""
        val chars = CharArray(len)
        for (i in 0 until len) chars[i] = bb.short.toInt().toChar()
        return String(chars).trimEnd('\u0000')
    }

    fun u16Array(): IntArray {
        val n = u32().toInt()
        return IntArray(n) { u16() }
    }

    fun u32Array(): IntArray {
        val n = u32().toInt()
        return IntArray(n) { i32() }
    }
}

class DeviceInfo(
    val vendorExtension: String,
    val operations: Set<Int>,
    val events: Set<Int>,
    val manufacturer: String,
    val model: String,
) {
    fun supports(op: Int) = op in operations

    companion object {
        fun parse(data: ByteArray): DeviceInfo {
            val r = PtpReader(data)
            r.u16()                     // StandardVersion
            r.u32()                     // VendorExtensionID
            r.u16()                     // VendorExtensionVersion
            val ext = r.string()        // VendorExtensionDesc
            r.u16()                     // FunctionalMode
            val ops = r.u16Array().toSet()
            val events = r.u16Array().toSet()
            r.u16Array()                // DevicePropertiesSupported
            r.u16Array()                // CaptureFormats
            r.u16Array()                // ImageFormats
            val manufacturer = r.string()
            val model = r.string()
            return DeviceInfo(ext, ops, events, manufacturer, model)
        }
    }
}

class ObjectInfo(
    val storageId: Int,
    val format: Int,
    val compressedSize: Long,
    val fileName: String,
) {
    val isFolder get() = format == PtpCodes.OFC_ASSOCIATION

    companion object {
        fun parse(data: ByteArray): ObjectInfo {
            val r = PtpReader(data)
            val storage = r.i32()
            val format = r.u16()
            r.u16()                     // ProtectionStatus
            val size = r.u32()
            r.skip(2 + 4 + 4 + 4 + 4 + 4 + 4 + 4 + 2 + 4 + 4)
            val name = r.string()
            return ObjectInfo(storage, format, size, name)
        }
    }
}

class PtpEvent(val code: Int, val params: IntArray)
