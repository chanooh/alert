package dev.chanooh.alert.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dev.chanooh.alert.ui.CriticalAlertActivity

class CriticalAlarmService : Service() {
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var originalAlarmVolume: Int? = null
    private var restoreVolumeAfterAck: Boolean = true

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                cleanup()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> startAlert(intent)
        }
        return START_NOT_STICKY
    }

    private fun startAlert(intent: Intent?) {
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Critical alert" }
        val message = intent?.getStringExtra(EXTRA_MESSAGE).orEmpty().ifBlank { "Immediate attention required" }
        val volumePercent = intent?.getIntExtra(EXTRA_VOLUME_PERCENT, 100) ?: 100
        restoreVolumeAfterAck = intent?.getBooleanExtra(EXTRA_RESTORE_VOLUME, true) ?: true

        startForeground(NOTIFICATION_ID, buildNotification(title, message))
        acquireWakeLock()
        raiseAlarmVolume(volumePercent)
        startVibration()
        startAlarmAudio()
    }

    private fun buildNotification(title: String, message: String): Notification {
        val fullScreenIntent = Intent(this, CriticalAlertActivity::class.java).apply {
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_MESSAGE, message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            100,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(pendingIntent, true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Critical alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Full-screen alarm-style alerts requiring acknowledgement"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
            setBypassDnd(true)
        }
        manager.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Alert:CriticalAlarm"
        ).apply { acquire(15 * 60 * 1000L) }
    }

    private fun raiseAlarmVolume(percent: Int) {
        val audioManager = getSystemService(AudioManager::class.java)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        if (originalAlarmVolume == null) {
            originalAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        }
        val target = (max * percent.coerceIn(10, 100) / 100f).toInt().coerceAtLeast(1)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
    }

    private fun restoreAlarmVolumeIfNeeded() {
        val original = originalAlarmVolume ?: return
        if (restoreVolumeAfterAck) {
            getSystemService(AudioManager::class.java)
                .setStreamVolume(AudioManager.STREAM_ALARM, original, 0)
        }
        originalAlarmVolume = null
    }

    private fun startAlarmAudio() {
        if (mediaPlayer != null) return
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setDataSource(this@CriticalAlarmService, alarmUri)
            isLooping = true
            prepare()
            start()
        }
    }

    private fun startVibration() {
        val pattern = longArrayOf(0, 800, 300, 800, 300, 1500, 500)
        val effect = VibrationEffect.createWaveform(pattern, 0)
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

    private fun cleanup() {
        mediaPlayer?.runCatching { stop() }
        mediaPlayer?.release()
        mediaPlayer = null
        stopVibration()
        restoreAlarmVolumeIfNeeded()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "dev.chanooh.alert.action.START_CRITICAL"
        const val ACTION_STOP = "dev.chanooh.alert.action.STOP_CRITICAL"
        const val EXTRA_TITLE = "title"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_VOLUME_PERCENT = "volume_percent"
        const val EXTRA_RESTORE_VOLUME = "restore_volume"
        private const val CHANNEL_ID = "critical_alerts"
        private const val NOTIFICATION_ID = 9001

        fun start(
            context: Context,
            title: String,
            message: String,
            volumePercent: Int = 100,
            restoreVolume: Boolean = true
        ) {
            val intent = Intent(context, CriticalAlarmService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_MESSAGE, message)
                putExtra(EXTRA_VOLUME_PERCENT, volumePercent)
                putExtra(EXTRA_RESTORE_VOLUME, restoreVolume)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CriticalAlarmService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }
}
