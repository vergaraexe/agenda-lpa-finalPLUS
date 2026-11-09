package cl.lpa.agenda

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Actas: completamente separadas de la agenda.
 *   files/actas/draft.json   -> acta en edicion (borrador); se guarda sola a cada trazo
 *   files/actas/final/*.pdf  -> actas finalizadas y enviadas (para consulta)
 * Los trazos se guardan en puntos de hoja carta (612 x 792), asi se ven igual en cualquier pantalla y en el PDF.
 */
class ActaStore(context: Context) {
    companion object {
        const val PAGES = 2          // el formato del acta tiene 2 hojas
        const val PAGE_W = 612f      // carta en puntos
        const val PAGE_H = 792f
    }

    private val root = File(context.filesDir, "actas").apply { mkdirs() }
    private val draftFile = File(root, "draft.json")
    private val finalDir = File(root, "final").apply { mkdirs() }

    fun loadDraft(): List<SnapshotStateList<InkStroke>> {
        val result = List(PAGES) { mutableStateListOf<InkStroke>() }
        if (!draftFile.exists()) return result
        try {
            val pages = JSONObject(draftFile.readText()).getJSONArray("pages")
            for (p in 0 until minOf(PAGES, pages.length())) {
                val arr = pages.getJSONArray(p)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val pts = o.getJSONArray("p")
                    result[p].add(
                        InkStroke(
                            points = List(pts.length() / 2) { k ->
                                Offset(pts.getDouble(2 * k).toFloat(), pts.getDouble(2 * k + 1).toFloat())
                            },
                            color = Color(o.getInt("c")),
                            width = o.getDouble("w").toFloat(),
                            highlighter = o.getBoolean("h")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // borrador danado: se parte en blanco
        }
        return result
    }

    fun saveDraft(pages: List<List<InkStroke>>) {
        val all = JSONArray()
        pages.forEach { strokes ->
            val arr = JSONArray()
            strokes.forEach { s ->
                val pts = JSONArray()
                s.points.forEach { pts.put(it.x.toDouble()); pts.put(it.y.toDouble()) }
                arr.put(
                    JSONObject().put("c", s.color.toArgb()).put("w", s.width.toDouble())
                        .put("h", s.highlighter).put("p", pts)
                )
            }
            all.put(arr)
        }
        val tmp = File(root, "draft.tmp")
        tmp.writeText(JSONObject().put("pages", all).toString())
        draftFile.delete()
        tmp.renameTo(draftFile)
    }

    fun clearDraft() {
        draftFile.delete()
    }

    /** Guarda el PDF finalizado en la carpeta de consulta y devuelve ese archivo. */
    fun addFinal(pdf: File): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault()).format(Date())
        val dest = File(finalDir, "Acta_$stamp.pdf")
        pdf.copyTo(dest, overwrite = true)
        return dest
    }

    fun listFinal(): List<File> =
        finalDir.listFiles { f -> f.extension.equals("pdf", true) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
}
