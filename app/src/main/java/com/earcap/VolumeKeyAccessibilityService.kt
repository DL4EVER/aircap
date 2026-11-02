package com.earcap

import android.accessibilityservice.AccessibilityService
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务：音量限制的主载体。
 *
 * 两件事都在这里做：
 * 1. 拦截手机侧音量上键：到上限后直接吃掉按键（按了没反应）；
 * 2. 承载钳制逻辑：ContentObserver 监听音量变化 + 2 秒兜底轮询，
 *    超限立即压回——覆盖耳机上按的音量键（AVRCP 路径）。
 *
 * 为什么放进无障碍：它由系统亲自绑定管理，国产 ROM 的后台清理几乎不碰它，
 * 即使被杀系统也会自动重绑。相比前台服务：无常驻通知、无需锁定后台。
 * 前台服务（VolumeClampService）降级为可选双保险，默认不启用。
 */
class VolumeKeyAccessibilityService : AccessibilityService() {

    private val audio: AudioManager by lazy {
        getSystemService(AUDIO_SERVICE) as AudioManager
    }
    private val handler = Handler(Looper.getMainLooper())
    private var watching = false

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            checkAndClamp()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            checkAndClamp()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        handler.post(tick)
        watching = true
    }

    override fun onDestroy() {
        if (watching) {
            contentResolver.unregisterContentObserver(observer)
            handler.removeCallbacksAndMessages(null)
        }
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP) return false

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_ENABLED, false)) return false
        if (!isBtAudioConnected(this)) return false
        // 只在真正在播音乐时拦截，避免误伤铃声音量、通话音量
        if (!audio.isMusicActive) return false

        val cap = capIndex(audio, prefs)
        // 返回 true = 消费掉这个按键
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) >= cap
    }

    private fun checkAndClamp() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_ENABLED, false)) return
        if (!isBtAudioConnected(this)) return
        val cap = capIndex(audio, prefs)
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) > cap) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, cap, 0)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        private const val TICK_MS = 2000L
    }
}
