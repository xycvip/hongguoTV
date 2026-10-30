// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** A normal, episode-level official comment. This is deliberately separate from Danmaku. */
data class OfficialComment(
    val id: String,
    val text: String,
    val author: String,
    val avatar: String,
    val createdAt: Long,
    val likes: Int,
    val replies: Int,
    val liked: Boolean,
    val own: Boolean = false,
)

data class OfficialCommentPage(
    val items: List<OfficialComment>,
    val total: Int,
    val hasMore: Boolean,
    val nextCursor: String,
)

/**
 * Official Red Fruit ordinary comments (comment_source=4 / server_channel=18 /
 * comment_type=4). This repository never calls the danmaku time-window endpoint.
 */
class OfficialCommentRepository(
    private val http: OkHttpClient,
    private val playbackSession: PlaybackDeviceSession = PlaybackDeviceSession.shared,
) {
    private data class Identity(val device: Map<String, String>, val cookie: String)

    private fun identity(account: AccountCredentials?, hasBody: Boolean): Identity {
        if (account == null) {
            val profile = playbackSession.profile(http)
            return Identity(profile.values, profile.cookie)
        }
        val device = VendorConstants.device.toMutableMap().apply {
            putAll(account.device)
            put("device_id", account.deviceId)
            put("iid", account.installId)
            for (key in listOf("version_code", "manifest_version_code", "update_version_code", "pv_player")) {
                put(key, if (hasBody) "73932" else "72232")
            }
            put("version_name", if (hasBody) "7.3.9.32" else "7.2.2.32")
        }
        return Identity(device, account.cookie)
    }

    private fun call(
        account: AccountCredentials?,
        path: String,
        method: String,
        params: Map<String, String> = emptyMap(),
        body: JSONObject? = null,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject {
        val identity = identity(account, body != null)
        val bytes = body?.toString()?.toByteArray(Charsets.UTF_8)
        val signed = Signer(identity.device).sign(
            "https://api5-normal-sinfonlinea.fqnovel.com$path",
            identity.device + params,
            bytes,
        )
        val request = Request.Builder().url(signed.url).apply {
            if (identity.cookie.isNotBlank()) header("Cookie", identity.cookie)
            signed.headers.forEach { (key, value) -> header(key, value) }
            headers.forEach { (key, value) -> header(key, value) }
            if (bytes != null) post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
            else get()
        }.build()
        return RequestScope.execute(http.newCall(request)) { response ->
            if (!response.isSuccessful) throw IOException("官方评论请求失败（HTTP ${response.code}）")
            val responseBody = response.body ?: throw IOException("官方评论响应为空")
            if (responseBody.contentLength() > MAX_BYTES) throw IOException("官方评论响应过大")
            val raw = responseBody.bytes()
            if (raw.size > MAX_BYTES) throw IOException("官方评论响应过大")
            if (raw.isEmpty()) throw IOException("官方评论响应为空")
            val payload = runCatching { JSONObject(String(raw, Charsets.UTF_8)) }
                .getOrElse { throw IOException("官方评论响应格式异常") }
            val code = payload.optInt("code", -1)
            if (code != 0) throw IOException("官方评论接口暂不可用（错误码 $code）")
            payload
        }
    }

    fun list(
        account: AccountCredentials?,
        seriesId: String,
        vid: String,
        cursor: String = "0",
    ): OfficialCommentPage {
        requireValidId(seriesId, "短剧")
        requireValidId(vid, "剧集")
        require(cursor.length <= 4000) { "评论分页游标无效" }
        val business = JSONObject().put("need_count", true).put("book_id", seriesId).put("req_type", 0)
        val params = linkedMapOf(
            "comment_source" to "4",
            "server_channel" to "18",
            "group_id" to vid,
            "group_type" to "30",
            "comment_type" to "4",
            "sort" to "1",
            "need_count" to "true",
            "book_id" to seriesId,
            "req_type" to "0",
            "business_param" to business.toString(),
            "count" to "20",
            "cursor" to cursor.ifBlank { "0" },
            "aid" to "8662",
            "compliance_status" to "0",
        )
        val payload = call(
            account,
            "/novel/commentapi/comment/list/$vid/v1/",
            "GET",
            params,
            headers = mapOf("comment-source" to "4", "server-channel" to "18"),
        )
        val data = payload.optJSONObject("data") ?: throw IOException("评论列表数据缺失")
        val info = data.optJSONObject("common_list_info") ?: throw IOException("评论分页信息缺失")
        val rows = data.optJSONArray("data_list") ?: throw IOException("评论列表格式异常")
        val items = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val comment = row.optJSONObject("comment") ?: row
            parseComment(comment)
        }
        val next = info.optString("cursor", "")
        val hasMore = info.optBoolean("has_more", false) && next.isNotBlank() && next != cursor
        return OfficialCommentPage(items, info.optInt("total", items.size), hasMore, next)
    }

    fun replies(
        account: AccountCredentials?,
        seriesId: String,
        vid: String,
        commentId: String,
        cursor: String = "0",
    ): OfficialCommentPage {
        requireValidId(seriesId, "短剧")
        requireValidId(vid, "剧集")
        requireValidId(commentId, "评论")
        require(cursor.length <= 4000) { "回复分页游标无效" }
        val body = JSONObject()
            .put("comment_source", 504)
            .put("server_channel", 18)
            .put("comment_id", commentId)
            .put("comment_type", 4)
            .put("group_id", vid)
            .put("group_type", 30)
            .put("business_param", JSONObject().put("need_count", true).put("book_id", seriesId))
            .put("count", 10)
            .put("cursor", cursor.ifBlank { "0" })
            .put("aid", 8662)
            .put("compliance_status", 0)
        val payload = call(
            account,
            "/novel/commentapi/reply/list/$commentId/v1/",
            "POST",
            body = body,
            headers = mapOf("comment-source" to "504", "server-channel" to "18"),
        )
        val data = payload.optJSONObject("data") ?: throw IOException("回复列表数据缺失")
        val info = data.optJSONObject("comment_list_info") ?: throw IOException("回复分页信息缺失")
        val rows = data.optJSONArray("reply_list") ?: throw IOException("回复列表格式异常")
        val items = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            parseComment(row.optJSONObject("reply") ?: row, reply = true)
        }
        val next = info.optString("cursor", "")
        val hasMore = info.optBoolean("has_more", false) && next.isNotBlank() && next != cursor
        return OfficialCommentPage(items, info.optInt("total", items.size), hasMore, next)
    }

    fun publish(account: AccountCredentials?, seriesId: String, vid: String, text: String): String {
        val credentials = account ?: throw IOException("登录红果账号后才能发表评论")
        requireValidId(seriesId, "短剧")
        requireValidId(vid, "剧集")
        val content = checkedText(text)
        val business = baseBusiness(seriesId).apply {
            put("offset", 0)
            put("text_feature", JSONObject())
            put("has_aigc_content", false)
            put("log_extra", JSONObject())
            put("preset_text_id", "")
        }
        val body = JSONObject()
            .put("commit_source", 3)
            .put("group_id", vid)
            .put("group_type", 30)
            .put("data_type", 4)
            .put("text", content)
            .put("business_param", business)
            .put("aid", 8662)
        val data = call(credentials, "/novel/commentapi/comment/add/v1/", "POST", body = body)
            .optJSONObject("data") ?: throw IOException("评论发布结果缺失")
        val id = data.optJSONObject("comment_info")?.optString("comment_id").orEmpty()
        if (!validId(id)) throw IOException("平台未返回评论编号，请刷新列表确认，避免重复发送")
        return id
    }

    fun reply(account: AccountCredentials?, seriesId: String, vid: String, commentId: String, text: String): String {
        val credentials = account ?: throw IOException("登录红果账号后才能回复评论")
        requireValidId(seriesId, "短剧")
        requireValidId(vid, "剧集")
        requireValidId(commentId, "评论")
        val content = checkedText(text)
        val business = baseBusiness(seriesId).apply {
            put("offset", 0)
            put("text_feature", JSONObject())
            put("has_aigc_content", false)
            put("log_extra", JSONObject())
        }
        val body = JSONObject()
            .put("commit_source", 9)
            .put("group_id", vid)
            .put("group_type", 30)
            .put("data_type", 4)
            .put("text", content)
            .put("reply_to_comment_id", commentId)
            .put("business_param", business)
            .put("aid", 8662)
        val data = call(credentials, "/novel/commentapi/reply/add/v1/", "POST", body = body)
            .optJSONObject("data") ?: throw IOException("回复发布结果缺失")
        val id = data.optJSONObject("reply")?.optString("reply_id").orEmpty()
        if (!validId(id)) throw IOException("平台未返回回复编号，请刷新列表确认，避免重复发送")
        return id
    }

    fun setLiked(account: AccountCredentials?, seriesId: String, commentId: String, liked: Boolean) {
        val credentials = account ?: throw IOException("登录红果账号后才能点赞")
        requireValidId(seriesId, "短剧")
        requireValidId(commentId, "评论")
        val body = JSONObject()
            .put("object_id", commentId)
            .put("object_type", 8)
            .put("action_type", if (liked) 8 else 9)
            .put("comment_type", 4)
            .put("business_param", JSONObject().put("shark_param", sharkParam()))
        call(credentials, "/novel/commentapi/comment/do_action/v1/", "POST", body = body)
    }

    private fun baseBusiness(seriesId: String) = JSONObject()
        .put("ignore_urge_rule", false)
        .put("book_id", seriesId)
        .put("shark_param", sharkParam())

    private fun sharkParam() = JSONObject()
        .put("enter_from", "MainFragmentActivity")
        .put("previous_page", "")
        .put("page_list", "MainFragmentActivity")

    private fun checkedText(value: String): String {
        val text = value.trim()
        require(text.isNotEmpty() && text.codePointCount(0, text.length) <= 500) { "评论不能为空或超过500字" }
        return text
    }

    private fun requireValidId(value: String, label: String) {
        require(validId(value) && value != "0") { "$label ID 无效" }
    }

    private fun validId(value: String) = value.matches(Regex("[0-9]{1,32}"))

    private fun parseComment(comment: JSONObject, reply: Boolean = false): OfficialComment? {
        val common = comment.optJSONObject("common") ?: comment.optJSONObject("Common") ?: JSONObject()
        val content = common.optJSONObject("content") ?: JSONObject()
        val expand = comment.optJSONObject("expand") ?: JSONObject()
        val stat = comment.optJSONObject("stat") ?: JSONObject()
        val user = common.optJSONObject("user_info") ?: JSONObject()
        val base = user.optJSONObject("base_info") ?: JSONObject()
        val action = comment.optJSONObject("user_action") ?: JSONObject()
        val id = comment.opt(if (reply) "reply_id" else "comment_id")?.toString().orEmpty()
        val text = content.optString("text", "").trim()
        if (!validId(id) || text.isBlank()) return null
        val avatar = base.optString("expand_user_avatar").ifBlank { base.optString("user_avatar") }
        return OfficialComment(
            id = id,
            text = text,
            author = base.optString("user_name").ifBlank { "红果用户" },
            avatar = avatar,
            createdAt = common.optLong("create_timestamp", 0L),
            likes = stat.optInt("digg_count", 0).coerceAtLeast(0),
            replies = stat.optInt("reply_count", 0).coerceAtLeast(0),
            liked = action.optBoolean("user_digg", false),
        )
    }

    companion object {
        private const val MAX_BYTES = 2 * 1024 * 1024
    }
}
