package cl.lpa.agenda

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Modulo de actas: muestra SOLO la hoja del acta (formato original, 2 hojas carta) en el area principal.
 * Se escribe y se firma con el lapiz usando las mismas herramientas de la barra lateral.
 */
@Composable
fun ActaScreen(
    modifier: Modifier,
    pages: List<MutableList<InkStroke>>,
    tool: Tool,
    penColor: Color,
    hlColor: Color,
    penWidth: Float,
    shapesOn: Boolean,
    onChanged: (Int) -> Unit,
    onNew: () -> Unit,
    onShowFinal: () -> Unit,
    onFinalize: () -> Unit
) {
    val context = LocalContext.current
    val backgrounds = remember {
        List(pages.size) { ActaAssets.load(context, it)?.asImageBitmap() }
    }

    Column(modifier.background(Color(0xFFE9E6DF))) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            pages.forEachIndexed { i, strokes ->
                ActaPage(
                    strokes = strokes, background = backgrounds[i],
                    tool = tool, penColor = penColor, hlColor = hlColor,
                    penWidth = penWidth, shapesOn = shapesOn,
                    onChanged = { onChanged(i) }
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().background(Color(0xFFF1EFE8)).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "📋 Acta de reunión · se guarda sola",
                fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E2A5A)
            )
            Spacer(Modifier.weight(1f))
            DialogButton("＋ Nueva acta") { onNew() }
            DialogButton("📂 Actas finalizadas") { onShowFinal() }
            DialogButton("✅ Finalizar y enviar acta", primary = true) { onFinalize() }
        }
    }
}

@Composable
private fun ActaPage(
    strokes: MutableList<InkStroke>,
    background: ImageBitmap?,
    tool: Tool,
    penColor: Color,
    hlColor: Color,
    penWidth: Float,
    shapesOn: Boolean,
    onChanged: () -> Unit
) {
    val current = remember { mutableStateListOf<Offset>() }
    val haptic = LocalHapticFeedback.current
    val pageW = ActaStore.PAGE_W
    val pageH = ActaStore.PAGE_H

    Canvas(
        modifier = Modifier
            .widthIn(max = 900.dp)
            .fillMaxWidth()
            .aspectRatio(pageW / pageH)
            .background(Color.White)
            .border(1.dp, Color(0xFFBBBBBB))
            .pointerInput(strokes, tool, penColor, hlColor, penWidth, shapesOn) {
                val pxPerPt = size.width / pageW            // pixeles por punto de hoja
                val ptPerDp = pageW / (size.width / density) // puntos de hoja por dp
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Solo lapiz: el dedo sirve para desplazar la hoja
                    if (down.type != PointerType.Stylus && down.type != PointerType.Eraser) return@awaitEachGesture

                    val erasing = tool == Tool.ERASER || down.type == PointerType.Eraser
                    var erased = false

                    fun erase(p: Offset) {
                        val r = ERASER_RADIUS * ptPerDp
                        if (strokes.removeAll { s -> s.points.any { (it - p).getDistance() < r } }) erased = true
                    }

                    down.consume()
                    val start = down.position / pxPerPt
                    if (erasing) erase(start) else { current.clear(); current.add(start) }

                    var anchor = start
                    var anchorTime = SystemClock.uptimeMillis()
                    var snapped = false
                    while (true) {
                        val event = withTimeoutOrNull(120L) { awaitPointerEvent() }
                        if (event != null) {
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val p = change.position / pxPerPt
                            if (erasing) erase(p) else if (!snapped) current.add(p)
                            if ((p - anchor).getDistance() > HOLD_SLOP * ptPerDp) {
                                anchor = p
                                anchorTime = SystemClock.uptimeMillis()
                            }
                            change.consume()
                        }
                        if (shapesOn && !erasing && !snapped && current.size >= 10 &&
                            SystemClock.uptimeMillis() - anchorTime >= HOLD_MS
                        ) {
                            val shape = ShapeRecognizer.recognize(
                                current.map { it / ptPerDp }, linesOnly = tool == Tool.HIGHLIGHTER
                            )
                            if (shape != null) {
                                current.clear()
                                current.addAll(shape.map { it * ptPerDp })
                                snapped = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            } else {
                                anchorTime = SystemClock.uptimeMillis()
                            }
                        }
                    }

                    if (erasing) {
                        if (erased) onChanged()
                    } else if (current.isNotEmpty()) {
                        val hl = tool == Tool.HIGHLIGHTER
                        strokes.add(
                            InkStroke(
                                current.toList(),
                                if (hl) hlColor.copy(alpha = 0.4f) else penColor,
                                (if (hl) HL_WIDTH else penWidth) * ptPerDp,
                                hl
                            )
                        )
                        current.clear()
                        onChanged()
                    }
                }
            }
    ) {
        val sc = size.width / pageW   // pixeles por punto
        if (background != null) {
            drawImage(
                background,
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                filterQuality = FilterQuality.High
            )
        }
        strokes.filter { it.highlighter }.forEach { drawInk(it, sc) }
        strokes.filter { !it.highlighter }.forEach { drawInk(it, sc) }
        if (current.isNotEmpty() && tool != Tool.ERASER) {
            val hl = tool == Tool.HIGHLIGHTER
            val ptPerDp = pageW / (size.width / density)
            drawInk(
                InkStroke(
                    current.toList(),
                    if (hl) hlColor.copy(alpha = 0.4f) else penColor,
                    (if (hl) HL_WIDTH else penWidth) * ptPerDp,
                    hl
                ), sc
            )
        }
    }
}
