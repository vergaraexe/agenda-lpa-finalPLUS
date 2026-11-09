package cl.lpa.agenda

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import android.content.Intent
import android.os.SystemClock
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

// ---------- Modelo (coordenadas y grosores en dp) ----------
enum class Tool { PEN, HIGHLIGHTER, ERASER }

data class InkStroke(
    val points: List<Offset>,
    val color: Color,
    val width: Float,
    val highlighter: Boolean
)

private const val PEN_WIDTH = 2.6f
private val penWidths = listOf(1.4f, 2.6f, 4.5f, 7f)   // fino, medio, grueso, extra grueso (dp)
internal const val HL_WIDTH = 26f
internal const val ERASER_RADIUS = 14f
internal const val HOLD_MS = 700L      // tiempo quieto con el lapiz para "enderezar" la forma
internal const val HOLD_SLOP = 3f      // movimiento (dp) que aun cuenta como "quieto"

private val penColors = listOf(
    Color(0xFF111111), Color(0xFF1E3A8A), Color(0xFFD32F2F), Color(0xFF2E7D32)
)
private val highlightColors = listOf(
    Color(0xFFFFEB3B), Color(0xFF76FF03), Color(0xFFFF80AB), Color(0xFFFFAB40)
)
private val navy = Color(0xFF1E2A5A)

// ---------- Pantalla principal ----------
@Composable
fun AgendaScreen() {
    val context = LocalContext.current
    val store = remember { DayStore(context) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var page by remember { mutableIntStateOf(1) }
    var pageCount by remember { mutableIntStateOf(store.pageCount(date)) }
    var canvasW by remember { mutableFloatStateOf(0f) }
    var canvasH by remember { mutableFloatStateOf(0f) }

    val strokes = remember(date, page) {
        mutableStateListOf<InkStroke>().apply { addAll(store.load(date, page)) }
    }
    val images = remember(date, page) {
        mutableStateListOf<PagePhoto>().apply { addAll(store.loadPage(date, page).images) }
    }
    var photos by remember(date) { mutableStateOf(store.loadPhotos(date)) }
    val thumbs = remember { HashMap<String, ImageBitmap?>() }

    fun commit() = store.save(date, page, strokes, canvasW, canvasH, images.toList())

    fun thumbOf(file: String): ImageBitmap? =
        thumbs.getOrPut("$date/$file") { ImageUtil.load(store.photoFile(date, file), 1200)?.asImageBitmap() }

    /** Pega la foto en miniatura en la hoja actual (esquina derecha, una bajo otra). */
    fun placePhoto(fileName: String) {
        val bmp = ImageUtil.load(store.photoFile(date, fileName), 300) ?: return
        val w = 150f
        val h = w * bmp.height / bmp.width
        val n = images.size
        val x = (canvasW - w - 16f - (n / 3) * (w + 10f)).coerceAtLeast(0f)
        val y = (16f + (n % 3) * (h + 10f)).coerceAtMost((canvasH - h).coerceAtLeast(0f))
        images.add(PagePhoto(fileName, x, y, w, h))
        commit()
    }
    fun goTo(d: LocalDate) { date = d; page = 1; pageCount = store.pageCount(d) }

    var tool by remember { mutableStateOf(Tool.PEN) }
    var penColor by remember { mutableStateOf(penColors[0]) }
    var hlColor by remember { mutableStateOf(highlightColors[0]) }
    var penWidth by remember { mutableFloatStateOf(PEN_WIDTH) }
    var shapesOn by remember { mutableStateOf(true) }
    var selectedPhoto by remember(date, page) { mutableStateOf<String?>(null) }
    var cropFile by remember { mutableStateOf<String?>(null) }
    var showEvidence by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showClear by remember { mutableStateOf(false) }

    // ---- Modulo de actas (independiente de la agenda) ----
    var module by rememberSaveable { mutableStateOf("agenda") }
    val actaStore = remember { ActaStore(context) }
    val actaPages = remember { actaStore.loadDraft() }
    var lastActaPage by remember { mutableIntStateOf(0) }
    var showActaList by remember { mutableStateOf(false) }
    var actaFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var showActaNew by remember { mutableStateOf(false) }
    var showActaClear by remember { mutableStateOf(false) }
    var actaBusy by remember { mutableStateOf(false) }
    var actaResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val scope = rememberCoroutineScope()

    fun commitActa() = actaStore.saveDraft(actaPages)

    fun finalizeActa() {
        if (actaBusy) return
        actaBusy = true
        val snapshot = actaPages.map { it.toList() }
        scope.launch(Dispatchers.IO) {
            val result = runCatching { ActaFinalizer.finalizeAndSend(context, snapshot) }
            withContext(Dispatchers.Main) {
                actaBusy = false
                result.onSuccess { msg ->
                    actaPages.forEach { it.clear() }
                    actaResult = Pair(true, msg)
                }.onFailure { e ->
                    actaResult = Pair(false, e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    // ---- Camara ----
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraDate by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPhoto by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDate by rememberSaveable { mutableStateOf<String?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = cameraPath
        if (ok && path != null) {
            pendingPhoto = path
            pendingDate = cameraDate
        } else if (path != null) {
            File(path).delete()
        }
        cameraPath = null
    }

    fun takePhoto() {
        val f = store.newPhotoFile(date)
        cameraPath = f.absolutePath
        cameraDate = date.toString()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        cameraLauncher.launch(uri)
    }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().background(Color(0xFFE9E6DF))) {
            Sidebar(
                tool = tool, penColor = penColor, hlColor = hlColor, penWidth = penWidth, photoCount = photos.size, shapesOn = shapesOn,
                module = module, onModule = { module = it },
                onTool = { tool = it },
                onPenColor = { penColor = it; tool = Tool.PEN },
                onPenWidth = { penWidth = it; tool = Tool.PEN },
                onHlColor = { hlColor = it; tool = Tool.HIGHLIGHTER },
                onUndo = {
                    if (module == "acta") {
                        val order = listOf(lastActaPage) + actaPages.indices.filter { it != lastActaPage }
                        val pg = order.firstOrNull { actaPages[it].isNotEmpty() }
                        if (pg != null) { actaPages[pg].removeAt(actaPages[pg].lastIndex); commitActa() }
                    } else if (strokes.isNotEmpty()) {
                        strokes.removeAt(strokes.lastIndex); commit()
                    }
                },
                onClear = {
                    if (module == "acta") { if (actaPages.any { it.isNotEmpty() }) showActaClear = true }
                    else if (strokes.isNotEmpty()) showClear = true
                },
                onPhoto = { takePhoto() },
                onEvidence = { showEvidence = true },
                onSettings = { showSettings = true },
                onShapes = { shapesOn = !shapesOn }
            )
            if (module == "acta") {
                ActaScreen(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    pages = actaPages,
                    tool = tool, penColor = penColor, hlColor = hlColor,
                    penWidth = penWidth, shapesOn = shapesOn,
                    onChanged = { i -> lastActaPage = i; commitActa() },
                    onNew = { if (actaPages.any { it.isNotEmpty() }) showActaNew = true },
                    onShowFinal = { actaFiles = actaStore.listFinal(); showActaList = true },
                    onFinalize = { finalizeActa() }
                )
            } else {
            Column(
                Modifier
                    .weight(1f).fillMaxHeight().padding(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFFFFEFA))
            ) {
                Header(date)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    PaperCanvas(
                        strokes, tool, penColor, hlColor, penWidth, images,
                        thumbOf = ::thumbOf,
                        shapesOn = shapesOn,
                        selected = selectedPhoto,
                        onSelect = { selectedPhoto = it },
                        onChange = { commit() },
                        onSize = { w, h -> canvasW = w; canvasH = h }
                    )
                    // Barra de la foto seleccionada: recortar, quitar de la hoja, listo
                    selectedPhoto?.let { f ->
                        images.firstOrNull { it.file == f }?.let { im ->
                            val tx = im.x.coerceIn(0f, maxOf(0f, canvasW - 300f))
                            val ty = if (im.y > 56f) im.y - 56f else im.y + im.h + 12f
                            Row(
                                Modifier
                                    .offset(x = tx.dp, y = ty.dp)
                                    .background(Color.White, RoundedCornerShape(10.dp))
                                    .border(1.dp, Color(0xFFBBBBBB), RoundedCornerShape(10.dp))
                                    .padding(6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                DialogButton("✂️ Recortar") { cropFile = f }
                                DialogButton("🗑️ Quitar") {
                                    images.removeAll { it.file == f }
                                    selectedPhoto = null
                                    commit()
                                }
                                DialogButton("✔ Listo", primary = true) { selectedPhoto = null }
                            }
                        }
                    }
                }
                PageBar(
                    date = date, page = page, pageCount = pageCount,
                    onPrevDay = { goTo(date.minusDays(1)) },
                    onNextDay = { goTo(date.plusDays(1)) },
                    onToday = { goTo(LocalDate.now()) },
                    onPrevPage = { if (page > 1) page-- },
                    onNextPage = { if (page < pageCount) page++ },
                    onNewPage = { page = store.addPage(date); pageCount = page }
                )
            }
            }
        }

        pendingPhoto?.let { path ->
            PhotoDescriptionDialog(
                photoPath = path,
                onSave = { desc ->
                    val d = pendingDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: date
                    store.addPhoto(d, File(path).name, desc.ifBlank { "Sin descripción" })
                    if (d == date) {
                        photos = store.loadPhotos(date)
                        placePhoto(File(path).name)
                    }
                    pendingPhoto = null
                    pendingDate = null
                },
                onCancel = {
                    File(path).delete()
                    pendingPhoto = null
                    pendingDate = null
                }
            )
        }
        if (showEvidence) {
            EvidenceDialog(
                photos = photos,
                placed = images.map { it.file }.toSet(),
                fileOf = { store.photoFile(date, it.file) },
                onTogglePlace = { ph ->
                    if (images.any { it.file == ph.file }) {
                        images.removeAll { it.file == ph.file }
                        commit()
                    } else {
                        placePhoto(ph.file)
                    }
                },
                onDelete = {
                    store.deletePhoto(date, it)
                    photos = store.loadPhotos(date)
                    images.clear()
                    images.addAll(store.loadPage(date, page).images)
                    selectedPhoto = null
                },
                onClose = { showEvidence = false }
            )
        }
        if (showSettings) SettingsDialog(onClose = { showSettings = false })
        if (showActaList) {
            ActaListDialog(actaFiles, onOpen = { openPdf(context, it) }, onClose = { showActaList = false })
        }
        if (showActaNew) {
            ConfirmDialog(
                text = "¿Descartar el acta actual y empezar una nueva? Se perderá lo escrito si no la finalizaste.",
                onYes = { actaPages.forEach { it.clear() }; actaStore.clearDraft(); showActaNew = false },
                onNo = { showActaNew = false }
            )
        }
        if (showActaClear) {
            ConfirmDialog(
                text = "¿Borrar todo lo escrito y firmado en el acta?",
                onYes = { actaPages.forEach { it.clear() }; commitActa(); showActaClear = false },
                onNo = { showActaClear = false }
            )
        }
        if (actaBusy) BusyDialog("Generando el PDF y enviando el acta…")
        actaResult?.let { r ->
            ResultDialog(
                title = if (r.first) "Acta enviada" else "No se pudo enviar el acta",
                text = r.second, ok = r.first, onClose = { actaResult = null }
            )
        }
        cropFile?.let { f ->
            val bmp = remember(f) { ImageUtil.load(store.photoFile(date, f), 1400) }
            val cur = images.firstOrNull { it.file == f }
            if (bmp != null && cur != null) {
                CropDialog(
                    bitmap = remember(bmp) { bmp.asImageBitmap() },
                    initial = listOf(cur.cl, cur.ct, cur.cr, cur.cb),
                    onApply = { l, t, r, b ->
                        val i = images.indexOfFirst { it.file == f }
                        if (i >= 0) {
                            val im = images[i]
                            var nw = im.w
                            var nh = im.w * ((b - t) * bmp.height) / ((r - l) * bmp.width)
                            val sc = minOf(1f, canvasW / nw, canvasH / nh)
                            nw *= sc
                            nh *= sc
                            images[i] = im.copy(
                                w = nw, h = nh, cl = l, ct = t, cr = r, cb = b,
                                x = im.x.coerceIn(0f, maxOf(0f, canvasW - nw)),
                                y = im.y.coerceIn(0f, maxOf(0f, canvasH - nh))
                            )
                            commit()
                        }
                        cropFile = null
                    },
                    onCancel = { cropFile = null }
                )
            }
        }
        if (showClear) {
            ConfirmDialog(
                text = "¿Borrar todo lo escrito en esta página?",
                onYes = { strokes.clear(); commit(); showClear = false },
                onNo = { showClear = false }
            )
        }
    }
}

// ---------- Encabezado: dia a la izquierda, logo arriba a la derecha ----------
@Composable
fun Header(date: LocalDate) {
    val dia = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("es", "CL"))
        .replaceFirstChar { it.uppercase() }
    val fecha = "%02d/%02d/%d".format(date.dayOfMonth, date.monthValue, date.year)

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(dia, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = navy)
            Text(fecha, fontSize = 18.sp, color = Color(0xFF555555))
        }
        Image(
            painter = painterResource(R.drawable.logo_lpa),
            contentDescription = "Logo Liceo Politécnico Arica",
            contentScale = ContentScale.Fit,
            modifier = Modifier.height(84.dp)
        )
    }
    Box(Modifier.fillMaxWidth().height(2.dp).background(navy))
}

// ---------- Barra inferior: dias y paginas ----------
@Composable
fun PageBar(
    date: LocalDate, page: Int, pageCount: Int,
    onPrevDay: () -> Unit, onNextDay: () -> Unit, onToday: () -> Unit,
    onPrevPage: () -> Unit, onNextPage: () -> Unit, onNewPage: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFFF1EFE8)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        NavButton("◀ Día", true, onPrevDay)
        if (date != LocalDate.now()) NavButton("Hoy", true, onToday)
        NavButton("Día ▶", true, onNextDay)
        Spacer(Modifier.weight(1f))
        NavButton("◀", page > 1, onPrevPage)
        Text("Página $page de $pageCount", fontSize = 15.sp, color = navy, fontWeight = FontWeight.Medium)
        NavButton("▶", page < pageCount, onNextPage)
        NavButton("＋ Nueva página", true, onNewPage)
    }
}

@Composable
private fun NavButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 15.sp,
        color = if (enabled) Color.White else Color(0x88FFFFFF),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) navy else Color(0xFF9AA0B5))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

// ---------- Hoja cuadriculada + dibujo con lapiz ----------
@Composable
fun PaperCanvas(
    strokes: MutableList<InkStroke>,
    tool: Tool,
    penColor: Color,
    hlColor: Color,
    penWidth: Float,
    images: MutableList<PagePhoto>,
    thumbOf: (String) -> ImageBitmap?,
    shapesOn: Boolean,
    selected: String?,
    onSelect: (String?) -> Unit,
    onChange: () -> Unit,
    onSize: (Float, Float) -> Unit
) {
    val current = remember { mutableStateListOf<Offset>() }
    val dens = LocalDensity.current.density
    val haptic = LocalHapticFeedback.current

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { onSize(it.width / dens, it.height / dens) }
            .pointerInput(strokes, tool, penColor, hlColor, penWidth, images, shapesOn, selected) {
                val d = density
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Con el DEDO se manejan las fotos pegadas: tocar = seleccionar,
                    // arrastrar = mover, esquina azul o pellizco con dos dedos = cambiar tamano.
                    if (down.type == PointerType.Touch) {
                        val pos = down.position / d
                        val cw = size.width / d
                        val chh = size.height / d
                        val selIdx = images.indexOfFirst { it.file == selected }

                        // a) tirador de tamano (esquina inferior derecha de la foto seleccionada)
                        if (selIdx >= 0) {
                            val sel = images[selIdx]
                            if ((pos - Offset(sel.x + sel.w, sel.y + sel.h)).getDistance() < 26f) {
                                down.consume()
                                var changed = false
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) break
                                    val delta = (ch.position - ch.previousPosition) / d
                                    val im = images[selIdx]
                                    val maxW = minOf(cw - im.x, (chh - im.y) * im.w / im.h)
                                    val nw = (im.w + (delta.x + delta.y * im.w / im.h) / 2f)
                                        .coerceIn(40f, maxOf(40f, maxW))
                                    images[selIdx] = im.copy(w = nw, h = nw * im.h / im.w)
                                    changed = true
                                    ch.consume()
                                }
                                if (changed) onChange()
                                return@awaitEachGesture
                            }
                        }

                        // b) tocar una foto: seleccionarla; luego mover o pellizcar
                        val idx = images.indexOfLast {
                            pos.x in (it.x - 4f)..(it.x + it.w + 4f) && pos.y in (it.y - 4f)..(it.y + it.h + 4f)
                        }
                        if (idx < 0) {
                            if (selected != null) onSelect(null)
                            return@awaitEachGesture
                        }
                        onSelect(images[idx].file)
                        down.consume()
                        var moved = false
                        var lastPinch = 0f
                        while (true) {
                            val ev = awaitPointerEvent()
                            val touches = ev.changes.filter { it.pressed && it.type == PointerType.Touch }
                            if (touches.isEmpty()) break
                            if (touches.size >= 2) {
                                val dNow = (touches[0].position - touches[1].position).getDistance() / d
                                if (lastPinch > 0f && dNow > 0f) {
                                    val im = images[idx]
                                    val maxW = minOf(cw, chh * im.w / im.h)
                                    val nw = (im.w * dNow / lastPinch).coerceIn(40f, maxOf(40f, maxW))
                                    val nh = nw * im.h / im.w
                                    images[idx] = im.copy(
                                        x = (im.x + (im.w - nw) / 2f).coerceIn(0f, maxOf(0f, cw - nw)),
                                        y = (im.y + (im.h - nh) / 2f).coerceIn(0f, maxOf(0f, chh - nh)),
                                        w = nw, h = nh
                                    )
                                    moved = true
                                }
                                lastPinch = dNow
                                touches.forEach { it.consume() }
                                continue
                            }
                            lastPinch = 0f
                            val ch = touches[0]
                            val delta = (ch.position - ch.previousPosition) / d
                            if (delta != Offset.Zero) {
                                val im = images[idx]
                                images[idx] = im.copy(
                                    x = (im.x + delta.x).coerceIn(0f, maxOf(0f, cw - im.w)),
                                    y = (im.y + delta.y).coerceIn(0f, maxOf(0f, chh - im.h))
                                )
                                moved = true
                            }
                            ch.consume()
                        }
                        if (moved) onChange()
                        return@awaitEachGesture
                    }
                    // Solo lapiz: la palma y otros toques se ignoran
                    if (down.type != PointerType.Stylus && down.type != PointerType.Eraser) return@awaitEachGesture

                    val erasing = tool == Tool.ERASER || down.type == PointerType.Eraser
                    var erased = false

                    fun erase(p: Offset) {
                        if (strokes.removeAll { s -> s.points.any { (it - p).getDistance() < ERASER_RADIUS } }) erased = true
                    }

                    down.consume()
                    val start = down.position / d
                    if (erasing) erase(start) else { current.clear(); current.add(start) }

                    // Formas inteligentes: si se mantiene el lapiz quieto al final del trazo,
                    // la linea / circulo / rectangulo / triangulo se dibuja perfecto.
                    var anchor = start
                    var anchorTime = SystemClock.uptimeMillis()
                    var snapped = false
                    while (true) {
                        val event = withTimeoutOrNull(120L) { awaitPointerEvent() }
                        if (event != null) {
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val p = change.position / d
                            if (erasing) erase(p) else if (!snapped) current.add(p)
                            if ((p - anchor).getDistance() > HOLD_SLOP) {
                                anchor = p
                                anchorTime = SystemClock.uptimeMillis()
                            }
                            change.consume()
                        }
                        if (shapesOn && !erasing && !snapped && current.size >= 10 &&
                            SystemClock.uptimeMillis() - anchorTime >= HOLD_MS
                        ) {
                            val shape = ShapeRecognizer.recognize(
                                current.toList(), linesOnly = tool == Tool.HIGHLIGHTER
                            )
                            if (shape != null) {
                                current.clear()
                                current.addAll(shape)
                                snapped = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            } else {
                                anchorTime = SystemClock.uptimeMillis()
                            }
                        }
                    }

                    if (erasing) {
                        if (erased) onChange()
                    } else if (current.isNotEmpty()) {
                        val hl = tool == Tool.HIGHLIGHTER
                        strokes.add(
                            InkStroke(
                                current.toList(),
                                if (hl) hlColor.copy(alpha = 0.4f) else penColor,
                                if (hl) HL_WIDTH else penWidth,
                                hl
                            )
                        )
                        current.clear()
                        onChange()
                    }
                }
            }
    ) {
        val dn = density
        val step = 26.dp.toPx()
        val lineColor = Color(0xFFBFD4EA)
        var x = 0f
        while (x <= size.width) { drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
        var y = 0f
        while (y <= size.height) { drawLine(lineColor, Offset(0f, y), Offset(size.width, y), 1f); y += step }

        // Fotos pegadas en miniatura (con marco blanco, como una foto pegada en la agenda)
        for (im in images) {
            val bmp = thumbOf(im.file) ?: continue
            val tl = Offset(im.x * dn, im.y * dn)
            val frameTl = Offset(tl.x - 4f * dn, tl.y - 4f * dn)
            val frameSize = Size((im.w + 8f) * dn, (im.h + 8f) * dn)
            drawRect(Color.White, frameTl, frameSize)
            drawRect(Color(0xFFBBBBBB), frameTl, frameSize, style = DrawStroke(1f))
            drawImage(
                bmp,
                srcOffset = IntOffset((im.cl * bmp.width).roundToInt(), (im.ct * bmp.height).roundToInt()),
                srcSize = IntSize(
                    ((im.cr - im.cl) * bmp.width).roundToInt().coerceAtLeast(1),
                    ((im.cb - im.ct) * bmp.height).roundToInt().coerceAtLeast(1)
                ),
                dstOffset = IntOffset(tl.x.roundToInt(), tl.y.roundToInt()),
                dstSize = IntSize((im.w * dn).roundToInt(), (im.h * dn).roundToInt()),
                filterQuality = FilterQuality.Medium
            )
            if (im.file == selected) {
                drawRect(Color(0xFF1E88E5), frameTl, frameSize, style = DrawStroke(2.5f * dn))
                val corner = Offset((im.x + im.w) * dn, (im.y + im.h) * dn)
                drawCircle(Color(0xFF1E88E5), 13f * dn, corner)
                drawCircle(Color.White, 8f * dn, corner)
            }
        }

        // El destacador va debajo del lapiz, como en papel
        strokes.filter { it.highlighter }.forEach { drawInk(it, dn) }
        strokes.filter { !it.highlighter }.forEach { drawInk(it, dn) }

        if (current.isNotEmpty() && tool != Tool.ERASER) {
            val hl = tool == Tool.HIGHLIGHTER
            drawInk(
                InkStroke(
                    current.toList(),
                    if (hl) hlColor.copy(alpha = 0.4f) else penColor,
                    if (hl) HL_WIDTH else penWidth,
                    hl
                ), dn
            )
        }
    }
}

internal fun DrawScope.drawInk(s: InkStroke, d: Float) {
    if (s.points.isEmpty()) return
    if (s.points.size == 1) {
        drawCircle(s.color, s.width * d / 2f, s.points[0] * d)
        return
    }
    val pts = s.points.map { it * d }
    val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) {
            val p0 = pts[i - 1]; val p1 = pts[i]
            quadraticBezierTo(p0.x, p0.y, (p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f)
        }
        lineTo(pts.last().x, pts.last().y)
    }
    drawPath(
        path, s.color,
        style = DrawStroke(
            width = s.width * d,
            cap = if (s.highlighter) StrokeCap.Square else StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
}

// ---------- Barra lateral ----------
@Composable
fun Sidebar(
    tool: Tool, penColor: Color, hlColor: Color, penWidth: Float, photoCount: Int, shapesOn: Boolean,
    module: String, onModule: (String) -> Unit,
    onTool: (Tool) -> Unit, onPenColor: (Color) -> Unit, onPenWidth: (Float) -> Unit, onHlColor: (Color) -> Unit,
    onUndo: () -> Unit, onClear: () -> Unit,
    onPhoto: () -> Unit, onEvidence: () -> Unit, onSettings: () -> Unit, onShapes: () -> Unit
) {
    Column(
        Modifier
            .width(84.dp).fillMaxHeight().background(navy)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (module == "acta") ToolButton("📅", "Agenda", false) { onModule("agenda") }
        else ToolButton("📋", "Acta", false) { onModule("acta") }

        ToolButton("✏️", "Lápiz", tool == Tool.PEN) { onTool(Tool.PEN) }
        penColors.forEach { ColorDot(it, it == penColor && tool == Tool.PEN) { onPenColor(it) } }
        Text("Grosor", fontSize = 11.sp, color = Color.White)
        penWidths.forEach { w -> WidthButton(w, w == penWidth && tool == Tool.PEN, penColor) { onPenWidth(w) } }

        Spacer(Modifier.height(4.dp))
        ToolButton("🖍️", "Destacar", tool == Tool.HIGHLIGHTER) { onTool(Tool.HIGHLIGHTER) }
        highlightColors.forEach { ColorDot(it, it == hlColor && tool == Tool.HIGHLIGHTER) { onHlColor(it) } }

        Spacer(Modifier.height(4.dp))
        ToolButton("🧽", "Goma", tool == Tool.ERASER) { onTool(Tool.ERASER) }
        ToolButton("↩️", "Deshacer", false, onUndo)
        ToolButton("🗑️", "Limpiar", false, onClear)
        ToolButton("📐", "Formas", shapesOn, onShapes)

        Spacer(Modifier.height(4.dp))
        if (module != "acta") {
            ToolButton("📷", "Foto", false, onPhoto)
            ToolButton("🖼️", "Fotos ($photoCount)", false, onEvidence)
        }
        ToolButton("⚙️", "Ajustes", false, onSettings)
    }
}

@Composable
private fun ToolButton(icon: String, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0x44FFFFFF) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, fontSize = 24.sp)
        Text(label, fontSize = 11.sp, color = Color.White)
    }
}

@Composable
private fun WidthButton(width: Float, selected: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .width(56.dp).height(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(0x55FFFFFF) else Color(0x22FFFFFF))
            .border(if (selected) 2.dp else 0.dp, if (selected) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // Muestra del trazo con el grosor real; si el lapiz es negro se dibuja en blanco para que se vea
        Box(
            Modifier
                .width(38.dp).height(width.dp)
                .clip(RoundedCornerShape(50))
                .background(if (color == penColors[0]) Color.White else color)
        )
    }
}

@Composable
private fun ColorDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp).clip(CircleShape).background(color)
            .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else Color(0x88FFFFFF), CircleShape)
            .clickable(onClick = onClick)
    )
}


/** Abre un PDF guardado (acta finalizada) con el visor de PDF de la tablet. */
fun openPdf(context: android.content.Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(
            context,
            "No se pudo abrir el PDF (¿hay un visor de PDF instalado?). La copia está en Documentos/Actas.",
            Toast.LENGTH_LONG
        ).show()
    }
}
