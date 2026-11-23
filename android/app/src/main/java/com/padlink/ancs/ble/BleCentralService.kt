package com.padlink.ancs.ble

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.padlink.ancs.R
import com.padlink.ancs.model.AppNotification
import com.padlink.ancs.model.NotificationStore
import com.padlink.ancs.notif.NotifForwarder
import com.padlink.ancs.prefs.Prefs
import com.padlink.ancs.ui.MainActivity

/**
 * BLE 中央设备前台服务（foregroundServiceType="connectedDevice"）。
 *
 * 职责：扫描（仅首次配对）→ createBond 配对 → 按已保存地址 connectGatt →
 * discoverServices → 串行订阅（先 Data Source 后 Notification Source，
 * 以 onDescriptorWrite GATT_SUCCESS 为步进信号）→ ANCS 会话（见 [AncsSession]）。
 * 断线指数退避自动重连（5s 起 ×2，上限 60s，仅蓝牙开启且屏幕亮时尝试）。
 */
class BleCentralService : Service() {

    companion object {
        private const val TAG = "BleCentralService"

        // —— Intent 动作与参数（UI/通知按钮 → 服务） ——
        const val ACTION_START_SCAN = "com.padlink.ancs.action.START_SCAN"
        const val ACTION_STOP_SCAN = "com.padlink.ancs.action.STOP_SCAN"
        const val ACTION_CONNECT = "com.padlink.ancs.action.CONNECT"
        const val ACTION_CONNECT_SAVED = "com.padlink.ancs.action.CONNECT_SAVED"
        const val ACTION_DISCONNECT = "com.padlink.ancs.action.DISCONNECT"
        const val ACTION_PERFORM = "com.padlink.ancs.action.PERFORM"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_NAME = "name"
        const val EXTRA_BOND = "bond"
        const val EXTRA_UID = "uid"
        const val EXTRA_ACTION_ID = "action_id"

        private const val SCAN_TIMEOUT_MS = 60_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val DISCOVER_FALLBACK_MS = 2_500L
        private const val SUBSCRIBE_TIMEOUT_MS = 10_000L
        private const val MAX_DISCOVER_RETRY = 3
        private const val RECONNECT_BASE_MS = 5_000L
        private const val RECONNECT_MAX_MS = 60_000L
        private const val MTU_SIZE = 247

        /** 进程内全局状态（Compose collectAsState）。 */
        val state = BleStateHolder.state

        /** App/广播接收器启动前台服务（静默降级为 startService）。 */
        fun start(context: Context) {
            val intent = Intent(context, BleCentralService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                try {
                    context.startService(intent)
                } catch (e2: Exception) {
                    Log.w(TAG, "startService failed", e2)
                }
            }
        }

        fun startScan(context: Context) = send(context, Intent(context, BleCentralService::class.java).setAction(ACTION_START_SCAN))

        fun stopScan(context: Context) = send(context, Intent(context, BleCentralService::class.java).setAction(ACTION_STOP_SCAN))

        fun connect(context: Context, address: String, name: String?, bond: Boolean) =
            send(
                context,
                Intent(context, BleCentralService::class.java)
                    .setAction(ACTION_CONNECT)
                    .putExtra(EXTRA_ADDRESS, address)
                    .putExtra(EXTRA_NAME, name)
                    .putExtra(EXTRA_BOND, bond)
            )

        fun connectSaved(context: Context) = send(context, Intent(context, BleCentralService::class.java).setAction(ACTION_CONNECT_SAVED))

        fun disconnect(context: Context) = send(context, Intent(context, BleCentralService::class.java).setAction(ACTION_DISCONNECT))

        /** 构造通知动作按钮的 Intent（PendingIntent.getService 用）。 */
        fun performActionIntent(context: Context, uid: Int, actionId: Int): Intent =
            Intent(context, BleCentralService::class.java)
                .setAction(ACTION_PERFORM)
                .putExtra(EXTRA_UID, uid)
                .putExtra(EXTRA_ACTION_ID, actionId)

        fun performAction(context: Context, uid: Int, actionId: Int) = send(context, performActionIntent(context, uid, actionId))

        private fun send(context: Context, intent: Intent) {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                try {
                    context.startService(intent)
                } catch (e2: Exception) {
                    Log.w(TAG, "deliver action failed", e2)
                }
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private val bluetoothManager: BluetoothManager? by lazy { getSystemService(BluetoothManager::class.java) }
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var gatt: BluetoothGatt? = null
    private var session: AncsSession? = null
    private var dataSourceChar: BluetoothGattCharacteristic? = null
    private var notificationSourceChar: BluetoothGattCharacteristic? = null
    private var controlPointChar: BluetoothGattCharacteristic? = null

    private var scanCallback: ScanCallback? = null
    private val scanResults = LinkedHashMap<String, ScanDevice>()

    private var discoverInFlight = false
    private var discoverRetry = 0
    private var subscribeAttempt = 0
    private var reconnectAttempt = 0
    private var manualDisconnect = false
    private var bondReceiverRegistered = false

    private val scanTimeoutRunnable = Runnable { stopScan() }

    private val connectTimeoutRunnable = Runnable {
        if (state.value.connection != ConnectionState.READY) {
            Log.w(TAG, "连接超时")
            closeGatt()
            BleStateHolder.set { it.copy(connection = ConnectionState.DISCONNECTED, error = "连接超时") }
            maybeScheduleReconnect()
        }
    }

    private val discoverFallbackRunnable = Runnable { discoverServices() }

    private val subscribeTimeoutRunnable = Runnable { subscribeFailed() }

    private val reconnectRunnable = Runnable {
        val s = Prefs.settings.value
        if (s.autoReconnect && !s.deviceAddress.isNullOrEmpty() &&
            adapter?.isEnabled == true && gatt == null && !isScreenOff() && !manualDisconnect
        ) {
            Log.d(TAG, "自动重连 ${s.deviceAddress}")
            connectGattInternal(adapter!!.getRemoteDevice(s.deviceAddress), s.deviceName)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== this@BleCentralService.gatt) return // 旧连接的迟到回调，忽略
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    handler.removeCallbacks(connectTimeoutRunnable)
                    BleStateHolder.set { it.copy(connection = ConnectionState.DISCOVERING, error = null) }
                    // 请求更大 MTU；若 iPhone 不应答，2.5s 后兜底直接 discoverServices
                    try {
                        gatt.requestMtu(MTU_SIZE)
                    } catch (e: Exception) {
                        Log.w(TAG, "requestMtu failed", e)
                        discoverServices()
                    }
                    handler.postDelayed(discoverFallbackRunnable, DISCOVER_FALLBACK_MS)
                }
                BluetoothProfile.STATE_DISCONNECTED -> onGattDisconnected()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU=$mtu status=$status")
            handler.removeCallbacks(discoverFallbackRunnable)
            discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            handler.removeCallbacks(discoverFallbackRunnable)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                discoverRetry++
                if (discoverRetry <= MAX_DISCOVER_RETRY) {
                    Log.w(TAG, "discoverServices 失败($status)，第 $discoverRetry 次重试")
                    handler.postDelayed({ discoverServices() }, 1000L)
                } else {
                    Log.w(TAG, "discoverServices 重试耗尽，断开")
                    gatt.disconnect()
                }
                return
            }
            discoverInFlight = false
            onServicesReady(gatt)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val ok = status == BluetoothGatt.GATT_SUCCESS
            if (descriptor.uuid != AncsProtocol.CLIENT_CHARACTERISTIC_CONFIG_UUID) return
            when (descriptor.characteristic.uuid) {
                AncsProtocol.DATA_SOURCE_UUID -> {
                    // 串行订阅：DataSource CCCD 写成功 → 订阅 NotificationSource
                    if (ok) subscribeNotificationSource() else subscribeFailed()
                }
                AncsProtocol.NOTIFICATION_SOURCE_UUID -> {
                    if (ok) onSubscribed() else subscribeFailed()
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: return
            when (characteristic.uuid) {
                AncsProtocol.NOTIFICATION_SOURCE_UUID -> session?.onNotificationSource(value)
                AncsProtocol.DATA_SOURCE_UUID -> session?.onDataSource(value)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) session?.onWriteSuccess() else session?.onWriteFailure()
        }
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF -> {
                    closeGatt()
                    BleStateHolder.set { it.copy(bluetoothEnabled = false, connection = ConnectionState.IDLE, error = null) }
                }
                BluetoothAdapter.STATE_ON -> {
                    BleStateHolder.set { it.copy(bluetoothEnabled = true) }
                    observeBondState()
                    val s = Prefs.settings.value
                    if (s.autoReconnect && !s.deviceAddress.isNullOrEmpty() && gatt == null) {
                        scheduleReconnect()
                    }
                }
            }
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                BluetoothDevice.BOND_BONDED -> {
                    // 配对成功才保存地址（iOS 地址随机化：扫描到的地址不可靠）
                    val address = device.address ?: return
                    val name = (try {
                        device.name
                    } catch (e: Exception) {
                        null
                    }) ?: "iPhone"
                    Prefs.setDevice(address, name)
                    BleStateHolder.set { it.copy(connection = ConnectionState.IDLE, error = null) }
                    connectSaved()
                }
                BluetoothDevice.BOND_NONE -> {
                    if (Prefs.settings.value.deviceAddress == device?.address) {
                        Prefs.setDevice(null, null)
                    }
                }
            }
        }
    }

    // ———————————————————— 生命周期 ————————————————————

    override fun onCreate() {
        super.onCreate()
        BleStateHolder.set { it.copy(bluetoothEnabled = adapter?.isEnabled == true) }
        registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        if (adapter?.isEnabled == true) observeBondState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        when (intent?.action) {
            ACTION_START_SCAN -> startScan()
            ACTION_STOP_SCAN -> stopScan()
            ACTION_CONNECT -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS) ?: return START_STICKY
                val name = intent.getStringExtra(EXTRA_NAME)
                val bond = intent.getBooleanExtra(EXTRA_BOND, false)
                connect(address, name, bond)
            }
            ACTION_CONNECT_SAVED -> connectSaved()
            ACTION_DISCONNECT -> disconnect()
            ACTION_PERFORM -> {
                val uid = intent.getIntExtra(EXTRA_UID, 0)
                val actionId = intent.getIntExtra(EXTRA_ACTION_ID, -1)
                performAction(uid, actionId)
            }
            null -> {
                // App 启动直接拉起服务：有已保存设备则自动连接
                if (!Prefs.settings.value.deviceAddress.isNullOrEmpty()) connectSaved()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(bluetoothStateReceiver)
        } catch (e: Exception) {
            // already unregistered
        }
        try {
            if (bondReceiverRegistered) unregisterReceiver(bondReceiver)
        } catch (e: Exception) {
            // already unregistered
        }
        stopScan()
        closeGatt()
        super.onDestroy()
    }

    // ———————————————————— 前台通知 ————————————————————

    private fun startAsForeground() {
        val notification = buildServiceNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NotifForwarder.SERVICE_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NotifForwarder.SERVICE_NOTIFICATION_ID, notification)
        }
    }

    private fun buildServiceNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val s = state.value
        val line = when {
            adapter?.isEnabled != true -> "蓝牙未开启"
            s.connection == ConnectionState.READY -> "已连接 ${s.deviceName ?: "iPhone"}"
            s.connection != ConnectionState.IDLE -> connectionStateLabel(s.connection)
            else -> "待机中，等待连接 iPhone"
        }
        return Notification.Builder(this, NotifForwarder.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(line)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun refreshServiceNotification() {
        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(NotifForwarder.SERVICE_NOTIFICATION_ID, buildServiceNotification())
        } catch (e: Exception) {
            Log.w(TAG, "refresh notification failed", e)
        }
    }

    // ———————————————————— 扫描 ————————————————————

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

    private fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

    private fun startScan() {
        val a = adapter ?: return
        if (!a.isEnabled) {
            BleStateHolder.set { it.copy(error = "请先打开蓝牙", scanActive = false) }
            return
        }
        if (state.value.connection != ConnectionState.IDLE && state.value.connection != ConnectionState.DISCONNECTED) {
            BleStateHolder.set { it.copy(error = "请先断开当前连接", scanActive = false) }
            return
        }
        if (!hasScanPermission()) {
            BleStateHolder.set { it.copy(error = "缺少蓝牙扫描权限，请在系统设置中授予", scanActive = false) }
            return
        }
        scanResults.clear()
        BleStateHolder.set { it.copy(scanActive = true, scanDevices = emptyList(), error = null) }

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val record = result.scanRecord
                val address = device.address ?: return
                val name = (try {
                    device.name
                } catch (e: Exception) {
                    null
                }) ?: record?.deviceName
                scanResults[address] = ScanDevice(
                    address = address,
                    name = name ?: "未知设备",
                    rssi = result.rssi,
                    advertisesPadLinkService = record?.serviceUuids?.any { it.uuid == AncsProtocol.PADLINK_PHONE_SERVICE_UUID } == true,
                    looksLikeIphone = name?.contains("iPhone", ignoreCase = true) == true
                )
                val sorted = scanResults.values.sortedWith(
                    compareByDescending<ScanDevice> { it.advertisesPadLinkService || it.looksLikeIphone }
                        .thenByDescending { it.rssi }
                )
                BleStateHolder.set { it.copy(scanDevices = sorted) }
            }

            override fun onScanFailed(errorCode: Int) {
                BleStateHolder.set { it.copy(scanActive = false, error = "扫描失败（错误码 $errorCode）") }
            }
        }
        scanCallback = cb
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .build()
            a.bluetoothLeScanner.startScan(null, settings, cb)
            handler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
        } catch (e: Exception) {
            BleStateHolder.set { it.copy(scanActive = false, error = "启动扫描失败：${e.message}") }
        }
    }

    private fun stopScan() {
        handler.removeCallbacks(scanTimeoutRunnable)
        val cb = scanCallback
        scanCallback = null
        if (cb != null) {
            try {
                adapter?.bluetoothLeScanner?.stopScan(cb)
            } catch (e: Exception) {
                Log.w(TAG, "stopScan failed", e)
            }
        }
        BleStateHolder.set { it.copy(scanActive = false) }
    }

    // ———————————————————— 连接 ————————————————————

    /** UI 触发：地址 + 可选配对 + 可选保存。 */
    private fun connect(address: String, name: String?, bond: Boolean) {
        stopScan()
        val a = adapter
        if (a == null || !a.isEnabled) {
            BleStateHolder.set { it.copy(error = "请先打开蓝牙") }
            return
        }
        val device = try {
            a.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            BleStateHolder.set { it.copy(error = "无效的设备地址") }
            return
        }
        if (bond && device.bondState != BluetoothDevice.BOND_BONDED) {
            BleStateHolder.set {
                it.copy(
                    connection = ConnectionState.BONDING,
                    deviceAddress = address,
                    deviceName = name,
                    error = null
                )
            }
            val ok = try {
                device.createBond()
            } catch (e: Exception) {
                BleStateHolder.set { it.copy(connection = ConnectionState.IDLE, error = "发起配对失败：${e.message}") }
                return
            }
            if (!ok) {
                BleStateHolder.set { it.copy(connection = ConnectionState.IDLE, error = "发起配对失败") }
            }
            // 后续由 ACTION_BOND_STATE_CHANGED(BOND_BONDED) → 保存地址 → connectSaved()
            return
        }
        if (bond) {
            Prefs.setDevice(address, name)
        }
        connectGattInternal(device, name)
    }

    /** 按已保存地址连接（配对引导/自动重连/App 启动）。 */
    private fun connectSaved() {
        val s = Prefs.settings.value
        val address = s.deviceAddress
        if (address.isNullOrEmpty()) {
            BleStateHolder.set { it.copy(connection = ConnectionState.IDLE) }
            return
        }
        val a = adapter
        if (a == null || !a.isEnabled) {
            BleStateHolder.set { it.copy(connection = ConnectionState.DISCONNECTED, error = "蓝牙未开启") }
            return
        }
        if (gatt != null && state.value.deviceAddress == address &&
            state.value.connection != ConnectionState.IDLE && state.value.connection != ConnectionState.DISCONNECTED
        ) {
            return // 同一设备已在连接流程中，勿打断
        }
        val device = try {
            a.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            BleStateHolder.set { it.copy(connection = ConnectionState.IDLE, error = "已保存的设备地址无效") }
            return
        }
        connectGattInternal(device, s.deviceName)
    }

    private fun connectGattInternal(device: BluetoothDevice, name: String?) {
        closeGatt()
        manualDisconnect = false
        reconnectAttempt = 0
        handler.removeCallbacks(reconnectRunnable)
        val deviceName = name ?: (try {
            device.name
        } catch (e: Exception) {
            null
        })
        BleStateHolder.set {
            it.copy(
                connection = ConnectionState.CONNECTING,
                deviceAddress = device.address,
                deviceName = deviceName,
                error = null
            )
        }
        if (!hasConnectPermission()) {
            BleStateHolder.set { it.copy(connection = ConnectionState.DISCONNECTED, error = "缺少蓝牙连接权限") }
            return
        }
        try {
            gatt = device.connectGatt(this, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            BleStateHolder.set { it.copy(connection = ConnectionState.DISCONNECTED, error = "连接失败：${e.message}") }
            maybeScheduleReconnect()
            return
        }
        handler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS)
    }

    /** 用户主动断开：停止重连、清空会话。 */
    private fun disconnect() {
        manualDisconnect = true
        handler.removeCallbacks(reconnectRunnable)
        reconnectAttempt = 0
        val g = gatt
        if (g != null) {
            try {
                g.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "disconnect failed", e)
            }
        }
        closeGatt()
        NotificationStore.clear()
        NotifForwarder.cancelAll(this)
        BleStateHolder.set { it.copy(connection = ConnectionState.IDLE, error = null) }
    }

    private fun closeGatt() {
        handler.removeCallbacks(connectTimeoutRunnable)
        handler.removeCallbacks(discoverFallbackRunnable)
        handler.removeCallbacks(subscribeTimeoutRunnable)
        val g = gatt
        gatt = null
        dataSourceChar = null
        notificationSourceChar = null
        controlPointChar = null
        session?.close()
        session = null
        discoverInFlight = false
        discoverRetry = 0
        subscribeAttempt = 0
        if (g != null) {
            try {
                g.close()
            } catch (e: Exception) {
                Log.w(TAG, "gatt close failed", e)
            }
        }
    }

    private fun onGattDisconnected() {
        if (manualDisconnect) return
        handler.removeCallbacks(connectTimeoutRunnable)
        closeGatt()
        // ANCS 会话语义：断线后 UID 作废，清空通知中心并移除系统通知
        NotificationStore.clear()
        NotifForwarder.cancelAll(this)
        BleStateHolder.set { it.copy(connection = ConnectionState.DISCONNECTED, error = "连接已断开") }
        refreshServiceNotification()
        maybeScheduleReconnect()
    }

    // ———————————————————— 服务发现与订阅 ————————————————————

    private fun discoverServices() {
        val g = gatt ?: return
        if (discoverInFlight) return
        if (state.value.connection == ConnectionState.READY) return
        discoverInFlight = true
        if (!g.discoverServices()) {
            discoverInFlight = false
            Log.w(TAG, "discoverServices 启动失败")
            g.disconnect()
        }
    }

    private fun onServicesReady(g: BluetoothGatt) {
        val service = g.getService(AncsProtocol.SERVICE_UUID)
        if (service == null) {
            BleStateHolder.set {
                it.copy(
                    connection = ConnectionState.DISCONNECTED,
                    error = "未找到 ANCS 服务：请确认连接的是 iPhone，并在「设置-蓝牙」中完成系统配对"
                )
            }
            g.disconnect()
            return
        }
        val ds = service.getCharacteristic(AncsProtocol.DATA_SOURCE_UUID)
        val ns = service.getCharacteristic(AncsProtocol.NOTIFICATION_SOURCE_UUID)
        val cp = service.getCharacteristic(AncsProtocol.CONTROL_POINT_UUID)
        if (ds == null || ns == null || cp == null) {
            BleStateHolder.set { it.copy(connection = ConnectionState.DISCONNECTED, error = "ANCS 特征不完整") }
            g.disconnect()
            return
        }
        dataSourceChar = ds
        notificationSourceChar = ns
        controlPointChar = cp
        session = AncsSession(
            writeControlPoint = { ch -> gatt?.writeCharacteristic(ch) == true },
            controlPoint = cp,
            onEvent = { uid, flags, categoryId, categoryCount -> onAncsEvent(uid, flags, categoryId, categoryCount) },
            onRemoved = { uid -> onAncsRemoved(uid) },
            onAttributes = { list -> onAncsAttributes(list) }
        )
        subscribeAttempt = 0
        BleStateHolder.set { it.copy(connection = ConnectionState.SUBSCRIBING, error = null) }
        // 串行订阅第一步：Data Source
        subscribeDataSource()
    }

    private fun subscribeDataSource() {
        val g = gatt ?: return
        val ch = dataSourceChar ?: return
        handler.removeCallbacks(subscribeTimeoutRunnable)
        handler.postDelayed(subscribeTimeoutRunnable, SUBSCRIBE_TIMEOUT_MS)
        if (!g.setCharacteristicNotification(ch, true)) {
            subscribeFailed()
            return
        }
        val descriptor = ch.getDescriptor(AncsProtocol.CLIENT_CHARACTERISTIC_CONFIG_UUID)
        if (descriptor == null) {
            subscribeFailed()
            return
        }
        // 系统栈写 CCCD 0x0001；onDescriptorWrite 成功后再订阅 NotificationSource
        val writeStarted: Boolean = g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        if (!writeStarted) {
            subscribeFailed()
        }
    }

    private fun subscribeNotificationSource() {
        val g = gatt ?: return
        val ch = notificationSourceChar ?: return
        if (!g.setCharacteristicNotification(ch, true)) {
            subscribeFailed()
            return
        }
        val descriptor = ch.getDescriptor(AncsProtocol.CLIENT_CHARACTERISTIC_CONFIG_UUID)
        if (descriptor == null) {
            subscribeFailed()
            return
        }
        val writeStarted: Boolean = g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        if (!writeStarted) {
            subscribeFailed()
        }
    }

    private fun subscribeFailed() {
        handler.removeCallbacks(subscribeTimeoutRunnable)
        subscribeAttempt++
        if (subscribeAttempt < 2) {
            Log.w(TAG, "订阅失败，重试一次")
            subscribeDataSource()
        } else {
            Log.w(TAG, "订阅重试耗尽，断开重连")
            gatt?.disconnect()
        }
    }

    private fun onSubscribed() {
        handler.removeCallbacks(subscribeTimeoutRunnable)
        handler.removeCallbacks(connectTimeoutRunnable)
        BleStateHolder.set {
            it.copy(
                connection = ConnectionState.READY,
                lastSyncAt = System.currentTimeMillis(),
                error = null
            )
        }
        refreshServiceNotification()
    }

    // ———————————————————— ANCS 事件落地 ————————————————————

    private fun onAncsEvent(uid: Int, flags: Int, categoryId: Int, categoryCount: Int) {
        val existing = NotificationStore.get(uid)
        val n = AppNotification(
            uid = uid,
            eventFlags = flags,
            categoryId = categoryId,
            categoryCount = categoryCount,
            receivedAt = existing?.receivedAt ?: System.currentTimeMillis()
        )
        NotificationStore.upsert(n)
        // 属性尚未取回时正文为空，show 会跳过；属性到达后再次 show 补齐
        NotifForwarder.show(this, n)
        BleStateHolder.set { it.copy(lastSyncAt = System.currentTimeMillis()) }
    }

    private fun onAncsRemoved(uid: Int) {
        NotificationStore.remove(uid)
        NotifForwarder.cancel(this, uid)
    }

    private fun onAncsAttributes(results: List<DataSourceAssembler.Result>) {
        val changedUids = HashSet<Int>()
        for (r in results) {
            if (r.attrId == DataSourceAssembler.ATTR_ERROR) {
                val code = r.value.firstOrNull()?.toInt()?.and(0xFF) ?: -1
                Log.w(TAG, "ANCS 错误响应 uid=${r.uid}: ${AncsProtocol.errorName(code)}")
                continue
            }
            val uid = r.uid
            when (r.attrId) {
                AncsProtocol.ATTR_APP_IDENTIFIER -> {
                    if (update(uid) { it.copy(appIdentifier = String(r.value, Charsets.UTF_8)) }) changedUids.add(uid)
                }
                AncsProtocol.ATTR_TITLE -> {
                    if (update(uid) { it.copy(title = String(r.value, Charsets.UTF_8)) }) changedUids.add(uid)
                }
                AncsProtocol.ATTR_SUBTITLE -> {
                    if (update(uid) { it.copy(subtitle = String(r.value, Charsets.UTF_8)) }) changedUids.add(uid)
                }
                AncsProtocol.ATTR_MESSAGE -> {
                    if (update(uid) { it.copy(message = String(r.value, Charsets.UTF_8)) }) changedUids.add(uid)
                }
                AncsProtocol.ATTR_DATE -> {
                    parseDate(r.value)?.let { date ->
                        if (update(uid) { it.copy(dateSeconds = date) }) changedUids.add(uid)
                    }
                }
                AncsProtocol.ATTR_POSITIVE_ACTION_LABEL -> {
                    if (update(uid) { it.copy(positiveActionLabel = String(r.value, Charsets.UTF_8)) }) changedUids.add(uid)
                }
                AncsProtocol.ATTR_NEGATIVE_ACTION_LABEL -> {
                    if (update(uid) { it.copy(negativeActionLabel = String(r.value, Charsets.UTF_8)) }) changedUids.add(uid)
                }
                AncsProtocol.ATTR_MESSAGE_SIZE -> Unit // 2 字节数值属性，一期不使用
                else -> Log.d(TAG, "未知属性 ID=${r.attrId} uid=$uid")
            }
        }
        // 属性补齐后刷新对应系统通知
        for (uid in changedUids) {
            NotificationStore.get(uid)?.let { NotifForwarder.show(this, it) }
        }
        if (changedUids.isNotEmpty()) {
            BleStateHolder.set { it.copy(lastSyncAt = System.currentTimeMillis()) }
        }
    }

    private fun update(uid: Int, transform: (AppNotification) -> AppNotification): Boolean =
        NotificationStore.update(uid, transform) != null

    /** ANCS Date 为 4 字节数值：先按 Unix 秒解释；若太小则按 iOS 参考时间（2001-01-01）换算。 */
    private fun parseDate(raw: ByteArray): Long? {
        if (raw.size < 4) return null
        val v = AncsBytes.leInt(raw, 0).toLong() and 0xFFFFFFFFL
        if (v == 0L || v == 0xFFFFFFFFL) return null
        return if (v > 978_307_200L) v else v + 978_307_200L
    }

    private fun performAction(uid: Int, actionId: Int) {
        if (state.value.connection != ConnectionState.READY) return
        if (actionId != AncsProtocol.ACTION_ID_POSITIVE && actionId != AncsProtocol.ACTION_ID_NEGATIVE) return
        session?.enqueuePerformAction(uid, actionId)
    }

    // ———————————————————— 自动重连 ————————————————————

    private fun maybeScheduleReconnect() {
        val s = Prefs.settings.value
        if (s.autoReconnect && !s.deviceAddress.isNullOrEmpty() && adapter?.isEnabled == true && !manualDisconnect) {
            scheduleReconnect()
        }
    }

    /** 指数退避：5s 起 ×2，上限 60s。 */
    private fun scheduleReconnect() {
        handler.removeCallbacks(reconnectRunnable)
        val attempt = reconnectAttempt
        val delay = (RECONNECT_BASE_MS shl attempt.coerceAtMost(4)).coerceAtMost(RECONNECT_MAX_MS)
        reconnectAttempt = (attempt + 1).coerceAtMost(99)
        Log.d(TAG, "计划 $delay ms 后重连（第 ${attempt + 1} 次）")
        handler.postDelayed(reconnectRunnable, delay)
    }

    private fun isScreenOff(): Boolean {
        val pm = getSystemService(PowerManager::class.java) ?: return false
        return !pm.isInteractive
    }

    private fun observeBondState() {
        if (bondReceiverRegistered) return
        bondReceiverRegistered = true
        registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
    }
}
