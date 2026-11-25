package com.example.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/**
 * Zero-dependency on-device document text extractor for PDF, DOCX/ODT/PPTX, and text files.
 * Works identically on Android runtime and pure-JVM unit tests.
 */
object DocumentTextExtractor {

    fun isStructuredDocument(fileName: String, mimeType: String = ""): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val mime = mimeType.lowercase()
        return ext in setOf("pdf", "docx", "odt", "pptx", "xlsx", "rtf") ||
            mime.contains("pdf") ||
            mime.contains("wordprocessingml") ||
            mime.contains("opendocument")
    }

    fun isPlainTextDocument(fileName: String, mimeType: String = ""): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val mime = mimeType.lowercase()
        return mime.startsWith("text/") || ext in setOf(
            "txt", "md", "markdown", "csv", "tsv", "json", "py", "js", "ts",
            "html", "htm", "css", "xml", "yaml", "yml", "sh", "log", "kt",
            "java", "sql", "ini", "toml", "env", "dxf", "svg"
        )
    }

    fun extractText(file: File, maxChars: Int = 24_000): String {
        if (!file.exists() || !file.isFile) return ""
        return extractText(file.name, file.readBytes(), mimeType = "", maxChars = maxChars)
    }

    fun extractText(fileName: String, bytes: ByteArray, mimeType: String = "", maxChars: Int = 24_000): String {
        if (bytes.isEmpty()) return ""
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val lowerMime = mimeType.lowercase()
        val raw = when {
            ext == "pdf" || lowerMime.contains("pdf") || startsWithPdfHeader(bytes) -> extractPdfText(bytes)
            ext in setOf("docx", "odt", "pptx", "xlsx") ||
                lowerMime.contains("wordprocessingml") ||
                lowerMime.contains("opendocument") -> extractOfficeZipXmlText(bytes)
            ext == "rtf" -> extractRtfText(bytes)
            else -> bytes.toString(Charsets.UTF_8)
        }
        return raw.trim().take(maxChars)
    }

    private fun startsWithPdfHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 5) return false
        val head = String(bytes, 0, minOf(bytes.size, 16), Charsets.ISO_8859_1)
        return head.contains("%PDF-")
    }

    fun extractPdfText(bytes: ByteArray): String {
        val latin = String(bytes, Charsets.ISO_8859_1)
        val out = StringBuilder()

        // 1. Parse all PDF streams (decompressing FlateDecode streams via zlib Inflater)
        var searchPos = 0
        while (searchPos < latin.length) {
            val streamIdx = latin.indexOf("stream", searchPos)
            if (streamIdx < 0) break
            // Confirm it's the PDF "stream" keyword rather than inside an identifier
            val beforeOk = streamIdx == 0 || !latin[streamIdx - 1].isLetterOrDigit()
            val afterPos = streamIdx + 6
            val afterOk = afterPos < latin.length && (latin[afterPos] == '\r' || latin[afterPos] == '\n')
            if (!beforeOk || !afterOk) {
                searchPos = streamIdx + 6
                continue
            }

            var dataStart = afterPos
            if (dataStart < latin.length && latin[dataStart] == '\r') dataStart++
            if (dataStart < latin.length && latin[dataStart] == '\n') dataStart++

            val endIdx = latin.indexOf("endstream", dataStart)
            if (endIdx < 0) break

            val rawSliceLen = (endIdx - dataStart).coerceAtLeast(0)
            if (rawSliceLen in 4..4_000_000) {
                val slice = bytes.copyOfRange(dataStart, endIdx)
                val headerWindow = latin.substring((streamIdx - 280).coerceAtLeast(0), streamIdx)
                val looksFlate = headerWindow.contains("/FlateDecode", ignoreCase = true) ||
                    headerWindow.contains("/Fl", ignoreCase = false)
                val decodedStream = if (looksFlate) {
                    decompressZlibOrDeflate(slice) ?: slice
                } else {
                    // Some PDFs omit explicit Flate header check in short windows; try inflate if header looks zlib
                    if (slice.size >= 2 && (slice[0].toInt() and 0xFF) == 0x78) {
                        decompressZlibOrDeflate(slice) ?: slice
                    } else slice
                }

                val streamContent = String(decodedStream, Charsets.ISO_8859_1)
                val extractedFromStream = extractPdfOperatorsText(streamContent)
                if (extractedFromStream.isNotBlank()) {
                    if (out.isNotEmpty()) out.append('\n')
                    out.append(extractedFromStream)
                }
            }
            searchPos = endIdx + 9
        }

        // 2. Fallback: extract any uncompressed BT...ET or literal text operators outside streams
        if (out.length < 30) {
            val direct = extractPdfOperatorsText(latin)
            if (direct.length > out.length) {
                out.clear()
                out.append(direct)
            }
        }

        // 3. Last-resort printable text scan (if PDF uses unusual object encoding)
        if (out.length < 30) {
            val printable = Regex("""[A-Za-z0-9@._,+\-:/() ]{6,}""")
                .findAll(latin)
                .map { it.value.trim() }
                .filter { line ->
                    !line.startsWith("obj") && !line.startsWith("endobj") &&
                        !line.contains("/Type/") && !line.contains("/Filter") &&
                        !line.contains("xref") && !line.contains("trailer")
                }
                .take(300)
                .joinToString("\n")
            if (printable.length > out.length) {
                out.clear()
                out.append(printable)
            }
        }

        return normalizeExtractedWhitespace(out.toString())
    }

    private fun decompressZlibOrDeflate(data: ByteArray): ByteArray? {
        for (nowrap in listOf(false, true)) {
            val result = runCatching {
                val inflater = Inflater(nowrap)
                inflater.setInput(data)
                val bos = ByteArrayOutputStream(minOf(data.size * 3, 262_144))
                val buf = ByteArray(8192)
                var total = 0
                while (!inflater.finished() && total < 2_500_000) {
                    val count = inflater.inflate(buf)
                    if (count == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) break
                        break
                    }
                    bos.write(buf, 0, count)
                    total += count
                }
                inflater.end()
                bos.toByteArray().takeIf { it.isNotEmpty() }
            }.getOrNull()
            if (result != null) return result
        }
        return null
    }

    private fun extractPdfOperatorsText(content: String): String {
        if (!content.contains("Tj") && !content.contains("TJ") && !content.contains("BT")) {
            return ""
        }
        val sb = StringBuilder()
        var i = 0
        val n = content.length
        while (i < n) {
            when (content[i]) {
                '(' -> {
                    val (lit, nextIdx) = readPdfLiteralString(content, i)
                    val after = skipWs(content, nextIdx)
                    val isTextOp = content.startsWith("Tj", after) ||
                        content.startsWith("'", after) ||
                        content.startsWith("\"", after) ||
                        isInsideTextArray(content, nextIdx)
                    if (isTextOp && lit.isNotBlank()) {
                        sb.append(lit)
                    }
                    i = nextIdx
                }
                '[' -> {
                    val endBracket = findMatchingBracket(content, i)
                    if (endBracket > i) {
                        val after = skipWs(content, endBracket + 1)
                        if (content.startsWith("TJ", after)) {
                            val arrContent = content.substring(i + 1, endBracket)
                            val chunk = parseTjArray(arrContent)
                            if (chunk.isNotEmpty()) sb.append(chunk)
                            i = after + 2
                            continue
                        }
                    }
                    i++
                }
                '<' -> {
                    if (i + 1 < n && content[i + 1] != '<') {
                        val closeHex = content.indexOf('>', i + 1)
                        if (closeHex > i && closeHex - i <= 4096) {
                            val after = skipWs(content, closeHex + 1)
                            if (content.startsWith("Tj", after) || isInsideTextArray(content, closeHex + 1)) {
                                val decoded = decodePdfHexString(content.substring(i + 1, closeHex))
                                if (decoded.isNotBlank()) sb.append(decoded)
                                i = closeHex + 1
                                continue
                            }
                        }
                    }
                    i++
                }
                'T' -> {
                    if (i + 1 < n && content[i + 1] in listOf('d', 'D', '*', 'L') &&
                        (i == 0 || content[i - 1].isWhitespace() || content[i - 1] == ']' || content[i - 1] == ')')
                    ) {
                        if (sb.isNotEmpty() && sb.last() != '\n' && sb.last() != ' ') {
                            sb.append(if (content[i + 1] == '*' || content[i + 1] == 'D' || content[i + 1] == 'd') '\n' else ' ')
                        }
                    }
                    i++
                }
                'E' -> {
                    if (content.startsWith("ET", i) && sb.isNotEmpty() && sb.last() != '\n') {
                        sb.append('\n')
                    }
                    i++
                }
                else -> i++
            }
        }
        return sb.toString()
    }

    private fun readPdfLiteralString(s: String, start: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var depth = 1
        var i = start + 1
        while (i < s.length && depth > 0) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val next = s[i + 1]
                when (next) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\n'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { i += 2 }
                    'f' -> { sb.append(' '); i += 2 }
                    '(', ')', '\\' -> { sb.append(next); i += 2 }
                    '\r', '\n' -> {
                        i += 2
                        if (next == '\r' && i < s.length && s[i] == '\n') i++
                    }
                    in '0'..'7' -> {
                        var oct = ""
                        var k = i + 1
                        while (k < s.length && oct.length < 3 && s[k] in '0'..'7') {
                            oct += s[k]
                            k++
                        }
                        val code = oct.toIntOrNull(8) ?: 32
                        if (code in 32..255 || code == 9 || code == 10) sb.append(code.toChar())
                        i = k
                    }
                    else -> { sb.append(next); i += 2 }
                }
                continue
            }
            when (c) {
                '(' -> { depth++; sb.append(c) }
                ')' -> {
                    depth--
                    if (depth > 0) sb.append(c)
                }
                else -> if (c.code >= 32 || c == '\n' || c == '\t') sb.append(c)
            }
            i++
        }
        return sb.toString() to i
    }

    private fun parseTjArray(arr: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < arr.length) {
            when (arr[i]) {
                '(' -> {
                    val (lit, nextIdx) = readPdfLiteralString(arr, i)
                    sb.append(lit)
                    i = nextIdx
                }
                '<' -> {
                    val close = arr.indexOf('>', i + 1)
                    if (close > i) {
                        sb.append(decodePdfHexString(arr.substring(i + 1, close)))
                        i = close + 1
                    } else i++
                }
                '-', in '0'..'9' -> {
                    var j = i + 1
                    while (j < arr.length && (arr[j].isDigit() || arr[j] == '.')) j++
                    val num = arr.substring(i, j).toDoubleOrNull() ?: 0.0
                    // Large negative kerning offset in PDF TJ arrays signifies a word space
                    if (num <= -120.0 && sb.isNotEmpty() && sb.last() != ' ' && sb.last() != '\n') {
                        sb.append(' ')
                    }
                    i = j
                }
                else -> i++
            }
        }
        return sb.toString()
    }

    private fun decodePdfHexString(hexRaw: String): String {
        val hex = hexRaw.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        if (hex.length < 2) return ""
        val padded = if (hex.length % 2 == 1) "${hex}0" else hex
        val rawBytes = ByteArray(padded.length / 2) { idx ->
            padded.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
        }
        if (rawBytes.size >= 2 && (rawBytes[0].toInt() and 0xFF) == 0xFE && (rawBytes[1].toInt() and 0xFF) == 0xFF) {
            return String(rawBytes, 2, rawBytes.size - 2, Charsets.UTF_16BE)
        }
        // Check if UTF-16BE without BOM (alternating null high bytes for ASCII range)
        if (rawBytes.size >= 4 && rawBytes.size % 2 == 0 && rawBytes[0].toInt() == 0 && rawBytes[1].toInt() in 32..126) {
            return String(rawBytes, Charsets.UTF_16BE)
        }
        val ascii = String(rawBytes, Charsets.ISO_8859_1)
        val printableRatio = ascii.count { it.code in 32..126 || it == '\n' || it == '\t' }.toFloat() / ascii.length.coerceAtLeast(1)
        return if (printableRatio >= 0.75f) ascii else ""
    }

    private fun findMatchingBracket(s: String, start: Int): Int {
        var inLiteral = false
        var esc = false
        for (i in (start + 1) until s.length) {
            val c = s[i]
            if (esc) { esc = false; continue }
            if (c == '\\' && inLiteral) { esc = true; continue }
            if (c == '(') inLiteral = true
            else if (c == ')') inLiteral = false
            else if (c == ']' && !inLiteral) return i
        }
        return -1
    }

    private fun isInsideTextArray(s: String, pos: Int): Boolean {
        val ahead = s.substring(pos, minOf(s.length, pos + 160))
        val close = ahead.indexOf(']')
        return close >= 0 && ahead.substring(close + 1).trimStart().startsWith("TJ")
    }

    private fun skipWs(s: String, idx: Int): Int {
        var i = idx
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }

    private fun normalizeExtractedWhitespace(text: String): String {
        return text
            .replace("\r\n", "\n")
            .replace(Regex("[ \\t\\x0B\\f]+"), " ")
            .replace(Regex(" *\n *"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun extractOfficeZipXmlText(bytes: ByteArray): String {
        val sb = StringBuilder()
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val n = entry.name.lowercase()
                    if (!entry.isDirectory && (
                            n == "word/document.xml" ||
                                n == "content.xml" ||
                                n == "xl/sharedstrings.xml" ||
                                (n.startsWith("ppt/slides/slide") && n.endsWith(".xml"))
                            )
                    ) {
                        val xml = zis.readBytes().toString(Charsets.UTF_8)
                        val text = xml
                            .replace(Regex("</(w:p|text:p|a:p|p|tr|t)>", RegexOption.IGNORE_CASE), "\n")
                            .replace(Regex("<[^>]+>"), " ")
                            .replace("&amp;", "&")
                            .replace("&lt;", "<")
                            .replace("&gt;", ">")
                            .replace("&quot;", "\"")
                            .replace("&apos;", "'")
                        if (text.isNotBlank()) {
                            sb.append(normalizeExtractedWhitespace(text)).append('\n')
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        return sb.toString().trim()
    }

    private fun extractRtfText(bytes: ByteArray): String {
        val raw = String(bytes, Charsets.UTF_8)
        return raw
            .replace(Regex("""\\par\b"""), "\n")
            .replace(Regex("""\\[a-zA-Z]+-?\d*\s?"""), "")
            .replace("{", "")
            .replace("}", "")
            .let { normalizeExtractedWhitespace(it) }
    }
}
