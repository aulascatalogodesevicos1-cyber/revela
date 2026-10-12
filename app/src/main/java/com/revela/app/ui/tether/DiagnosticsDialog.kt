package com.revela.app.ui.tether

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.revela.app.tether.TetherLog
import com.revela.app.tether.usb.UsbAccess
import com.revela.app.ui.theme.TextDim

/** Mostra o que o Android vê no USB e o registro da última conexão, com botão de copiar. */
@Composable
fun DiagnosticsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val log by TetherLog.lines.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }

    val devices = remember(refresh) { UsbAccess.allDevices(ctx) }
    val deviceText = if (devices.isEmpty()) {
        "Nenhum dispositivo USB detectado.\n" +
            "→ Verifique cabo (precisa ser de dados), se o celular tem a opção OTG ligada " +
            "(Configurações › Conexões/Dispositivos conectados › OTG) e se a câmera está ligada."
    } else {
        devices.joinToString("\n") { d ->
            "• " + UsbAccess.describe(d) + " · permissão: " + if (UsbAccess.hasPermission(ctx, d)) "sim" else "não"
        }
    }
    val full = buildString {
        append("USB:\n").append(deviceText).append("\n\nRegistro:\n")
        append(if (log.isEmpty()) "(vazio — toque em Cabo USB e volte aqui)" else log.joinToString("\n"))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Diagnóstico da conexão") },
        text = {
            Column {
                Text(
                    "Copie e mande este texto para ajustar a conexão com a sua câmera.",
                    color = TextDim, style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0B0B0D))
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    Text(full, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, color = Color(0xFFD6D6DC))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Diagnóstico Revela", full))
                Toast.makeText(ctx, "Copiado", Toast.LENGTH_SHORT).show()
            }) { Text("Copiar") }
        },
        dismissButton = {
            Column {
                TextButton(onClick = { refresh++ }) { Text("Atualizar") }
                TextButton(onClick = { TetherLog.clear() }) { Text("Limpar") }
                TextButton(onClick = onDismiss) { Text("Fechar") }
            }
        },
    )
}
