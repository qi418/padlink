package com.padlink.ancs.launcher

import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * iOS AppIdentifier → 平板本地应用映射（技术规格 §5.4）。
 * 未命中映射时兜底尝试原 id，失败 Toast 提示手动打开。
 */
object PhoneLauncher {

    data class LocalApp(val displayName: String, val packageName: String)

    private val appMap = linkedMapOf(
        "com.tencent.xin" to LocalApp("微信", "com.tencent.mm"),
        "com.tencent.mobileqq" to LocalApp("QQ", "com.tencent.mobileqq")
    )

    fun displayName(appId: String?): String =
        appMap[appId]?.displayName ?: appId?.takeIf { it.isNotBlank() } ?: "未知应用"

    fun targetPackage(appId: String?): String? =
        appMap[appId]?.packageName ?: appId?.takeIf { it.isNotBlank() }

    /** 返回是否成功启动本地 App；失败时已 Toast 提示。 */
    fun launch(context: Context, appId: String?): Boolean {
        val ctx = context.applicationContext
        val pkg = targetPackage(appId)
        if (pkg == null) {
            toast(ctx, "没有可映射的本地应用，请在平板上手动打开")
            return false
        }
        return try {
            val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            if (intent == null) {
                toast(ctx, "平板上未安装「${displayName(appId)}」（$pkg），请先安装并登录同账号")
                false
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                true
            }
        } catch (e: Exception) {
            toast(ctx, "打开失败：${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    private fun toast(context: Context, msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
