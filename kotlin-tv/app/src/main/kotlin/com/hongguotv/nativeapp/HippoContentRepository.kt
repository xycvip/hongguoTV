// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.util.Base64
import android.util.Log
import com.hongguotv.core.*
import okhttp3.OkHttpClient
import okhttp3.CookieJar
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 河马剧场的 ContentRepository 适配层。
 * UI 继续使用现有 Series/CatalogPage/Detail 模型，但所有数据请求均由独立 HippoPortalApi 完成。
 */
internal class HippoContentRepository(context: Context, http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).cookieJar(CookieJar.NO_COOKIES).build()) : ContentRepository(http) {
    private val api = HippoPortalApi(context, http)
    private val homeCursors = ConcurrentHashMap<String, String>()
    private val seriesCache = ConcurrentHashMap<String, Series>()
    private val lockedEpisodeIds = ConcurrentHashMap.newKeySet<String>()
    private val theatreCursors = ConcurrentHashMap<String, JSONObject>()
    private val theatreGroupIds = ConcurrentHashMap<String, String>()
    private val rankingCursors = ConcurrentHashMap<String, String>()

    override fun home(page: Int, type: ContentType, genre: Genre?): CatalogPage {
        require(page in 1..100)
        api.ensureInitialized()
        val key = "home:${type.name}:${genre?.slug.orEmpty()}"
        val cursor = if (page <= 1) "" else homeCursors[key].orEmpty()
        val params = JSONObject().put("pageFlag", cursor).put("watchRecords", JSONArray())
        val data = api.request("1113", params)
        val rows = findList(data, "dataList")
        val items = rows.mapNotNull(::series).filter { it.id.isNotBlank() }
        homeCursors[key] = data.optString("pageFlag")
        return CatalogPage(items, data.optBoolean("hasMore"))
    }

    override fun theater(page: Int, type: ContentType?, selected: Map<String, String>): TheaterCatalogPage {
        api.ensureInitialized()
        val typeKey = type?.name ?: "ALL"
        val requestedGroupId = selected["channelGroupId"]?.takeIf { it.isNotBlank() }
            ?: theatreGroupIds[typeKey]?.takeIf { page > 1 }
        val key = typeKey + ":" + requestedGroupId.orEmpty() + ":" + selected["channelId"].orEmpty()
        val previous = theatreCursors[key]
        val params = JSONObject()
        requestedGroupId?.let { params.put("channelGroupId", it) }
        selected["channelId"]?.takeIf { it.isNotBlank() }?.let { params.put("channelId", it.toIntOrNull() ?: it) }
        if (page > 1 && previous != null) {
            listOf("pageFlag", "fromColumnId", "fromItemId", "lastColumnStyle").forEach { field ->
                if (previous.has(field) && !previous.isNull(field)) params.put(field, previous.opt(field))
            }
        }
        var data = try { api.request("1125", params) } catch (first: Exception) {
            params.put("recSwitch", true).put("playListFlag", true)
            try { api.request("1121", params) } catch (second: Exception) {
                second.addSuppressed(first); throw second
            }
        }
        val rawGroups = findList(data, "channelGroupData")
        val seenBookGroups = mutableSetOf<String>()
        val groups = rawGroups.filter { group ->
            val isBookGroup = value(group, "channelGroupType") == "2"
            !isBookGroup || seenBookGroups.add(value(group, "channelGroupName", "title").trim())
        }
        // 1125/1121 的 checkedFlag 是服务端默认分类，不能拿它覆盖用户选择的内容类型。
        // 河马剧场按接口返回的真实分类名称定位 groupId；绝不把漫剧默认组当成真人剧。
        fun matchesContentType(group: JSONObject, requested: ContentType): Boolean {
            val name = value(group, "channelGroupName", "title").trim().lowercase()
            return when (requested) {
                ContentType.SHORT -> (name.contains("真人") || name.contains("短剧")) &&
                    !name.contains("漫剧") && !name.contains("动漫") && !name.contains("ai")
                ContentType.COMIC -> name.contains("漫剧") || name.contains("动漫") || name.contains("漫画")
                ContentType.AI -> name.contains("ai") || name.contains("人工智能")
            }
        }
        val explicitlySelectedGroup = selected["channelGroupId"]?.let { id ->
            groups.firstOrNull { value(it, "channelGroupId", "id") == id }
        }
        val typedGroup = type?.let { requested -> groups.firstOrNull { matchesContentType(it, requested) } }
        val selectedGroup = when {
            explicitlySelectedGroup != null && (type == null || matchesContentType(explicitlySelectedGroup, type)) -> explicitlySelectedGroup
            typedGroup != null -> typedGroup
            type == null -> groups.firstOrNull { it.optBoolean("checkedFlag") } ?: groups.firstOrNull()
            else -> throw IOException("河马剧场未返回「${type?.label ?: "指定类型"}」分类，已阻止错误展示其他类型内容")
        }
        val selectedGroupId = selectedGroup?.let { value(it, "channelGroupId", "id") }.orEmpty()
        if (selectedGroupId.isNotBlank()) {
            theatreGroupIds[typeKey] = selectedGroupId
            // 首次请求先读取分类元数据；若服务端默认勾选的不是目标类型，
            // 必须带上真实 channelGroupId 再请求一次，不能只在本地选中分组却继续展示默认漫剧数据。
            if (requestedGroupId != selectedGroupId) {
                params.put("channelGroupId", selectedGroupId)
                data = try { api.request("1125", params) } catch (first: Exception) {
                    params.put("recSwitch", true).put("playListFlag", true)
                    try { api.request("1121", params) } catch (second: Exception) {
                        second.addSuppressed(first); throw second
                    }
                }
            }
        }
        val resolvedKey = typeKey + ":" + selectedGroupId + ":" + selected["channelId"].orEmpty()
        val groupOptions = (if (type == null) groups else emptyList()).mapNotNull { group ->
            val id = value(group, "channelGroupId", "id")
            val label = value(group, "channelGroupName", "title")
            if (id.isBlank() || label.isBlank()) null else TheaterFilterOption(id, label)
        }
        val channelRows = (selectedGroup?.optJSONArray("channelData") ?: JSONArray()).objects()
        val channelOptions = channelRows.mapNotNull { channel ->
            val id = value(channel, "channelId", "id")
            val label = value(channel, "channelName", "title")
            if (id.isBlank() || label.isBlank()) null else TheaterFilterOption(id, label)
        }
        val selectedChannelId = selected["channelId"]
            ?: channelRows.firstOrNull { it.optBoolean("checkedFlag") }?.let { value(it, "channelId", "id") }
            ?: channelOptions.firstOrNull()?.id
        val filters = buildList {
            if (groupOptions.isNotEmpty()) add(TheaterFilterGroup("channelGroupId", "分类", groupOptions))
            if (channelOptions.isNotEmpty()) add(TheaterFilterGroup("channelId", selectedGroup?.let { value(it, "channelGroupName", "title") } ?: "频道", channelOptions))
        }
        val columns = findList(data, "columnData")
        theatreCursors[resolvedKey] = JSONObject().also { cursor ->
            listOf("pageFlag", "fromColumnId", "fromItemId").forEach { f -> if (data.has(f)) cursor.put(f, data.opt(f)) }
            columns.lastOrNull()?.optInt("styleType")?.takeIf { it > 0 }?.let { cursor.put("lastColumnStyle", it) }
        }
        val items = columns.flatMap { column -> findList(column, "videoData") }.mapNotNull(::series)
        return TheaterCatalogPage(CatalogPage(items, data.optBoolean("hasMore")), filters,
            if (data.optBoolean("hasMore")) page + 1 else null, "hippo:$key")
    }

    override fun theaterFilters(type: ContentType): List<TheaterFilterGroup> = theater(1, type).filters

    override fun genres(type: ContentType): List<Genre> = theaterFilters(type).map { group ->
        Genre(group.type, group.label, group.options.map { it.id })
    }

    /**
     * 河马平台的「排行榜」入口展示官方精选剧单。
     * 1115 返回剧单卡片与 formItemId 游标；点开卡片后再由 1116 拉取剧单内短剧。
     */
    override fun ranking(board: RankBoard, page: Int): CatalogPage {
        api.ensureInitialized()
        val cursor = if (page <= 1) "" else rankingCursors["playlist"].orEmpty()
        val params = JSONObject()
        if (cursor.isNotBlank()) params.put("formItemId", cursor)
        val data = api.request("1115", params)
        val rows = findList(data, "playListData")
        val items = rows.mapNotNull { row ->
            val id = value(row, "id", "playListId")
            val title = value(row, "mainTitle", "name", "title")
            if (id.isBlank() || title.isBlank()) null else {
                val subtitle = value(row, "subTitle", "subtitle", "description")
                // 1115 的剧单对象未必提供独立封面；Flutter 原版从 bookInfoVoList 读取成员，
                // 因此优先取剧单显式封面，缺失时回退第一部短剧的封面。
                val firstDrama = findList(row, "bookInfoVoList").firstOrNull()
                val cover = value(
                    row, "coverWap", "coverCutWap", "coverUrl", "cover", "pic",
                    "coverImg", "imageUrl", "imgUrl", "playlistCover", "playListCover"
                ).ifBlank {
                    firstDrama?.let { value(it, "coverWap", "coverCutWap", "coverUrl", "chapterImg") }.orEmpty()
                }
                val dramaCount = findList(row, "bookInfoVoList").size
                Series(
                    id = "hippo-playlist:$id",
                    title = title,
                    cover = cover,
                    description = subtitle,
                    badge = if (dramaCount > 0) "$dramaCount 部短剧" else if (row.optBoolean("showSort") || row.optInt("showSort") == 1) "排行榜单" else "精选剧单",
                    tags = "河马剧单"
                )
            }
        }
        rankingCursors["playlist"] = data.optString("formItemId")
        return CatalogPage(items, data.optBoolean("hasMore"))
    }

    /** 1116：获取剧单详情中的短剧列表，短剧本身仍走 1132/1133 原生详情与播放链路。 */
    fun playlistDetail(playlistId: String): List<Series> {
        api.ensureInitialized()
        val data = api.request("1116", JSONObject().put("playListId", playlistId))
        val payload = data.optJSONObject("playListVo") ?: data
        val rows = findList(payload, "bookInfoVoList")
        return rows.mapNotNull(::series)
    }

    override fun search(keyword: String, page: Int, type: ContentType): CatalogPage {
        if (keyword.isBlank()) return CatalogPage(emptyList(), false)
        api.ensureInitialized()
        val data = api.request("1803", JSONObject().put("keyword", keyword).put("page", page).put("size", 15)
            .put("searchSource", "搜索按钮").put("hotWordType", 2))
        val rows = listOf("searchVos", "guessLikes", "dataList").firstNotNullOfOrNull { key ->
            findList(data, key).takeIf { it.isNotEmpty() }
        }.orEmpty()
        val more = data.optBoolean("isMore") || data.optInt("isMore") == 1 || data.optBoolean("hasMore")
        return CatalogPage(rows.mapNotNull(::series), more)
    }

    override fun searchCombined(keyword: String, page: Int): CatalogPage = search(keyword, page, ContentType.SHORT)

    override fun probeSuggest(query: String): Int = suggest(query).size

    override fun sameNameSeasons(current: Series, related: List<Series>): List<Series> = emptyList()

    override fun suggest(query: String): List<String> {
        api.ensureInitialized()
        val data = api.request("1801")
        val wordVo = data.optJSONObject("searchWordVo")
        val words = wordVo?.optJSONArray("data") ?: JSONArray()
        return words.objects().map { value(it, "tags") }.filter { it.isNotBlank() }.distinct()
    }

    override fun detail(id: String): Detail {
        val (bookId, _) = decodeEpisodeId(id)
        api.ensureInitialized()
        val data = api.request("1132", JSONObject().put("bookId", bookId).put("chapterMin", 0).put("chapterMax", 100))
        val chapters = findList(data, "chapterList").sortedBy { it.optInt("chapterIndex", it.optInt("index", 0)) }
        val episodes = chapters.mapNotNull { chapter ->
            val chapterId = value(chapter, "chapterId", "chapter_id", "chapterid", "id")
            chapterId.takeIf { it.isNotBlank() }?.let {
                val encoded = encodeEpisodeId(bookId, it)
                if (chapter.optInt("isCharge", 0) == 1) lockedEpisodeIds.add(encoded)
                else lockedEpisodeIds.remove(encoded)
                encoded
            }
        }
        val info = findObject(data, setOf("bookInfoVo", "bookInfo", "bookVo"))
        val series = seriesCache[bookId] ?: Series(bookId, value(info, "bookName", "title").ifBlank { "河马短剧" },
            value(info, "coverWap", "coverCutWap", "coverUrl"), value(info, "introduction", "description", "bookDesc"),
            value(info, "finishStatusCn"), value(info, "bookTags", "tags"))
        return Detail(series, episodes)
    }

    override fun stream(id: String, maxQuality: Int): StreamInfo {
        if (lockedEpisodeIds.contains(id)) throw IOException("该集为付费内容，已按原平台规则阻止播放")
        val (bookId, chapterId) = decodeEpisodeId(id)
        if (chapterId.isBlank()) throw IOException("河马播放请求缺少 chapterId")
        api.ensureInitialized()
        val data = api.request("1133", JSONObject().put("bookId", bookId).put("chapterId", chapterId)
            .put("unClockType", "pay").put("confirmPay", 1).put("autoPayFlag", false))
        val rows = findList(data, "chapterInfo")
        val row = rows.firstOrNull { value(it, "chapterId", "chapter_id") == chapterId } ?: rows.firstOrNull() ?: data
        val urls = collectUrls(row, maxQuality)
        val url = urls.firstOrNull() ?: throw IOException("河马播放接口未返回可用地址")
        val mime = if (url.substringBefore('?').endsWith(".m3u8", true)) "application/x-mpegURL" else "video/mp4"
        return StreamInfo(url, null, if (url.contains("1080")) "1080P" else "720P", mime)
    }

    fun rememberSeries(series: Series) { seriesCache[series.id] = series }

    private fun series(row: JSONObject): Series? {
        val episodeId = value(row, "chapterId", "id")
        val bookId = value(row, "bookId", "book_id", "bookid").ifBlank { value(row, "id") }
        val id = bookId.ifBlank { episodeId }
        val title = value(row, "bookName", "title", "name")
        if (id.isBlank() || title.isBlank()) return null
        val count = row.optInt("updateNum", row.optInt("chapterCount", row.optInt("totalChapterNum", 0)))
        val tags = strings(row.opt("bookTags")).ifEmpty { strings(row.opt("tags") ) }.joinToString(" · ")
        val badge = value(row, "finishStatusCn", "chapterName", "coverBottomTips")
        val mapped = Series(id, title, value(row, "coverWap", "coverCutWap", "coverUrl", "chapterImg"),
            value(row, "introduction", "description", "bookDesc"), badge.ifBlank { if (count > 0) "全 $count 集" else "" }, tags)
        seriesCache[id] = mapped
        while (seriesCache.size > 500) {
            val oldest = seriesCache.keys.firstOrNull() ?: break
            seriesCache.remove(oldest)
        }
        return mapped
    }

    private fun findList(root: JSONObject, key: String): List<JSONObject> {
        if (root.opt(key) is JSONArray) return root.optJSONArray(key)!!.objects()
        val keys = root.keys()
        while (keys.hasNext()) {
            val value = root.opt(keys.next())
            val found = when (value) {
                is JSONObject -> findList(value, key)
                is JSONArray -> value.objects().firstNotNullOfOrNull { findList(it, key).takeIf { rows -> rows.isNotEmpty() } }.orEmpty()
                else -> emptyList()
            }
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private fun findObject(root: JSONObject, names: Set<String>): JSONObject {
        names.firstNotNullOfOrNull { root.optJSONObject(it) }?.let { return it }
        val keys = root.keys()
        while (keys.hasNext()) when (val value = root.opt(keys.next())) {
            is JSONObject -> findObject(value, names).takeIf { it.length() > 0 }?.let { return it }
            is JSONArray -> value.objects().forEach { findObject(it, names).takeIf { o -> o.length() > 0 }?.let { return it } }
        }
        return JSONObject()
    }

    private fun collectUrls(root: JSONObject, maxQuality: Int): List<String> {
        val high = listOf("m3u81080pUrl", "m3u81080p", "mp41080pUrl", "mp41080p")
        val medium = listOf("m3u8720pUrl", "m3u8720p", "mp4720pUrl", "mp4720p")
        val priority = (if (maxQuality in 1..719) medium + high else high + medium) + listOf(
            "mp4", "m3u8", "url",
            "m3u81080pSwitchUrl", "mp41080pSwitchUrl",
            "m3u8720pSwitchUrl", "mp4720pSwitchUrl"
        )
        val found = linkedMapOf<String, LinkedHashSet<String>>()
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val iterator = value.keys()
                    while (iterator.hasNext()) {
                        val key = iterator.next()
                        val child = value.opt(key)
                        if (key in priority) {
                            when (child) {
                                is String -> if (child.startsWith("http")) found.getOrPut(key) { linkedSetOf() }.add(child)
                                is JSONArray -> for (index in 0 until child.length()) {
                                    val url = child.optString(index)
                                    if (url.startsWith("http")) found.getOrPut(key) { linkedSetOf() }.add(url)
                                }
                                else -> visit(child)
                            }
                        } else visit(child)
                    }
                }
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
            }
        }
        visit(root)
        return priority.flatMap { found[it].orEmpty() }
    }

    private companion object { const val EPISODE_ID_PREFIX = "hippo-episode:" }
    private fun encodeEpisodeId(book: String, chapter: String) = EPISODE_ID_PREFIX +
        Base64.encodeToString("$book\u0000$chapter".toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
    private fun decodeEpisodeId(id: String): Pair<String, String> {
        // 普通 bookId（详情入口）绝不尝试 Base64 解码；仅识别带专用前缀的内部剧集 ID。
        if (!id.startsWith(EPISODE_ID_PREFIX)) return id to ""
        val payload = id.removePrefix(EPISODE_ID_PREFIX)
        val decoded = runCatching { String(Base64.decode(payload, Base64.NO_WRAP or Base64.URL_SAFE)) }
            .getOrElse { throw IOException("河马剧集标识编码损坏", it) }
        val parts = decoded.split('\u0000', limit = 2)
        if (parts.size != 2 || parts.any { it.isBlank() }) throw IOException("河马剧集标识无效")
        return parts[0] to parts[1]
    }
    private fun value(row: JSONObject, vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
        row.opt(key)?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() }
    }.orEmpty()
    private fun strings(raw: Any?): List<String> = when(raw) {
        is JSONArray -> (0 until raw.length()).mapNotNull { raw.opt(it)?.toString()?.takeIf(String::isNotBlank) }
        is String -> listOf(raw)
        else -> emptyList()
    }
    private fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONArray.objectsOrStrings() = (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
}
