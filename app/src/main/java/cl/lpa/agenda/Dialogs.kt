package cl.lpa.agenda

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

private val dNavy = Color(0xFF1E2A5A)

@Composable
fun DialogButton(text: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        color = if (primary) Color.White else dNavy,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (primary) dNavy else Color(0xFFE6E8F0))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    )
}

// ---------- Escritura a mano (para la descripcion de las fotos) ----------
@Composable
fun HandwritingPad(strokes: MutableList<List<InkPt>>, modifier: Modifier = Modifier) {
    val cur = remember { mutableStateListOf<InkPt>() }
    Canvas(
        modifier
            .background(Color(0xFFFFFEFA))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    cur.clear()
                    cur.add(InkPt(down.position.x, down.position.y, System.currentTimeMillis()))
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        cur.add(InkPt(ch.position.x, ch.position.y, System.currentTimeMillis()))
                        ch.consume()
                    }
                    if (cur.isNotEmpty()) strokes.add(cur.toList())
                    cur.clear()
                }
            }
    ) {
        val y = size.height * 0.7f
        drawLine(Color(0xFFBFD4EA), Offset(0f, y), Offset(size.width, y), 2f)
        strokes.forEach { drawPolyline(it) }
        drawPolyline(cur.toList())
    }
}

private fun DrawScope.drawPolyline(pts: List<InkPt>) {
    if (pts.isEmpty()) return
    if (pts.size == 1) {
        drawCircle(Color(0xFF111111), 3f, Offset(pts[0].x, pts[0].y))
        return
    }
    val p = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
    }
    drawPath(p, Color(0xFF111111), style = DrawStroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

// ---------- Descripcion de la foto recien tomada ----------
@Composable
fun PhotoDescriptionDialog(photoPath: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    val strokes = remember { mutableStateListOf<List<InkPt>>() }
    var text by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val thumb = remember(photoPath) { ImageUtil.load(File(photoPath), 700)?.asImageBitmap() }

    LaunchedEffect(strokes.size) {
        if (strokes.isNotEmpty()) {
            delay(800)
            status = "Leyendo la escritura…"
            InkRecognizer.recognize(strokes.toList()) { r ->
                if (r != null) {
                    text = r
                    status = ""
                } else {
                    status = "No se pudo leer la escritura (la primera vez necesita internet para descargar el reconocedor). Puedes escribirla con el teclado."
                }
            }
        }
    }

    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(16.dp), color = Color.White, modifier = Modifier.fillMaxWidth(0.85f)) {
            Column(
                Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Foto lista. Escribe con el lápiz qué muestra:", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                thumb?.let {
                    Image(
                        bitmap = it, contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.height(180.dp).align(Alignment.CenterHorizontally)
                    )
                }
                HandwritingPad(
                    strokes,
                    Modifier.fillMaxWidth().height(150.dp).border(1.dp, Color(0xFFBBBBBB), RoundedCornerShape(6.dp))
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("Descripción (puedes corregirla con el teclado)") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (status.isNotEmpty()) Text(status, fontSize = 13.sp, color = Color(0xFF666666))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    DialogButton("Borrar escritura") { strokes.clear(); text = ""; status = "" }
                    Spacer(Modifier.weight(1f))
                    DialogButton("Descartar foto") { onCancel() }
                    DialogButton("Guardar", primary = true) { onSave(text.trim()) }
                }
            }
        }
    }
}

// ---------- Lista de evidencias del dia ----------
@Composable
fun EvidenceDialog(
    photos: List<Photo>,
    placed: Set<String>,
    fileOf: (Photo) -> File,
    onTogglePlace: (Photo) -> Unit,
    onDelete: (Photo) -> Unit,
    onClose: () -> Unit
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(16.dp), color = Color.White,
            modifier = Modifier.fillMaxWidth(0.85f).fillMaxHeight(0.85f)
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Evidencias del día (${photos.size})", fontWeight = FontWeight.Bold,
                        fontSize = 20.sp, modifier = Modifier.weight(1f)
                    )
                    DialogButton("Cerrar", primary = true) { onClose() }
                }
                Spacer(Modifier.height(12.dp))
                if (photos.isEmpty()) {
                    Text("Todavía no hay fotos en este día. Usa el botón Foto de la barra lateral.")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(photos) { p ->
                            val thumb = remember(p.file) { ImageUtil.load(fileOf(p), 400)?.asImageBitmap() }
                            val hora = remember(p.time) {
                                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(p.time))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                                thumb?.let {
                                    Image(
                                        bitmap = it, contentDescription = null, contentScale = ContentScale.Fit,
                                        modifier = Modifier.width(200.dp).height(150.dp)
                                    )
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(hora, fontSize = 13.sp, color = Color(0xFF666666))
                                    Text(p.description, fontSize = 17.sp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        DialogButton(
                                            if (p.file in placed) "Quitar de la hoja" else "Pegar en esta hoja"
                                        ) { onTogglePlace(p) }
                                        DialogButton("Eliminar foto") { onDelete(p) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- Confirmacion ----------
@Composable
fun ConfirmDialog(text: String, onYes: () -> Unit, onNo: () -> Unit) {
    Dialog(onDismissRequest = onNo) {
        Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(text, fontSize = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DialogButton("Cancelar") { onNo() }
                    DialogButton("Sí, borrar", primary = true) { onYes() }
                }
            }
        }
    }
}

// ---------- Ajustes de correo / exportacion ----------
@Composable
fun SettingsDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = remember { runCatching { MailSettings.load(ctx) }.getOrNull() }
    var sender by remember { mutableStateOf(initial?.sender ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var dest by remember { mutableStateOf(initial?.dest ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "smtp.gmail.com") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun current() = MailSettings.Config(
        sender.trim(), password.replace(" ", ""), dest.trim(), host.trim().ifBlank { "smtp.gmail.com" }
    )

    fun runTask(label: String, block: () -> String) {
        if (busy) return
        busy = true
        msg = "$label…"
        scope.launch(Dispatchers.IO) {
            val r = try {
                block()
            } catch (e: Exception) {
                "Error: ${e.message ?: e.javaClass.simpleName}"
            }
            withContext(Dispatchers.Main) { msg = r; busy = false }
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(16.dp), color = Color.White, modifier = Modifier.fillMaxWidth(0.8f)) {
            Column(
                Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ajustes de envío", fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.weight(1f))
                    DialogButton("Cerrar", primary = true) { onClose() }
                }
                Text(
                    "Cada domingo (20:00, con internet) se envía un correo con el Word de cada día de la semana. " +
                        "Los archivos también quedan en la carpeta Documentos/Agenda de la tablet.",
                    fontSize = 14.sp, color = Color(0xFF555555)
                )
                OutlinedTextField(
                    value = sender, onValueChange = { sender = it }, singleLine = true,
                    label = { Text("Correo que envía (Gmail)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, singleLine = true,
                    label = { Text("Contraseña de aplicación de Google (16 letras)") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dest, onValueChange = { dest = it }, singleLine = true,
                    label = { Text("Correo que recibe los archivos") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = host, onValueChange = { host = it }, singleLine = true,
                    label = { Text("Servidor SMTP") }, modifier = Modifier.fillMaxWidth()
                )
                if (msg.isNotEmpty()) Text(msg, fontSize = 15.sp, color = dNavy, fontWeight = FontWeight.Medium)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialogButton("Guardar datos", primary = true) {
                        try {
                            MailSettings.save(ctx, current()); msg = "Datos guardados."
                        } catch (e: Exception) {
                            msg = "Error al guardar: ${e.message}"
                        }
                    }
                    DialogButton("Enviar esta semana ahora", enabled = !busy) {
                        val cfg = current()
                        runTask("Enviando") {
                            MailSettings.save(ctx, cfg)
                            val n = WeeklyReport.sendWeek(ctx, WeeklyReport.thisWeekSunday(LocalDate.now()))
                            if (n == 0) "No hay días con contenido esta semana." else "Enviado con $n archivo(s)."
                        }
                    }
                    DialogButton("Crear Word de hoy", enabled = !busy) {
                        runTask("Creando Word") {
                            val f = DocxExporter.export(ctx, LocalDate.now())
                            if (f == null) "Hoy no hay nada escrito." else "Listo: ${f.name} (copia en Documentos/Agenda)."
                        }
                    }
                }
            }
        }
    }
}


// ---------- Recortar una foto pegada en la hoja ----------
@Composable
fun CropDialog(
    bitmap: ImageBitmap,
    initial: List<Float>,
    onApply: (Float, Float, Float, Float) -> Unit,
    onCancel: () -> Unit
) {
    var l by remember { mutableFloatStateOf(initial[0]) }
    var t by remember { mutableFloatStateOf(initial[1]) }
    var r by remember { mutableFloatStateOf(initial[2]) }
    var b by remember { mutableFloatStateOf(initial[3]) }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(16.dp), color = Color.White,
            modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.92f)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Recortar foto: arrastra las esquinas, o mueve el recuadro completo",
                    fontWeight = FontWeight.Bold, fontSize = 17.sp
                )
                Canvas(
                    Modifier
                        .weight(1f).fillMaxWidth().background(Color(0xFF222222))
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val bw = bitmap.width.toFloat()
                                val bh = bitmap.height.toFloat()
                                val sc = minOf(size.width / bw, size.height / bh)
                                val iw = bw * sc
                                val ih = bh * sc
                                val ox = (size.width - iw) / 2f
                                val oy = (size.height - ih) / 2f
                                val corners = listOf(
                                    Offset(ox + l * iw, oy + t * ih), Offset(ox + r * iw, oy + t * ih),
                                    Offset(ox + l * iw, oy + b * ih), Offset(ox + r * iw, oy + b * ih)
                                )
                                var mode = -1
                                for (i in 0..3) {
                                    if ((down.position - corners[i]).getDistance() < 48f) { mode = i; break }
                                }
                                if (mode < 0) {
                                    val inside = down.position.x in corners[0].x..corners[1].x &&
                                        down.position.y in corners[0].y..corners[2].y
                                    if (inside) mode = 4 else return@awaitEachGesture
                                }
                                down.consume()
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) break
                                    val dx = (ch.position.x - ch.previousPosition.x) / iw
                                    val dy = (ch.position.y - ch.previousPosition.y) / ih
                                    val minS = 0.08f
                                    when (mode) {
                                        0 -> { l = (l + dx).coerceIn(0f, r - minS); t = (t + dy).coerceIn(0f, b - minS) }
                                        1 -> { r = (r + dx).coerceIn(l + minS, 1f); t = (t + dy).coerceIn(0f, b - minS) }
                                        2 -> { l = (l + dx).coerceIn(0f, r - minS); b = (b + dy).coerceIn(t + minS, 1f) }
                                        3 -> { r = (r + dx).coerceIn(l + minS, 1f); b = (b + dy).coerceIn(t + minS, 1f) }
                                        else -> {
                                            val wd = r - l
                                            val ht = b - t
                                            l = (l + dx).coerceIn(0f, 1f - wd)
                                            t = (t + dy).coerceIn(0f, 1f - ht)
                                            r = l + wd
                                            b = t + ht
                                        }
                                    }
                                    ch.consume()
                                }
                            }
                        }
                ) {
                    val bw = bitmap.width.toFloat()
                    val bh = bitmap.height.toFloat()
                    val sc = minOf(size.width / bw, size.height / bh)
                    val iw = bw * sc
                    val ih = bh * sc
                    val ox = (size.width - iw) / 2f
                    val oy = (size.height - ih) / 2f
                    drawImage(
                        bitmap,
                        dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
                        dstSize = IntSize(iw.roundToInt(), ih.roundToInt())
                    )
                    val cl = ox + l * iw
                    val ct = oy + t * ih
                    val cr = ox + r * iw
                    val cb = oy + b * ih
                    val dim = Color(0x99000000)
                    drawRect(dim, Offset(ox, oy), Size(iw, ct - oy))
                    drawRect(dim, Offset(ox, cb), Size(iw, oy + ih - cb))
                    drawRect(dim, Offset(ox, ct), Size(cl - ox, cb - ct))
                    drawRect(dim, Offset(cr, ct), Size(ox + iw - cr, cb - ct))
                    drawRect(Color.White, Offset(cl, ct), Size(cr - cl, cb - ct), style = DrawStroke(3f))
                    for (c in listOf(Offset(cl, ct), Offset(cr, ct), Offset(cl, cb), Offset(cr, cb))) {
                        drawCircle(Color.White, 16f, c)
                        drawCircle(Color(0xFF1E88E5), 10f, c)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    DialogButton("Quitar recorte") { l = 0f; t = 0f; r = 1f; b = 1f }
                    Spacer(Modifier.weight(1f))
                    DialogButton("Cancelar") { onCancel() }
                    DialogButton("Aplicar recorte", primary = true) { onApply(l, t, r, b) }
                }
            }
        }
    }
}

// ---------- Actas: enviando / resultado / lista ----------
@Composable
fun BusyDialog(text: String) {
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Espera un momento, no cierres la aplicación.", fontSize = 14.sp, color = Color(0xFF555555))
            }
        }
    }
}

@Composable
fun ResultDialog(title: String, text: String, ok: Boolean, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    (if (ok) "✅ " else "⚠️ ") + title,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = if (ok) Color(0xFF2E7D32) else Color(0xFFC62828)
                )
                Text(text, fontSize = 16.sp)
                DialogButton("Aceptar", primary = true) { onClose() }
            }
        }
    }
}

@Composable
fun ActaListDialog(files: List<File>, onOpen: (File) -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(16.dp), color = Color.White,
            modifier = Modifier.fillMaxWidth(0.7f).fillMaxHeight(0.75f)
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Actas finalizadas (${files.size})", fontWeight = FontWeight.Bold,
                        fontSize = 20.sp, modifier = Modifier.weight(1f)
                    )
                    DialogButton("Cerrar", primary = true) { onClose() }
                }
                Spacer(Modifier.height(12.dp))
                if (files.isEmpty()) {
                    Text("Todavía no hay actas finalizadas.")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(files) { f ->
                            val fecha = remember(f) {
                                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(f.lastModified()))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text("Acta del $fecha", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                    Text(f.name, fontSize = 13.sp, color = Color(0xFF666666))
                                }
                                DialogButton("Abrir PDF") { onOpen(f) }
                            }
                        }
                    }
                }
            }
        }
    }
}
