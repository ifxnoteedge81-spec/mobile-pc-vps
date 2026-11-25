package com.example.runtime

import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class CadOutputSummary(
    val slug: String,
    val title: String,
    val units: String,
    val entityCount: Int,
    val width: Double,
    val height: Double,
    val dxfPath: String,
    val svgPath: String,
    val viewerHtmlPath: String,
    val stlPath: String?
)

class CadDrawingEngine(private val workspace: VirtualWorkspaceManager) {

    sealed class CadEntity(val layer: String) {
        class Line(val x1: Double, val y1: Double, val x2: Double, val y2: Double, layer: String) : CadEntity(layer)
        class Rect(val x: Double, val y: Double, val w: Double, val h: Double, layer: String) : CadEntity(layer)
        class Circle(val cx: Double, val cy: Double, val r: Double, layer: String) : CadEntity(layer)
        class Arc(val cx: Double, val cy: Double, val r: Double, val startDeg: Double, val endDeg: Double, layer: String) : CadEntity(layer)
        class Polyline(val points: List<Pair<Double, Double>>, val closed: Boolean, layer: String) : CadEntity(layer)
        class LabelText(val x: Double, val y: Double, val text: String, val height: Double, layer: String) : CadEntity(layer)
        class Dimension(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val offset: Double, val label: String, layer: String) : CadEntity(layer)
    }

    fun generateCadProject(
        slug: String,
        title: String,
        units: String = "mm",
        entitiesJson: String = "",
        template: String = "",
        extrudeHeight3d: Double = 0.0
    ): Result<CadOutputSummary> = runCatching {
        val safeSlug = slug.lowercase().replace(Regex("[^a-z0-9_-]"), "_").trim('_').ifBlank { "cad_${System.currentTimeMillis() % 10000}" }
        val cadDir = workspace.resolveSafe("projects/cad/$safeSlug")
        if (!cadDir.exists()) cadDir.mkdirs()

        val entities = mutableListOf<CadEntity>()
        if (template.isNotBlank()) {
            entities.addAll(buildTemplateEntities(template.trim().lowercase()))
        }
        if (entitiesJson.isNotBlank()) {
            entities.addAll(parseEntitiesJson(entitiesJson))
        }
        if (entities.isEmpty()) {
            entities.addAll(buildTemplateEntities("mechanical_plate"))
        }

        val bounds = computeBounds(entities)
        val minX = bounds[0]
        val minY = bounds[1]
        val maxX = bounds[2]
        val maxY = bounds[3]
        val width = (maxX - minX).coerceAtLeast(10.0)
        val height = (maxY - minY).coerceAtLeast(10.0)

        val dxfFile = File(cadDir, "$safeSlug.dxf")
        dxfFile.writeText(buildDxf(title, units, entities))

        val svgContent = buildSvg(title, units, entities, minX, minY, width, height)
        val svgFile = File(cadDir, "$safeSlug.svg")
        svgFile.writeText(svgContent)

        val htmlFile = File(cadDir, "${safeSlug}_viewer.html")
        htmlFile.writeText(buildInteractiveHtmlViewer(title, units, entities.size, width, height, svgContent, safeSlug))

        val stlFile = if (extrudeHeight3d > 0.0) {
            File(cadDir, "$safeSlug.stl").also {
                it.writeText(buildExtrudedAsciiStl(safeSlug, entities, extrudeHeight3d))
            }
        } else null

        CadOutputSummary(
            slug = safeSlug,
            title = title,
            units = units,
            entityCount = entities.size,
            width = width,
            height = height,
            dxfPath = workspace.relativePathOf(dxfFile),
            svgPath = workspace.relativePathOf(svgFile),
            viewerHtmlPath = workspace.relativePathOf(htmlFile),
            stlPath = stlFile?.let { workspace.relativePathOf(it) }
        )
    }

    private fun parseEntitiesJson(raw: String): List<CadEntity> {
        val parsed = MiniJsonParser(raw).parseValue()
        val arr: List<*> = when (parsed) {
            is List<*> -> parsed
            is Map<*, *> -> (parsed["entities"] as? List<*>) ?: emptyList<Any>()
            else -> emptyList<Any>()
        }

        fun Map<*, *>.str(key: String, def: String = ""): String =
            (this[key] as? String) ?: this[key]?.toString() ?: def
        fun Map<*, *>.num(key: String, def: Double): Double =
            (this[key] as? Number)?.toDouble() ?: (this[key] as? String)?.toDoubleOrNull() ?: def
        fun Map<*, *>.bool(key: String, def: Boolean): Boolean =
            (this[key] as? Boolean) ?: def

        val list = mutableListOf<CadEntity>()
        for (item in arr) {
            val obj = item as? Map<*, *> ?: continue
            val type = obj.str("type", "line").lowercase()
            val layer = obj.str("layer", "GEOMETRY").uppercase().ifBlank { "GEOMETRY" }
            when (type) {
                "line" -> list.add(
                    CadEntity.Line(obj.num("x1", 0.0), obj.num("y1", 0.0), obj.num("x2", 100.0), obj.num("y2", 0.0), layer)
                )
                "rect", "rectangle", "box" -> list.add(
                    CadEntity.Rect(
                        obj.num("x", 0.0),
                        obj.num("y", 0.0),
                        obj.num("w", obj.num("width", 50.0)),
                        obj.num("h", obj.num("height", 30.0)),
                        layer
                    )
                )
                "circle" -> list.add(
                    CadEntity.Circle(
                        obj.num("cx", obj.num("x", 0.0)),
                        obj.num("cy", obj.num("y", 0.0)),
                        obj.num("r", obj.num("radius", 10.0)),
                        layer
                    )
                )
                "arc" -> list.add(
                    CadEntity.Arc(
                        obj.num("cx", obj.num("x", 0.0)),
                        obj.num("cy", obj.num("y", 0.0)),
                        obj.num("r", obj.num("radius", 20.0)),
                        obj.num("start_angle", 0.0),
                        obj.num("end_angle", 90.0),
                        layer
                    )
                )
                "polyline", "polygon" -> {
                    val ptsRaw = obj["points"] as? List<*> ?: continue
                    val pts = mutableListOf<Pair<Double, Double>>()
                    for (ptAny in ptsRaw) {
                        when (ptAny) {
                            is List<*> -> if (ptAny.size >= 2) {
                                val x = (ptAny[0] as? Number)?.toDouble() ?: 0.0
                                val y = (ptAny[1] as? Number)?.toDouble() ?: 0.0
                                pts.add(x to y)
                            }
                            is Map<*, *> -> pts.add(ptAny.num("x", 0.0) to ptAny.num("y", 0.0))
                        }
                    }
                    if (pts.size >= 2) {
                        list.add(CadEntity.Polyline(pts, obj.bool("closed", type == "polygon"), layer))
                    }
                }
                "text", "label" -> list.add(
                    CadEntity.LabelText(
                        obj.num("x", 0.0),
                        obj.num("y", 0.0),
                        obj.str("text", ""),
                        obj.num("height", 5.0).coerceAtLeast(1.5),
                        obj.str("layer", "TEXT").uppercase()
                    )
                )
                "dimension", "dim" -> {
                    val x1 = obj.num("x1", 0.0)
                    val y1 = obj.num("y1", 0.0)
                    val x2 = obj.num("x2", 100.0)
                    val y2 = obj.num("y2", 0.0)
                    val dist = hypot(x2 - x1, y2 - y1)
                    val label = obj.str("label", String.format(Locale.US, "%.2f", dist))
                    list.add(
                        CadEntity.Dimension(
                            x1, y1, x2, y2,
                            obj.num("offset", 12.0),
                            label,
                            obj.str("layer", "DIMENSIONS").uppercase()
                        )
                    )
                }
            }
        }
        return list
    }

    private class MiniJsonParser(private val src: String) {
        private var pos = 0

        fun parseValue(): Any? {
            skipWs()
            if (pos >= src.length) return null
            return when (src[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { pos += 4; true }
                'f' -> { pos += 5; false }
                'n' -> { pos += 4; null }
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            val map = linkedMapOf<String, Any?>()
            pos++ // {
            skipWs()
            if (pos < src.length && src[pos] == '}') { pos++; return map }
            while (pos < src.length) {
                skipWs()
                val key = parseString()
                skipWs()
                if (pos < src.length && src[pos] == ':') pos++
                map[key] = parseValue()
                skipWs()
                if (pos < src.length && src[pos] == ',') { pos++; continue }
                if (pos < src.length && src[pos] == '}') { pos++; break }
                break
            }
            return map
        }

        private fun parseArray(): List<Any?> {
            val list = mutableListOf<Any?>()
            pos++ // [
            skipWs()
            if (pos < src.length && src[pos] == ']') { pos++; return list }
            while (pos < src.length) {
                list.add(parseValue())
                skipWs()
                if (pos < src.length && src[pos] == ',') { pos++; continue }
                if (pos < src.length && src[pos] == ']') { pos++; break }
                break
            }
            return list
        }

        private fun parseString(): String {
            if (pos >= src.length || src[pos] != '"') return ""
            pos++ // opening quote
            val sb = StringBuilder()
            while (pos < src.length) {
                val c = src[pos++]
                if (c == '"') break
                if (c == '\\' && pos < src.length) {
                    when (val esc = src[pos++]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun parseNumber(): Double {
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] in setOf('-', '+', '.', 'e', 'E'))) {
                pos++
            }
            return src.substring(start, pos).toDoubleOrNull() ?: 0.0
        }

        private fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }
    }

    private fun buildTemplateEntities(template: String): List<CadEntity> {
        return when {
            template.contains("floor") || template.contains("house") || template.contains("room") || template.contains("building") -> listOf(
                CadEntity.Rect(0.0, 0.0, 1200.0, 900.0, "WALLS"),
                CadEntity.Rect(20.0, 20.0, 1160.0, 860.0, "WALLS"),
                CadEntity.Line(600.0, 20.0, 600.0, 880.0, "WALLS"),
                CadEntity.Line(20.0, 480.0, 600.0, 480.0, "WALLS"),
                CadEntity.Line(600.0, 420.0, 1180.0, 420.0, "WALLS"),
                CadEntity.Arc(500.0, 20.0, 90.0, 0.0, 90.0, "DOORS"),
                CadEntity.Line(500.0, 20.0, 500.0, 110.0, "DOORS"),
                CadEntity.LabelText(220.0, 680.0, "LIVING HALL (580 x 400)", 28.0, "TEXT"),
                CadEntity.LabelText(760.0, 660.0, "MASTER BEDROOM (580 x 460)", 28.0, "TEXT"),
                CadEntity.LabelText(180.0, 230.0, "KITCHEN & DINING", 26.0, "TEXT"),
                CadEntity.LabelText(780.0, 210.0, "BEDROOM 2 / STUDY", 26.0, "TEXT"),
                CadEntity.Dimension(0.0, 0.0, 1200.0, 0.0, -70.0, "1200 cm", "DIMENSIONS"),
                CadEntity.Dimension(1200.0, 0.0, 1200.0, 900.0, 70.0, "900 cm", "DIMENSIONS")
            )
            template.contains("gear") -> {
                val out = mutableListOf<CadEntity>()
                val teeth = 16
                val rOuter = 60.0
                val rRoot = 48.0
                val pts = mutableListOf<Pair<Double, Double>>()
                for (i in 0 until teeth) {
                    val a0 = (2.0 * PI * i) / teeth
                    val a1 = a0 + (PI / (teeth * 2.2))
                    val a2 = a0 + (PI / teeth)
                    val a3 = a0 + (1.5 * PI / teeth)
                    pts.add(rRoot * cos(a0) to rRoot * sin(a0))
                    pts.add(rOuter * cos(a1) to rOuter * sin(a1))
                    pts.add(rOuter * cos(a2) to rOuter * sin(a2))
                    pts.add(rRoot * cos(a3) to rRoot * sin(a3))
                }
                out.add(CadEntity.Polyline(pts, true, "MECHANICAL"))
                out.add(CadEntity.Circle(0.0, 0.0, 16.0, "HOLES"))
                out.add(CadEntity.Circle(0.0, 0.0, 32.0, "CENTERLINES"))
                out.add(CadEntity.Dimension(-rOuter, 0.0, rOuter, 0.0, -75.0, "OD 120.0 mm", "DIMENSIONS"))
                out
            }
            template.contains("bracket") -> listOf(
                CadEntity.Polyline(
                    listOf(0.0 to 0.0, 140.0 to 0.0, 140.0 to 25.0, 25.0 to 25.0, 25.0 to 110.0, 0.0 to 110.0),
                    closed = true,
                    layer = "MECHANICAL"
                ),
                CadEntity.Circle(105.0, 12.5, 6.0, "HOLES"),
                CadEntity.Circle(12.5, 80.0, 6.0, "HOLES"),
                CadEntity.Dimension(0.0, 0.0, 140.0, 0.0, -18.0, "140 mm", "DIMENSIONS"),
                CadEntity.Dimension(0.0, 0.0, 0.0, 110.0, -18.0, "110 mm", "DIMENSIONS")
            )
            else -> listOf(
                CadEntity.Rect(0.0, 0.0, 160.0, 100.0, "OUTLINE"),
                CadEntity.Circle(20.0, 20.0, 6.0, "HOLES"),
                CadEntity.Circle(140.0, 20.0, 6.0, "HOLES"),
                CadEntity.Circle(20.0, 80.0, 6.0, "HOLES"),
                CadEntity.Circle(140.0, 80.0, 6.0, "HOLES"),
                CadEntity.Circle(80.0, 50.0, 24.0, "MECHANICAL"),
                CadEntity.LabelText(44.0, 112.0, "MOUNTING PLATE", 7.0, "TEXT"),
                CadEntity.Dimension(0.0, 0.0, 160.0, 0.0, -16.0, "160.0 mm", "DIMENSIONS"),
                CadEntity.Dimension(160.0, 0.0, 160.0, 100.0, 16.0, "100.0 mm", "DIMENSIONS")
            )
        }
    }

    private fun layerAciColor(layer: String): Int = when {
        layer.contains("WALL") || layer.contains("OUTLINE") -> 7 // white/black
        layer.contains("DOOR") || layer.contains("ARC") -> 3 // green
        layer.contains("DIM") -> 2 // yellow
        layer.contains("HOLE") || layer.contains("ELECTRICAL") -> 1 // red
        layer.contains("CENTER") -> 6 // magenta
        layer.contains("TEXT") -> 4 // cyan
        else -> 5 // blue
    }

    private fun layerHexColor(layer: String): String = when {
        layer.contains("WALL") || layer.contains("OUTLINE") -> "#E2E8F0"
        layer.contains("DOOR") -> "#10B981"
        layer.contains("DIM") -> "#FBBF24"
        layer.contains("HOLE") || layer.contains("ELECTRICAL") -> "#FB7185"
        layer.contains("CENTER") -> "#C084FC"
        layer.contains("TEXT") -> "#22D3EE"
        else -> "#38BDF8"
    }

    private fun dxfUnitCode(units: String): Int = when (units.lowercase()) {
        "in", "inch", "inches" -> 1
        "ft", "feet" -> 2
        "mm" -> 4
        "cm" -> 5
        "m" -> 6
        else -> 4
    }

    private fun buildDxf(title: String, units: String, entities: List<CadEntity>): String {
        val layers = entities.map { it.layer }.distinct().ifEmpty { listOf("GEOMETRY") }
        val sb = StringBuilder()
        sb.append("999\nGenerated by Personal AI Computer CAD Engine: $title\n")
        sb.append("0\nSECTION\n2\nHEADER\n")
        sb.append("9\n\$ACADVER\n1\nAC1009\n")
        sb.append("9\n\$INSUNITS\n70\n${dxfUnitCode(units)}\n")
        sb.append("0\nENDSEC\n")

        sb.append("0\nSECTION\n2\nTABLES\n0\nTABLE\n2\nLAYER\n70\n${layers.size}\n")
        layers.forEach { lyr ->
            sb.append("0\nLAYER\n2\n$lyr\n70\n0\n62\n${layerAciColor(lyr)}\n6\nCONTINUOUS\n")
        }
        sb.append("0\nENDTAB\n0\nENDSEC\n")

        sb.append("0\nSECTION\n2\nENTITIES\n")
        fun appendDxfLine(x1: Double, y1: Double, x2: Double, y2: Double, lyr: String) {
            sb.append("0\nLINE\n8\n$lyr\n10\n${fmt(x1)}\n20\n${fmt(y1)}\n30\n0.0\n11\n${fmt(x2)}\n21\n${fmt(y2)}\n31\n0.0\n")
        }
        entities.forEach { e ->
            when (e) {
                is CadEntity.Line -> appendDxfLine(e.x1, e.y1, e.x2, e.y2, e.layer)
                is CadEntity.Rect -> {
                    appendDxfLine(e.x, e.y, e.x + e.w, e.y, e.layer)
                    appendDxfLine(e.x + e.w, e.y, e.x + e.w, e.y + e.h, e.layer)
                    appendDxfLine(e.x + e.w, e.y + e.h, e.x, e.y + e.h, e.layer)
                    appendDxfLine(e.x, e.y + e.h, e.x, e.y, e.layer)
                }
                is CadEntity.Circle -> {
                    sb.append("0\nCIRCLE\n8\n${e.layer}\n10\n${fmt(e.cx)}\n20\n${fmt(e.cy)}\n30\n0.0\n40\n${fmt(e.r)}\n")
                }
                is CadEntity.Arc -> {
                    sb.append("0\nARC\n8\n${e.layer}\n10\n${fmt(e.cx)}\n20\n${fmt(e.cy)}\n30\n0.0\n40\n${fmt(e.r)}\n50\n${fmt(e.startDeg)}\n51\n${fmt(e.endDeg)}\n")
                }
                is CadEntity.Polyline -> {
                    for (i in 0 until e.points.size - 1) {
                        val a = e.points[i]
                        val b = e.points[i + 1]
                        appendDxfLine(a.first, a.second, b.first, b.second, e.layer)
                    }
                    if (e.closed && e.points.size >= 3) {
                        val last = e.points.last()
                        val first = e.points.first()
                        appendDxfLine(last.first, last.second, first.first, first.second, e.layer)
                    }
                }
                is CadEntity.LabelText -> {
                    sb.append("0\nTEXT\n8\n${e.layer}\n10\n${fmt(e.x)}\n20\n${fmt(e.y)}\n30\n0.0\n40\n${fmt(e.height)}\n1\n${e.text.replace("\n", " ")}\n")
                }
                is CadEntity.Dimension -> {
                    val (dx1, dy1, dx2, dy2, mx, my) = dimensionEndpoints(e)
                    appendDxfLine(e.x1, e.y1, dx1, dy1, e.layer)
                    appendDxfLine(e.x2, e.y2, dx2, dy2, e.layer)
                    appendDxfLine(dx1, dy1, dx2, dy2, e.layer)
                    val txtH = (hypot(e.x2 - e.x1, e.y2 - e.y1) * 0.045).coerceIn(2.5, 24.0)
                    sb.append("0\nTEXT\n8\n${e.layer}\n10\n${fmt(mx)}\n20\n${fmt(my)}\n30\n0.0\n40\n${fmt(txtH)}\n1\n${e.label}\n")
                }
            }
        }
        sb.append("0\nENDSEC\n0\nEOF\n")
        return sb.toString()
    }

    private fun buildSvg(
        title: String,
        units: String,
        entities: List<CadEntity>,
        minX: Double,
        minY: Double,
        width: Double,
        height: Double
    ): String {
        val pad = max(width, height) * 0.14
        val vbX = minX - pad
        val vbW = width + pad * 2.0
        val vbH = height + pad * 2.0
        val strokeW = (max(width, height) * 0.0045).coerceAtLeast(0.4)
        // Flip Y for CAD Cartesian (+Y up) -> SVG (+Y down)
        fun sy(y: Double) = (minY + height) - (y - minY)

        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="${fmt(vbX)} ${fmt(minY - pad)} ${fmt(vbW)} ${fmt(vbH)}" style="background:#07111e;width:100%;height:100%;">""")
        sb.append("""<defs><pattern id="cadgrid" width="${fmt(max(width, height) / 16.0)}" height="${fmt(max(width, height) / 16.0)}" patternUnits="userSpaceOnUse"><path d="M ${fmt(max(width, height) / 16.0)} 0 L 0 0 0 ${fmt(max(width, height) / 16.0)}" fill="none" stroke="#13253b" stroke-width="${fmt(strokeW * 0.5)}"/></pattern></defs>""")
        sb.append("""<rect x="${fmt(vbX)}" y="${fmt(minY - pad)}" width="${fmt(vbW)}" height="${fmt(vbH)}" fill="url(#cadgrid)"/>""")

        entities.forEach { e ->
            val color = layerHexColor(e.layer)
            when (e) {
                is CadEntity.Line -> sb.append("""<line x1="${fmt(e.x1)}" y1="${fmt(sy(e.y1))}" x2="${fmt(e.x2)}" y2="${fmt(sy(e.y2))}" stroke="$color" stroke-width="${fmt(strokeW)}"/>""")
                is CadEntity.Rect -> sb.append("""<rect x="${fmt(e.x)}" y="${fmt(sy(e.y + e.h))}" width="${fmt(e.w)}" height="${fmt(e.h)}" fill="none" stroke="$color" stroke-width="${fmt(strokeW)}"/>""")
                is CadEntity.Circle -> sb.append("""<circle cx="${fmt(e.cx)}" cy="${fmt(sy(e.cy))}" r="${fmt(e.r)}" fill="none" stroke="$color" stroke-width="${fmt(strokeW)}"/>""")
                is CadEntity.Arc -> {
                    val sRad = Math.toRadians(e.startDeg)
                    val eRad = Math.toRadians(e.endDeg)
                    val sx1 = e.cx + e.r * cos(sRad)
                    val sy1 = sy(e.cy + e.r * sin(sRad))
                    val sx2 = e.cx + e.r * cos(eRad)
                    val sy2 = sy(e.cy + e.r * sin(eRad))
                    val largeArc = if (((e.endDeg - e.startDeg + 360.0) % 360.0) > 180.0) 1 else 0
                    sb.append("""<path d="M ${fmt(sx1)} ${fmt(sy1)} A ${fmt(e.r)} ${fmt(e.r)} 0 $largeArc 0 ${fmt(sx2)} ${fmt(sy2)}" fill="none" stroke="$color" stroke-width="${fmt(strokeW)}"/>""")
                }
                is CadEntity.Polyline -> {
                    val ptsStr = e.points.joinToString(" ") { "${fmt(it.first)},${fmt(sy(it.second))}" }
                    val tag = if (e.closed) "polygon" else "polyline"
                    sb.append("""<$tag points="$ptsStr" fill="none" stroke="$color" stroke-width="${fmt(strokeW)}"/>""")
                }
                is CadEntity.LabelText -> {
                    sb.append("""<text x="${fmt(e.x)}" y="${fmt(sy(e.y))}" fill="$color" font-size="${fmt(e.height)}" font-family="monospace">${escapeXml(e.text)}</text>""")
                }
                is CadEntity.Dimension -> {
                    val (dx1, dy1, dx2, dy2, mx, my) = dimensionEndpoints(e)
                    val dimStroke = fmt(strokeW * 0.7)
                    val fSize = fmt((max(width, height) * 0.024).coerceAtLeast(3.0))
                    sb.append("""<line x1="${fmt(e.x1)}" y1="${fmt(sy(e.y1))}" x2="${fmt(dx1)}" y2="${fmt(sy(dy1))}" stroke="$color" stroke-width="$dimStroke" stroke-dasharray="${fmt(strokeW * 2)}"/>""")
                    sb.append("""<line x1="${fmt(e.x2)}" y1="${fmt(sy(e.y2))}" x2="${fmt(dx2)}" y2="${fmt(sy(dy2))}" stroke="$color" stroke-width="$dimStroke" stroke-dasharray="${fmt(strokeW * 2)}"/>""")
                    sb.append("""<line x1="${fmt(dx1)}" y1="${fmt(sy(dy1))}" x2="${fmt(dx2)}" y2="${fmt(sy(dy2))}" stroke="$color" stroke-width="$dimStroke"/>""")
                    sb.append("""<text x="${fmt(mx)}" y="${fmt(sy(my))}" fill="$color" font-size="$fSize" font-family="monospace" text-anchor="middle">${escapeXml(e.label)}</text>""")
                }
            }
        }
        val titleSize = fmt((max(width, height) * 0.025).coerceAtLeast(3.5))
        sb.append("""<text x="${fmt(minX)}" y="${fmt(sy(minY + height) - pad * 0.35)}" fill="#22d3ee" font-size="$titleSize" font-family="monospace">${escapeXml(title)} [${units}]</text>""")
        sb.append("</svg>")
        return sb.toString()
    }

    private fun buildInteractiveHtmlViewer(
        title: String,
        units: String,
        entityCount: Int,
        width: Double,
        height: Double,
        svgContent: String,
        slug: String
    ): String = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="UTF-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1.0" />
          <title>CAD Blueprint — ${escapeXml(title)}</title>
          <style>
            body { margin:0; background:#07111e; color:#e2e8f0; font-family:system-ui,monospace; display:flex; flex-direction:column; height:100vh; }
            header { padding:10px 14px; background:#0c192c; border-bottom:1px solid #1e3a5f; display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:8px; }
            .meta { font-size:12px; color:#38bdf8; }
            .stage { flex:1; overflow:hidden; display:flex; align-items:center; justify-content:center; padding:8px; touch-action:pinch-zoom; }
            button { background:#164e63; color:#ecfeff; border:1px solid #22d3ee; border-radius:6px; padding:6px 10px; font-size:12px; }
          </style>
        </head>
        <body>
          <header>
            <div>
              <strong>${escapeXml(title)}</strong>
              <div class="meta">$entityCount entities • ${fmt(width)} x ${fmt(height)} $units • AutoCAD $slug.dxf ready</div>
            </div>
            <div>
              <button onclick="zoom(1.25)">Zoom +</button>
              <button onclick="zoom(0.8)">Zoom -</button>
              <button onclick="resetZoom()">Fit</button>
            </div>
          </header>
          <div class="stage" id="stage">$svgContent</div>
          <script>
            let scale = 1;
            const svg = document.querySelector('#stage svg');
            function zoom(f){ scale = Math.max(0.3, Math.min(8, scale * f)); svg.style.transform = 'scale(' + scale + ')'; }
            function resetZoom(){ scale = 1; svg.style.transform = 'scale(1)'; }
          </script>
        </body>
        </html>
    """.trimIndent()

    private fun buildExtrudedAsciiStl(slug: String, entities: List<CadEntity>, height: Double): String {
        val sb = StringBuilder("solid $slug\n")
        fun addTri(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double, x3: Double, y3: Double, z3: Double) {
            sb.append("  facet normal 0 0 1\n    outer loop\n")
            sb.append("      vertex ${fmt(x1)} ${fmt(y1)} ${fmt(z1)}\n")
            sb.append("      vertex ${fmt(x2)} ${fmt(y2)} ${fmt(z2)}\n")
            sb.append("      vertex ${fmt(x3)} ${fmt(y3)} ${fmt(z3)}\n")
            sb.append("    endloop\n  endfacet\n")
        }
        fun addBox(x: Double, y: Double, w: Double, h: Double, zTop: Double) {
            val x2 = x + w; val y2 = y + h
            // Top & bottom
            addTri(x, y, zTop, x2, y, zTop, x2, y2, zTop)
            addTri(x, y, zTop, x2, y2, zTop, x, y2, zTop)
            addTri(x, y, 0.0, x2, y2, 0.0, x2, y, 0.0)
            addTri(x, y, 0.0, x, y2, 0.0, x2, y2, 0.0)
            // Sides
            addTri(x, y, 0.0, x2, y, 0.0, x2, y, zTop); addTri(x, y, 0.0, x2, y, zTop, x, y, zTop)
            addTri(x2, y, 0.0, x2, y2, 0.0, x2, y2, zTop); addTri(x2, y, 0.0, x2, y2, zTop, x2, y, zTop)
            addTri(x2, y2, 0.0, x, y2, 0.0, x, y2, zTop); addTri(x2, y2, 0.0, x, y2, zTop, x2, y2, zTop)
            addTri(x, y2, 0.0, x, y, 0.0, x, y, zTop); addTri(x, y2, 0.0, x, y, zTop, x, y2, zTop)
        }
        entities.forEach { e ->
            when (e) {
                is CadEntity.Rect -> addBox(e.x, e.y, e.w, e.h, height)
                is CadEntity.Line -> {
                    val t = 2.0
                    addBox(min(e.x1, e.x2), min(e.y1, e.y2), (kotlin.math.abs(e.x2 - e.x1)).coerceAtLeast(t), (kotlin.math.abs(e.y2 - e.y1)).coerceAtLeast(t), height)
                }
                is CadEntity.Circle -> {
                    val segs = 24
                    for (i in 0 until segs) {
                        val a1 = 2.0 * PI * i / segs
                        val a2 = 2.0 * PI * (i + 1) / segs
                        val px1 = e.cx + e.r * cos(a1); val py1 = e.cy + e.r * sin(a1)
                        val px2 = e.cx + e.r * cos(a2); val py2 = e.cy + e.r * sin(a2)
                        addTri(e.cx, e.cy, height, px1, py1, height, px2, py2, height)
                        addTri(e.cx, e.cy, 0.0, px2, py2, 0.0, px1, py1, 0.0)
                        addTri(px1, py1, 0.0, px2, py2, 0.0, px2, py2, height)
                        addTri(px1, py1, 0.0, px2, py2, height, px1, py1, height)
                    }
                }
                else -> {}
            }
        }
        sb.append("endsolid $slug\n")
        return sb.toString()
    }

    private data class DimCoords(val dx1: Double, val dy1: Double, val dx2: Double, val dy2: Double, val mx: Double, val my: Double)

    private fun dimensionEndpoints(e: CadEntity.Dimension): DimCoords {
        val angle = atan2(e.y2 - e.y1, e.x2 - e.x1)
        val nx = -sin(angle) * e.offset
        val ny = cos(angle) * e.offset
        val dx1 = e.x1 + nx; val dy1 = e.y1 + ny
        val dx2 = e.x2 + nx; val dy2 = e.y2 + ny
        return DimCoords(dx1, dy1, dx2, dy2, (dx1 + dx2) / 2.0, (dy1 + dy2) / 2.0)
    }

    private fun computeBounds(entities: List<CadEntity>): DoubleArray {
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        fun expand(x: Double, y: Double) {
            minX = min(minX, x); minY = min(minY, y)
            maxX = max(maxX, x); maxY = max(maxY, y)
        }
        entities.forEach { e ->
            when (e) {
                is CadEntity.Line -> { expand(e.x1, e.y1); expand(e.x2, e.y2) }
                is CadEntity.Rect -> { expand(e.x, e.y); expand(e.x + e.w, e.y + e.h) }
                is CadEntity.Circle -> { expand(e.cx - e.r, e.cy - e.r); expand(e.cx + e.r, e.cy + e.r) }
                is CadEntity.Arc -> { expand(e.cx - e.r, e.cy - e.r); expand(e.cx + e.r, e.cy + e.r) }
                is CadEntity.Polyline -> e.points.forEach { expand(it.first, it.second) }
                is CadEntity.LabelText -> { expand(e.x, e.y); expand(e.x + e.text.length * e.height * 0.55, e.y + e.height) }
                is CadEntity.Dimension -> {
                    val d = dimensionEndpoints(e)
                    expand(e.x1, e.y1); expand(e.x2, e.y2); expand(d.dx1, d.dy1); expand(d.dx2, d.dy2)
                }
            }
        }
        if (!minX.isFinite()) return doubleArrayOf(0.0, 0.0, 100.0, 100.0)
        return doubleArrayOf(minX, minY, maxX, maxY)
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.3f", v)

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
