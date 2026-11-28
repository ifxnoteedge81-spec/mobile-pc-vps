package com.example.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import com.example.storage.SettingsDataStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

data class VoiceRuntimeState(
    val isEnrolling: Boolean = false,
    val isTestingMatch: Boolean = false,
    val enrolledSampleCount: Int = 0,
    val lastMatchScorePercent: Int? = null,
    val lastMatchAccepted: Boolean? = null,
    val isHandsFreeActive: Boolean = false,
    val isCapturingSpeech: Boolean = false,
    val lastTranscript: String = "",
    val lastSpokenReply: String = "",
    val statusMessage: String = "ভয়েস লক ও হ্যান্ডস-ফ্রি মোড রেডি"
)

class VoiceAssistantController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsStore: SettingsDataStore,
    private val voiceprintManager: SpeakerVoiceprintManager,
    private val onExecuteVoiceCommand: suspend (String) -> String
) {
    private val _state = MutableStateFlow(VoiceRuntimeState())
    val state: StateFlow<VoiceRuntimeState> = _state.asStateFlow()

    private var monitorJob: Job? = null
    @Volatile
    private var ttsEngine: TextToSpeech? = null
    @Volatile
    private var ttsReady: Boolean = false

    init {
        scope.launch(Dispatchers.IO) {
            runCatching {
                settingsStore.settings.collect { settings ->
                    _state.update { cur ->
                        cur.copy(
                            enrolledSampleCount = settings.voiceSampleCount,
                            isHandsFreeActive = settings.voiceHandsFreeEnabled && monitorJob?.isActive == true
                        )
                    }
                }
            }
        }
    }

    fun hasRecordPermission(): Boolean = voiceprintManager.hasRecordAudioPermission()

    suspend fun enrollOwnerSample(): Result<Int> {
        if (!hasRecordPermission()) {
            val err = SecurityException("মাইক্রোফোন পারমিশন (RECORD_AUDIO) দিন।")
            _state.update { it.copy(statusMessage = err.message ?: "Permission required") }
            return Result.failure(err)
        }
        _state.update {
            it.copy(
                isEnrolling = true,
                statusMessage = "🎤 ২.৮ সেকেন্ড আপনার স্বাভাবিক কণ্ঠে কথা বলুন..."
            )
        }
        return try {
            val pcm = voiceprintManager.recordVoiceClip(2800L).getOrElse { err ->
                _state.update { it.copy(statusMessage = "রেকর্ডিং ব্যর্থ: ${err.message}") }
                return Result.failure(err)
            }
            val sampleVec = SpeakerVoiceprintManager.extractAcousticEmbedding(pcm).getOrElse { err ->
                _state.update { it.copy(statusMessage = err.message ?: "ভয়েস ফিচার পাওয়া যায়নি") }
                return Result.failure(err)
            }
            val settings = settingsStore.settings.first()
            val existing = SpeakerVoiceprintManager.decodeEmbedding(settings.voiceOwnerEmbeddingJson)
            val merged = SpeakerVoiceprintManager.mergeEnrollments(existing, sampleVec, settings.voiceSampleCount)
            val newCount = settings.voiceSampleCount + 1
            settingsStore.updateVoiceConfig(
                ownerEmbeddingJson = SpeakerVoiceprintManager.encodeEmbedding(merged),
                sampleCount = newCount
            )
            _state.update {
                it.copy(
                    enrolledSampleCount = newCount,
                    lastMatchScorePercent = 100,
                    lastMatchAccepted = true,
                    statusMessage = "✅ ভয়েস স্যাম্পল #$newCount সেভ হয়েছে! (মোট $newCount টি স্যাম্পল ট্রেইনড)"
                )
            }
            Result.success(newCount)
        } finally {
            _state.update { it.copy(isEnrolling = false) }
        }
    }

    suspend fun testOwnerMatch(): Result<Pair<Int, Boolean>> {
        if (!hasRecordPermission()) {
            return Result.failure(SecurityException("মাইক্রোফোন পারমিশন (RECORD_AUDIO) দিন।"))
        }
        val settings = settingsStore.settings.first()
        val ownerVec = SpeakerVoiceprintManager.decodeEmbedding(settings.voiceOwnerEmbeddingJson)
            ?: return Result.failure(IllegalStateException("আগে অন্তত ১টি ভয়েস স্যাম্পল ট্রেইন (Enroll) করুন।"))

        _state.update {
            it.copy(
                isTestingMatch = true,
                statusMessage = "🎤 ২.৪ সেকেন্ড কথা বলুন — কণ্ঠ যাচাই হচ্ছে..."
            )
        }
        return try {
            val pcm = voiceprintManager.recordVoiceClip(2400L).getOrElse { err ->
                _state.update { it.copy(statusMessage = "টেস্ট ব্যর্থ: ${err.message}") }
                return Result.failure(err)
            }
            val candVec = SpeakerVoiceprintManager.extractAcousticEmbedding(pcm).getOrElse { err ->
                _state.update { it.copy(statusMessage = err.message ?: "ভয়েস শনাক্ত হয়নি") }
                return Result.failure(err)
            }
            val score = SpeakerVoiceprintManager.computeSimilarityScore(ownerVec, candVec)
            val percent = (score * 100f).toInt().coerceIn(0, 100)
            val accepted = score >= settings.voiceMatchThreshold
            _state.update {
                it.copy(
                    lastMatchScorePercent = percent,
                    lastMatchAccepted = accepted,
                    statusMessage = if (accepted) {
                        "✅ Owner Voice Match: $percent% (অনুমোদিত — থ্রেশহোল্ড ${(settings.voiceMatchThreshold * 100).toInt()}%)"
                    } else {
                        "🚫 Voice Rejected: $percent% (অন্য কারো কণ্ঠ — কমপক্ষে ${(settings.voiceMatchThreshold * 100).toInt()}% প্রয়োজন)"
                    }
                )
            }
            Result.success(percent to accepted)
        } finally {
            _state.update { it.copy(isTestingMatch = false) }
        }
    }

    suspend fun clearVoiceProfile() {
        stopHandsFreeMonitoring()
        settingsStore.updateVoiceConfig(
            ownerEmbeddingJson = "",
            sampleCount = 0,
            handsFreeEnabled = false
        )
        _state.update {
            it.copy(
                enrolledSampleCount = 0,
                lastMatchScorePercent = null,
                lastMatchAccepted = null,
                isHandsFreeActive = false,
                statusMessage = "ভয়েস প্রোফাইল মুছে ফেলা হয়েছে।"
            )
        }
    }

    suspend fun setHandsFreeMonitoring(enabled: Boolean): Result<Boolean> {
        if (enabled && !hasRecordPermission()) {
            settingsStore.updateVoiceConfig(handsFreeEnabled = false)
            _state.update {
                it.copy(
                    isHandsFreeActive = false,
                    statusMessage = "হ্যান্ডস-ফ্রি চালানোর জন্য মাইক্রোফোন পারমিশন দিন।"
                )
            }
            return Result.failure(SecurityException("RECORD_AUDIO permission missing"))
        }
        settingsStore.updateVoiceConfig(handsFreeEnabled = enabled)
        if (enabled) {
            VoiceMonitorService.start(context)
            startMonitorLoop()
        } else {
            stopHandsFreeMonitoring()
        }
        return Result.success(enabled)
    }

    fun onForegroundServiceStarted() {
        scope.launch(Dispatchers.IO) {
            val s = settingsStore.settings.first()
            if (s.voiceHandsFreeEnabled && monitorJob?.isActive != true) {
                startMonitorLoop()
            }
        }
    }

    fun stopHandsFreeMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        VoiceMonitorService.stop(context)
        _state.update {
            it.copy(
                isHandsFreeActive = false,
                isCapturingSpeech = false,
                statusMessage = "হ্যান্ডস-ফ্রি ভয়েস মনিটর বন্ধ আছে।"
            )
        }
    }

    private fun startMonitorLoop() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch(Dispatchers.IO) {
            _state.update {
                it.copy(
                    isHandsFreeActive = true,
                    statusMessage = "👂 হ্যান্ডস-ফ্রি চালু — কথা বললে অটো শুনবে ও উত্তর দেবে"
                )
            }
            try {
                while (isActive) {
                    val settings = settingsStore.settings.first()
                    if (!settings.voiceHandsFreeEnabled || !hasRecordPermission()) {
                        break
                    }
                    // Wait cleanly if user is actively recording training or testing samples
                    if (_state.value.isEnrolling || _state.value.isTestingMatch) {
                        delay(400L)
                        continue
                    }

                    // 1. Acoustic speech energy & Owner Voiceprint Gate (when lock enabled & trained)
                    val ownerVec = if (settings.voiceLockEnabled) {
                        SpeakerVoiceprintManager.decodeEmbedding(settings.voiceOwnerEmbeddingJson)
                    } else null

                    if (ownerVec != null) {
                        val burst = voiceprintManager.recordVoiceClip(1750L).getOrNull()
                        if (burst == null) {
                            delay(500L)
                            continue
                        }
                        val rms = SpeakerVoiceprintManager.computeClipRms(burst)
                        if (rms < 0.015f) {
                            delay(200L)
                            continue
                        }
                        val cand = SpeakerVoiceprintManager.extractAcousticEmbedding(burst).getOrNull()
                        if (cand == null) {
                            delay(250L)
                            continue
                        }
                        val sim = SpeakerVoiceprintManager.computeSimilarityScore(ownerVec, cand)
                        val pct = (sim * 100f).toInt()
                        val accepted = sim >= settings.voiceMatchThreshold
                        _state.update {
                            it.copy(
                                lastMatchScorePercent = pct,
                                lastMatchAccepted = accepted
                            )
                        }
                        if (!accepted) {
                            _state.update {
                                it.copy(statusMessage = "🚫 অপরিচিত কণ্ঠ ($pct%) — হ্যান্ডস-ফ্রি ব্লক করা হয়েছে")
                            }
                            delay(450L)
                            continue
                        }
                        _state.update {
                            it.copy(statusMessage = "✅ Owner Verified ($pct%) — কমান্ড শুনছি...")
                        }
                    } else {
                        _state.update {
                            it.copy(statusMessage = "👂 হ্যান্ডস-ফ্রি অ্যাক্টিভ — এখন কমান্ড বলুন...")
                        }
                    }

                    // 2. Capture Speech-to-Text transcript via Android SpeechRecognizer on Main thread
                    _state.update { it.copy(isCapturingSpeech = true) }
                    val transcript = captureSpeechOnceOnMain().orEmpty().trim()
                    _state.update { it.copy(isCapturingSpeech = false) }

                    if (transcript.isBlank()) {
                        delay(700L)
                        continue
                    }

                    _state.update {
                        it.copy(
                            lastTranscript = transcript,
                            statusMessage = "⚡ কমান্ড চলছে: ${transcript.take(65)}"
                        )
                    }

                    // 3. Execute on Autonomous Agent Engine
                    val report = runCatching { onExecuteVoiceCommand(transcript) }
                        .getOrElse { "কাজটি সম্পন্ন করতে সমস্যা হয়েছে: ${it.message}" }

                    // 4. Optional Spoken Feedback via Android TextToSpeech
                    if (settings.voiceSpokenFeedbackEnabled && report.isNotBlank()) {
                        val concise = report.replace(Regex("[*_`#]+"), "").trim().take(280)
                        _state.update {
                            it.copy(
                                lastSpokenReply = concise,
                                statusMessage = "🔊 উত্তর বলা হচ্ছে..."
                            )
                        }
                        speakText(concise)
                    }

                    delay(800L)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _state.update {
                    it.copy(statusMessage = "ভয়েস মনিটর পুনরায় শুরু করুন: ${t.message ?: "Error"}")
                }
            } finally {
                _state.update { it.copy(isHandsFreeActive = false, isCapturingSpeech = false) }
            }
        }
    }

    private suspend fun captureSpeechOnceOnMain(): String? = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return@withContext null
        val deferred = CompletableDeferred<String?>()
        val sr = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
            ?: return@withContext null

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (!deferred.isCompleted) deferred.complete(null)
            }
            override fun onResults(results: Bundle?) {
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!deferred.isCompleted) deferred.complete(spoken)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-US", "bn-BD"))
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { sr.startListening(intent) }
            .onFailure { if (!deferred.isCompleted) deferred.complete(null) }

        val text = withTimeoutOrNull(8_500L) { deferred.await() }
        runCatching { sr.destroy() }
        text
    }

    suspend fun speakText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        withContext(Dispatchers.Main) {
            ensureTtsInitialized()
            val tts = ttsEngine ?: return@withContext
            val hasBanglaChars = clean.any { it in '\u0980'..'\u09FF' }
            val locale = if (hasBanglaChars) Locale("bn", "BD") else Locale.US
            val langRes = tts.isLanguageAvailable(locale)
            if (langRes >= TextToSpeech.LANG_AVAILABLE) {
                tts.language = locale
            }
            tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "ai_voice_${System.currentTimeMillis()}")
        }
    }

    private suspend fun ensureTtsInitialized() {
        if (ttsReady && ttsEngine != null) return
        val readyDeferred = CompletableDeferred<Boolean>()
        ttsEngine = TextToSpeech(context.applicationContext) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
            if (!readyDeferred.isCompleted) readyDeferred.complete(ttsReady)
        }
        withTimeoutOrNull(3_000L) { readyDeferred.await() }
    }

    fun shutdown() {
        stopHandsFreeMonitoring()
        runCatching {
            ttsEngine?.stop()
            ttsEngine?.shutdown()
            ttsEngine = null
            ttsReady = false
        }
    }
}
