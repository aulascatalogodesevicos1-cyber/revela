package com.revela.app.tether.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object UsbAccess {
    private const val ACTION_PERMISSION = "com.revela.app.USB_PERMISSION"

    /** Câmeras conectadas no USB (dispositivos com interface de imagem estática ou de fabricante conhecido). */
    fun cameras(ctx: Context): List<UsbDevice> {
        val mgr = ctx.getSystemService(UsbManager::class.java) ?: return emptyList()
        return mgr.deviceList.values.filter { d ->
            (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE } ||
                d.vendorId in KNOWN_VENDORS
        }
    }

    private val KNOWN_VENDORS = setOf(0x054C, 0x04A9, 0x04B0, 0x04CB, 0x04DA, 0x07B4)

    suspend fun ensurePermission(ctx: Context, device: UsbDevice): Boolean {
        val mgr = ctx.getSystemService(UsbManager::class.java) ?: return false
        if (mgr.hasPermission(device)) return true
        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action != ACTION_PERMISSION) return
                    try { ctx.unregisterReceiver(this) } catch (_: Exception) {}
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (cont.isActive) cont.resume(granted)
                }
            }
            ContextCompat.registerReceiver(
                ctx, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            cont.invokeOnCancellation { try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {} }
            val pi = PendingIntent.getBroadcast(
                ctx, 0, Intent(ACTION_PERMISSION).setPackage(ctx.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            mgr.requestPermission(device, pi)
        }
    }
}
