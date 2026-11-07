package com.benton.izukijs.controller.hid

import android.content.Context
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.core.log.LogBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 蓝牙 HID 生命周期管理。连接就绪时注册 HidController，并同步数位板分辨率。
 */
class HidManager(
    private val context: Context,
    private val controllers: ControllerManager,
    private val logBus: LogBus,
    private val scope: CoroutineScope,
) {

    val client = HidGattClient(context, logBus)

    private val controller = HidController(client, logBus)

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init() {
        val lastAddress = prefs.getString(KEY_LAST_ADDRESS, null)
        if (lastAddress != null) client.setLastAddress(lastAddress)

        scope.launch {
            client.state.collectLatest { state ->
                if (state == HidConnectionState.READY) {
                    controllers.register(controller)
                    // Client completes encrypted resolution setup before exposing READY.
                } else {
                    controllers.unregister(ControlMode.HID)
                }
            }
        }

        // hidReady 依赖加密 + 系统 HID 主机订阅报表，比 state==READY 晚置位。
        // 它变化时必须主动 refresh，否则设置页 / 运行页一直显示「未就绪」。
        scope.launch {
            client.hidReady.collect { ready ->
                controllers.refresh()
                // Connection setup owns the handshake. In firmware v3 it is a
                // cancellation barrier, so never repeat it after READY.
            }
        }

        // 看门狗：狗刚通电 / 回调丢失时，主动重连，保证「其他流程」能用上 HID。
        scope.launch {
            while (true) {
                delay(WATCHDOG_INTERVAL_MS)
                client.ensureConnected()
            }
        }

        if (lastAddress != null && client.hasPermissions()) {
            scope.launch(Dispatchers.IO) { runCatching { client.reconnectIfNeeded() } }
        }
    }

    fun connect(address: String) {
        prefs.edit().putString(KEY_LAST_ADDRESS, address).apply()
        client.setLastAddress(address)
        client.connect(address)
    }

    fun disconnect() = client.disconnect()

    /** 撤回 HID：断开并清除记忆的设备。 */
    fun revoke() {
        prefs.edit().remove(KEY_LAST_ADDRESS).apply()
        client.disconnect()
    }

    fun startScan() = client.startScan()

    fun stopScan() = client.stopScan()

    fun syncResolution() {
        val metrics = context.resources.displayMetrics
        client.send(HidProtocol.setResolution(metrics.widthPixels, metrics.heightPixels))
    }

    private companion object {
        const val PREFS = "izukijs_hid"
        const val KEY_LAST_ADDRESS = "last_address"
        const val WATCHDOG_INTERVAL_MS = 3000L
    }
}
