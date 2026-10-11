package com.revela.app.tether.usb

/** Códigos PTP (ISO 15740) + extensões Sony usadas no modo "PC Remoto". */
object PtpCodes {
    // Tipos de container USB
    const val TYPE_COMMAND = 1
    const val TYPE_DATA = 2
    const val TYPE_RESPONSE = 3
    const val TYPE_EVENT = 4

    // Operações padrão
    const val OC_GET_DEVICE_INFO = 0x1001
    const val OC_OPEN_SESSION = 0x1002
    const val OC_CLOSE_SESSION = 0x1003
    const val OC_GET_STORAGE_IDS = 0x1004
    const val OC_GET_OBJECT_HANDLES = 0x1007
    const val OC_GET_OBJECT_INFO = 0x1008
    const val OC_GET_OBJECT = 0x1009
    const val OC_INITIATE_CAPTURE = 0x100E

    // Respostas
    const val RC_OK = 0x2001
    const val RC_GENERAL_ERROR = 0x2002
    const val RC_SESSION_NOT_OPEN = 0x2003
    const val RC_DEVICE_BUSY = 0x2019
    const val RC_SESSION_ALREADY_OPEN = 0x201E

    // Eventos
    const val EC_OBJECT_ADDED = 0x4002
    const val EC_CAPTURE_COMPLETE = 0x400D

    // Formatos
    const val OFC_ASSOCIATION = 0x3001
    const val OFC_EXIF_JPEG = 0x3801

    // Sony (extensões SDIO usadas pelo Imaging Edge / libgphoto2)
    const val SONY_SDIO_CONNECT = 0x9201
    const val SONY_GET_SDIO_EXT_DEVICE_INFO = 0x9202
    const val SONY_SET_CONTROL_DEVICE_B = 0x9207
    const val SONY_EC_OBJECT_ADDED = 0xC201
    const val SONY_PROP_AUTOFOCUS = 0xD2C1   // S1 (meio-clique)
    const val SONY_PROP_CAPTURE = 0xD2C2     // S2 (disparo)
    const val SONY_OBJECT_IN_MEMORY_HANDLE = 0xFFFFC001.toInt()

    const val VENDOR_SONY = 0x054C

    fun responseName(code: Int): String = when (code) {
        RC_OK -> "OK"
        RC_GENERAL_ERROR -> "Erro geral"
        RC_SESSION_NOT_OPEN -> "Sessão não aberta"
        RC_DEVICE_BUSY -> "Câmera ocupada"
        RC_SESSION_ALREADY_OPEN -> "Sessão já aberta"
        0x2005 -> "Operação não suportada"
        0x2009 -> "Objeto inválido"
        0x201D -> "Parâmetro inválido"
        else -> "0x" + code.toString(16)
    }
}
