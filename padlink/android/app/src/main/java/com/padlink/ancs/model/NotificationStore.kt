package com.padlink.ancs.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 通知存储：内存 LinkedHashMap（UID 去重、上限 200 条环形淘汰）
 * + filesDir/notifications.json 持久化（一期不引入 Room，降低构建风险）。
 * 线程安全：GATT 回调在 binder 线程触发，所有读写加锁。
 */
object NotificationStore {

    const val MAX_ITEMS = 200
    private const val FILE_NAME = "notifications.json"

    private val lock = Any()
    private val items = LinkedHashMap<Int, AppNotification>()
    private val _flow = MutableStateFlow<List<AppNotification>>(emptyList())
    val flow: StateFlow<List<AppNotification>> = _flow.asStateFlow()

    private lateinit var context: Context
    private val io = Executors.newSingleThreadExecutor()
    private var saveQueued = false

    fun init(context: Context) {
        this.context = context.applicationContext
        load()
    }

    fun get(uid: Int): AppNotification? = synchronized(lock) { items[uid] }

    fun getAll(): List<AppNotification> = synchronized(lock) { items.values.toList() }

    /** 新增或更新事件字段（同一 UID 保留已取回的属性，等待属性响应覆盖）。 */
    fun upsert(n: AppNotification) {
        val existing = synchronized(lock) { items[n.uid] }
        val merged = if (existing == null) {
            n
        } else {
            n.copy(
                appIdentifier = existing.appIdentifier,
                title = existing.title,
                subtitle = existing.subtitle,
                message = existing.message,
                dateSeconds = existing.dateSeconds,
                positiveActionLabel = existing.positiveActionLabel,
                negativeActionLabel = existing.negativeActionLabel
            )
        }
        synchronized(lock) {
            items[n.uid] = merged
            while (items.size > MAX_ITEMS) {
                items.remove(items.keys.first())
            }
        }
        emit()
    }

    /** 按 UID 合并属性并返回更新后的通知；UID 不存在时返回 null。 */
    fun update(uid: Int, transform: (AppNotification) -> AppNotification): AppNotification? {
        val updated = synchronized(lock) {
            val cur = items[uid] ?: return@synchronized null
            val next = transform(cur)
            items[uid] = next
            next
        } ?: return null
        emit()
        return updated
    }

    fun remove(uid: Int) {
        synchronized(lock) { items.remove(uid) }
        emit()
    }

    /** 断线/会话结束：按 ANCS 会话语义清空全部通知与 UID 映射。 */
    fun clear() {
        synchronized(lock) { items.clear() }
        emit()
    }

    fun appIdentifiers(): Set<String> =
        synchronized(lock) { items.values.mapNotNull { it.appIdentifier }.toSet() }

    private fun emit() {
        val snapshot = synchronized(lock) { items.values.toList() }
        _flow.value = snapshot
        scheduleSave()
    }

    private fun scheduleSave() {
        synchronized(lock) {
            if (saveQueued) return
            saveQueued = true
        }
        io.execute {
            synchronized(lock) { saveQueued = false }
            writeToDisk()
        }
    }

    private fun writeToDisk() {
        val snapshot = synchronized(lock) { items.values.toList() }
        val arr = JSONArray()
        for (n in snapshot) {
            arr.put(
                JSONObject().apply {
                    put("uid", n.uid.toLong() and 0xFFFFFFFFL)
                    put("flags", n.eventFlags)
                    put("category", n.categoryId)
                    put("count", n.categoryCount)
                    n.appIdentifier?.let { put("app", it) }
                    n.title?.let { put("title", it) }
                    n.subtitle?.let { put("subtitle", it) }
                    n.message?.let { put("message", it) }
                    n.dateSeconds?.let { put("date", it) }
                    n.positiveActionLabel?.let { put("positive", it) }
                    n.negativeActionLabel?.let { put("negative", it) }
                    put("at", n.receivedAt)
                }
            )
        }
        try {
            context.getFileStreamPath(FILE_NAME).parentFile?.mkdirs()
            context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE).use { out ->
                out.write(arr.toString().toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            // 一期：持久化失败只记录，不影响内存态
            Log.w("NotificationStore", "persist failed", e)
        }
    }

    private fun load() {
        try {
            if (!context.getFileStreamPath(FILE_NAME).exists()) return
            val text = context.openFileInput(FILE_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(text)
            val list = ArrayList<AppNotification>()
            for (i in 0 until arr.length()) {
                try {
                    val o = arr.getJSONObject(i)
                    list.add(
                        AppNotification(
                            uid = (o.optLong("uid", 0L) and 0xFFFFFFFFL).toInt(),
                            eventFlags = o.optInt("flags", 0),
                            categoryId = o.optInt("category", 0),
                            categoryCount = o.optInt("count", 0),
                            appIdentifier = o.optString("app").takeIf { o.has("app") },
                            title = o.optString("title").takeIf { o.has("title") },
                            subtitle = o.optString("subtitle").takeIf { o.has("subtitle") },
                            message = o.optString("message").takeIf { o.has("message") },
                            dateSeconds = o.optLong("date").takeIf { o.has("date") },
                            positiveActionLabel = o.optString("positive").takeIf { o.has("positive") },
                            negativeActionLabel = o.optString("negative").takeIf { o.has("negative") },
                            receivedAt = o.optLong("at", System.currentTimeMillis())
                        )
                    )
                } catch (e: Exception) {
                    Log.w("NotificationStore", "skip bad entry", e)
                }
            }
            synchronized(lock) {
                items.clear()
                for (n in list.takeLast(MAX_ITEMS)) items[n.uid] = n
            }
            emit()
        } catch (e: Exception) {
            Log.w("NotificationStore", "load failed", e)
        }
    }
}
