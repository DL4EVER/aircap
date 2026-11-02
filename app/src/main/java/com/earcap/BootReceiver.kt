package com.earcap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机自启：开关是开的就直接把钳制服务拉起来 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 只恢复可选的前台服务；主载体无障碍服务由系统自己管理
        if (prefs.getBoolean(PREF_FGS, false)) {
            context.startForegroundService(Intent(context, VolumeClampService::class.java))
        }
    }
}
