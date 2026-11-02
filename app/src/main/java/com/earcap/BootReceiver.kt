package com.earcap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机自启：开关是开的就直接把钳制服务拉起来 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(PREF_ENABLED, false)) {
            context.startForegroundService(Intent(context, VolumeClampService::class.java))
        }
    }
}
