package com.revela.app.tether

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Registro das últimas etapas da conexão com a câmera, mostrado em Captura vinculada › Diagnóstico. */
object TetherLog {
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun add(msg: String) {
        val line = "${fmt.format(Date())}  $msg"
        _lines.update { (it + line).takeLast(300) }
    }

    fun clear() {
        _lines.value = emptyList()
    }

    fun hex(v: Int, digits: Int = 4) = "0x" + Integer.toHexString(v).uppercase().padStart(digits, '0')
}
