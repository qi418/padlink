package com.padlink.ancs.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.util.Log

/**
 * ANCS 会话逻辑（在服务连接成功后创建，断线即丢弃）：
 *
 * 1. Notification Source 8 字节事件解析：
 *    EventID=byte0，Removed 判定为 (byte0 & 0x02) != 0；
 *    Added/Modified 时向 Control Point 排队写 GetNotificationAttributes。
 * 2. Control Point 写串行队列：同一时刻只允许一个在途写，
 *    onCharacteristicWrite 返回 GATT_SUCCESS 后才出队下一个。
 * 3. Data Source 字节流交给 [DataSourceAssembler] 增量重组（跨 MTU 分片）。
 */
class AncsSession(
    private val writeControlPoint: (BluetoothGattCharacteristic) -> Boolean,
    private val controlPoint: BluetoothGattCharacteristic,
    private val onEvent: (uid: Int, eventFlags: Int, categoryId: Int, categoryCount: Int) -> Unit,
    private val onRemoved: (uid: Int) -> Unit,
    private val onAttributes: (List<DataSourceAssembler.Result>) -> Unit
) {

    companion object {
        private const val TAG = "AncsSession"
        private const val EVENT_REMOVED_MASK = 0x02
        private const val MAX_LENGTH = 0xFF
    }

    val dataSourceAssembler = DataSourceAssembler()

    private val queue = ArrayDeque<ByteArray>()
    private val lock = Any()

    @Volatile
    private var inFlight = false

    @Volatile
    private var closed = false

    /** Notification Source 事件（8 字节小端）。 */
    fun onNotificationSource(bytes: ByteArray) {
        if (bytes.size < 8) {
            Log.w(TAG, "Notification Source 事件不足 8 字节: ${AncsBytes.hex(bytes)}")
            return
        }
        val flags = bytes[0].toInt() and 0xFF
        val uid = AncsBytes.leInt(bytes, 4)
        if ((flags and EVENT_REMOVED_MASK) != 0) {
            onRemoved(uid)
            return
        }
        val categoryId = bytes[2].toInt() and 0xFF
        val categoryCount = bytes[3].toInt() and 0xFF
        onEvent(uid, flags, categoryId, categoryCount)
        enqueueGetNotificationAttributes(uid)
    }

    /** Data Source 分片（跨 MTU），解析完成即回调。 */
    fun onDataSource(bytes: ByteArray) {
        val results = dataSourceAssembler.feed(bytes)
        if (results.isNotEmpty()) {
            onAttributes(results)
        }
    }

    /** 服务在 onCharacteristicWrite(status==GATT_SUCCESS) 后调用，出队下一个写。 */
    fun onWriteSuccess() {
        inFlight = false
        pump()
    }

    /** 服务在写失败后调用，丢弃队列（等待事件重新触发）。 */
    fun onWriteFailure() {
        inFlight = false
        synchronized(lock) { queue.clear() }
        Log.w(TAG, "Control Point 写失败，清空待写队列")
    }

    /**
     * GetNotificationAttributes 命令。
     * 每次事件发两条（顺序由队列保证）：
     *  1) 规格标准：Title + Message
     *  2) 扩展：AppIdentifier（定长不带长度）+ Date + 正/负动作标签，
     *     用于本地应用映射（微信/QQ）与通知动作按钮。
     */
    fun enqueueGetNotificationAttributes(uid: Int) {
        val uidBytes = AncsBytes.leBytes(uid)
        enqueue(
            byteArrayOf(AncsProtocol.CMD_GET_NOTIFICATION_ATTRIBUTES.toByte()) +
                uidBytes +
                byteArrayOf(
                    AncsProtocol.ATTR_TITLE.toByte(), MAX_LENGTH.toByte(), MAX_LENGTH.toByte(),
                    AncsProtocol.ATTR_MESSAGE.toByte(), MAX_LENGTH.toByte(), MAX_LENGTH.toByte()
                )
        )
        enqueue(
            byteArrayOf(AncsProtocol.CMD_GET_NOTIFICATION_ATTRIBUTES.toByte()) +
                uidBytes +
                byteArrayOf(
                    AncsProtocol.ATTR_APP_IDENTIFIER.toByte(), MAX_LENGTH.toByte(), MAX_LENGTH.toByte(),
                    AncsProtocol.ATTR_DATE.toByte(),
                    AncsProtocol.ATTR_POSITIVE_ACTION_LABEL.toByte(), MAX_LENGTH.toByte(), MAX_LENGTH.toByte(),
                    AncsProtocol.ATTR_NEGATIVE_ACTION_LABEL.toByte(), MAX_LENGTH.toByte(), MAX_LENGTH.toByte()
                )
        )
    }

    /** PerformNotificationAction 命令：[0x02, uid(4B), actionId]。 */
    fun enqueuePerformAction(uid: Int, actionId: Int) {
        enqueue(
            byteArrayOf(AncsProtocol.CMD_PERFORM_NOTIFICATION_ACTION.toByte()) +
                AncsBytes.leBytes(uid) +
                byteArrayOf(actionId.toByte())
        )
    }

    /** 断线时清空会话缓存：待写队列 + Data Source 流式缓冲。 */
    fun close() {
        closed = true
        inFlight = false
        synchronized(lock) { queue.clear() }
        dataSourceAssembler.reset()
    }

    private fun enqueue(payload: ByteArray) {
        if (closed) return
        synchronized(lock) { queue.addLast(payload) }
        pump()
    }

    private fun pump() {
        if (closed || inFlight) return
        val next = synchronized(lock) {
            if (closed || inFlight) return
            queue.removeFirstOrNull() ?: return
        }
        inFlight = true
        controlPoint.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        controlPoint.value = next
        if (!writeControlPoint(controlPoint)) {
            inFlight = false
            synchronized(lock) { queue.clear() }
            Log.w(TAG, "writeCharacteristic 返回失败，清空队列")
        }
    }
}
