package com.earcap

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import android.media.AudioManager

// 全局配置（个人小工具，用 SharedPreferences 足够）
const val PREFS = "config"
const val PREF_ENABLED = "enabled"
const val PREF_CAP_PERCENT = "cap_percent"
const val DEFAULT_CAP_PERCENT = 40

/** 根据用户设置的上限百分比，换算成媒体流的音量档位 */
fun capIndex(audio: AudioManager, prefs: SharedPreferences): Int {
    val percent = prefs.getInt(PREF_CAP_PERCENT, DEFAULT_CAP_PERCENT)
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    return (max * percent / 100).coerceIn(0, max)
}

/**
 * 是否有蓝牙音频设备正在输出。
 * 用 AudioManager.getDevices() 检测，不需要任何蓝牙运行时权限。
 * 现代蓝牙耳机走 AVRCP 绝对音量：媒体流音量 == 耳机内部音量，
 * 所以钳住手机侧媒体流，就等于钳住了耳机实际输出的响度。
 */
fun isBtAudioConnected(context: Context): Boolean {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }
}
