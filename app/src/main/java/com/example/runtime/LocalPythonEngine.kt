package com.example.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

data class ScriptExecutionResult(
    val success: Boolean,
    val stdout: String,
    val stderr: String,
    val engineLabel: String,
    val durationMs: Long
)

/**
 * Executes Python scripts locally inside the sandboxed VirtualWorkspace.
 * Checks if a native python3 binary is present in PATH/Termux/PRoot first;
 * otherwise runs a genuine Kotlin-implemented Python 3 subset interpreter (PyLite)
 * with variables, control flow, functions, file I/O, math, json, and datetime.
 */
class LocalPythonEngine(private val workspace: VirtualWorkspaceManager) {

    fun findNativePythonBinary(): String? {
        val candidates = listOf(
            "/data/data/com.termux/files/usr/bin/python3",
            "/system/bin/python3",
            "/vendor/bin/python3",
            File(workspace.rootDir, "usr/bin/python3").absolutePath
        )
        return candidates.firstOrNull { File(it).let { f -> f.exists() && f.canExecute() } }
    }

    fun executeScript(code: String, workingDir: File = workspace.workspaceDir): ScriptExecutionResult {
        val start = System.currentTimeMillis()
        val nativeBin = findNativePythonBinary()
        if (nativeBin != null) {
            return runCatching {
                val tmpScript = File(workspace.tmpDir, "run_${System.nanoTime()}.py")
                tmpScript.writeText(code)
                val process = ProcessBuilder(nativeBin, tmpScript.absolutePath)
                    .directory(workingDir)
                    .apply {
                        environment()["HOME"] = workspace.homeDir.absolutePath
                        environment()["TMPDIR"] = workspace.tmpDir.absolutePath
                    }
                    .start()
                val out = process.inputStream.bufferedReader().readText()
                val err = process.errorStream.bufferedReader().readText()
                val exit = process.waitFor()
                tmpScript.delete()
                ScriptExecutionResult(
                    success = exit == 0,
                    stdout = out,
                    stderr = err,
                    engineLabel = "Native Python3 ($nativeBin)",
                    durationMs = System.currentTimeMillis() - start
                )
            }.getOrElse {
                runPyLite(code, workingDir, start)
            }
        }
        return runPyLite(code, workingDir, start)
    }

    private fun runPyLite(code: String, workingDir: File, startMs: Long): ScriptExecutionResult {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val interpreter = PyLiteInterpreter(workspace, workingDir, stdout)
        return try {
            interpreter.execute(code)
            ScriptExecutionResult(
                success = true,
                stdout = stdout.toString().trimEnd(),
                stderr = stderr.toString(),
                engineLabel = "PyLite On-Device Python 3 Engine",
                durationMs = System.currentTimeMillis() - startMs
            )
        } catch (e: Exception) {
            ScriptExecutionResult(
                success = false,
                stdout = stdout.toString().trimEnd(),
                stderr = "PyLiteError: ${e.message}",
                engineLabel = "PyLite On-Device Python 3 Engine",
                durationMs = System.currentTimeMillis() - startMs
            )
        }
    }
}

private class ReturnSignal(val value: Any?) : RuntimeException()

private class PyLiteInterpreter(
    private val workspace: VirtualWorkspaceManager,
    private val workingDir: File,
    private val stdout: StringBuilder
) {
    private val globals = mutableMapOf<String, Any?>(
        "True" to true,
        "False" to false,
        "None" to null
    )
    private val functions = mutableMapOf<String, PyFunction>()
    private val importedModules = mutableSetOf<String>()

    data class PyFunction(val params: List<String>, val body: List<LineNode>)
    data class LineNode(val indent: Int, val text: String, val lineNumber: Int)

    fun execute(source: String) {
        val lines = preprocessLines(source)
        executeBlock(lines, globals)
    }

    private fun preprocessLines(source: String): List<LineNode> {
        val raw = source.replace("\r\n", "\n").split("\n")
        val result = mutableListOf<LineNode>()
        var i = 0
        while (i < raw.size) {
            val line = raw[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                i++
                continue
            }
            val indent = line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            var combined = trimmed
            // Join multi-line brackets/braces/parens
            while (braceDepth(combined) > 0 && i + 1 < raw.size) {
                i++
                combined += " " + raw[i].trim()
            }
            result.add(LineNode(indent, combined, i + 1))
            i++
        }
        return result
    }

    private fun braceDepth(s: String): Int {
        var depth = 0
        var inStr: Char? = null
        var esc = false
        for (c in s) {
            if (esc) { esc = false; continue }
            if (c == '\\') { esc = true; continue }
            if (inStr != null) {
                if (c == inStr) inStr = null
                continue
            }
            if (c == '"' || c == '\'') inStr = c
            else if (c == '(' || c == '[' || c == '{') depth++
            else if (c == ')' || c == ']' || c == '}') depth--
        }
        return depth
    }

    private fun executeBlock(nodes: List<LineNode>, scope: MutableMap<String, Any?>) {
        var idx = 0
        while (idx < nodes.size) {
            val node = nodes[idx]
            val stmt = node.text
            when {
                stmt.startsWith("import ") -> {
                    stmt.removePrefix("import ").split(",").forEach {
                        importedModules.add(it.trim().substringBefore(" as "))
                    }
                    idx++
                }
                stmt.startsWith("from ") && stmt.contains(" import ") -> {
                    val mod = stmt.substringAfter("from ").substringBefore(" import ").trim()
                    importedModules.add(mod)
                    idx++
                }
                stmt.startsWith("def ") && stmt.endsWith(":") -> {
                    val sig = stmt.removePrefix("def ").removeSuffix(":").trim()
                    val name = sig.substringBefore("(").trim()
                    val paramsStr = sig.substringAfter("(").substringBeforeLast(")")
                    val params = if (paramsStr.isBlank()) emptyList() else paramsStr.split(",").map { it.trim() }
                    val (body, nextIdx) = collectIndentedBlock(nodes, idx + 1, node.indent)
                    functions[name] = PyFunction(params, body)
                    idx = nextIdx
                }
                stmt.startsWith("if ") && stmt.endsWith(":") -> {
                    var currentIdx = idx
                    var executedBranch = false
                    while (currentIdx < nodes.size) {
                        val cur = nodes[currentIdx]
                        if (cur.indent != node.indent) break
                        val condText = when {
                            cur.text.startsWith("if ") && cur.text.endsWith(":") -> cur.text.removePrefix("if ").removeSuffix(":")
                            cur.text.startsWith("elif ") && cur.text.endsWith(":") -> cur.text.removePrefix("elif ").removeSuffix(":")
                            cur.text == "else:" -> null
                            else -> break
                        }
                        if (cur != node && cur.text.startsWith("if ")) break
                        val (branchBody, afterBranch) = collectIndentedBlock(nodes, currentIdx + 1, node.indent)
                        if (!executedBranch && (condText == null || isTruthy(evalExpr(condText, scope)))) {
                            executeBlock(branchBody, scope)
                            executedBranch = true
                        }
                        currentIdx = afterBranch
                        if (condText == null) break
                    }
                    idx = currentIdx
                }
                stmt.startsWith("for ") && stmt.contains(" in ") && stmt.endsWith(":") -> {
                    val header = stmt.removePrefix("for ").removeSuffix(":")
                    val varName = header.substringBefore(" in ").trim()
                    val iterExpr = header.substringAfter(" in ").trim()
                    val iterable = toIterable(evalExpr(iterExpr, scope))
                    val (body, nextIdx) = collectIndentedBlock(nodes, idx + 1, node.indent)
                    for (item in iterable) {
                        scope[varName] = item
                        executeBlock(body, scope)
                    }
                    idx = nextIdx
                }
                stmt.startsWith("while ") && stmt.endsWith(":") -> {
                    val condExpr = stmt.removePrefix("while ").removeSuffix(":")
                    val (body, nextIdx) = collectIndentedBlock(nodes, idx + 1, node.indent)
                    var guard = 0
                    while (isTruthy(evalExpr(condExpr, scope)) && guard < 10_000) {
                        executeBlock(body, scope)
                        guard++
                    }
                    idx = nextIdx
                }
                stmt.startsWith("try:") -> {
                    val (tryBody, afterTry) = collectIndentedBlock(nodes, idx + 1, node.indent)
                    var nextIdx = afterTry
                    var exceptBody: List<LineNode>? = null
                    if (afterTry < nodes.size && nodes[afterTry].indent == node.indent && nodes[afterTry].text.startsWith("except")) {
                        val collected = collectIndentedBlock(nodes, afterTry + 1, node.indent)
                        exceptBody = collected.first
                        nextIdx = collected.second
                    }
                    try {
                        executeBlock(tryBody, scope)
                    } catch (e: ReturnSignal) {
                        throw e
                    } catch (_: Exception) {
                        if (exceptBody != null) executeBlock(exceptBody, scope)
                    }
                    idx = nextIdx
                }
                stmt.startsWith("return") -> {
                    val expr = stmt.removePrefix("return").trim()
                    val value = if (expr.isEmpty()) null else evalExpr(expr, scope)
                    throw ReturnSignal(value)
                }
                stmt == "pass" -> idx++
                else -> {
                    executeSimpleStatement(stmt, scope)
                    idx++
                }
            }
        }
    }

    private fun collectIndentedBlock(nodes: List<LineNode>, startIdx: Int, parentIndent: Int): Pair<List<LineNode>, Int> {
        val block = mutableListOf<LineNode>()
        var i = startIdx
        while (i < nodes.size && nodes[i].indent > parentIndent) {
            block.add(nodes[i])
            i++
        }
        return block to i
    }

    private fun executeSimpleStatement(stmt: String, scope: MutableMap<String, Any?>) {
        val eqIdx = findTopLevelAssign(stmt)
        if (eqIdx > 0) {
            val op = if (stmt[eqIdx - 1] in listOf('+', '-', '*', '/')) stmt.substring(eqIdx - 1, eqIdx + 1) else "="
            val lhs = stmt.substring(0, if (op.length == 2) eqIdx - 1 else eqIdx).trim()
            val rhs = stmt.substring(eqIdx + 1).trim()
            val rhsVal = evalExpr(rhs, scope)
            if (op == "=") {
                if (lhs.contains("[") && lhs.endsWith("]")) {
                    val targetName = lhs.substringBefore("[").trim()
                    val keyExpr = lhs.substringAfter("[").substringBeforeLast("]")
                    val keyVal = evalExpr(keyExpr, scope)
                    val targetObj = scope[targetName] ?: globals[targetName]
                    if (targetObj is MutableMap<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        (targetObj as MutableMap<Any?, Any?>)[keyVal] = rhsVal
                    } else if (targetObj is MutableList<*> && keyVal is Number) {
                        @Suppress("UNCHECKED_CAST")
                        (targetObj as MutableList<Any?>)[keyVal.toInt()] = rhsVal
                    }
                } else {
                    scope[lhs] = rhsVal
                }
            } else {
                val prev = scope[lhs] ?: globals[lhs]
                scope[lhs] = applyBinaryOp(op.first().toString(), prev, rhsVal)
            }
        } else {
            evalExpr(stmt, scope)
        }
    }

    private fun findTopLevelAssign(s: String): Int {
        var depth = 0
        var inStr: Char? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (inStr != null) {
                if (c == '\\') { i += 2; continue }
                if (c == inStr) inStr = null
            } else {
                when (c) {
                    '"', '\'' -> inStr = c
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth--
                    '=' -> if (depth == 0) {
                        val next = s.getOrNull(i + 1)
                        val prev = s.getOrNull(i - 1)
                        if (next != '=' && prev !in listOf('=', '!', '<', '>')) return i
                    }
                }
            }
            i++
        }
        return -1
    }

    private fun evalExpr(raw: String, scope: MutableMap<String, Any?>): Any? {
        val expr = raw.trim()
        if (expr.isEmpty()) return null
        if (expr == "True") return true
        if (expr == "False") return false
        if (expr == "None") return null

        // Parenthesized expression
        if (expr.startsWith("(") && expr.endsWith(")") && matchingClose(expr, 0) == expr.length - 1) {
            return evalExpr(expr.substring(1, expr.length - 1), scope)
        }

        // Boolean or/and
        splitTopLevel(expr, " or ")?.let { (l, r) -> return isTruthy(evalExpr(l, scope)) || isTruthy(evalExpr(r, scope)) }
        splitTopLevel(expr, " and ")?.let { (l, r) -> return isTruthy(evalExpr(l, scope)) && isTruthy(evalExpr(r, scope)) }

        // Comparison ops
        for (op in listOf("==", "!=", ">=", "<=", ">", "<")) {
            splitTopLevel(expr, op)?.let { (l, r) ->
                return compareValues(evalExpr(l, scope), evalExpr(r, scope), op)
            }
        }

        // Addition / Subtraction (right-to-left for left-associativity)
        splitTopLevelChar(expr, listOf('+', '-'))?.let { (l, op, r) ->
            if (l.isNotEmpty()) return applyBinaryOp(op.toString(), evalExpr(l, scope), evalExpr(r, scope))
        }

        // Mul / Div / Mod
        splitTopLevelChar(expr, listOf('*', '/', '%'))?.let { (l, op, r) ->
            return applyBinaryOp(op.toString(), evalExpr(l, scope), evalExpr(r, scope))
        }

        // String literal or f-string
        if ((expr.startsWith("f\"") && expr.endsWith("\"")) || (expr.startsWith("f'") && expr.endsWith("'"))) {
            val inner = expr.substring(2, expr.length - 1)
            return interpolateFString(inner, scope)
        }
        if ((expr.startsWith("\"") && expr.endsWith("\"")) || (expr.startsWith("'") && expr.endsWith("'"))) {
            return expr.substring(1, expr.length - 1)
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
        }

        // List literal
        if (expr.startsWith("[") && expr.endsWith("]") && matchingClose(expr, 0) == expr.length - 1) {
            val inner = expr.substring(1, expr.length - 1).trim()
            if (inner.isEmpty()) return mutableListOf<Any?>()
            return splitTopLevelArgs(inner).map { evalExpr(it, scope) }.toMutableList()
        }

        // Dict literal
        if (expr.startsWith("{") && expr.endsWith("}") && matchingClose(expr, 0) == expr.length - 1) {
            val inner = expr.substring(1, expr.length - 1).trim()
            val map = linkedMapOf<Any?, Any?>()
            if (inner.isNotEmpty()) {
                for (pair in splitTopLevelArgs(inner)) {
                    val colon = findTopLevelChar(pair, ':')
                    if (colon > 0) {
                        val k = evalExpr(pair.substring(0, colon), scope)
                        val v = evalExpr(pair.substring(colon + 1), scope)
                        map[k] = v
                    }
                }
            }
            return map
        }

        // Numeric literal
        expr.toLongOrNull()?.let { return it }
        expr.toDoubleOrNull()?.let { return it }

        // Method or function call
        val parenIdx = findTopLevelChar(expr, '(')
        if (parenIdx > 0 && expr.endsWith(")")) {
            val target = expr.substring(0, parenIdx).trim()
            val argsStr = expr.substring(parenIdx + 1, expr.length - 1)
            val args = if (argsStr.isBlank()) emptyList() else splitTopLevelArgs(argsStr)
            return invokeCall(target, args, scope)
        }

        // Indexing obj[idx]
        val bracketIdx = findTopLevelChar(expr, '[')
        if (bracketIdx > 0 && expr.endsWith("]")) {
            val baseObj = evalExpr(expr.substring(0, bracketIdx), scope)
            val key = evalExpr(expr.substring(bracketIdx + 1, expr.length - 1), scope)
            return when {
                baseObj is List<*> && key is Number -> baseObj.getOrNull(key.toInt())
                baseObj is Map<*, *> -> baseObj[key]
                baseObj is String && key is Number -> baseObj.getOrNull(key.toInt())?.toString()
                else -> null
            }
        }

        // Variable lookup
        if (scope.containsKey(expr)) return scope[expr]
        if (globals.containsKey(expr)) return globals[expr]
        if (expr == "math.pi") return PI
        throw IllegalStateException("Undefined symbol: $expr")
    }

    private fun invokeCall(target: String, rawArgs: List<String>, scope: MutableMap<String, Any?>): Any? {
        // Parse positional & keyword args
        val positional = mutableListOf<Any?>()
        val kwargs = mutableMapOf<String, Any?>()
        for (a in rawArgs) {
            val eq = findTopLevelAssign(a)
            if (eq > 0) {
                kwargs[a.substring(0, eq).trim()] = evalExpr(a.substring(eq + 1), scope)
            } else {
                positional.add(evalExpr(a, scope))
            }
        }

        // Dotted call
        val dot = findTopLevelCharFromEnd(target, '.')
        if (dot > 0) {
            val receiverExpr = target.substring(0, dot).trim()
            val method = target.substring(dot + 1).trim()
            return when (receiverExpr) {
                "math" -> when (method) {
                    "sqrt" -> sqrt((positional.first() as Number).toDouble())
                    "sin" -> sin((positional.first() as Number).toDouble())
                    "cos" -> cos((positional.first() as Number).toDouble())
                    "floor" -> floor((positional.first() as Number).toDouble()).toLong()
                    "ceil" -> ceil((positional.first() as Number).toDouble()).toLong()
                    "pow" -> (positional[0] as Number).toDouble().pow((positional[1] as Number).toDouble())
                    "log" -> ln((positional.first() as Number).toDouble())
                    else -> throw IllegalArgumentException("Unsupported math.$method")
                }
                "json" -> when (method) {
                    "dumps" -> toJsonValue(positional.firstOrNull()).let {
                        val indent = (kwargs["indent"] as? Number)?.toInt() ?: 0
                        when (it) {
                            is JSONObject -> if (indent > 0) it.toString(indent) else it.toString()
                            is JSONArray -> if (indent > 0) it.toString(indent) else it.toString()
                            else -> it.toString()
                        }
                    }
                    "loads" -> parseJson(positional.first().toString())
                    else -> null
                }
                "datetime" -> when (method) {
                    "now" -> SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                    else -> Date().toString()
                }
                "os" -> when (method) {
                    "getcwd" -> workingDir.absolutePath
                    "listdir" -> {
                        val dir = if (positional.isEmpty()) workingDir else workspace.resolveSafe(positional[0].toString(), workingDir)
                        (dir.list() ?: emptyArray()).toMutableList()
                    }
                    else -> null
                }
                "random" -> when (method) {
                    "randint" -> Random.nextInt((positional[0] as Number).toInt(), (positional[1] as Number).toInt() + 1).toLong()
                    "random" -> Random.nextDouble()
                    else -> null
                }
                else -> {
                    val recv = evalExpr(receiverExpr, scope)
                    when (recv) {
                        is MutableList<*> -> when (method) {
                            "append" -> {
                                @Suppress("UNCHECKED_CAST")
                                (recv as MutableList<Any?>).add(positional.firstOrNull())
                                null
                            }
                            else -> null
                        }
                        is String -> when (method) {
                            "upper" -> recv.uppercase()
                            "lower" -> recv.lowercase()
                            "strip" -> recv.trim()
                            "split" -> if (positional.isEmpty()) recv.trim().split(Regex("\\s+")).toMutableList() else recv.split(positional[0].toString()).toMutableList()
                            "join" -> (positional.firstOrNull() as? List<*>)?.joinToString(recv) { pyStr(it) } ?: ""
                            "replace" -> recv.replace(positional[0].toString(), positional[1].toString())
                            else -> null
                        }
                        is Map<*, *> -> when (method) {
                            "get" -> recv[positional[0]] ?: positional.getOrNull(1)
                            "keys" -> recv.keys.toMutableList()
                            "values" -> recv.values.toMutableList()
                            else -> null
                        }
                        else -> null
                    }
                }
            }
        }

        return when (target) {
            "print" -> {
                val sep = kwargs["sep"]?.toString() ?: " "
                val end = kwargs["end"]?.toString() ?: "\n"
                stdout.append(positional.joinToString(sep) { pyStr(it) }).append(end)
                null
            }
            "len" -> when (val x = positional.firstOrNull()) {
                is String -> x.length.toLong()
                is Collection<*> -> x.size.toLong()
                is Map<*, *> -> x.size.toLong()
                else -> 0L
            }
            "range" -> when (positional.size) {
                1 -> (0L until (positional[0] as Number).toLong()).toMutableList()
                2 -> ((positional[0] as Number).toLong() until (positional[1] as Number).toLong()).toMutableList()
                else -> {
                    val start = (positional[0] as Number).toLong()
                    val end = (positional[1] as Number).toLong()
                    val step = (positional[2] as Number).toLong().coerceAtLeast(1)
                    (start until end step step).toMutableList()
                }
            }
            "int" -> (positional.firstOrNull()?.toString()?.toDoubleOrNull()?.toLong() ?: 0L)
            "float" -> (positional.firstOrNull()?.toString()?.toDoubleOrNull() ?: 0.0)
            "str" -> pyStr(positional.firstOrNull())
            "round" -> {
                val v = (positional[0] as Number).toDouble()
                val digits = (positional.getOrNull(1) as? Number)?.toInt() ?: 0
                val factor = 10.0.pow(digits)
                if (digits == 0) v.roundToInt().toLong() else kotlin.math.round(v * factor) / factor
            }
            "abs" -> {
                val v = positional[0] as Number
                if (v is Long) abs(v) else abs(v.toDouble())
            }
            "sum" -> (positional[0] as List<*>).sumOf { (it as Number).toDouble() }
            "min" -> (positional[0] as List<*>).minOf { (it as Number).toDouble() }
            "max" -> (positional[0] as List<*>).maxOf { (it as Number).toDouble() }
            "sorted" -> (positional[0] as List<*>).sortedBy { pyStr(it) }.toMutableList()
            "open" -> {
                val rel = positional[0].toString()
                val mode = positional.getOrNull(1)?.toString() ?: "r"
                val file = workspace.resolveSafe(rel, workingDir)
                if (mode.startsWith("w")) {
                    file.parentFile?.mkdirs()
                    file.writeText("")
                }
                PyFileHandle(file, mode)
            }
            "write_file" -> {
                val rel = positional[0].toString()
                val text = positional[1].toString()
                workspace.writeTextFile(rel, text).getOrThrow().absolutePath
            }
            "read_file" -> workspace.readTextFile(positional[0].toString()).getOrThrow()
            else -> {
                val fn = functions[target] ?: throw IllegalArgumentException("Unknown function: $target")
                val localScope = HashMap(globals)
                fn.params.forEachIndexed { idx, p -> localScope[p] = positional.getOrNull(idx) }
                try {
                    executeBlock(fn.body, localScope)
                    null
                } catch (ret: ReturnSignal) {
                    ret.value
                }
            }
        }
    }

    private fun interpolateFString(template: String, scope: MutableMap<String, Any?>): String {
        val sb = StringBuilder()
        var i = 0
        while (i < template.length) {
            if (template[i] == '{') {
                val close = template.indexOf('}', i + 1)
                if (close > i) {
                    val expr = template.substring(i + 1, close)
                    sb.append(pyStr(evalExpr(expr, scope)))
                    i = close + 1
                    continue
                }
            }
            sb.append(template[i])
            i++
        }
        return sb.toString()
    }

    private fun toJsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply {
            value.forEach { (k, v) -> put(k.toString(), toJsonValue(v)) }
        }
        is List<*> -> JSONArray().apply {
            value.forEach { put(toJsonValue(it)) }
        }
        else -> value
    }

    private fun parseJson(text: String): Any? {
        val t = text.trim()
        return when {
            t.startsWith("{") -> jsonObjToMap(JSONObject(t))
            t.startsWith("[") -> jsonArrToList(JSONArray(t))
            else -> t
        }
    }

    private fun jsonObjToMap(obj: JSONObject): MutableMap<Any?, Any?> {
        val map = linkedMapOf<Any?, Any?>()
        obj.keys().forEach { key ->
            val v = obj.get(key)
            map[key] = when (v) {
                is JSONObject -> jsonObjToMap(v)
                is JSONArray -> jsonArrToList(v)
                JSONObject.NULL -> null
                is Int -> v.toLong()
                else -> v
            }
        }
        return map
    }

    private fun jsonArrToList(arr: JSONArray): MutableList<Any?> {
        return (0 until arr.length()).map { i ->
            when (val v = arr.get(i)) {
                is JSONObject -> jsonObjToMap(v)
                is JSONArray -> jsonArrToList(v)
                JSONObject.NULL -> null
                is Int -> v.toLong()
                else -> v
            }
        }.toMutableList()
    }

    private fun pyStr(v: Any?): String = when (v) {
        null -> "None"
        true -> "True"
        false -> "False"
        is List<*> -> v.joinToString(", ", "[", "]") { pyStr(it) }
        is Map<*, *> -> v.entries.joinToString(", ", "{", "}") { "${pyStr(it.key)}: ${pyStr(it.value)}" }
        else -> v.toString()
    }

    private fun isTruthy(v: Any?): Boolean = when (v) {
        null, false -> false
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty()
        is Collection<*> -> v.isNotEmpty()
        is Map<*, *> -> v.isNotEmpty()
        else -> true
    }

    private fun toIterable(v: Any?): List<Any?> = when (v) {
        is List<*> -> v
        is String -> v.map { it.toString() }
        is Map<*, *> -> v.keys.toList()
        else -> emptyList()
    }

    private fun compareValues(a: Any?, b: Any?, op: String): Boolean {
        val cmp = when {
            a is Number && b is Number -> a.toDouble().compareTo(b.toDouble())
            else -> pyStr(a).compareTo(pyStr(b))
        }
        return when (op) {
            "==" -> a == b || (a is Number && b is Number && a.toDouble() == b.toDouble())
            "!=" -> !(a == b || (a is Number && b is Number && a.toDouble() == b.toDouble()))
            ">" -> cmp > 0
            "<" -> cmp < 0
            ">=" -> cmp >= 0
            "<=" -> cmp <= 0
            else -> false
        }
    }

    private fun applyBinaryOp(op: String, a: Any?, b: Any?): Any? {
        if (op == "+" && (a is String || b is String)) return pyStr(a) + pyStr(b)
        if (a is Number && b is Number) {
            val useDouble = a is Double || b is Double || op == "/"
            return if (useDouble) {
                val da = a.toDouble(); val db = b.toDouble()
                when (op) {
                    "+" -> da + db
                    "-" -> da - db
                    "*" -> da * db
                    "/" -> da / db
                    "%" -> da % db
                    else -> 0.0
                }
            } else {
                val la = a.toLong(); val lb = b.toLong()
                when (op) {
                    "+" -> la + lb
                    "-" -> la - lb
                    "*" -> la * lb
                    "%" -> la % lb
                    else -> 0L
                }
            }
        }
        return null
    }

    private fun splitTopLevel(expr: String, token: String): Pair<String, String>? {
        var depth = 0
        var inStr: Char? = null
        var i = 0
        while (i <= expr.length - token.length) {
            val c = expr[i]
            if (inStr != null) {
                if (c == '\\') { i += 2; continue }
                if (c == inStr) inStr = null
            } else {
                when (c) {
                    '"', '\'' -> inStr = c
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth--
                    else -> if (depth == 0 && expr.startsWith(token, i)) {
                        return expr.substring(0, i) to expr.substring(i + token.length)
                    }
                }
            }
            i++
        }
        return null
    }

    private fun splitTopLevelChar(expr: String, ops: List<Char>): Triple<String, Char, String>? {
        var depth = 0
        var inStr: Char? = null
        for (i in expr.indices.reversed()) {
            val c = expr[i]
            if (inStr != null) {
                if (c == inStr && expr.getOrNull(i - 1) != '\\') inStr = null
            } else {
                when (c) {
                    '"', '\'' -> inStr = c
                    ')', ']', '}' -> depth++
                    '(', '[', '{' -> depth--
                    else -> if (depth == 0 && c in ops && i > 0) {
                        val prev = expr[i - 1]
                        if (prev !in listOf('e', 'E', '+', '-', '*', '/', '(')) {
                            return Triple(expr.substring(0, i), c, expr.substring(i + 1))
                        }
                    }
                }
            }
        }
        return null
    }

    private fun splitTopLevelArgs(s: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var inStr: Char? = null
        var start = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (inStr != null) {
                if (c == '\\') { i += 2; continue }
                if (c == inStr) inStr = null
            } else {
                when (c) {
                    '"', '\'' -> inStr = c
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth--
                    ',' -> if (depth == 0) {
                        result.add(s.substring(start, i).trim())
                        start = i + 1
                    }
                }
            }
            i++
        }
        if (start < s.length) result.add(s.substring(start).trim())
        return result
    }

    private fun findTopLevelChar(s: String, target: Char): Int {
        var depth = 0
        var inStr: Char? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (inStr != null) {
                if (c == '\\') { i += 2; continue }
                if (c == inStr) inStr = null
            } else {
                if (depth == 0 && c == target) return i
                when (c) {
                    '"', '\'' -> inStr = c
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth--
                }
            }
            i++
        }
        return -1
    }

    private fun findTopLevelCharFromEnd(s: String, target: Char): Int {
        var depth = 0
        var inStr: Char? = null
        for (i in s.indices.reversed()) {
            val c = s[i]
            if (inStr != null) {
                if (c == inStr) inStr = null
            } else {
                if (depth == 0 && c == target) return i
                when (c) {
                    '"', '\'' -> inStr = c
                    ')', ']', '}' -> depth++
                    '(', '[', '{' -> depth--
                }
            }
        }
        return -1
    }

    private fun matchingClose(s: String, openIdx: Int): Int {
        val open = s[openIdx]
        val close = when (open) { '(' -> ')'; '[' -> ']'; '{' -> '}'; else -> return -1 }
        var depth = 0
        var inStr: Char? = null
        var i = openIdx
        while (i < s.length) {
            val c = s[i]
            if (inStr != null) {
                if (c == '\\') { i += 2; continue }
                if (c == inStr) inStr = null
            } else {
                when (c) {
                    '"', '\'' -> inStr = c
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return -1
    }

    class PyFileHandle(val file: File, val mode: String) {
        fun read(): String = file.readText()
        fun write(text: String) {
            if (mode.contains("a")) file.appendText(text) else file.writeText(text)
        }
    }
}
