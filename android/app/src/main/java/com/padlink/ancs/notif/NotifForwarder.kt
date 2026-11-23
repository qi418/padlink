package com.padlink.ancs.notif

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.padlink.ancs.R
import com.padlink.ancs.ble.AncsProtocol
import com.padlink.ancs.ble.BleCentralService
import com.padlink.ancs.launcher.PhoneLauncher
import com.padlink.ancs.model.AppNotification
import com.padlink.ancs.model.NotificationStore
import com.padlink.ancs.prefs.Prefs
import com.padlink.ancs.ui.MainActivity

/**
 * 把 ANCS 通知转发为 Android 系统通知。
 * 正文 = App 名 + 标题 + 正文（按设置截断）；
 * 有动作（EventFlags bit3/bit4）时附加正向/负向按钮，点击回写 ANCS ControlPoint；
 * 点击通知打开平板 App（通知中心详情页）。
 */
object NotifForwarder {

    const val CHANNEL_NOTIFICATIONS = "iphone_notifications"
    const val CHANNEL_SERVICE = "padlink_service"
    const val SERVICE_NOTIFICATION_ID = 1
    const val EXTRA_OPEN_UID = "padlink.extra.open_uid"

    fun init(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NOTIFICATIONS,
                context.getString(R.string.channel_notifications),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "转发自 iPhone 的通知"
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                context.getString(R.string.channel_service),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "PadLink 后台常驻服务状态"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
    }

    fun show(context: Context, n: AppNotification) {
        // 黑名单与静默通知（EventFlags bit0）不发系统通知，但仍保留在通知中心
        if (n.appIdentifier != null && Prefs.settings.value.blacklist.contains(n.appIdentifier)) return
        if (n.isSilent) return
        val body = buildBody(n)
        if (body.isBlank()) return

        val maxLen = Prefs.settings.value.maxMessageLength
        val truncated = if (body.length > maxLen) body.take(maxLen) + "…" else body

        val contentIntent = PendingIntent.getActivity(
            context,
            n.uid,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_UID, n.uid)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(context, CHANNEL_NOTIFICATIONS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(PhoneLauncher.displayName(n.appIdentifier))
            .setContentText(truncated)
            .setStyle(Notification.BigTextStyle().bigText(truncated))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SOCIAL)
            .setWhen(n.receivedAt)
            .setShowWhen(true)
            .setContentIntent(contentIntent)

        if (n.hasPositiveAction && !n.positiveActionLabel.isNullOrBlank()) {
            builder.addAction(0, n.positiveActionLabel, actionIntent(context, n.uid, AncsProtocol.ACTION_ID_POSITIVE))
        }
        if (n.hasNegativeAction && !n.negativeActionLabel.isNullOrBlank()) {
            builder.addAction(0, n.negativeActionLabel, actionIntent(context, n.uid, AncsProtocol.ACTION_ID_NEGATIVE))
        }

        context.getSystemService(NotificationManager::class.java).notify(notificationId(n.uid), builder.build())
    }

    fun cancel(context: Context, uid: Int) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(uid))
    }

    /** UID 是 32 位无符号数，转 Int 可能为负；通知 ID 取低 31 位保证为正。 */
    private fun notificationId(uid: Int): Int = uid and 0x7FFFFFFF

    fun cancelAll(context: Context) {
        for (uid in NotificationStore.getAll().map { it.uid }) {
            cancel(context, uid)
        }
    }

    private fun buildBody(n: AppNotification): String =
        listOfNotNull(
            n.title?.takeIf { it.isNotBlank() },
            n.message?.takeIf { it.isNotBlank() }
        ).joinToString("\n")

    private fun actionIntent(context: Context, uid: Int, actionId: Int): PendingIntent =
        PendingIntent.getService(
            context,
            uid * 10 + actionId,
            BleCentralService.performActionIntent(context, uid, actionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
