package com.padlink.ancs.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    /** 已配对 iPhone 的 BLE 地址（配对成功后才保存）。 */
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    val autoReconnect: Boolean = true,
    /** 可选功能：自动连 iPhone 热点，一期未实现，仅预留开关（默认关闭）。 */
    val autoHotspot: Boolean = false,
    /** 系统通知正文最大长度（字符，100-2000）。 */
    val maxMessageLength: Int = 500,
    val blacklist: Set<String> = emptySet()
)

object Prefs {

    private const val FILE = "padlink_prefs"
    private const val KEY_ADDRESS = "device_address"
    private const val KEY_NAME = "device_name"
    private const val KEY_AUTO_RECONNECT = "auto_reconnect"
    private const val KEY_AUTO_HOTSPOT = "auto_hotspot"
    private const val KEY_MAX_LEN = "max_message_length"
    private const val KEY_BLACKLIST = "blacklist"

    private lateinit var sp: SharedPreferences
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        _settings.value = read()
    }

    fun setDevice(address: String?, name: String?) =
        update { it.copy(deviceAddress = address, deviceName = name) }

    fun setAutoReconnect(v: Boolean) = update { it.copy(autoReconnect = v) }

    fun setAutoHotspot(v: Boolean) = update { it.copy(autoHotspot = v) }

    fun setMaxMessageLength(v: Int) = update { it.copy(maxMessageLength = v.coerceIn(100, 2000)) }

    fun setBlacklisted(appId: String, blacklisted: Boolean) = update {
        val set = it.blacklist.toMutableSet()
        if (blacklisted) set.add(appId) else set.remove(appId)
        it.copy(blacklist = set)
    }

    private fun read(): Settings = Settings(
        deviceAddress = sp.getString(KEY_ADDRESS, null),
        deviceName = sp.getString(KEY_NAME, null),
        autoReconnect = sp.getBoolean(KEY_AUTO_RECONNECT, true),
        autoHotspot = sp.getBoolean(KEY_AUTO_HOTSPOT, false),
        maxMessageLength = sp.getInt(KEY_MAX_LEN, 500),
        blacklist = sp.getStringSet(KEY_BLACKLIST, emptySet())?.toSet() ?: emptySet()
    )

    private fun update(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        _settings.value = next
        sp.edit()
            .putString(KEY_ADDRESS, next.deviceAddress)
            .putString(KEY_NAME, next.deviceName)
            .putBoolean(KEY_AUTO_RECONNECT, next.autoReconnect)
            .putBoolean(KEY_AUTO_HOTSPOT, next.autoHotspot)
            .putInt(KEY_MAX_LEN, next.maxMessageLength)
            .putStringSet(KEY_BLACKLIST, next.blacklist)
            .apply()
    }
}
