package com.altaf.soundamplifier

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.altaf.soundamplifier.audio.AudioEngine

class SoundAmplifierService : Service() {

    private lateinit var engine: AudioEngine

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        engine = AudioEngine(this).apply {
            levelListener = { value ->
                currentLevel = value
                listener?.invoke(isAmplifying, currentLevel, lastMessage)
            }

            errorListener = { text ->
                lastMessage = text
                isAmplifying = false
                listener?.invoke(false, currentLevel, lastMessage)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startAmplifier(intent)
            ACTION_UPDATE -> updateSettings(intent)
            ACTION_STOP -> stopAmplifier()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            engine.stop()
        } catch (_: Throwable) {
        }
        isAmplifying = false
        currentLevel = 0f
        lastMessage = "Amplifier stopped."
        listener?.invoke(false, 0f, lastMessage)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAmplifier(intent: Intent) {
        applySettings(intent)

        startForeground(
            NOTIFICATION_ID,
            buildNotification("Live amplification is starting…")
        )

        val started = engine.start()
        isAmplifying = started

        if (started) {
            lastMessage = "Live amplification is active in background."
            updateNotification("Listening in background • tap to open")
            listener?.invoke(true, currentLevel, lastMessage)
        } else {
            lastMessage = "Could not start live amplification."
            listener?.invoke(false, currentLevel, lastMessage)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun updateSettings(intent: Intent) {
        applySettings(intent)
        if (isAmplifying) {
            updateNotification("Listening in background • tap to open")
        }
    }

    private fun stopAmplifier() {
        try {
            engine.stop()
        } catch (_: Throwable) {
        }

        isAmplifying = false
        currentLevel = 0f
        lastMessage = "Amplifier stopped."
        listener?.invoke(false, 0f, lastMessage)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun applySettings(intent: Intent) {
        engine.gain = intent.getFloatExtra(EXTRA_GAIN, 2.2f)
        engine.balance = intent.getFloatExtra(EXTRA_BALANCE, 0f)
        engine.updateMicSensitivity(intent.getFloatExtra(EXTRA_MIC_SENSITIVITY, 1.5f))
        engine.updateOutputBoostMb(intent.getIntExtra(EXTRA_OUTPUT_BOOST, 1200))
        engine.setNoiseReduction(intent.getBooleanExtra(EXTRA_NOISE_REDUCTION, true))
        engine.setVoiceFocus(intent.getBooleanExtra(EXTRA_VOICE_FOCUS, true))

        val eq = intent.getFloatArrayExtra(EXTRA_EQ)
        if (eq != null) {
            for (i in 0 until minOf(5, eq.size)) {
                engine.setEqBand(i, eq[i])
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sound Amplifier",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps live sound amplification running in the background."
            setSound(null, null)
        }

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val contentPendingIntent = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, SoundAmplifierService::class.java).apply {
            action = ACTION_STOP
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Altaf Sound Amplifier")
            .setContentText(status)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .build()
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    companion object {
        const val ACTION_START = "com.altaf.soundamplifier.action.START"
        const val ACTION_UPDATE = "com.altaf.soundamplifier.action.UPDATE"
        const val ACTION_STOP = "com.altaf.soundamplifier.action.STOP"

        const val EXTRA_GAIN = "gain"
        const val EXTRA_MIC_SENSITIVITY = "mic_sensitivity"
        const val EXTRA_OUTPUT_BOOST = "output_boost"
        const val EXTRA_BALANCE = "balance"
        const val EXTRA_NOISE_REDUCTION = "noise_reduction"
        const val EXTRA_VOICE_FOCUS = "voice_focus"
        const val EXTRA_EQ = "eq"

        private const val CHANNEL_ID = "altaf_sound_amplifier_live"
        private const val NOTIFICATION_ID = 1001

        @Volatile var isAmplifying: Boolean = false
        @Volatile var currentLevel: Float = 0f
        @Volatile var lastMessage: String = "Connect headphones, then tap Start."

        @Volatile
        var listener: ((Boolean, Float, String) -> Unit)? = null
    }
}
