package com.altaf.soundamplifier.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.tanh

class AudioEngine(private val context: Context) {

    private val running = AtomicBoolean(false)

    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var worker: Thread? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var equalizer: Equalizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    @Volatile var gain: Float = 2.2f
    @Volatile var micSensitivity: Float = 1.5f
    @Volatile var outputBoostMb: Int = 1200
    @Volatile var balance: Float = 0f

    @Volatile var noiseReductionEnabled: Boolean = true
    @Volatile var voiceFocusEnabled: Boolean = true
    @Volatile var smartVoiceEnabled: Boolean = true
    @Volatile var compressorEnabled: Boolean = true
    @Volatile var feedbackGuardEnabled: Boolean = true
    @Volatile var adaptiveNoiseEnabled: Boolean = true

    private val eqValues = FloatArray(10) { 0f }

    private var previousInput = 0f
    private var previousHighPass = 0f
    private var noiseFloorRms = 600f
    private var feedbackReduction = 1f
    private var hotBufferCount = 0

    var levelListener: ((Float) -> Unit)? = null
    var errorListener: ((String) -> Unit)? = null

    fun isRunning(): Boolean = running.get()

    @Synchronized
    fun start(): Boolean {
        if (running.get()) return true

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            errorListener?.invoke("Microphone permission is required.")
            return false
        }

        cleanup()

        previousInput = 0f
        previousHighPass = 0f
        feedbackReduction = 1f
        hotBufferCount = 0

        val sampleRate = 48_000
        val inputChannel = AudioFormat.CHANNEL_IN_MONO
        val outputChannel = AudioFormat.CHANNEL_OUT_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        val minRecordBytes = AudioRecord.getMinBufferSize(sampleRate, inputChannel, encoding)
        val minTrackBytes = AudioTrack.getMinBufferSize(sampleRate, outputChannel, encoding)

        if (minRecordBytes <= 0 || minTrackBytes <= 0) {
            errorListener?.invoke("This device did not provide a usable low-latency audio buffer.")
            return false
        }

        try {
            recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(inputChannel)
                        .build()
                )
                .setBufferSizeInBytes(max(minRecordBytes * 2, 8_192))
                .build()

            player = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(outputChannel)
                        .build()
                )
                .setBufferSizeInBytes(max(minTrackBytes * 2, 8_192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            if (recorder?.state != AudioRecord.STATE_INITIALIZED ||
                player?.state != AudioTrack.STATE_INITIALIZED
            ) {
                errorListener?.invoke("Audio hardware could not be initialized.")
                cleanup()
                return false
            }

            setupEffects()
            applyEffectSettings()
            applyAllEq()
            applyOutputBoost()

            running.set(true)
            player?.setVolume(1f)
            player?.play()
            recorder?.startRecording()

            worker = Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                audioLoop()
            }.also {
                it.name = "AltafAudioLoop"
                it.start()
            }

            return true
        } catch (t: Throwable) {
            errorListener?.invoke(t.message ?: "Could not start live amplification.")
            running.set(false)
            cleanup()
            return false
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)

        try {
            recorder?.stop()
        } catch (_: Throwable) {
        }

        try {
            player?.pause()
            player?.flush()
        } catch (_: Throwable) {
        }

        try {
            worker?.join(600)
        } catch (_: InterruptedException) {
        }

        worker = null
        cleanup()
        levelListener?.invoke(0f)
    }

    fun setNoiseReduction(enabled: Boolean) {
        noiseReductionEnabled = enabled
        applyEffectSettings()
    }

    fun setVoiceFocus(enabled: Boolean) {
        voiceFocusEnabled = enabled
        applyEffectSettings()
    }

    fun updateMicSensitivity(value: Float) {
        micSensitivity = value.coerceIn(1f, 3f)
    }

    fun updateOutputBoostMb(value: Int) {
        outputBoostMb = value.coerceIn(0, 1800)
        applyOutputBoost()
    }

    fun updateAdvancedProcessing(
        smartVoice: Boolean,
        compressor: Boolean,
        feedbackGuard: Boolean,
        adaptiveNoise: Boolean
    ) {
        smartVoiceEnabled = smartVoice
        compressorEnabled = compressor
        feedbackGuardEnabled = feedbackGuard
        adaptiveNoiseEnabled = adaptiveNoise
        applyEffectSettings()
    }

    fun setEqBand(index: Int, normalized: Float) {
        if (index !in 0..9) return
        eqValues[index] = normalized.coerceIn(-1f, 1f)
        applyEqBand(index)
    }

    private fun audioLoop() {
        val input = ShortArray(480)
        val stereo = ShortArray(input.size * 2)
        var meterCounter = 0

        while (running.get()) {
            val read = try {
                recorder?.read(input, 0, input.size, AudioRecord.READ_BLOCKING) ?: -1
            } catch (_: Throwable) {
                -1
            }

            if (read <= 0) {
                if (running.get()) errorListener?.invoke("Microphone stream was interrupted.")
                break
            }

            var sumSquares = 0.0
            var rawPeak = 0
            for (i in 0 until read) {
                val raw = input[i].toInt()
                rawPeak = max(rawPeak, abs(raw))
                sumSquares += raw.toDouble() * raw.toDouble()
            }

            val rms = sqrt(sumSquares / read.coerceAtLeast(1)).toFloat()

            val adaptiveGate = if (adaptiveNoiseEnabled) {
                if (rms < noiseFloorRms * 2.2f) {
                    noiseFloorRms = (noiseFloorRms * 0.992f) + (rms * 0.008f)
                }

                when {
                    rms < max(180f, noiseFloorRms * 1.15f) -> 0.48f
                    rms < max(350f, noiseFloorRms * 1.45f) -> 0.72f
                    else -> 1f
                }
            } else {
                1f
            }

            val localGain = gain.coerceIn(1f, 8f)
            val localMicSensitivity = micSensitivity.coerceIn(1f, 3f)
            val localBalance = balance.coerceIn(-1f, 1f)
            val leftGain = if (localBalance > 0f) 1f - localBalance else 1f
            val rightGain = if (localBalance < 0f) 1f + localBalance else 1f

            val protectedGain = if (feedbackGuardEnabled) feedbackReduction else 1f
            val totalGain = localGain * localMicSensitivity * adaptiveGate * protectedGain

            var outputPeak = 0

            for (i in 0 until read) {
                val raw = input[i].toFloat()

                var processed = if (smartVoiceEnabled) {
                    val highPass = raw - previousInput + (0.94f * previousHighPass)
                    previousInput = raw
                    previousHighPass = highPass

                    // Speech-presence emphasis: reduce low rumble while adding clarity.
                    (raw * 0.78f) + (highPass * 0.55f)
                } else {
                    previousInput = raw
                    raw
                }

                processed *= totalGain

                if (compressorEnabled) {
                    processed = compress(processed)
                }

                processed = softClip(processed)

                val left = (processed * leftGain).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                val right = (processed * rightGain).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

                outputPeak = max(outputPeak, max(abs(left), abs(right)))

                val out = i * 2
                stereo[out] = left.toShort()
                stereo[out + 1] = right.toShort()
            }

            if (feedbackGuardEnabled) {
                val hot = outputPeak > 30_500 && rawPeak > 13_000
                if (hot) {
                    hotBufferCount++
                    if (hotBufferCount >= 3) {
                        feedbackReduction = max(0.42f, feedbackReduction * 0.80f)
                        hotBufferCount = 0
                    }
                } else {
                    hotBufferCount = max(0, hotBufferCount - 1)
                    feedbackReduction = (feedbackReduction + 0.012f).coerceAtMost(1f)
                }
            } else {
                feedbackReduction = 1f
                hotBufferCount = 0
            }

            try {
                player?.write(stereo, 0, read * 2, AudioTrack.WRITE_BLOCKING)
            } catch (_: Throwable) {
                if (running.get()) errorListener?.invoke("Headphone output was interrupted.")
                break
            }

            meterCounter++
            if (meterCounter >= 3) {
                meterCounter = 0
                levelListener?.invoke((outputPeak / 32767f).coerceIn(0f, 1f))
            }
        }

        running.set(false)
    }

    private fun compress(value: Float): Float {
        val normalized = value / 32768f
        val magnitude = abs(normalized)
        val threshold = 0.56f

        if (magnitude <= threshold) return value

        val compressedMagnitude = threshold + ((magnitude - threshold) / 4.2f)
        return sign(normalized) * compressedMagnitude * 32768f
    }

    private fun softClip(value: Float): Float {
        val normalized = (value / 32768f).coerceIn(-4f, 4f)
        return (tanh(normalized.toDouble()) * 32767.0).toFloat()
    }

    private fun setupEffects() {
        val recordSession = recorder?.audioSessionId ?: return
        val trackSession = player?.audioSessionId ?: return

        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(recordSession)
            }
        } catch (_: Throwable) {
        }

        try {
            if (AutomaticGainControl.isAvailable()) {
                automaticGainControl = AutomaticGainControl.create(recordSession)
            }
        } catch (_: Throwable) {
        }

        try {
            equalizer = Equalizer(0, trackSession).apply {
                enabled = true
            }
        } catch (_: Throwable) {
            equalizer = null
        }

        try {
            loudnessEnhancer = LoudnessEnhancer(trackSession).apply {
                enabled = true
            }
        } catch (_: Throwable) {
            loudnessEnhancer = null
        }
    }

    private fun applyEffectSettings() {
        try {
            noiseSuppressor?.enabled = noiseReductionEnabled || adaptiveNoiseEnabled
        } catch (_: Throwable) {
        }

        try {
            automaticGainControl?.enabled = voiceFocusEnabled
        } catch (_: Throwable) {
        }
    }

    private fun applyOutputBoost() {
        try {
            loudnessEnhancer?.setTargetGain(outputBoostMb)
            loudnessEnhancer?.enabled = outputBoostMb > 0
        } catch (_: Throwable) {
        }
    }

    private fun applyAllEq() {
        for (i in 0..9) applyEqBand(i)
    }

    private fun applyEqBand(uiBand: Int) {
        val eq = equalizer ?: return

        try {
            val bandCount = eq.numberOfBands.toInt()
            if (bandCount <= 0) return

            val actualBand = if (bandCount == 1) {
                0
            } else {
                ((uiBand * (bandCount - 1)) / 9).coerceIn(0, bandCount - 1)
            }

            val range = eq.bandLevelRange
            val minLevel = range[0].toInt()
            val maxLevel = range[1].toInt()
            val normalized = eqValues[uiBand].coerceIn(-1f, 1f)

            val level = if (normalized >= 0f) {
                (normalized * maxLevel).toInt()
            } else {
                (-normalized * minLevel).toInt()
            }.coerceIn(minLevel, maxLevel)

            eq.setBandLevel(actualBand.toShort(), level.toShort())
        } catch (_: Throwable) {
        }
    }

    private fun cleanup() {
        try {
            loudnessEnhancer?.release()
        } catch (_: Throwable) {
        }
        loudnessEnhancer = null

        try {
            noiseSuppressor?.release()
        } catch (_: Throwable) {
        }
        noiseSuppressor = null

        try {
            automaticGainControl?.release()
        } catch (_: Throwable) {
        }
        automaticGainControl = null

        try {
            equalizer?.release()
        } catch (_: Throwable) {
        }
        equalizer = null

        try {
            recorder?.release()
        } catch (_: Throwable) {
        }
        recorder = null

        try {
            player?.release()
        } catch (_: Throwable) {
        }
        player = null
    }
}
