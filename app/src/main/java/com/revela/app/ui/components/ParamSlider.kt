package com.revela.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.revela.app.ui.theme.Accent
import com.revela.app.ui.theme.TextDim
import java.util.Locale

/**
 * Controle deslizante no estilo Lightroom: nome à esquerda, valor à direita,
 * toque duplo no nome volta ao padrão. [gradient] desenha uma faixa de cor (ex.: temperatura).
 */
@Composable
fun ParamSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit,
    default: Float = 0f,
    decimals: Int = 0,
    signed: Boolean = true,
    gradient: List<Color>? = null,
) {
    val changed = value != default
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .pointerInput(default) {
                    detectTapGestures(onDoubleTap = { onChange(default); onCommit() })
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (changed) MaterialTheme.colorScheme.onSurface else TextDim,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatValue(value, decimals, signed),
                style = MaterialTheme.typography.labelMedium,
                color = if (changed) Accent else TextDim,
            )
        }
        if (gradient != null) {
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Brush.horizontalGradient(gradient)),
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onCommit,
            valueRange = range,
            modifier = Modifier.height(32.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = if (gradient != null) Color.Transparent else Accent.copy(alpha = 0.85f),
                inactiveTrackColor = if (gradient != null) Color.Transparent else Color(0xFF3A3A40),
            ),
        )
    }
}

private fun formatValue(v: Float, decimals: Int, signed: Boolean): String {
    val s = String.format(Locale.US, "%.${decimals}f", v)
    return if (signed && v > 0f) "+$s" else s
}
