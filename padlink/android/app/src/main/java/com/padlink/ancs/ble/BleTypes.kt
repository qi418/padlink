package com.padlink.ancs.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** BLE 连接状态机。 */
enum class ConnectionState {
    IDLE,
    BONDING,
    CONNECTING,
    DISCOVERING,
    SUBSCRIBING,
    READY,
    DISCONNECTED
}

fun connectionStateLabel(s: ConnectionState): String = when (s) {
    ConnectionState.IDLE -> "未连接"
    ConnectionState.BONDING -> "配对中…"
    ConnectionState.CONNECTING -> "连接中…"
    ConnectionState.DISCOVERING -> "发现服务…"
    ConnectionState.SUBSCRIBING -> "订阅通知…"
    ConnectionState.READY -> "已连接"
    ConnectionState.DISCONNECTED -> "已断开"
}

/** 扫描到的候选设备（仅首次配对用）。 */
data class ScanDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val advertisesPadLinkService: Boolean = false,
    val looksLikeIphone: Boolean = false
)

/** 前台服务对外暴露的整体状态（Compose 直接 collectAsState）。 */
data class BleServiceState(
    val bluetoothEnabled: Boolean = false,
    val connection: ConnectionState = ConnectionState.IDLE,
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    val scanActive: Boolean = false,
    val scanDevices: List<ScanDevice> = emptyList(),
    val lastSyncAt: Long? = null,
    val error: String? = null
)

/** 服务状态单例（伴生对象级，服务重建不丢状态）。 */
object BleStateHolder {
    private val _state = MutableStateFlow(BleServiceState())
    val state: StateFlow<BleServiceState> = _state.asStateFlow()

    fun set(transform: (BleServiceState) -> BleServiceState) {
        _state.value = transform(_state.value)
    }
}
