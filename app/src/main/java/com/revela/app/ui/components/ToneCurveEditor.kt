package com.revela.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.revela.app.imaging.Curves
import com.revela.app.model.CurvePoint
import kotlin.math.hypot

/**
 * Editor de curva de tons. Arraste um ponto para movê-lo, toque/arraste em área livre para
 * criar um ponto, toque duplo em um ponto para removê-lo.
 */
@Composable
fun ToneCurveEditor(
    points: List<CurvePoint>,
    color: Color,
    onChange: (List<CurvePoint>) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(points)
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    var dragIndex by remember { mutableIntStateOf(-1) }

    Canvas(
        modifier
            .pointerInput(Unit) {
                val hit = 28f
                fun toNorm(o: Offset) = CurvePoint(
                    (o.x / size.width).coerceIn(0f, 1f),
                    (1f - o.y / size.height).coerceIn(0f, 1f),
                )
                fun toPx(p: CurvePoint) = Offset(p.x * size.width, (1f - p.y) * size.height)
                // Lista de trabalho local: não depende de recomposição durante o arraste
                var working: List<CurvePoint> = emptyList()

                detectDragGestures(
                    onDragStart = { pos ->
                        val pts = current.sortedBy { it.x }
                        val near = pts.indices.minByOrNull { (toPx(pts[it]) - pos).getDistance() }
                        if (near != null && (toPx(pts[near]) - pos).getDistance() < hit * 1.6f) {
                            dragIndex = near
                            working = pts
                        } else {
                            val np = toNorm(pos)
                            val list = (pts + np).sortedBy { it.x }
                            dragIndex = list.indexOf(np)
                            working = list
                        }
                        change(working)
                    },
                    onDrag = { ev, _ ->
                        ev.consume()
                        val pts = working.toMutableList()
                        val i = dragIndex
                        if (i !in pts.indices) return@detectDragGestures
                        val np = toNorm(ev.position)
                        val minX = if (i > 0) pts[i - 1].x + 0.01f else 0f
                        val maxX = if (i < pts.size - 1) pts[i + 1].x - 0.01f else 1f
                        pts[i] = CurvePoint(np.x.coerceIn(minX, maxX.coerceAtLeast(minX)), np.y)
                        working = pts
                        change(pts)
                    },
                    onDragEnd = { dragIndex = -1; commit() },
                    onDragCancel = { dragIndex = -1; commit() },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { pos ->
                    val pts = current.sortedBy { it.x }
                    if (pts.size <= 2) return@detectTapGestures
                    val idx = pts.indices.minByOrNull {
                        hypot(pts[it].x * size.width - pos.x, (1f - pts[it].y) * size.height - pos.y)
                    } ?: return@detectTapGestures
                    // As pontas (preto e branco) só podem ser movidas, não removidas
                    if (idx == 0 || idx == pts.size - 1) return@detectTapGestures
                    change(pts.filterIndexed { i, _ -> i != idx })
                    commit()
                })
            },
    ) {
        val w = size.width
        val h = size.height
        val grid = Color(0x33FFFFFF)
        for (k in 1..3) {
            drawLine(grid, Offset(w * k / 4f, 0f), Offset(w * k / 4f, h), 1f)
            drawLine(grid, Offset(0f, h * k / 4f), Offset(w, h * k / 4f), 1f)
        }
        drawLine(Color(0x22FFFFFF), Offset(0f, h), Offset(w, 0f), 1f)
        drawRect(Color(0x44FFFFFF), style = Stroke(1f))

        val lut = Curves.buildLut(points, 256)
        val path = Path()
        for (i in 0..256) {
            val x = i / 256f
            val y = lut?.get(i) ?: x
            val px = x * w
            val py = (1f - y) * h
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, color, style = Stroke(2.5f))

        points.forEach { p ->
            val c = Offset(p.x * w, (1f - p.y) * h)
            drawCircle(Color.Black, 9f, c)
            drawCircle(color, 7f, c)
        }
    }
}
