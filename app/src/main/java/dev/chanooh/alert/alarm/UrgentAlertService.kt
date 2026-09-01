package dev.chanooh.alert.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Short, attention-grabbing alert for URGENT events.
 *
 * Unlike CRITICAL this service never opens a full-screen activity and it stops
 * automatically. The server is ACKed when dispatch succeeds; this service only
 * provides the local loud-audio + repeating-vibration behavior.
 */
class UrgentAlertService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var originalAlarmVolume: Int? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopAlert()
            else -> startAlert(intent)
        }
        return START_NOT_STICKY
    }

    private fun startAlert(intent: Intent?) {
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Urgent alert" }
        val message = intent?.getStringExtra(EXTRA_MESSAGE).orEmpty().ifBlank { "Attention required" }
        val volumePercent = intent?.getIntExtra(EXTRA_VOLUME_PERCENT, DEFAULT_VOLUME_PERCENT)
            ?: DEFAULT_VOLUME_PERCENT

        startForeground(NOTIFICATION_ID, buildNotification(title, message))
        raiseAlarmVolume(volumePercent)
        startVibration()
        startAlarmAudio()
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ stopAlert() }, DURATION_MS)
    }

    private fun buildNotification(title: String, message: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .build()

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Urgent alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Short loud alerts with repeating vibration"
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    private fun raiseAlarmVolume(percent: Int) {
        val audioManager = getSystemService(AudioManager::class.java)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        if (originalAlarmVolume == null) {
            originalAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        }
        val target = (max * percent.coerceIn(30, 100) / 100f).toInt().coerceAtLeast(1)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
    }

    private fun restoreAlarmVolume() {
        val original = originalAlarmVolume ?: return
        getSystemService(AudioManager::class.java)
            .setStreamVolume(AudioManager.STREAM_ALARM, original, 0)
        originalAlarmVolume = null
    }

    private fun startAlarmAudio() {
        if (mediaPlayer != null) return
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return
        mediaPlayer = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@UrgentAlertService, uri)
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
    }

    private fun startVibration() {
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 600, 250, 900, 350), 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(effect)
        }
    }

    private fun stopVibration() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator.cancel()
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel()
        }
    }

    private fun stopAlert() {
        handler.removeCallbacksAndMessages(null)
        mediaPlayer?.runCatching { stop() }
        mediaPlayer?.release()
        mediaPlayer = null
        stopVibration()
        restoreAlarmVolume()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        mediaPlayer?.runCatching { stop() }
        mediaPlayer?.release()
        mediaPlayer = null
        stopVibration()
        restoreAlarmVolume()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACTION_START = "dev.chanooh.alert.action.START_URGENT"
        private const val ACTION_STOP = "dev.chanooh.alert.action.STOP_URGENT"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_MESSAGE = "message"
        private const val EXTRA_VOLUME_PERCENT = "volume_percent"
        private const val CHANNEL_ID = "urgent_alerts"
        private const val NOTIFICATION_ID = 9002
        private const val DEFAULT_VOLUME_PERCENT = 80
        private const val DURATION_MS = 30_000L

        fun start(
            context: Context,
            title: String,
            message: String,
            volumePercent: Int
        ) {
            context.startForegroundService(Intent(context, UrgentAlertService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_MESSAGE, message)
                putExtra(EXTRA_VOLUME_PERCENT, volumePercent)
            })
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, UrgentAlertService::class.java))
        }
    }
}
