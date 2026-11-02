package com.earcap

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var audio: AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var tvStatus: TextView
    private lateinit var tvCurrentVol: TextView
    private lateinit var tvCapValue: TextView
    private lateinit var swEnabled: Switch
    private lateinit var sbCap: SeekBar

    // 只用来刷新界面上的"当前音量"显示
    private val volumeObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            runOnUiThread { refreshStatus() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager

        tvStatus = findViewById(R.id.tvStatus)
        tvCurrentVol = findViewById(R.id.tvCurrentVol)
        tvCapValue = findViewById(R.id.tvCapValue)
        swEnabled = findViewById(R.id.swEnabled)
        sbCap = findViewById(R.id.sbCap)

        swEnabled.isChecked = prefs.getBoolean(PREF_ENABLED, false)
        swEnabled.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_ENABLED, checked).apply()
            applyServiceState()
            refreshStatus()
        }

        sbCap.min = 5
        sbCap.progress = prefs.getInt(PREF_CAP_PERCENT, DEFAULT_CAP_PERCENT)
        tvCapValue.text = capText()
        sbCap.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                prefs.edit().putInt(PREF_CAP_PERCENT, value).apply()
                tvCapValue.text = capText()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                clampNow()
            }
        })

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (_: Exception) {
                }
            }
        }

        requestNotificationPermissionIfNeeded()
        applyServiceState()
    }

    private fun capText() = "音量上限：${prefs.getInt(PREF_CAP_PERCENT, DEFAULT_CAP_PERCENT)}%"

    private fun clampNow() {
        if (!prefs.getBoolean(PREF_ENABLED, false)) return
        if (!isBtAudioConnected(this)) return
        val cap = capIndex(audio, prefs)
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) > cap) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, cap, 0)
        }
    }

    private fun applyServiceState() {
        if (prefs.getBoolean(PREF_ENABLED, false)) {
            startForegroundService(Intent(this, VolumeClampService::class.java))
        } else {
            stopService(Intent(this, VolumeClampService::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
        refreshStatus()
    }

    override fun onPause() {
        contentResolver.unregisterContentObserver(volumeObserver)
        super.onPause()
    }

    private fun refreshStatus() {
        val enabled = prefs.getBoolean(PREF_ENABLED, false)
        val bt = isBtAudioConnected(this)
        tvStatus.text = listOf(
            "限制开关：" + if (enabled) "已启用" else "已关闭",
            "蓝牙耳机：" + if (bt) "已连接" else "未连接"
        ).joinToString("\n")
        tvCurrentVol.text =
            "当前媒体音量：${audio.getStreamVolume(AudioManager.STREAM_MUSIC)} / " +
                    audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
