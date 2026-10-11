package com.revela.app.tether

class CapturedFile(val name: String, val bytes: ByteArray)

/** Uma câmera conectada (USB ou Wi-Fi). */
interface CameraLink {
    val displayName: String

    /** Se o app consegue disparar o obturador remotamente. */
    val canTrigger: Boolean

    /**
     * Fica escutando novas fotos até a câmera desconectar (lança exceção) ou a
     * coroutine ser cancelada. Cada arquivo recebido é entregue em [onCapture].
     */
    suspend fun listen(onInfo: (String) -> Unit, onCapture: suspend (CapturedFile) -> Unit)

    suspend fun trigger()

    fun close()
}

sealed interface TetherStatus {
    data object Disconnected : TetherStatus
    data class Connecting(val message: String) : TetherStatus
    data class Connected(val camera: String, val via: String, val canTrigger: Boolean) : TetherStatus
    data class Failed(val message: String) : TetherStatus
}
