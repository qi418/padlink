package com.padlink.ancs

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.padlink.ancs.ble.BleCentralService
import com.padlink.ancs.prefs.Prefs

/**
 * 开机自启（可选）：仅当已配对且自动重连开启时拉起前台服务。
 * 启动失败（系统后台限制）不影响 App 手动打开后的正常使用。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = Prefs.settings.value
        if (settings.autoReconnect && !settings.deviceAddress.isNullOrEmpty()) {
            try {
                BleCentralService.start(context)
            } catch (t: Throwable) {
                Log.w("PadLink", "boot auto-start failed", t)
            }
        }
    }
}
