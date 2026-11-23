package com.padlink.ancs.ble

import java.util.UUID

/**
 * ANCS 协议常量（Apple 官方规范，见 padlink/docs/技术规格.md §4）。
 */
object AncsProtocol {

    // —— 服务与特征 UUID ——
    val SERVICE_UUID: UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
    val NOTIFICATION_SOURCE_UUID: UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
    val CONTROL_POINT_UUID: UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")
    val DATA_SOURCE_UUID: UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** iPhone 助手 App（可选）广播的自定义服务 UUID，仅用于辅助发现。 */
    val PADLINK_PHONE_SERVICE_UUID: UUID = UUID.fromString("5E3F6A11-2C4D-4B7E-9A10-1F2E3D4C5B6A")

    /** CCCD 写入值：0x0001 = 使能通知。 */
    const val CCCD_ENABLE_NOTIFICATION = 0x0001

    // —— CommandID ——
    const val CMD_GET_NOTIFICATION_ATTRIBUTES = 0x00
    const val CMD_GET_APP_ATTRIBUTES = 0x01
    const val CMD_PERFORM_NOTIFICATION_ACTION = 0x02

    // —— Notification Source EventID ——
    const val EVENT_ID_ADDED = 0x00
    const val EVENT_ID_MODIFIED = 0x01
    const val EVENT_ID_REMOVED = 0x02

    // —— EventFlags ——
    const val FLAG_SILENT = 0x01
    const val FLAG_IMPORTANT = 0x02
    const val FLAG_PREEXISTING = 0x04
    const val FLAG_POSITIVE_ACTION = 0x08
    const val FLAG_NEGATIVE_ACTION = 0x10

    // —— 属性 ID ——
    const val ATTR_APP_IDENTIFIER = 0x00
    const val ATTR_TITLE = 0x01
    const val ATTR_SUBTITLE = 0x02
    const val ATTR_MESSAGE = 0x03
    const val ATTR_MESSAGE_SIZE = 0x04
    const val ATTR_DATE = 0x05
    const val ATTR_POSITIVE_ACTION_LABEL = 0x06
    const val ATTR_NEGATIVE_ACTION_LABEL = 0x07

    // —— PerformNotificationAction ActionID ——
    const val ACTION_ID_POSITIVE = 0x00
    const val ACTION_ID_NEGATIVE = 0x01

    // —— 错误码 ——
    const val ERROR_UNKNOWN_COMMAND = 0xA0
    const val ERROR_INVALID_COMMAND = 0xA1
    const val ERROR_INVALID_PARAMETER = 0xA2
    const val ERROR_ACTION_FAILED = 0xA3

    fun errorName(code: Int): String = when (code) {
        ERROR_UNKNOWN_COMMAND -> "未知命令"
        ERROR_INVALID_COMMAND -> "命令格式错误"
        ERROR_INVALID_PARAMETER -> "参数无效"
        ERROR_ACTION_FAILED -> "动作失败"
        else -> "错误 0x${code.toString(16).uppercase()}"
    }
}
