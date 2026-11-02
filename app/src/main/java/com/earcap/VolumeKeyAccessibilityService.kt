package com.earcap

import android.accessibilityservice.AccessibilityService
import android.media.AudioManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务：拦截手机侧的音量上键。
 *
 * 媒体音量已到上限时，直接「吃掉」音量+按键——按了没反应，
 * 而不是「跳上去再被服务弹回来」。音量下键不受影响。
 *
 * 需要用户在系统设置里手动开启本无障碍服务。
 * 耳机上按的音量键走 AVRCP，不是按键事件，由钳制服务负责。
 */
class VolumeKeyAccessibilityService : AccessibilityService() {

    private val audio: AudioManager by lazy {
        getSystemService(AUDIO_SERVICE) as AudioManager
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
