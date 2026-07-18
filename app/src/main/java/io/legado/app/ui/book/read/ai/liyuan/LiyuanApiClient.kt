package io.legado.app.ui.book.read.ai.liyuan

import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 梨园 REST API 客户端
 *
 * 通过 HTTP 接口访问梨园后端的角色卡、世界书、会话管理等资源。
 * 当前为阶段1 基础实现，阶段2 将补充完整 CRUD。
 *
 * 基础 URL 与 WebSocket 同源（从 AiConfig 读取 wsUrl 推导）。
 */
class LiyuanApiClient(private val baseUrl: String) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * 获取角色卡列表
     */
    suspend fun getCards(): List<CardDto> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/cards"
        val request = Request.Builder().url(url).get().build()
        val response = okHttpClient.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext emptyList()
        @Suppress("UNCHECKED_CAST")
        val json = GSON.fromJson(body, Map::class.java) as? Map<String, Any?>
        val cards = json?.get("cards") as? List<Map<String, Any?>> ?: emptyList()
        cards.mapNotNull { parseCard(it) }
    }

    /**
     * 获取当前角色卡详情
     */
    suspend fun getCurrentCard(): CardDto? = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/card"
        val request = Request.Builder().url(url).get().build()
        val response = okHttpClient.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext null
        @Suppress("UNCHECKED_CAST")
        val json = GSON.fromJson(body, Map::class.java) as? Map<String, Any?>
        parseCard(json)
    }

    /**
     * 获取世界书列表
     */
    suspend fun getLorebooks(): List<LorebookDto> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/lorebooks"
        val request = Request.Builder().url(url).get().build()
        val response = okHttpClient.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext emptyList()
        @Suppress("UNCHECKED_CAST")
        val json = GSON.fromJson(body, Map::class.java) as? Map<String, Any?>
        val books = json?.get("books") as? List<Map<String, Any?>> ?: emptyList()
        books.mapNotNull { parseLorebook(it) }
    }

    /**
     * 获取会话列表
     */
    suspend fun getSessions(): List<SessionDto> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/sessions"
        val request = Request.Builder().url(url).get().build()
        val response = okHttpClient.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext emptyList()
        @Suppress("UNCHECKED_CAST")
        val json = GSON.fromJson(body, Map::class.java) as? Map<String, Any?>
        val sessions = json?.get("sessions") as? List<Map<String, Any?>> ?: emptyList()
        sessions.mapNotNull { parseSession(it) }
    }

    // ===== 内部解析 =====

    @Suppress("UNCHECKED_CAST")
    private fun parseCard(map: Map<String, Any?>?): CardDto? {
        if (map == null) return null
        return CardDto(
            path = map["path"] as? String ?: "",
            name = map["name"] as? String ?: "未知角色",
            description = map["description"] as? String ?: "",
            avatar = map["avatar"] as? String
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseLorebook(map: Map<String, Any?>?): LorebookDto? {
        if (map == null) return null
        return LorebookDto(
            path = map["path"] as? String ?: "",
            name = map["name"] as? String ?: "未知世界书",
            entryCount = (map["entryCount"] as? Number)?.toInt() ?: 0
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseSession(map: Map<String, Any?>?): SessionDto? {
        if (map == null) return null
        return SessionDto(
            path = map["path"] as? String ?: "",
            name = map["name"] as? String ?: "未知会话",
            current = map["current"] as? Boolean ?: false
        )
    }

    // ===== 数据类 =====

    data class CardDto(
        val path: String,
        val name: String,
        val description: String,
        val avatar: String?
    )

    data class LorebookDto(
        val path: String,
        val name: String,
        val entryCount: Int
    )

    data class SessionDto(
        val path: String,
        val name: String,
        val current: Boolean
    )
}
