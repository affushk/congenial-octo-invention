package com.altaf.soundamplifier

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.altaf.soundamplifier.audio.AudioEngine
import com.altaf.soundamplifier.audio.WavRecorder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SoundAmplifierService : Service() {

    private lateinit var engine: AudioEngine
    private val wavRecorder = WavRecorder(sampleRate = 48_000, channels = 1)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        engine = AudioEngine(this).apply {
            levelListener = { value ->
                currentLevel = value
                listener?.invoke(isAmplifying, currentLevel, lastMessage, currentGain)
            }

            errorListener = { text ->
                lastMessage = text
                isAmplifying = false
                listener?.invoke(false, currentLevel, lastMessage, currentGain)
            }

            processedAudioSink = { samples, count ->
                if (isRecording) {
                    wavRecorder.enqueue(samples, count)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startAmplifier(intent)
            ACTION_UPDATE -> updateSettings(intent)
            ACTION_GAIN_UP -> adjustGain(0.25f)
            ACTION_GAIN_DOWN -> adjustGain(-0.25f)
            ACTION_START_RECORDING -> startRecording()
            ACTION_STOP_RECORDING -> stopRecording()
            ACTION_STOP -> stopAmplifier()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRecording()

        try {
            engine.stop()
        } catch (_: Throwable) {
        }

        releaseWakeLock()

        isAmplifying = false
        currentLevel = 0f
        lastMessage = "Amplifier stopped."
        listener?.invoke(false, 0f, lastMessage, currentGain)

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAmplifier(intent: Intent) {
        applySettings(intent)

        startForeground(
            NOTIFICATION_ID,
            buildNotification("Starting live amplification…")
        )

        acquireWakeLock()

        val started = engine.start()
        isAmplifying = started

        if (started) {
            lastMessage = "Live amplification is active in background."
            updateNotification()
            listener?.invoke(true, currentLevel, lastMessage, currentGain)
        } else {
            releaseWakeLock()
            lastMessage = "Could not start live amplification."
            listener?.invoke(false, currentLevel, lastMessage, currentGain)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun updateSettings(intent: Intent) {
        applySettings(intent)

        if (isAmplifying) {
            lastMessage = if (isRecording) {
                "Recording active • processed speech audio is being saved locally."
            } else {
                "Advanced live processing is active."
            }
            updateNotification()
            listener?.invoke(true, currentLevel, lastMessage, currentGain)
        }
    }

    private fun adjustGain(delta: Float) {
        if (!isAmplifying) return

        currentGain = (currentGain + delta).coerceIn(1f, 8f)
        engine.gain = currentGain
        lastMessage = if (isRecording) {
            "Recording active • Gain ${String.format("%.2fx", currentGain)}"
        } else {
            "Gain ${String.format("%.2fx", currentGain)} • background listening active"
        }
        updateNotification()
        listener?.invoke(true, currentLevel, lastMessage, currentGain)
    }

    private fun startRecording() {
        if (!isAmplifying) {
            lastMessage = "Start live amplification before recording."
            listener?.invoke(false, currentLevel, lastMessage, currentGain)
            return
        }

        if (isRecording) return

        val directory = recordingsDirectory()
        directory.mkdirs()

        val stamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(Date())

        val file = File(
            directory,
            "Altaf_Speech_$stamp.wav"
        )

        if (wavRecorder.start(file)) {
            isRecording = true
            recordingStartedAt = System.currentTimeMillis()
            lastRecordingPath = file.absolutePath
            lastMessage = "Recording active • visible indicator is on."
            updateNotification()
            listener?.invoke(true, currentLevel, lastMessage, currentGain)
        } else {
            lastMessage = "Could not start local recording."
            listener?.invoke(true, currentLevel, lastMessage, currentGain)
        }
    }

    private fun stopRecording() {
        if (!isRecording) return

        val saved = wavRecorder.stop()
        isRecording = false
        recordingStartedAt = 0L

        if (saved != null) {
            lastRecordingPath = saved.absolutePath
            lastMessage = "Recording saved locally: ${saved.name}"
        } else {
            lastMessage = "Recording stopped."
        }

        if (isAmplifying) {
            updateNotification()
            listener?.invoke(true, currentLevel, lastMessage, currentGain)
        }
    }

    private fun stopAmplifier() {
        stopRecording()

        try {
            engine.stop()
        } catch (_: Throwable) {
        }

        releaseWakeLock()

        isAmplifying = false
        currentLevel = 0f
        lastMessage = "Amplifier stopped."
        listener?.invoke(false, 0f, lastMessage, currentGain)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun applySettings(intent: Intent) {
        currentGain = intent.getFloatExtra(EXTRA_GAIN, currentGain).coerceIn(1f, 8f)

        engine.gain = currentGain
        engine.balance = intent.getFloatExtra(EXTRA_BALANCE, 0f)
        engine.updateMicSensitivity(
            intent.getFloatExtra(EXTRA_MIC_SENSITIVITY, 1.5f)
        )
        engine.updateOutputBoostMb(
            intent.getIntExtra(EXTRA_OUTPUT_BOOST, 1200)
        )
        engine.setNoiseReduction(
            intent.getBooleanExtra(EXTRA_NOISE_REDUCTION, true)
        )
        engine.setVoiceFocus(
            intent.getBooleanExtra(EXTRA_VOICE_FOCUS, true)
        )
        engine.updateAdvancedProcessing(
            smartVoice = intent.getBooleanExtra(EXTRA_SMART_VOICE, true),
            speechFocus = intent.getBooleanExtra(EXTRA_SPEECH_FOCUS, true),
            compressor = intent.getBooleanExtra(EXTRA_COMPRESSOR, true),
            feedbackGuard = intent.getBooleanExtra(EXTRA_FEEDBACK_GUARD, true),
            adaptiveNoise = intent.getBooleanExtra(EXTRA_ADAPTIVE_NOISE, true)
        )

        val eq = intent.getFloatArrayExtra(EXTRA_EQ)
        if (eq != null) {
            for (i in 0 until minOf(10, eq.size)) {
                engine.setEqBand(i, eq[i])
            }
        }
    }

    private fun recordingsDirectory(): File {
        val musicRoot = getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        return File(
            musicRoot ?: filesDir,
            "AltafSoundAmplifier/Recordings"
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager

        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AltafSoundAmplifier:LiveAudio"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Throwable) {
        }

        wakeLock = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sound Amplifier",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when live listening or local recording is active."
            setSound(null, null)
        }

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(statusOverride: String? = null): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val contentPendingIntent = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun serviceAction(actionName: String, requestCode: Int): PendingIntent {
            return PendingIntent.getService(
                this,
                requestCode,
                Intent(this, SoundAmplifierService::class.java).apply {
                    action = actionName
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val status = statusOverride ?: if (isRecording) {
            "RECORDING • processed speech audio is being saved locally"
        } else {
            "Listening in background • Gain ${String.format("%.2fx", currentGain)}"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(
                if (isRecording) "Altaf Sound Amplifier • Recording"
                else "Altaf Sound Amplifier"
            )
            .setContentText(status)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_media_rew,
                "Gain −",
                serviceAction(ACTION_GAIN_DOWN, 2)
            )
            .addAction(
                android.R.drawable.ic_media_ff,
                "Gain +",
                serviceAction(ACTION_GAIN_UP, 3)
            )

        if (isRecording) {
            builder.addAction(
                android.R.drawable.ic_media_pause,
                "Stop recording",
                serviceAction(ACTION_STOP_RECORDING, 4)
            )
        } else {
            builder.addAction(
                android.R.drawable.ic_btn_speak_now,
                "Record",
                serviceAction(ACTION_START_RECORDING, 4)
            )
        }

        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Stop",
            serviceAction(ACTION_STOP, 5)
        )

        return builder.build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        const val ACTION_START = "com.altaf.soundamplifier.action.START"
        const val ACTION_UPDATE = "com.altaf.soundamplifier.action.UPDATE"
        const val ACTION_GAIN_UP = "com.altaf.soundamplifier.action.GAIN_UP"
        const val ACTION_GAIN_DOWN = "com.altaf.soundamplifier.action.GAIN_DOWN"
        const val ACTION_START_RECORDING = "com.altaf.soundamplifier.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.altaf.soundamplifier.action.STOP_RECORDING"
        const val ACTION_STOP = "com.altaf.soundamplifier.action.STOP"

        const val EXTRA_GAIN = "gain"
        const val EXTRA_MIC_SENSITIVITY = "mic_sensitivity"
        const val EXTRA_OUTPUT_BOOST = "output_boost"
        const val EXTRA_BALANCE = "balance"
        const val EXTRA_NOISE_REDUCTION = "noise_reduction"
        const val EXTRA_VOICE_FOCUS = "voice_focus"
        const val EXTRA_SMART_VOICE = "smart_voice"
        const val EXTRA_SPEECH_FOCUS = "speech_focus"
        const val EXTRA_COMPRESSOR = "compressor"
        const val EXTRA_FEEDBACK_GUARD = "feedback_guard"
        const val EXTRA_ADAPTIVE_NOISE = "adaptive_noise"
        const val EXTRA_EQ = "eq"

        private const val CHANNEL_ID = "altaf_sound_amplifier_live"
        private const val NOTIFICATION_ID = 1001

        @Volatile var isAmplifying: Boolean = false
        @Volatile var isRecording: Boolean = false
        @Volatile var recordingStartedAt: Long = 0L
        @Volatile var lastRecordingPath: String? = null
        @Volatile var currentLevel: Float = 0f
        @Volatile var currentGain: Float = 2.2f
        @Volatile var lastMessage: String = "Connect headphones, then tap Start."

        @Volatile
        var listener: ((Boolean, Float, String, Float) -> Unit)? = null
    }
}
