package com.benton.izukijs.controller.hid

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.benton.izukijs.core.log.LogBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import java.util.UUID

enum class HidConnectionState { IDLE, SCANNING, CONNECTING, CONNECTED, READY, ERROR }

data class HidDevice(val name: String, val address: String, val rssi: Int)

/**
 * BLE GATT 客户端：扫描、连接外部数位板狗、订阅事件、下发控制帧。
 */
class HidGattClient(
    private val context: Context,
    private val logBus: LogBus,
) {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _state = MutableStateFlow(HidConnectionState.IDLE)
    val state: StateFlow<HidConnectionState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<HidDevice>>(emptyList())
    val devices: StateFlow<List<HidDevice>> = _devices.asStateFlow()

    private val _connectedAddress = MutableStateFlow<String?>(null)
    val connectedAddress: StateFlow<String?> = _connectedAddress.asStateFlow()

    /** 固件回报的 HID 通道是否真正可用（链路已连 + 已加密）。 */
    private val _hidReady = MutableStateFlow(false)
    val hidReady: StateFlow<Boolean> = _hidReady.asStateFlow()

    private val _dongleResolution = MutableStateFlow<Pair<Int, Int>?>(null)
    val dongleResolution: StateFlow<Pair<Int, Int>?> = _dongleResolution.asStateFlow()

    /** 固件协议版本（来自 HANDSHAKE_ACK）；未握手为 0。 */
    @Volatile
    private var _protocolVersion = 0
    val protocolVersion: Int get() = _protocolVersion

    /** 固件是否支持 CMD_GESTURE（复杂轨迹 / 停顿）。 */
    fun supportsGesture(): Boolean = _protocolVersion >= HidProtocol.MIN_GESTURE_VERSION && maxPayload >= 15

    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var bluetoothGatt: BluetoothGatt? = null
    @Volatile private var controlChar: BluetoothGattCharacteristic? = null
    private var eventChar: BluetoothGattCharacteristic? = null

    private val link = HidLinkState()
    private var cccdWriteInFlight = false
    private var eventSubscribed = false
    private var servicesDiscovered = false
    private var discoveryInFlight = false
    private var handshakeReceived = false
    private var resolutionPending = false
    private var resolutionConfigured = false
    private var initializationDeadline = 0L
    @Volatile private var negotiatedMtu = 23
    val maxPayload: Int get() = minOf(260, negotiatedMtu - 3)

    /** 是否开启自动重连（连接过 / 记忆了设备后为 true）。看门狗据此保持重试。 */
    private val _autoReconnect = MutableStateFlow(false)
    val autoReconnect: StateFlow<Boolean> = _autoReconnect.asStateFlow()

    /** 是否处于「掉线后自动重连」过程，用于 UI 区分首次连接并显示「重连中」。 */
    private val _reconnecting = MutableStateFlow(false)
    val reconnecting: StateFlow<Boolean> = _reconnecting.asStateFlow()

    /** 一轮掉线周期内是否已告警过，避免自动重连失败时反复刷屏。连接成功或主动断开后重置。 */
    @Volatile
    private var retryLogged = false

    /** 用户主动扫描中时不执行自动重连，避免 connect() 把扫描打断。 */
    @Volatile
    private var userScanning = false

    private var scanGeneration = 0L
    private var activeScanCallback: ScanCallback? = null

    /** 按地址去重的扫描结果，仅在节流窗口结束后发布，避免每个广播包都重建并排序列表。 */
    private val discovered = LinkedHashMap<String, HidDevice>()

    @Volatile
    private var publishScheduled = false

    private val publishDevices = Runnable {
        publishScheduled = false
        _devices.value = synchronized(discovered) {
            discovered.values.sortedByDescending { it.rssi }
        }
    }

    /** 断开后仅记录一次，避免自动重连失败时反复刷屏。 */
    @Volatile
    private var disconnectLogged = false

    private var lastAddress: String? = null

    /** 最近一次发起连接的时刻，用于连接超时重试。 */
    @Volatile
    private var connectingSince = 0L

    val isReady: Boolean get() =
        _state.value == HidConnectionState.READY && _hidReady.value && link.fault == null && bluetoothGatt != null

    // ---- 权限 ----

    fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return has(Manifest.permission.BLUETOOTH_SCAN) && has(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return has(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    // ---- 扫描 ----

    @SuppressLint("MissingPermission")
    fun startScan() = onMain {
        if (bluetoothGatt != null) {
            logBus.warn("请先断开当前HID连接再扫描")
            return@onMain
        }
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || !hasPermissions()) {
            logBus.warn("无法扫描：蓝牙未开启或缺权限")
            return@onMain
        }
        stopScan()
        synchronized(discovered) { discovered.clear() }
        _devices.value = emptyList()
        addBondedDevices()
        val generation = ++scanGeneration
        val callback = scanCallback(generation)
        activeScanCallback = callback
        _state.value = HidConnectionState.SCANNING
        userScanning = true
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(null, settings, callback) }.onFailure {
            logBus.error("扫描失败: ${it.javaClass.simpleName}")
            stopScan()
        }
        handler.postDelayed({ if (scanGeneration == generation) stopScan() }, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() = onMain {
        val old = activeScanCallback
        activeScanCallback = null
        scanGeneration++
        userScanning = false
        handler.removeCallbacks(publishDevices)
        publishScheduled = false
        if (hasPermissions() && old != null) runCatching { adapter?.bluetoothLeScanner?.stopScan(old) }
        if (_state.value == HidConnectionState.SCANNING) _state.value = HidConnectionState.IDLE
    }

    /** 已配对过的狗即使当前没在广播（被系统 HID 主机占用连接）也能直接连接。 */
    @SuppressLint("MissingPermission")
    private fun addBondedDevices() {
        if (!hasPermissions()) return
        val bonded = runCatching { adapter?.bondedDevices }.getOrNull() ?: return
        synchronized(discovered) {
            for (device in bonded) {
                val name = runCatching { device.name }.getOrNull() ?: continue
                if (!name.startsWith(DEVICE_NAME_PREFIX, ignoreCase = true)) continue
                if (!discovered.containsKey(device.address)) {
                    discovered[device.address] = HidDevice(name, device.address, 0)
                }
            }
        }
        handler.removeCallbacks(publishDevices)
        publishScheduled = false
        publishDevices.run()
    }

    private fun scanCallback(generation: Long) = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) = onMain {
            if (generation != scanGeneration || !userScanning || _state.value != HidConnectionState.SCANNING) return@onMain
            val device = result.device ?: return@onMain
            val name = runCatching { device.name }.getOrNull() ?: "未知设备"
            val matches = result.scanRecord?.serviceUuids?.any { it.uuid == HidProtocol.SERVICE_UUID } == true ||
                name.startsWith(DEVICE_NAME_PREFIX, ignoreCase = true)
            if (!matches) return@onMain
            synchronized(discovered) { discovered[device.address] = HidDevice(name, device.address, result.rssi) }
            schedulePublish()
        }

        override fun onScanFailed(errorCode: Int) = onMain {
            if (generation != scanGeneration || !userScanning) return@onMain
            logBus.error("扫描失败，错误码 $errorCode")
            stopScan()
        }
    }

    private fun schedulePublish() {
        if (publishScheduled) return
        publishScheduled = true
        handler.postDelayed(publishDevices, PUBLISH_THROTTLE_MS)
    }

    // All Android GATT state transitions run on the main looper. Queue admission
    // is synchronized separately and carries the session it was admitted under.
    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == handler.looper) action() else handler.post { action() }
    }

    private fun later(epoch: Long, delayMs: Long, action: () -> Unit) {
        handler.postDelayed({ if (link.session == epoch) action() }, delayMs)
    }

    private fun fromGatt(epoch: Long, gatt: BluetoothGatt, action: () -> Unit) {
        handler.post {
            if (link.session == epoch && bluetoothGatt === gatt) action()
            else logBus.debug("HID 忽略旧连接回调 session=$epoch")
        }
    }

    fun connect(address: String) = onMain {
        _reconnecting.value = false
        startConnect(address)
    }

    @SuppressLint("MissingPermission")
    private fun startConnect(address: String) {
        if (!hasPermissions() || !isBluetoothEnabled()) {
            closeGatt()
            _state.value = HidConnectionState.IDLE
            return
        }
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return
        closeGatt()
        _autoReconnect.value = true
        lastAddress = address
        stopScan()
        connectingSince = SystemClock.elapsedRealtime()
        initializationDeadline = connectingSince + CONNECT_TIMEOUT_MS
        _state.value = HidConnectionState.CONNECTING
        val epoch = link.session
        runCatching {
            bluetoothGatt = device.connectGatt(context, false, callback(epoch), BluetoothDevice.TRANSPORT_LE)
            if (bluetoothGatt == null) failLink("connect_rejected")
        }.onFailure { failLink("connect_exception:${it.javaClass.simpleName}") }
        later(epoch, CONNECT_TIMEOUT_MS) {
            if (!isReady && initializationDeadline != 0L) failLink("initialization_timeout")
        }
    }

    fun disconnect() = onMain {
        _autoReconnect.value = false
        lastAddress = null
        _reconnecting.value = false
        closeGatt()
        _state.value = HidConnectionState.IDLE
    }

    fun reconnectIfNeeded() = onMain {
        if (!userScanning && _autoReconnect.value && bluetoothGatt == null) {
            lastAddress?.let { _reconnecting.value = true; startConnect(it) }
        }
    }

    fun setLastAddress(address: String?) = onMain {
        lastAddress = address
        _autoReconnect.value = address != null
    }

    fun ensureConnected() = onMain {
        if (!_autoReconnect.value || userScanning) return@onMain
        if (!hasPermissions() || !isBluetoothEnabled()) {
            if (bluetoothGatt != null) closeGatt()
            _state.value = HidConnectionState.IDLE
            return@onMain
        }
        val now = SystemClock.elapsedRealtime()
        val fault = link.check(now)
        if (fault != null) { failLink(fault); return@onMain }
        when (_state.value) {
            HidConnectionState.IDLE, HidConnectionState.ERROR -> lastAddress?.let { startConnect(it) }
            HidConnectionState.CONNECTING, HidConnectionState.CONNECTED -> {
                if (initializationDeadline != 0L && now >= initializationDeadline) failLink("initialization_timeout")
            }
            HidConnectionState.READY -> {
                val realtime = protocolVersion >= 3
                if (link.shouldProbe(now, realtime)) {
                    val epoch = link.session
                    if (link.enqueueProbe(epoch, now, realtime)) drainWrites(epoch)
                }
            }
            else -> Unit
        }
    }

    @SuppressLint("MissingPermission")
    private fun discover(gatt: BluetoothGatt) {
        if (servicesDiscovered || discoveryInFlight) return
        discoveryInFlight = true
        if (!runCatching { gatt.discoverServices() }.getOrDefault(false)) failLink("discovery_rejected")
    }

    @SuppressLint("MissingPermission")
    private fun subscribeEvent() {
        if (eventSubscribed || cccdWriteInFlight) return
        val gatt = bluetoothGatt ?: return
        val event = eventChar ?: return
        val descriptor = event.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
        if (descriptor == null || !gatt.setCharacteristicNotification(event, true)) {
            failLink("subscription_unavailable"); return
        }
        cccdWriteInFlight = true
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }.getOrDefault(false)
        if (!started) { failLink("subscription_rejected"); return }
        later(link.session, SUBSCRIBE_TIMEOUT_MS) {
            if (cccdWriteInFlight) failLink("subscription_timeout")
        }
    }

    private fun updateReady() {
        if (servicesDiscovered && eventSubscribed && handshakeReceived && _hidReady.value) {
            // Initialization must enter the firmware FIFO before business input.
            if (!resolutionConfigured) {
                if (!resolutionPending) {
                    val metrics = context.resources.displayMetrics
                    resolutionPending = true
                    if (!sendInternal(HidProtocol.setResolution(metrics.widthPixels, metrics.heightPixels))) {
                        failLink("resolution_setup_rejected")
                    }
                }
                return
            }
            initializationDeadline = 0
            if (_state.value != HidConnectionState.READY) logBus.success("HID 会话就绪 session=${link.session}（输入执行须另行核对）")
            _state.value = HidConnectionState.READY
            _reconnecting.value = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun failLink(reason: String) {
        logBus.error("HID_TRANSPORT_FAULT session=${link.session} reason=$reason；撤销就绪，丢弃待发命令，不重放输入")
        link.fail(reason)
        val address = lastAddress
        closeGatt()
        _state.value = HidConnectionState.ERROR
        _reconnecting.value = _autoReconnect.value
        val epoch = link.session
        if (_autoReconnect.value && address != null) later(epoch, RECONNECT_DELAY_MS) {
            if (_autoReconnect.value && !userScanning && lastAddress == address) startConnect(address)
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        val old = bluetoothGatt
        // Invalidate before disconnect: even a synchronous old callback is stale.
        bluetoothGatt = null
        link.reset(SystemClock.elapsedRealtime())
        controlChar = null; eventChar = null
        eventSubscribed = false; servicesDiscovered = false; discoveryInFlight = false
        cccdWriteInFlight = false; handshakeReceived = false
        resolutionPending = false; resolutionConfigured = false
        initializationDeadline = 0; negotiatedMtu = 23
        _hidReady.value = false; _protocolVersion = 0
        _dongleResolution.value = null; _connectedAddress.value = null
        runCatching { old?.disconnect() }
        runCatching { old?.close() }
    }

    private fun callback(epoch: Long) = object : BluetoothGattCallback() {
        override fun onServiceChanged(gatt: BluetoothGatt) = fromGatt(epoch, gatt) {
            failLink("services_changed")
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = fromGatt(epoch, gatt) {
            if (status != BluetoothGatt.GATT_SUCCESS) { failLink("connection_status:$status"); return@fromGatt }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (_state.value != HidConnectionState.CONNECTING) return@fromGatt
                    _state.value = HidConnectionState.CONNECTED
                    _connectedAddress.value = gatt.device.address
                    logBus.info("HID GATT已连接 session=$epoch，协商MTU")
                    if (!runCatching { gatt.requestMtu(517) }.getOrDefault(false)) discover(gatt)
                    // A lost MTU callback must not strand the CONNECTED state.
                    later(epoch, MTU_TIMEOUT_MS) { if (!servicesDiscovered && !discoveryInFlight) discover(gatt) }
                }
                BluetoothProfile.STATE_DISCONNECTED -> failLink("disconnected")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = fromGatt(epoch, gatt) {
            if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu.coerceAtLeast(23)
            discover(gatt)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = fromGatt(epoch, gatt) {
            discoveryInFlight = false
            if (servicesDiscovered) return@fromGatt
            if (status != BluetoothGatt.GATT_SUCCESS) { failLink("discovery_status:$status"); return@fromGatt }
            val service = gatt.getService(HidProtocol.SERVICE_UUID)
            controlChar = service?.getCharacteristic(HidProtocol.CONTROL_UUID)
            eventChar = service?.getCharacteristic(HidProtocol.EVENT_UUID)
            if (controlChar == null || eventChar == null) { failLink("missing_characteristic"); return@fromGatt }
            if ((controlChar!!.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) {
                failLink("write_response_unsupported"); return@fromGatt
            }
            servicesDiscovered = true
            later(epoch, 150) { subscribeEvent() }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = characteristic.value?.copyOf() ?: return
            if (characteristic.uuid == HidProtocol.EVENT_UUID) fromGatt(epoch, gatt) { handleEvent(value) }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val copy = value.copyOf()
            if (characteristic.uuid == HidProtocol.EVENT_UUID) fromGatt(epoch, gatt) { handleEvent(copy) }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = fromGatt(epoch, gatt) {
            if (characteristic.uuid != HidProtocol.CONTROL_UUID) return@fromGatt
            val write = link.inFlight ?: return@fromGatt
            if (!link.complete(epoch, write.id, status == BluetoothGatt.GATT_SUCCESS, "write_status:$status")) return@fromGatt
            if (status != BluetoothGatt.GATT_SUCCESS) { failLink("write_status:$status id=${write.id}"); return@fromGatt }
            if (resolutionPending && write.payload.firstOrNull()?.toInt() == HidProtocol.CMD_SET_RESOLUTION) {
                resolutionPending = false
                resolutionConfigured = true
                updateReady()
            }
            logBus.debug("HID_TX_TRANSPORT_COMPLETE session=$epoch id=${write.id}（非输入执行确认）")
            drainWrites(epoch)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = fromGatt(epoch, gatt) {
            if (descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIG || descriptor.characteristic.uuid != HidProtocol.EVENT_UUID || !cccdWriteInFlight) return@fromGatt
            cccdWriteInFlight = false
            if (status != BluetoothGatt.GATT_SUCCESS) { failLink("subscription_status:$status"); return@fromGatt }
            eventSubscribed = true
            logBus.info("HID 事件通道已订阅 session=$epoch")
            sendInternal(HidProtocol.handshake())
        }
    }

    private fun handleEvent(data: ByteArray) {
        when (HidProtocol.kind(data)) {
            HidProtocol.EVT_HANDSHAKE_ACK -> HidProtocol.parseHandshakeAck(data)?.let {
                _protocolVersion = it.version
                _dongleResolution.value = it.width to it.height
                handshakeReceived = true
                logBus.info("HID 固件v${it.version}，数位板 ${it.width}x${it.height}")
                updateReady()
            }
            HidProtocol.EVT_STATUS -> HidProtocol.parseStatus(data)?.let {
                val ready = it.linkUp && it.encrypted && it.hidReady
                if (isReady && !ready) { failLink("firmware_not_ready"); return }
                _hidReady.value = ready
                logBus.info("HID 状态: 链路=${it.linkUp} 加密=${it.encrypted} HID就绪=$ready App=${it.appReady}")
                updateReady()
            }
            HidProtocol.EVT_PONG -> if (data.size == 1) {
                link.pong(SystemClock.elapsedRealtime())
                logBus.debug("HID_HEARTBEAT_PONG session=${link.session}")
            }
            HidProtocol.EVT_ERROR -> HidProtocol.parseError(data)?.let { failLink("firmware_error:$it ${HidProtocol.errorText(it)}") }
        }
    }

    /** true means accepted into this session's bounded queue, never execution success. */
    fun send(payload: ByteArray): Boolean = sendBatch(listOf(payload))

    fun sendBatch(frames: List<ByteArray>): Boolean {
        val epoch = link.session
        if (!isReady || !hasPermissions() || frames.isEmpty() || frames.any { it.isEmpty() || it.size > maxPayload }) return false
        val now = SystemClock.elapsedRealtime()
        if (link.check(now) != null) { onMain { if (link.session == epoch) failLink(link.fault ?: "expired") }; return false }
        if (!link.enqueue(epoch, frames, now)) return false
        scheduleAdmittedBatch(epoch, frames.size)
        return true
    }

    /** Explicit transport receipt. No firmware execution acknowledgement is implied. */
    fun submitBatch(frames: List<ByteArray>): HidBatchReceipt {
        val epoch = link.session
        val rejection = when {
            !isReady -> "not_ready"
            !hasPermissions() -> "missing_permission"
            frames.any { it.isEmpty() || it.size > maxPayload } -> "invalid_payload"
            else -> null
        }
        if (rejection != null) return link.reject(epoch, frames.size, rejection)
        val now = SystemClock.elapsedRealtime()
        if (link.check(now) != null) {
            onMain { if (link.session == epoch) failLink(link.fault ?: "expired") }
        }
        val receipt = link.submit(epoch, frames, now)
        if (receipt.accepted) scheduleAdmittedBatch(epoch, frames.size, receipt.id)
        return receipt
    }

    fun rejectBatch(frameCount: Int, reason: String): HidBatchReceipt =
        link.reject(link.session, frameCount, reason)

    fun receipt(id: String): HidBatchReceipt? {
        val epoch = link.session
        link.check(SystemClock.elapsedRealtime())?.let { fault ->
            onMain { if (link.session == epoch) failLink(fault) }
        }
        return link.receipt(id)
    }

    private fun scheduleAdmittedBatch(epoch: Long, frameCount: Int, batchId: String? = null) {
        logBus.debug("HID_TX_ACCEPTED session=$epoch frames=$frameCount batch=${batchId ?: "untracked"}（仅入队）")
        onMain { if (link.session == epoch) drainWrites(epoch) }
        later(epoch, WRITE_TIMEOUT_MS) { link.check(SystemClock.elapsedRealtime())?.let { failLink(it) } }
    }

    private fun sendInternal(payload: ByteArray): Boolean {
        val epoch = link.session
        if (bluetoothGatt == null || !eventSubscribed || !link.enqueue(epoch, listOf(payload), SystemClock.elapsedRealtime())) return false
        drainWrites(epoch)
        return link.session == epoch
    }

    @SuppressLint("MissingPermission")
    private fun drainWrites(epoch: Long) {
        if (link.session != epoch || cccdWriteInFlight) return
        val fault = link.check(SystemClock.elapsedRealtime())
        if (fault != null) { failLink(fault); return }
        val gatt = bluetoothGatt ?: return
        val control = controlChar ?: return
        val write = link.take(SystemClock.elapsedRealtime()) ?: return
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(control, write.payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                control.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                control.value = write.payload
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(control)
            }
        }.getOrDefault(false)
        if (!ok) { link.complete(epoch, write.id, false, "write_rejected"); failLink("write_rejected id=${write.id}"); return }
        write.realtimeProbe?.let { realtime ->
            link.probeSent(SystemClock.elapsedRealtime(), realtime)
            later(epoch, HEARTBEAT_TIMEOUT_MS) { link.check(SystemClock.elapsedRealtime())?.let { failLink(it) } }
        }
        later(epoch, WRITE_TIMEOUT_MS) {
            if (link.inFlight?.id == write.id) link.check(SystemClock.elapsedRealtime())?.let { failLink(it) }
        }
    }

    companion object {
        private val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val MTU_TIMEOUT_MS = 3_000L
        private const val SUBSCRIBE_TIMEOUT_MS = 1_500L
        private const val WRITE_TIMEOUT_MS = 2_000L
        private const val HEARTBEAT_TIMEOUT_MS = 5_000L
        private const val SCAN_TIMEOUT_MS = 20_000L
        private const val PUBLISH_THROTTLE_MS = 400L
        private const val DEVICE_NAME_PREFIX = "Izuki"
    }
}
