package com.padlink.ancs.model

import com.padlink.ancs.ble.AncsProtocol

/**
 * 一条 ANCS 通知的内存模型。UID 仅在单个会话内有效（断线后作废）。
 */
data class AppNotification(
    val uid: Int,
    val eventFlags: Int,
    val categoryId: Int,
    val categoryCount: Int,
    val appIdentifier: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val message: String? = null,
    /** ANCS Date 属性原始秒数（Unix epoch 或 2001-01-01 epoch，展示时按启发式换算）。 */
    val dateSeconds: Long? = null,
    val positiveActionLabel: String? = null,
    val negativeActionLabel: String? = null,
    /** 平板本地收到事件的时间（毫秒）。 */
    val receivedAt: Long = System.currentTimeMillis()
) {
    val hasPositiveAction: Boolean get() = (eventFlags and AncsProtocol.FLAG_POSITIVE_ACTION) != 0
    val hasNegativeAction: Boolean get() = (eventFlags and AncsProtocol.FLAG_NEGATIVE_ACTION) != 0
    val isSilent: Boolean get() = (eventFlags and AncsProtocol.FLAG_SILENT) != 0

    /** 通知摘要（列表展示用）。 */
    val summary: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: message?.takeIf { it.isNotBlank() }
            ?: "（无内容）"
}
