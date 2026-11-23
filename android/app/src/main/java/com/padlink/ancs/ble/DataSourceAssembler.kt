package com.padlink.ancs.ble

/**
 * ANCS Data Source 响应流式重组器。
 *
 * 响应格式：[CommandID(1B) | UID(4B 小端) | 三元组序列(AttrID(1B), Len(2B 小端), 值(Len B))]，
 * 可能被 iOS 按 MTU 拆成多个 GATT notification。
 *
 * 增量流式解析：每喂入一段字节就尝试解析，能完整解析出一个三元组即产出消费，
 * 剩余不完整字节留在内部缓冲，等待下一个分片。断线时调用 [reset] 清空。
 */
class DataSourceAssembler {

    data class Result(val uid: Int, val attrId: Int, val value: ByteArray)

    companion object {
        /** attrId 为 ATTR_ERROR 时表示解析到 ANCS 错误响应，value = 单字节错误码（0xA0-0xA3）。 */
        const val ATTR_ERROR = -1
        private const val ERROR_MIN = 0xA0
        private const val ERROR_MAX = 0xA3
    }

    private var buf = ByteArray(512)
    private var len = 0

    fun feed(chunk: ByteArray): List<Result> {
        val results = ArrayList<Result>()
        if (chunk.isNotEmpty()) {
            ensureCapacity(len + chunk.size)
            System.arraycopy(chunk, 0, buf, len, chunk.size)
            len += chunk.size
        }

        var offset = 0
        while (len - offset >= 6) {
            // 错误响应：[CommandID, UID(4B), ErrorCode(1B)]，共 6 字节，ErrorCode ∈ 0xA0..0xA3。
            // 合法的三元组 AttrID 只可能是 0x00-0x07，不会与错误码冲突。
            if (len - offset == 6) {
                val err = buf[offset + 5].toInt() and 0xFF
                if (err in ERROR_MIN..ERROR_MAX) {
                    results.add(Result(AncsBytes.leInt(buf, offset + 1), ATTR_ERROR, byteArrayOf(buf[offset + 5])))
                    offset += 6
                    continue
                }
                break
            }
            if (len - offset < 7) break

            var p = offset + 5
            var parsedAny = false
            while (len - p >= 3) {
                val attrId = buf[p].toInt() and 0xFF
                val valueLen = AncsBytes.leShort(buf, p + 1)
                if (len - (p + 3) < valueLen) break // 值未到齐，等下一个分片
                results.add(Result(AncsBytes.leInt(buf, offset + 1), attrId, buf.copyOfRange(p + 3, p + 3 + valueLen)))
                p += 3 + valueLen
                parsedAny = true
            }
            if (!parsedAny) break
            offset = p
        }

        if (offset > 0) {
            val remaining = len - offset
            System.arraycopy(buf, offset, buf, 0, remaining)
            len = remaining
        }
        return results
    }

    fun reset() {
        len = 0
    }

    private fun ensureCapacity(cap: Int) {
        if (buf.size < cap) buf = buf.copyOf(maxOf(cap, buf.size * 2))
    }
}
