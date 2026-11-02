package com.earcap

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.IOException

/** AirPods 降噪模式（对应 AAP LISTEN_MODE 设置，ID 0x0D） */
enum class AncMode(val wire: Int, val label: String) {
    OFF(0x01, "关闭"),
    ANC(0x02, "降噪"),
    TRANSPARENCY(0x03, "通透"),
    ADAPTIVE(0x04, "自适应"),
}

/**
 * AirPods 控制（AAP 协议，逆向自开源项目 CAPod，MIT）。
 *
 * 原理：AirPods 在配对状态下额外监听一个经典蓝牙 L2CAP 固定通道（PSM 0x1001），
 * 走苹果私有的 AAP/AACP 协议。连上后发握手包，即可用明文设置命令控制降噪模式。
 * 整个过程不需要 root，也不需要苹果的密钥（密钥只用于解密 BLE 广播的精确电量）。
 *
 * 兼容性：依赖 Android 10+ 的公开 API createInsecureL2capChannel()。
 * 部分厂商 ROM 可能阉割该通道，需实测。
 */
class AirPodsController(private val context: Context) {

    private var socket: BluetoothSocket? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    var connected = false
        private set

    @Volatile
    var deviceName: String? = null
        private set

    /** 从已配对设备里找 AirPods / Beats（两者的 AAP 协议通用） */
    fun findAirPods(): BluetoothDevice? {
        if (!hasConnectPermission()) return null
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
        return runCatching {
            adapter.bondedDevices?.firstOrNull {
                val n = it.name?.lowercase() ?: ""
                n.contains("airpods") || n.contains("beats")
            }
        }.getOrNull()
    }

    fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

    /** 后台线程连接 + 握手；结果回调到主线程 */
    fun connect(callback: (Boolean, String) -> Unit) {
        if (connected) {
            callback(true, "已连接 ${deviceName ?: ""}")
            return
        }
        if (!hasConnectPermission()) {
            callback(false, "缺少蓝牙权限")
            return
        }
        val device = findAirPods()
        if (device == null) {
            callback(false, "没找到已配对的 AirPods（请先在系统蓝牙里配对）")
            return
        }
        if (Build.VERSION.SDK_INT < 29) {
            callback(false, "需要 Android 10 及以上系统")
            return
        }
        // AirPods 在盒子里 / 未连接时，AAP 通道不会建立 —— 先拦下来给明确提示
        if (!isBtAudioConnected(context)) {
            callback(false, "耳机未处于连接状态：请先戴上 AirPods、播放音乐，再点连接")
            return
        }
        Thread {
            try {
                val sock = openSocket(device)
                sock.outputStream.write(HANDSHAKE)
                sock.outputStream.flush()
                // 等设备完成会话建立，再发通知使能（可选）与扩展初始化
                Thread.sleep(300)
                sock.outputStream.write(NOTIF_ENABLE_1)
                sock.outputStream.write(NOTIF_ENABLE_2)
                sock.outputStream.write(INIT_EXT)
                sock.outputStream.flush()
                socket = sock
                deviceName = runCatching { device.name }.getOrNull()
                connected = true
                startReadLoop(sock)
                handler.post { callback(true, "已连接 ${deviceName ?: "AirPods"}") }
            } catch (e: Exception) {
                runCatching { socket?.close() }
                socket = null
                connected = false
                val reason = when (e) {
                    is java.net.SocketTimeoutException -> "连接超时（耳机可能未连接，或系统屏蔽了蓝牙底层通道）"
                    is SecurityException -> "权限被拒绝"
                    else -> e.message ?: e.javaClass.simpleName
                }
                handler.post { callback(false, "连接失败：$reason") }
            }
        }.start()
    }

    /**
     * 建立 L2CAP 连接。先尝试不加密通道，失败再尝试需认证的通道
     * （个别 ROM 对 insecure 通道的处理有差异，两条路都试提高成功率）。
     * 每次 connect 限时 8 秒，避免卡在系统默认的长超时。
     */
    private fun openSocket(device: BluetoothDevice): BluetoothSocket {
        var last: Exception? = null
        for (secure in listOf(false, true)) {
            val sock = try {
                if (secure) device.createL2capChannel(PSM)
                else device.createInsecureL2capChannel(PSM)
            } catch (e: Exception) {
                last = e
                continue
            }
            try {
                connectWithTimeout(sock, 8000)
                return sock
            } catch (e: Exception) {
                last = e
                runCatching { sock.close() }
            }
        }
        throw last ?: IOException("L2CAP 连接失败")
    }

    private fun connectWithTimeout(sock: BluetoothSocket, timeoutMs: Long) {
        var err: Exception? = null
        val t = Thread {
            try {
                sock.connect()
            } catch (e: Exception) {
                err = e
            }
        }
        t.start()
        t.join(timeoutMs)
        if (t.isAlive) {
            runCatching { sock.close() }
            throw java.net.SocketTimeoutException("L2CAP connect timed out")
        }
        err?.let { throw it }
    }

    /** 发送降噪模式切换命令 */
    fun setMode(mode: AncMode, callback: (Boolean, String) -> Unit) {
        val sock = socket
        if (!connected || sock == null) {
            callback(false, "未连接 AirPods")
            return
        }
        Thread {
            try {
                sock.outputStream.write(settingsMessage(SETTING_LISTEN_MODE, mode.wire))
                sock.outputStream.flush()
                handler.post { callback(true, "已切换：${mode.label}") }
            } catch (e: Exception) {
                handler.post { callback(false, "发送失败：${e.message ?: e.javaClass.simpleName}") }
            }
        }.start()
    }

    /** 读循环：保持连接活性，设备主动断开时同步状态 */
    private fun startReadLoop(sock: BluetoothSocket) {
        Thread {
            val buf = ByteArray(1024)
            try {
                while (true) {
                    if (sock.inputStream.read(buf) <= 0) break
                }
            } catch (_: IOException) {
            } finally {
                connected = false
                socket = null
            }
        }.apply { isDaemon = true }.start()
    }

    fun close() {
        connected = false
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        /** AirPods AAP 的 L2CAP 固定通道 */
        private const val PSM = 0x1001
        /** AAP 设置 ID：LISTEN_MODE（降噪模式） */
        private const val SETTING_LISTEN_MODE = 0x0D

        /** 会话握手 */
        private val HANDSHAKE = byteArrayOf(
            0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )

        /** 通知使能（让设备推送状态变化） */
        private val NOTIF_ENABLE_1 = byteArrayOf(
            0x04, 0x00, 0x04, 0x00, 0x0F, 0x00,
            0xFF.toByte(), 0xFF.toByte(), 0xEF.toByte(), 0xFF.toByte()
        )
        private val NOTIF_ENABLE_2 = byteArrayOf(
            0x04, 0x00, 0x04, 0x00, 0x0F, 0x00,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()
        )

        /** 扩展初始化（不同固件需要，CAPod 抓包验证过） */
        private val INIT_EXT = byteArrayOf(
            0x04, 0x00, 0x04, 0x00, 0x4D, 0x00, 0xD7.toByte(), 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )

        /** 设置命令帧：Message 包 + CONTROL 命令 + 设置 ID + 值 */
        private fun settingsMessage(settingId: Int, value: Int): ByteArray = byteArrayOf(
            0x04, 0x00, 0x04, 0x00, 0x09, 0x00,
            settingId.toByte(), value.toByte(), 0x00, 0x00, 0x00
        )
    }
}
