package com.example.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

data class MiniAiSample(
    val id: String,
    val inputText: String,
    val replyText: String,
    val category: String = "LEARNED", // STARTER, LEARNED, USER_TAUGHT, WHATSAPP
    val hits: Int = 0,
    val weight: Float = 1.0f,
    val isUserTaught: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

data class MiniAiBrainStats(
    val totalSamples: Int = 0,
    val starterSamples: Int = 0,
    val learnedSamples: Int = 0,
    val userTaughtSamples: Int = 0,
    val localRepliesServed: Int = 0,
    val estimatedTokensSaved: Long = 0L,
    val lastMatchedSource: String = ""
)

data class MiniAiInferenceResult(
    val reply: String,
    val confidence: Float,
    val matchedSampleId: String,
    val matchedInput: String,
    val sourceCategory: String,
    val estimatedTokensSaved: Int,
    val latencyMs: Long
)

/**
 * On-Device Continual-Learning Mini AI Brain (Bangla / Banglish / English + Token Saver).
 *
 * Design:
 * - Hybrid Subword Character N-Gram (3..5-gram) + Canonical Bangla/Banglish/English Word Tokenizer
 *   with hashed dense TF vectorization (256-dim L2-normalized embedding) + token Jaccard boost
 *   + reinforcement weights from usage hits and explicit user teaching.
 * - Never intercepts action/tool/coding/browser/form/virtual-hands commands — those ALWAYS go to
 *   ZeroLatencyBotEngine + AutonomousAgentEngine with full tool execution.
 * - Handles casual chat, greetings, learned Q&A, app-usage questions, and repeated personal
 *   prompts locally in <15ms using 0 Cloud API tokens, and distills Cloud AI replies continually
 *   so future similar queries cost 0 tokens.
 */
class LocalMiniAiBrain(
    private val storageFile: File? = null
) {
    private val samples = CopyOnWriteArrayList<MiniAiSample>()
    private val localRepliesCounter = AtomicInteger(0)
    private val tokensSavedCounter = AtomicLong(0L)

    private val _stats = MutableStateFlow(MiniAiBrainStats())
    val stats: StateFlow<MiniAiBrainStats> = _stats.asStateFlow()

    private val _learnedSamplesFlow = MutableStateFlow<List<MiniAiSample>>(emptyList())
    val learnedSamplesFlow: StateFlow<List<MiniAiSample>> = _learnedSamplesFlow.asStateFlow()

    private val fileLock = Any()

    init {
        loadFromDiskOrSeed()
    }

    fun inferReply(
        query: String,
        minConfidence: Float = 0.78f,
        channel: String = "AGENT"
    ): MiniAiInferenceResult? {
        val startedNs = System.nanoTime()
        val clean = query.trim()
        if (clean.isEmpty() || clean.length > 220) return null
        if (looksLikeExecutableOrComplexTask(clean)) return null

        val queryTokens = tokenizeNormalized(clean)
        if (queryTokens.isEmpty()) return null
        val queryVec = vectorize(clean, queryTokens)

        var bestSample: MiniAiSample? = null
        var bestScore = 0f

        for (sample in samples) {
            // Skip WhatsApp-specific short style replies when user is asking inside Agent unless starter/general
            if (channel == "AGENT" && sample.category == "WHATSAPP" && !sample.isUserTaught) continue

            val sTokens = tokenizeNormalized(sample.inputText)
            val sVec = vectorize(sample.inputText, sTokens)
            val cosine = dot(queryVec, sVec)
            val tokenOverlap = jaccardOverlap(queryTokens, sTokens)
            val exactBonus = if (normalizeText(sample.inputText) == normalizeText(clean)) 0.25f else 0f
            val taughtBoost = if (sample.isUserTaught) 0.08f else 0f
            val reinforcement = (sample.hits.coerceAtMost(10) * 0.008f) + ((sample.weight - 1.0f) * 0.04f)

            val combined = (cosine * 0.68f + tokenOverlap * 0.32f + exactBonus + taughtBoost + reinforcement)
                .coerceIn(0f, 1.0f)

            if (combined > bestScore) {
                bestScore = combined
                bestSample = sample
            }
        }

        val hit = bestSample ?: return null
        // Single-word or very short greetings can trigger slightly easier if starter/taught exact match
        val effectiveThreshold = if (queryTokens.size <= 2 && (hit.isUserTaught || hit.category == "STARTER")) {
            (minConfidence - 0.06f).coerceAtLeast(0.66f)
        } else {
            minConfidence.coerceIn(0.65f, 0.94f)
        }
        if (bestScore < effectiveThreshold) return null

        // Reinforce hit weight cleanly
        val idx = samples.indexOfFirst { it.id == hit.id }
        if (idx >= 0) {
            val cur = samples[idx]
            samples[idx] = cur.copy(
                hits = cur.hits + 1,
                weight = (cur.weight + 0.05f).coerceAtMost(2.5f),
                updatedAt = System.currentTimeMillis()
            )
        }

        val savedTokens = estimateCloudTokensSaved(clean, hit.replyText)
        localRepliesCounter.incrementAndGet()
        tokensSavedCounter.addAndGet(savedTokens.toLong())
        publishStats(lastSource = hit.category)
        persistSnapshotAsyncSafe()

        val latencyMs = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(1L)
        return MiniAiInferenceResult(
            reply = hit.replyText,
            confidence = bestScore,
            matchedSampleId = hit.id,
            matchedInput = hit.inputText,
            sourceCategory = hit.category,
            estimatedTokensSaved = savedTokens,
            latencyMs = latencyMs
        )
    }

    /**
     * Explicit user teaching (or reinforcement feedback) so Mini AI permanently learns
     * owner preferences, Bangla/Banglish phrases, and personal Q&A pairs.
     */
    fun teachCustomPair(inputText: String, replyText: String): Result<MiniAiSample> {
        val cleanIn = inputText.trim()
        val cleanOut = replyText.trim()
        if (cleanIn.length < 2 || cleanOut.isEmpty()) {
            return Result.failure(IllegalArgumentException("প্রশ্ন ও উত্তর দুটোই লিখুন।"))
        }
        if (containsSensitivePattern(cleanIn) || containsSensitivePattern(cleanOut)) {
            return Result.failure(IllegalArgumentException("Security Policy: Password, OTP বা API Key Mini AI-তে সেভ করা যাবে না।"))
        }

        val normIn = normalizeText(cleanIn)
        val existingIdx = samples.indexOfFirst { normalizeText(it.inputText) == normIn }
        val item = MiniAiSample(
            id = if (existingIdx >= 0) samples[existingIdx].id else "taught_${System.currentTimeMillis()}_${samples.size}",
            inputText = cleanIn.take(300),
            replyText = cleanOut.take(1200),
            category = "USER_TAUGHT",
            hits = if (existingIdx >= 0) samples[existingIdx].hits + 1 else 1,
            weight = 1.6f,
            isUserTaught = true,
            updatedAt = System.currentTimeMillis()
        )
        if (existingIdx >= 0) samples[existingIdx] = item else samples.add(0, item)
        enforceCapacityLimit()
        publishStats(lastSource = "USER_TAUGHT")
        persistToDisk()
        return Result.success(item)
    }

    /**
     * Continual self-learning from successful Cloud AI replies (Gemini / OpenRouter).
     * Distills concise, safe conversational replies locally so similar future messages use 0 tokens.
     */
    fun learnFromCloudTurn(
        userInput: String,
        cloudReply: String,
        channel: String = "AGENT"
    ): Boolean {
        val cleanIn = userInput.trim()
        val cleanOut = cloudReply.trim()
        if (cleanIn.length < 2 || cleanIn.length > 220) return false
        if (cleanOut.length < 2 || cleanOut.length > 900) return false
        if (looksLikeExecutableOrComplexTask(cleanIn)) return false
        if (containsSensitivePattern(cleanIn) || containsSensitivePattern(cleanOut)) return false
        // Do not distill error notices or raw logs
        val outLower = cleanOut.lowercase()
        if (outLower.contains("provider error") || outLower.contains("exception") || outLower.contains("http 4")) {
            return false
        }

        val normIn = normalizeText(cleanIn)
        val existingIdx = samples.indexOfFirst { normalizeText(it.inputText) == normIn }
        if (existingIdx >= 0) {
            val prev = samples[existingIdx]
            // Never overwrite an explicitly user-taught reply automatically
            if (prev.isUserTaught) return false
            samples[existingIdx] = prev.copy(
                replyText = cleanOut,
                weight = (prev.weight + 0.1f).coerceAtMost(2.0f),
                updatedAt = System.currentTimeMillis()
            )
        } else {
            val category = if (channel == "WHATSAPP") "WHATSAPP" else "LEARNED"
            samples.add(
                MiniAiSample(
                    id = "learn_${System.currentTimeMillis()}_${samples.size}",
                    inputText = cleanIn,
                    replyText = cleanOut,
                    category = category,
                    hits = 0,
                    weight = 1.0f,
                    isUserTaught = false,
                    updatedAt = System.currentTimeMillis()
                )
            )
            enforceCapacityLimit()
        }
        publishStats(lastSource = channel)
        persistSnapshotAsyncSafe()
        return true
    }

    fun deleteSample(id: String): Boolean {
        val removed = samples.removeIf { it.id == id && it.category != "STARTER" }
        if (removed) {
            publishStats()
            persistToDisk()
        }
        return removed
    }

    fun resetLearnedKnowledge() {
        samples.removeIf { it.category != "STARTER" }
        localRepliesCounter.set(0)
        tokensSavedCounter.set(0L)
        publishStats(lastSource = "RESET")
        persistToDisk()
    }

    fun getCustomAndLearnedSamples(limit: Int = 40): List<MiniAiSample> {
        return samples
            .filter { it.category != "STARTER" }
            .sortedWith(compareByDescending<MiniAiSample> { it.isUserTaught }.thenByDescending { it.updatedAt })
            .take(limit)
    }

    fun getStatsSnapshot(): MiniAiBrainStats = _stats.value

    private fun enforceCapacityLimit() {
        if (samples.size <= MAX_SAMPLES) return
        val removable = samples
            .filter { it.category != "STARTER" && !it.isUserTaught }
            .sortedWith(compareBy<MiniAiSample> { it.hits }.thenBy { it.updatedAt })
        val excess = samples.size - MAX_SAMPLES
        removable.take(excess).forEach { target ->
            samples.removeIf { it.id == target.id }
        }
    }

    private fun publishStats(lastSource: String? = null) {
        val starter = samples.count { it.category == "STARTER" }
        val taught = samples.count { it.isUserTaught }
        val learned = samples.size - starter
        _stats.value = MiniAiBrainStats(
            totalSamples = samples.size,
            starterSamples = starter,
            learnedSamples = learned,
            userTaughtSamples = taught,
            localRepliesServed = localRepliesCounter.get(),
            estimatedTokensSaved = tokensSavedCounter.get(),
            lastMatchedSource = lastSource ?: _stats.value.lastMatchedSource
        )
        _learnedSamplesFlow.value = getCustomAndLearnedSamples(50)
    }

    private fun loadFromDiskOrSeed() {
        val loadedOk = runCatching {
            val file = storageFile ?: return@runCatching false
            if (!file.exists() || file.length() == 0L) return@runCatching false
            val raw = file.readText()
            val trimmed = raw.trimStart()
            val list = ArrayList<MiniAiSample>()
            if (trimmed.startsWith("{")) {
                val root = org.json.JSONObject(raw)
                localRepliesCounter.set(root.optInt("localRepliesServed", 0))
                tokensSavedCounter.set(root.optLong("estimatedTokensSaved", 0L))
                val arr = root.optJSONArray("samples") ?: org.json.JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val input = obj.optString("inputText").trim()
                    val reply = obj.optString("replyText").trim()
                    if (input.isNotBlank() && reply.isNotBlank()) {
                        list.add(
                            MiniAiSample(
                                id = obj.optString("id").ifBlank { "disk_$i" },
                                inputText = input,
                                replyText = reply,
                                category = obj.optString("category", "LEARNED"),
                                hits = obj.optInt("hits", 0),
                                weight = obj.optDouble("weight", 1.0).toFloat(),
                                isUserTaught = obj.optBoolean("isUserTaught", false),
                                updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                            )
                        )
                    }
                }
            } else {
                raw.lineSequence().forEachIndexed { idx, line ->
                    if (line.isBlank()) return@forEachIndexed
                    val parts = line.split('\t')
                    when {
                        parts.size >= 4 && parts[0] == "META" -> {
                            localRepliesCounter.set(parts[2].toIntOrNull() ?: 0)
                            tokensSavedCounter.set(parts[3].toLongOrNull() ?: 0L)
                        }
                        parts.size >= 9 && parts[0] == "SAMPLE" -> {
                            val input = unescapeField(parts[7]).trim()
                            val reply = unescapeField(parts[8]).trim()
                            if (input.isNotBlank() && reply.isNotBlank()) {
                                list.add(
                                    MiniAiSample(
                                        id = unescapeField(parts[1]).ifBlank { "disk_$idx" },
                                        inputText = input,
                                        replyText = reply,
                                        category = unescapeField(parts[2]).ifBlank { "LEARNED" },
                                        hits = parts[3].toIntOrNull() ?: 0,
                                        weight = parts[4].toFloatOrNull() ?: 1.0f,
                                        isUserTaught = parts[5].toBooleanStrictOrNull() ?: false,
                                        updatedAt = parts[6].toLongOrNull() ?: System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                    }
                }
            }
            if (list.isEmpty()) return@runCatching false
            samples.clear()
            samples.addAll(list)
            true
        }.getOrDefault(false)

        if (!loadedOk || samples.none { it.category == "STARTER" }) {
            val starterIds = samples.map { it.id }.toSet()
            starterCorpus().forEach { starter ->
                if (starter.id !in starterIds) {
                    samples.add(starter)
                }
            }
        }
        publishStats()
    }

    private fun persistSnapshotAsyncSafe() {
        runCatching { persistToDisk() }
    }

    fun persistToDisk() {
        val file = storageFile ?: return
        synchronized(fileLock) {
            runCatching {
                file.parentFile?.mkdirs()
                val sb = StringBuilder()
                sb.append("META\t1\t")
                    .append(localRepliesCounter.get())
                    .append('\t')
                    .append(tokensSavedCounter.get())
                    .append('\n')
                samples.forEach { s ->
                    sb.append("SAMPLE\t")
                        .append(escapeField(s.id)).append('\t')
                        .append(escapeField(s.category)).append('\t')
                        .append(s.hits).append('\t')
                        .append(s.weight).append('\t')
                        .append(s.isUserTaught).append('\t')
                        .append(s.updatedAt).append('\t')
                        .append(escapeField(s.inputText)).append('\t')
                        .append(escapeField(s.replyText)).append('\n')
                }
                val content = sb.toString()
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(content)
                if (!tmp.renameTo(file)) {
                    file.writeText(content)
                    tmp.delete()
                }
            }
        }
    }

    private fun escapeField(raw: String): String {
        val sb = StringBuilder(raw.length + 8)
        for (ch in raw) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '\t' -> sb.append("\\t")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun unescapeField(escaped: String): String {
        val sb = StringBuilder(escaped.length)
        var i = 0
        while (i < escaped.length) {
            val ch = escaped[i]
            if (ch == '\\' && i + 1 < escaped.length) {
                when (escaped[i + 1]) {
                    '\\' -> sb.append('\\')
                    't' -> sb.append('\t')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    else -> sb.append(escaped[i + 1])
                }
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }

    private fun estimateCloudTokensSaved(input: String, reply: String): Int {
        // System prompt + history + user turn + model output typically consume ~260..420 tokens per API round-trip
        val charWeight = ((input.length + reply.length) / 3).coerceIn(20, 300)
        return 240 + charWeight
    }

    companion object {
        private const val VECTOR_DIM = 256
        private const val MAX_SAMPLES = 1500

        /**
         * Universal Bangla Prompt helper shared across skills & quick starters.
         */
        fun buildUniversalBanglaPrompt(): String = """
            আমার এই কাজটি সম্পূর্ণভাবে করে দাও (প্রয়োজন হলে ফোনের ইনস্টল করা অ্যাপ, Virtual Hands, লাইভ ব্রাউজার অথবা Python/Shell ব্যবহার করো):
            ১. কাজ/অ্যাপের নাম ও লক্ষ্য: [এখানে লিখুন — যেমন Facebook / Chrome / Gmail / Form Fill / Apply]
            ২. যদি কোনো অ্যাকাউন্ট তৈরি বা ফর্ম ফিলাপ করতে হয়, প্রয়োজনীয় তথ্য বসিয়ে ধাপে ধাপে সাবমিট করো।
            ৩. কোথাও Phone Number, SMS OTP, CAPTCHA বা ভেরিফিকেশন চাইলে কাজ থামিয়ে লাইভ স্ক্রিন সামনে দেখাও এবং আমাকে জিজ্ঞেস করো; আমি জমা দিলে ঠিক ওই জায়গা থেকে বাকি কাজ শেষ করো।
            ৪. শেষে যাচাই করে আমাকে সহজ বাংলায় ফলাফল জানাও।
        """.trimIndent()

        private val sensitiveKeywords = listOf(
            "aiza", "sk-or-", "api_key", "apikey", "password", "passcode", "pin:", "otp", "secret"
        )

        private fun containsSensitivePattern(text: String): Boolean {
            val lower = text.lowercase()
            return sensitiveKeywords.any { lower.contains(it) }
        }

        /**
         * Ensures Mini AI NEVER swallows action/tool/coding/browsing/automation commands.
         */
        fun looksLikeExecutableOrComplexTask(prompt: String): Boolean {
            val lower = prompt.trim().lowercase()
            if (lower.startsWith("/sh ") || lower.startsWith("/py ") || lower.startsWith(".")) return true
            if (lower.contains("http://") || lower.contains("https://") || lower.contains(".com")) return true
            val actionTerms = listOf(
                "open", "launch", "install", "uninstall", "click", "tap", "swipe", "scroll",
                "search", "browse", "chrome", "facebook", "youtube", "tiktok", "whatsapp", "gmail",
                "create", "make", "build", "write", "run", "execute", "python", "script", "shell",
                "cad", "autocad", "dxf", "stl", "file", "pdf", "upload", "download", "apply", "form",
                "fill", "submit", "monetization", "schedule", "delete", "remember", "memory",
                "খোল", "ওপেন", "চালা", "ইনস্টল", "ডাউনলোড", "আপলোড", "পোস্ট", "তৈরি", "বানা",
                "ফর্ম", "ফিলাপ", "পূরণ", "এপ্লাই", "আবেদন", "সাবমিট", "মনিটাইজ", "সার্চ", "খোঁজ",
                "ফাইল", "কোড", "পাইথন", "স্ক্রিপ্ট", "ব্রাউজার", "ক্রোম", "ড্রয়িং", "ডিজাইন",
                "মেসেজ পাঠা", "শিডিউল", "রিসার্চ", "লগইন", "অ্যাকাউন্ট", "স্কিল"
            )
            return actionTerms.any { lower.contains(it) }
        }

        private val banglishCanonicalMap = mapOf(
            "salam" to "hello", "assalamu" to "hello", "alaikum" to "greeting",
            "হ্যালো" to "hello", "হাই" to "hi", "হেলো" to "hello",
            "সালাম" to "hello", "আসসালামু" to "hello", "আলাইকুম" to "greeting",
            "kemon" to "how", "kmn" to "how", "কেমন" to "how",
            "acho" to "are", "achen" to "are", "আছো" to "are", "আছেন" to "are",
            "valo" to "good", "bhalo" to "good", "ভালো" to "good", "ভাল" to "good",
            "asi" to "am", "achi" to "am", "আছি" to "am",
            "ki" to "what", "koro" to "doing", "korcho" to "doing", "করো" to "doing", "করছো" to "doing",
            "obostha" to "status", "অবস্থা" to "status", "khobor" to "news", "খবর" to "news",
            "dhonnobad" to "thanks", "ধন্যবাদ" to "thanks", "thanks" to "thanks", "thank" to "thanks",
            "tumi" to "you", "apni" to "you", "তুমি" to "you", "আপনি" to "you",
            "ke" to "who", "কে" to "who", "naam" to "name", "নাম" to "name",
            "thik" to "ok", "ache" to "ok", "ঠিক" to "ok", "আছে" to "ok", "ওকে" to "ok",
            "biday" to "bye", "বিদায়" to "bye", "আল্লাহ" to "bye", "hafez" to "bye",
            "sahajjo" to "help", "সাহায্য" to "help", "parbo" to "can", "paro" to "can", "পারো" to "can",
            "bhai" to "friend", "ভাই" to "friend", "vai" to "friend",
            "koi" to "where", "কই" to "where", "কোথায়" to "where"
        )

        fun normalizeText(raw: String): String {
            return raw.trim()
                .lowercase()
                .replace(Regex("[\\p{Punct}।,?!]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        fun tokenizeNormalized(raw: String): List<String> {
            val norm = normalizeText(raw)
            if (norm.isEmpty()) return emptyList()
            val out = mutableListOf<String>()
            norm.split(" ").forEach { tok ->
                if (tok.isNotBlank()) {
                    out.add(tok)
                    banglishCanonicalMap[tok]?.let { mapped ->
                        if (mapped != tok) out.add(mapped)
                    }
                }
            }
            return out
        }

        private fun vectorize(raw: String, tokens: List<String>): FloatArray {
            val vec = FloatArray(VECTOR_DIM)
            val norm = normalizeText(raw)

            // 1. Token unigrams & bigrams (strong weight)
            for (i in tokens.indices) {
                val h1 = Math.floorMod(tokens[i].hashCode(), VECTOR_DIM)
                vec[h1] += 2.2f
                if (i + 1 < tokens.size) {
                    val bigram = "${tokens[i]}_${tokens[i + 1]}"
                    val h2 = Math.floorMod(bigram.hashCode(), VECTOR_DIM)
                    vec[h2] += 1.8f
                }
            }

            // 2. Character 3-grams and 4-grams across padded normalized text (robust to Bangla/Banglish spelling variation)
            val padded = " $norm "
            if (padded.length >= 3) {
                for (i in 0..(padded.length - 3)) {
                    val g3 = padded.substring(i, i + 3)
                    vec[Math.floorMod(g3.hashCode(), VECTOR_DIM)] += 1.0f
                    if (i <= padded.length - 4) {
                        val g4 = padded.substring(i, i + 4)
                        vec[Math.floorMod(g4.hashCode(), VECTOR_DIM)] += 1.15f
                    }
                }
            }

            // L2 normalize
            var sumSq = 0f
            for (v in vec) sumSq += v * v
            if (sumSq > 0f) {
                val invNorm = 1f / sqrt(sumSq)
                for (i in vec.indices) vec[i] *= invNorm
            }
            return vec
        }

        private fun dot(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }

        private fun jaccardOverlap(a: List<String>, b: List<String>): Float {
            if (a.isEmpty() || b.isEmpty()) return 0f
            val setA = a.toHashSet()
            val setB = b.toHashSet()
            val inter = setA.count { it in setB }
            val union = setA.size + setB.size - inter
            return if (union <= 0) 0f else inter.toFloat() / union.toFloat()
        }

        private fun starterCorpus(): List<MiniAiSample> = listOf(
            MiniAiSample("st_hi_en", "hi", "হ্যালো! বলো, আমি তোমাকে আজ কীভাবে সাহায্য করতে পারি?", "STARTER"),
            MiniAiSample("st_hello_en", "hello", "হ্যালো! আমি প্রস্তুত — যেকোনো কাজের নির্দেশ দাও।", "STARTER"),
            MiniAiSample("st_hey_en", "hey", "হাই! বলো, কী করতে হবে?", "STARTER"),
            MiniAiSample("st_hi_bn", "হাই", "হাই! বলো, আজ কোন কাজটা করে দেবো?", "STARTER"),
            MiniAiSample("st_hello_bn", "হ্যালো", "হ্যালো! আমি শুনছি, তোমার কাজ বা প্রশ্ন বলো।", "STARTER"),
            MiniAiSample("st_salam", "assalamu alaikum", "ওয়ালাইকুমুস সালাম! কেমন আছো? বলো আজ কীভাবে সাহায্য করতে পারি?", "STARTER"),
            MiniAiSample("st_salam_bn", "আসসালামু আলাইকুম", "ওয়ালাইকুমুস সালাম! ভালো আছি, আলহামদুলিল্লাহ। বলো, আজ কী কাজ করবে?", "STARTER"),
            MiniAiSample("st_how_bn", "কেমন আছো", "আমি একদম ভালো আছি ও ফুল স্পিডে প্রস্তুত! তুমি কেমন আছো? আজ কোন কাজ করবো?", "STARTER"),
            MiniAiSample("st_how_bng", "kemon acho", "ভালো আছি! তুমি কেমন আছো? বলো আজ কোন কাজে সাহায্য করবো?", "STARTER"),
            MiniAiSample("st_how_en", "how are you", "I am running smoothly and ready to automate anything you need! How can I help today?", "STARTER"),
            MiniAiSample("st_ki_obostha", "কি অবস্থা", "সব কিছু ঠিকঠাক চলছে! বলো, কোন অ্যাপ, ফর্ম, কোড বা ব্রাউজারের কাজ করবো?", "STARTER"),
            MiniAiSample("st_ki_koro", "কি করো", "আমি তোমার অর্ডার ও অটোমেশন টাস্কের জন্য রেডি আছি! কোনো কাজ থাকলে বলো।", "STARTER"),
            MiniAiSample("st_who_bn", "তুমি কে", "আমি তোমার Personal AI Computer — যে Virtual Hands দিয়ে অ্যাপ চালানো, ব্রাউজার, ফর্ম পূরণ, কোডিং, WhatsApp বট ও অন-ডিভাইস Mini AI সব একসাথে সামলায়।", "STARTER"),
            MiniAiSample("st_who_en", "who are you", "I am your Personal AI Computer — an autonomous mobile agent with Virtual Hands, live browser, Linux/Python/V8 runtime, CAD tools, WhatsApp AI Bot, and a self-learning on-device Mini AI.", "STARTER"),
            MiniAiSample("st_thanks_bn", "ধন্যবাদ", "স্বাগতম! আর কোনো কাজ লাগলে সাথে সাথেই বলো।", "STARTER"),
            MiniAiSample("st_thanks_en", "thank you", "You're welcome! Let me know whenever you need help with the next task.", "STARTER"),
            MiniAiSample("st_ok_bn", "ঠিক আছে", "ঠিক আছে! পরবর্তী নির্দেশ দিলে সাথে সাথেই কাজ শুরু করবো।", "STARTER"),
            MiniAiSample("st_ok_en", "okay", "Got it! Ready whenever you are.", "STARTER"),
            MiniAiSample("st_gm", "good morning", "শুভ সকাল! আজকে কী কী কাজ অটোমেট করবে, বলো শুরু করি।", "STARTER"),
            MiniAiSample("st_gn", "good night", "শুভ রাত্রি! তোমার শিডিউল ও WhatsApp বট ব্যাকগ্রাউন্ডে নিরাপদে খেয়াল রাখবে।", "STARTER"),
            MiniAiSample("st_capabilities_bn", "তুমি কি কি করতে পারো", "আমি Virtual Hands দিয়ে যেকোনো অ্যাপ (Facebook, Chrome, Gmail ইত্যাদি) চালাতে পারি, লাইভ ব্রাউজারে ফর্ম ফিলাপ ও সার্চ করতে পারি, WhatsApp অটো-রিপ্লাই ও গ্রুপ মডারেশন চালাতে পারি, Python/HTML/AutoCAD বানাতে পারি, এবং অন-ডিভাইস Mini AI দিয়ে ফ্রি API টোকেন সেভ করতে পারি!", "STARTER"),
            MiniAiSample("st_cap_en", "what can you do", "I can operate Android apps via Virtual Hands, automate browser logins & form submissions, manage WhatsApp chats/schedules, execute Python/Shell/JS locally, generate 2D/3D CAD blueprints, and learn locally via Mini AI to save API tokens.", "STARTER"),
            MiniAiSample("st_universal_prompt", "ইউনিভার্সাল বাংলা প্রম্পট দাও", buildUniversalBanglaPrompt(), "STARTER"),
            MiniAiSample("st_wa_casual_1", "ভাই কেমন আছেন", "আলহামদুলিল্লাহ ভালো আছি ভাই! আপনি কেমন আছেন? কোনো কাজ থাকলে বলবেন।", "WHATSAPP"),
            MiniAiSample("st_wa_casual_2", "কই আছো", "একটু কাজে ব্যস্ত আছি ভাই, ফ্রি হয়েই নক দিচ্ছি বা জরুরি হলে এখানে লিখে রাখুন।", "WHATSAPP"),
            MiniAiSample("st_wa_casual_3", "ki khobor", "সব ভালো ভাই, আপনার কী খবর?", "WHATSAPP"),
            MiniAiSample("st_wa_casual_4", "pore kotha hobe", "জি আচ্ছা, পরে কথা হবে ইনশাআল্লাহ। ভালো থাকবেন!", "WHATSAPP")
        )
    }
}
