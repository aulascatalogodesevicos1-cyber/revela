package com.revela.app

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.IntentCompat
import com.revela.app.ui.AppNav
import com.revela.app.ui.theme.RevelaTheme

class MainActivity : ComponentActivity() {

    /** Câmera que acabou de ser conectada no USB (o app abre sozinho). */
    private val attachedCamera = mutableStateOf<UsbDevice?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            RevelaTheme {
                AppNav(
                    attachedCamera = attachedCamera.value,
                    onAttachedCameraHandled = { attachedCamera.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            attachedCamera.value = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        }
    }
}
