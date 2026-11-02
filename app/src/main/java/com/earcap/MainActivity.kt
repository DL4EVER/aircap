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
    private lateinit var swFgs: Switch
    private lateinit var tvAirpodsStatus: TextView
    private lateinit var btnAirpodsConnect: Button
    private lateinit var airpods: AirPodsController
    private val modeButtons = mutableListOf<Pair<Button, AncMode>>()

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

        swFgs = findViewById(R.id.swFgs)
        swFgs.isChecked = prefs.getBoolean(PREF_FGS, false)
        swFgs.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_FGS, checked).apply()
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
        findViewById<Button>(R.id.btnAppInfo).setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
            }
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

        setupAirPodsUi()
        requestNotificationPermissionIfNeeded()
        applyServiceState()
    }

    private fun setupAirPodsUi() {
        airpods = AirPodsController(this)
        tvAirpodsStatus = findViewById(R.id.tvAirpodsStatus)
        btnAirpodsConnect = findViewById(R.id.btnAirpodsConnect)

        modeButtons += findViewById<Button>(R.id.btnAnc) to AncMode.ANC
        modeButtons += findViewById<Button>(R.id.btnAdaptive) to AncMode.ADAPTIVE
        modeButtons += findViewById<Button>(R.id.btnTransparency) to AncMode.TRANSPARENCY
        modeButtons += findViewById<Button>(R.id.btnOff) to AncMode.OFF
        modeButtons.forEach { (btn, mode) ->
            btn.setOnClickListener {
                if (!airpods.connected) {
                    toast("请先连接 AirPods")
                    return@setOnClickListener
                }
                airpods.setMode(mode) { ok, msg ->
                    if (!ok) toast(msg) else highlightMode(mode)
                }
            }
        }

        btnAirpodsConnect.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BT)
                return@setOnClickListener
            }
            connectAirpods()
        }
        refreshAirpodsStatus()
    }

    private fun connectAirpods() {
        tvAirpodsStatus.text = "正在连接…"
        airpods.connect { ok, msg ->
            refreshAirpodsStatus()
            toast(msg)
            if (!ok) tvAirpodsStatus.text = msg
        }
    }

    private fun refreshAirpodsStatus() {
        val connected = airpods.connected
        tvAirpodsStatus.text = if (connected) {
            "已连接：${airpods.deviceName ?: "AirPods"}"
        } else {
            "未连接"
        }
        btnAirpodsConnect.text = if (connected) "重新连接" else "连接 AirPods"
        modeButtons.forEach { (btn, _) -> btn.isEnabled = connected }
    }

    private fun highlightMode(mode: AncMode) {
        modeButtons.forEach { (btn, m) ->
            btn.alpha = if (m == mode) 1f else 0.5f
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BT && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            connectAirpods()
        }
    }

    override fun onDestroy() {
        airpods.close()
        super.onDestroy()
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
        // 前台服务只是可选双保险，默认不开；主力是无障碍服务
        if (prefs.getBoolean(PREF_FGS, false)) {
            startForegroundService(Intent(this, VolumeClampService::class.java))
        } else {
            stopService(Intent(this, VolumeClampService::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
        // 若服务被系统清理，打开/回到本应用时自动拉起（自愈）
        applyServiceState()
        refreshStatus()
        refreshAirpodsStatus()
    }

    override fun onPause() {
        contentResolver.unregisterContentObserver(volumeObserver)
        super.onPause()
    }

    private fun refreshStatus() {
        val enabled = prefs.getBoolean(PREF_ENABLED, false)
        val fgsOn = prefs.getBoolean(PREF_FGS, false)
        val bt = isBtAudioConnected(this)
        val svc = when {
            !fgsOn -> "未启用（可选）"
            VolumeClampService.isRunning -> "运行中"
            else -> "已停止（回到本界面已自动恢复）"
        }
        // 心跳诊断：无障碍服务在后台是否被冻结/清理过（展示后清零，统计下一段）
        val maxGap = prefs.getLong(PREF_MAX_GAP, 0L)
        prefs.edit().putLong(PREF_MAX_GAP, 0L).apply()
        val gapText = if (maxGap <= 10000) {
            "无断流（服务存活）"
        } else {
            val m = maxGap / 60000
            val s = maxGap % 60000 / 1000
            "断流 ${m}分${s}秒 —— 后台被冻结/清理过，去电池设置允许后台运行"
        }

        tvStatus.text = listOf(
            "限制开关：" + if (enabled) "已启用" else "已关闭",
            "无障碍拦截：" + if (isAccessibilityEnabled(this)) "已开启" else "未开启（点下方按钮去开）",
            "后台存活检测：$gapText",
            "前台服务：$svc",
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

    companion object {
        private const val REQUEST_BT = 2
    }
}
