package com.earcap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.content.pm.ServiceInfo
import android.os.Looper
import android.provider.Settings

/**
 * 音量钳制前台服务。
 *
 * 三重保险，确保超限音量立刻被压回上限：
 * 1. ContentObserver 监听系统设置变化（音量变化通常会写入 Settings.System）→ 几乎实时；
 * 2. 兜底轮询（2 秒）：部分 ROM 对设置写入做了优化不回调，或耳机重连时漏掉事件；
 * 3. 每次 onStartCommand 也检查一遍。
 *
 * 只在「开关已启用 且 蓝牙耳机已连接」时生效，外放不受影响。
 */
class VolumeClampService : Service() {

    private lateinit var audio: AudioManager
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            checkAndClamp()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            checkAndClamp()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        startForegroundNotify()
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        running = true
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        checkAndClamp()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        contentResolver.unregisterContentObserver(observer)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun checkAndClamp() {
        // 心跳：与无障碍服务同一套后台存活统计
        val now = System.currentTimeMillis()
        val last = prefs.getLong(PREF_LAST_CHECK, now)
        val gap = now - last
        if (gap > prefs.getLong(PREF_MAX_GAP, 0L)) {
            prefs.edit().putLong(PREF_MAX_GAP, gap).apply()
        }
        prefs.edit().putLong(PREF_LAST_CHECK, now).apply()

        if (!prefs.getBoolean(PREF_ENABLED, false)) return
        if (!isBtAudioConnected(this)) return
        val cap = capIndex(audio, prefs)
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) > cap) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, cap, 0)
        }
    }

    private fun startForegroundNotify() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "音量限制", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "volume_cap"
        private const val NOTIF_ID = 1
        private const val TICK_MS = 2000L

        /** 供界面显示"服务是否还活着"（同进程，静态变量即可） */
        @Volatile
        var isRunning = false
            private set
    }
}
