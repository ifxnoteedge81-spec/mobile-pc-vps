package com.example.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * On-Device Acoustic Speaker Voiceprint Engine (16 kHz PCM).
 * Uses zero-mean Mel-filterbank spectral shape, autocorrelation F0 pitch,
 * voicing ratio, spectral centroid, and zero-crossing rate statistics to create
 * a compact, privacy-preserving L2-normalized Owner voiceprint.
 */
class SpeakerVoiceprintManager(private val context: Context? = null) {

    fun hasRecordAudioPermission(): Boolean {
        val ctx = context ?: return false
        return ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    suspend fun recordVoiceClip(durationMs: Long = 2600L): Result<ShortArray> = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext Result.failure(IllegalStateException("Context required"))
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return@withContext Result.failure(SecurityException("Microphone permission (RECORD_AUDIO) required."))
        }

        val sampleRate = SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val totalSamplesTarget = ((sampleRate * durationMs) / 1000L).toInt().coerceAtLeast(sampleRate)
        val outSamples = ShortArray(totalSamplesTarget)

        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        }.getOrNull() ?: runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        }.getOrNull() ?: return@withContext Result.failure(IllegalStateException("Unable to initialize microphone hardware."))

        return@withContext try {
            recorder.startRecording()
            var offset = 0
            val chunk = ShortArray(1024)
            val deadline = System.currentTimeMillis() + durationMs + 700L
            while (offset < totalSamplesTarget && System.currentTimeMillis() < deadline) {
                val read = recorder.read(chunk, 0, minOf(chunk.size, totalSamplesTarget - offset))
                if (read > 0) {
                    System.arraycopy(chunk, 0, outSamples, offset, read)
                    offset += read
                } else if (read < 0) {
                    break
                }
            }
            if (offset < sampleRate / 2) {
                Result.failure(IllegalStateException("Insufficient audio captured."))
            } else {
                Result.success(if (offset == totalSamplesTarget) outSamples else outSamples.copyOf(offset))
            }
        } catch (t: Throwable) {
            Result.failure(IllegalStateException(t.message ?: "Recording error"))
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private val MEL_BAND_FREQS_HZ = floatArrayOf(
            120f, 220f, 340f, 500f, 700f, 960f,
            1300f, 1750f, 2350f, 3150f, 4300f, 5900f
        )
        // 12 mean mel + 12 std mel + pitchMean + pitchStd + zcrMean + centroidMean + voicedRatio = 29 dims
        const val EMBEDDING_DIM = 29

        fun computeClipRms(pcm: ShortArray): Float {
            if (pcm.isEmpty()) return 0f
            var sumSq = 0.0
            for (s in pcm) {
                val norm = s / 32768.0
                sumSq += norm * norm
            }
            return sqrt(sumSq / pcm.size).toFloat()
        }

        fun extractAcousticEmbedding(
            pcmSamples: ShortArray,
            sampleRate: Int = SAMPLE_RATE
        ): Result<FloatArray> {
            val frameLen = (sampleRate * 0.025).toInt().coerceAtLeast(160) // 25ms
            val hopLen = (sampleRate * 0.010).toInt().coerceAtLeast(80)    // 10ms
            if (pcmSamples.size < frameLen * 3) {
                return Result.failure(IllegalArgumentException("Audio clip too short to extract voiceprint."))
            }

            // First pass: measure per-frame RMS to apply adaptive energy VAD
            val frameCountTotal = ((pcmSamples.size - frameLen) / hopLen) + 1
            val frameRms = FloatArray(frameCountTotal)
            var peakRms = 0f
            for (f in 0 until frameCountTotal) {
                val start = f * hopLen
                var sumSq = 0.0
                for (i in 0 until frameLen) {
                    val v = pcmSamples[start + i] / 32768.0
                    sumSq += v * v
                }
                val rms = sqrt(sumSq / frameLen).toFloat()
                frameRms[f] = rms
                if (rms > peakRms) peakRms = rms
            }

            if (peakRms < 0.008f) {
                return Result.failure(IllegalArgumentException("কথা খুব আস্তে বা নীরব — দয়া করে মাইকের কাছে স্পষ্টভাবে কথা বলুন।"))
            }

            val vadThreshold = (peakRms * 0.22f).coerceAtLeast(0.006f)
            val hamming = FloatArray(frameLen) { i ->
                (0.54 - 0.46 * cos((2.0 * PI * i) / (frameLen - 1))).toFloat()
            }

            val frameFeatures = mutableListOf<FloatArray>() // size 15 per active frame: [12 mel, pitchNorm, zcr, centroid]
            val frameBuf = FloatArray(frameLen)
            var voicedCount = 0

            val lagMin = (sampleRate / 380).coerceAtLeast(20)
            val lagMax = (sampleRate / 75).coerceAtMost(frameLen - 4)

            for (f in 0 until frameCountTotal) {
                if (frameRms[f] < vadThreshold) continue
                val start = f * hopLen

                // Pre-emphasis + Hamming window
                var zcrCount = 0
                var prevRaw = pcmSamples[start].toFloat()
                for (i in 0 until frameLen) {
                    val cur = pcmSamples[start + i].toFloat()
                    if ((cur >= 0f && prevRaw < 0f) || (cur < 0f && prevRaw >= 0f)) zcrCount++
                    val pre = if (i == 0) cur else (cur - 0.96f * prevRaw)
                    frameBuf[i] = (pre / 32768f) * hamming[i]
                    prevRaw = cur
                }

                // 12 Goertzel spectral band log energies + spectral centroid
                val rawMel = FloatArray(MEL_BAND_FREQS_HZ.size)
                var melMean = 0f
                var weightedFreqSum = 0.0
                var totalBandPower = 1e-9
                for (b in MEL_BAND_FREQS_HZ.indices) {
                    val freq = MEL_BAND_FREQS_HZ[b]
                    val power = goertzelPower(frameBuf, sampleRate, freq)
                    val logE = ln(power + 1e-6).toFloat()
                    rawMel[b] = logE
                    melMean += logE
                    weightedFreqSum += freq * power
                    totalBandPower += power
                }
                melMean /= rawMel.size
                // Spectral mean normalization removes volume/gain differences, preserving speaker vocal-tract formant shape
                val feat = FloatArray(15)
                var melSq = 0f
                for (b in rawMel.indices) {
                    val centered = rawMel[b] - melMean
                    feat[b] = centered
                    melSq += centered * centered
                }
                val melNorm = sqrt(melSq).coerceAtLeast(1e-4f)
                for (b in rawMel.indices) {
                    feat[b] = feat[b] / melNorm
                }

                // Autocorrelation pitch estimation on un-preemphasized windowed frame
                var frameEnergy = 1e-9
                for (i in 0 until frameLen) {
                    val s = pcmSamples[start + i] / 32768.0
                    frameEnergy += s * s
                }
                var bestCorr = 0.0
                var bestLag = 0
                for (lag in lagMin..lagMax) {
                    var num = 0.0
                    var e1 = 0.0
                    var e2 = 0.0
                    var i = 0
                    while (i < frameLen - lag) {
                        val a = pcmSamples[start + i] / 32768.0
                        val b = pcmSamples[start + i + lag] / 32768.0
                        num += a * b
                        e1 += a * a
                        e2 += b * b
                        i++
                    }
                    val denom = sqrt(e1 * e2).coerceAtLeast(1e-9)
                    val corr = num / denom
                    if (corr > bestCorr) {
                        bestCorr = corr
                        bestLag = lag
                    }
                }
                val isVoiced = bestCorr > 0.38 && bestLag > 0
                if (isVoiced) voicedCount++
                val f0Hz = if (isVoiced) sampleRate.toFloat() / bestLag else 0f
                // Center pitch roughly around 165Hz mapped to [-1, 1.5]
                feat[12] = if (isVoiced) ((f0Hz - 165f) / 110f).coerceIn(-1.2f, 2.0f) else 0f
                feat[13] = ((zcrCount.toFloat() / frameLen) * 2.5f - 0.5f).coerceIn(-1f, 1.5f)
                val centroidHz = (weightedFreqSum / totalBandPower).toFloat()
                feat[14] = ((centroidHz - 1400f) / 1500f).coerceIn(-1.2f, 2.0f)

                frameFeatures.add(feat)
            }

            if (frameFeatures.size < 6) {
                return Result.failure(IllegalArgumentException("পর্যাপ্ত ভয়েস পাওয়া যায়নি। অন্তত ২ সেকেন্ড স্পষ্টভাবে কথা বলুন।"))
            }

            val n = frameFeatures.size.toFloat()
            val means = FloatArray(15)
            for (ff in frameFeatures) {
                for (d in 0 until 15) means[d] += ff[d]
            }
            for (d in 0 until 15) means[d] /= n

            val stds = FloatArray(15)
            for (ff in frameFeatures) {
                for (d in 0 until 15) {
                    val diff = ff[d] - means[d]
                    stds[d] += diff * diff
                }
            }
            for (d in 0 until 15) stds[d] = sqrt(stds[d] / n)

            val out = FloatArray(EMBEDDING_DIM)
            // Spectral shape weighted carefully alongside F0 pitch & centroid
            for (b in 0 until 12) {
                out[b] = means[b] * 1.15f
                out[12 + b] = stds[b] * 0.65f
            }
            out[24] = means[12] * 1.45f // Pitch (F0) is a strong speaker biometric discriminator
            out[25] = stds[12] * 0.70f
            out[26] = means[13] * 0.85f  // ZCR
            out[27] = means[14] * 1.10f  // Spectral centroid
            out[28] = ((voicedCount / n) - 0.5f) * 1.1f

            return Result.success(l2Normalize(out))
        }

        private fun goertzelPower(samples: FloatArray, sampleRate: Int, targetFreqHz: Float): Double {
            val k = (0.5 + (samples.size * targetFreqHz) / sampleRate).toInt()
            val omega = (2.0 * PI * k) / samples.size
            val coeff = 2.0 * cos(omega)
            var q0 = 0.0
            var q1 = 0.0
            var q2 = 0.0
            for (s in samples) {
                q0 = coeff * q1 - q2 + s
                q2 = q1
                q1 = q0
            }
            val real = q1 - q2 * cos(omega)
            val imag = q2 * kotlin.math.sin(omega)
            return ((real * real) + (imag * imag)) / samples.size.coerceAtLeast(1)
        }

        fun l2Normalize(vec: FloatArray): FloatArray {
            var normSq = 0.0
            for (v in vec) normSq += v * v
            val norm = sqrt(normSq).toFloat().coerceAtLeast(1e-6f)
            return FloatArray(vec.size) { i -> vec[i] / norm }
        }

        fun mergeEnrollments(existing: FloatArray?, newSample: FloatArray, existingCount: Int): FloatArray {
            if (existing == null || existing.size != newSample.size || existingCount <= 0) {
                return l2Normalize(newSample)
            }
            val wOld = existingCount.toFloat()
            val wNew = 1f
            val merged = FloatArray(newSample.size) { i ->
                (existing[i] * wOld + newSample[i] * wNew) / (wOld + wNew)
            }
            return l2Normalize(merged)
        }

        /**
         * Returns calibrated [0f..1f] speaker similarity where ~0.78+ means strong match.
         */
        fun computeSimilarityScore(ownerEmbedding: FloatArray, candidateEmbedding: FloatArray): Float {
            if (ownerEmbedding.isEmpty() || ownerEmbedding.size != candidateEmbedding.size) return 0f
            var dot = 0.0
            var n1 = 0.0
            var n2 = 0.0
            for (i in ownerEmbedding.indices) {
                dot += ownerEmbedding[i] * candidateEmbedding[i]
                n1 += ownerEmbedding[i] * ownerEmbedding[i]
                n2 += candidateEmbedding[i] * candidateEmbedding[i]
            }
            val denom = sqrt(n1 * n2).coerceAtLeast(1e-9)
            val cosSim = (dot / denom).toFloat().coerceIn(-1f, 1f)
            return ((cosSim + 1f) * 0.5f).coerceIn(0f, 1f)
        }

        fun encodeEmbedding(vec: FloatArray): String {
            return vec.joinToString(separator = ",", prefix = "[", postfix = "]") { value ->
                if (value.isFinite()) value.toString() else "0.0"
            }
        }

        fun decodeEmbedding(json: String): FloatArray? {
            val trimmed = json.trim()
            if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return null
            val inner = trimmed.substring(1, trimmed.length - 1).trim()
            if (inner.isEmpty()) return null
            return runCatching {
                val parts = inner.split(',')
                FloatArray(parts.size) { i ->
                    val parsed = parts[i].trim().toFloatOrNull()
                        ?: throw IllegalArgumentException("Invalid float token")
                    if (!parsed.isFinite()) 0f else parsed
                }
            }.getOrNull()
        }
    }
}
