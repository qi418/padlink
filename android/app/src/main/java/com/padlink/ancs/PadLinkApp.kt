package com.padlink.ancs

import android.app.Application
import com.padlink.ancs.ble.BleCentralService
import com.padlink.ancs.model.NotificationStore
import com.padlink.ancs.notif.NotifForwarder
import com.padlink.ancs.prefs.Prefs

class PadLinkApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        NotificationStore.init(this)
        NotifForwarder.init(this)
        // 启动前台服务：有已保存设备时自动连接，否则待机（低优先级常驻通知）
        BleCentralService.start(this)
    }
}
