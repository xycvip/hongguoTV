// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hongguotv.core.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// The backup and native library use the same immutable progress shape.
typealias WatchProgress = BackupProgress

/** Memory-only UI access; disk reads, JSON serialization and commits run on workers. */
class Library(context: Context) {
    private companion object {
        // A replacement Activity must not load before its predecessor's final checkpoint commits.
        val retiredWrites = mutableListOf<LatestWriteQueue<String, () -> Any?>>()
    }
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor { task -> Thread(task, "hongguotv-library-load") }
    private val prefs by lazy { app.getSharedPreferences("native-library-v1", Context.MODE_PRIVATE) }
    private val homePrefs by lazy { app.getSharedPreferences("native-home-cache-v1", Context.MODE_PRIVATE) }
    private val writes = LatestWriteQueue<String, () -> Any?>("hongguotv-library-write", write = { batch -> commit(prefs, batch) },
        failed = { Log.w("HongguoTV", "Local library commit failed; pending changes retained for retry") })
    private val homeWrites = LatestWriteQueue<String, () -> Any?>("hongguotv-home-write", write = { batch -> commit(homePrefs, batch) },
        failed = { Log.w("HongguoTV", "Home cache commit failed") })
    private var index: LibraryIndex? = null
    private val homes = mutableMapOf<ContentType, CatalogPage>()
    private val homeSavedAt = mutableMapOf<ContentType, Long>()
    private var loading = false
    private var closed = false
    private val readyCallbacks = mutableListOf<() -> Unit>()
    private val errorCallbacks = mutableListOf<(Throwable) -> Unit>()
    val isLoaded get() = index != null

    /** Call before showing the first page. Successful/error callbacks run on the main thread. */
    fun loadAsync(onFailure: (Throwable) -> Unit = { Log.e("HongguoTV", "Local library failed to load", it) }, onReady: () -> Unit) {
        check(!closed)
        if (isLoaded) { onReady(); return }
        readyCallbacks += onReady
        errorCallbacks += onFailure
        if (loading) return
        loading = true
        loader.execute {
            val result = runCatching {
                val retired = synchronized(retiredWrites) { retiredWrites.toList() }
                retired.forEach { check(it.awaitClosed(30, TimeUnit.SECONDS)) { "上次观看记录仍在保存，请稍后重试" } }
                synchronized(retiredWrites) { retiredWrites.removeAll(retired.toSet()) }
                val values = prefs.all
                val loaded = LibraryDisk.decode(values)
                // 0.19.7 片源入口默认隐藏：升级后首次加载放开一次，之后尊重用户在设置里的手动选择
                if(!values.containsKey("sourceEntryShownV2")) {
                    loaded.settings = loaded.settings.copy(sourceHidden = false)
                    prefs.edit().putBoolean("sourceEntryShownV2", true).putBoolean("sourceHidden", false).apply()
                }
                val now = System.currentTimeMillis()
                val cached = mutableMapOf<ContentType, CatalogPage>()
                val stamps = mutableMapOf<ContentType, Long>()
                val migration = homePrefs.edit()
                val oldKeys = mutableListOf<String>()
                ContentType.entries.forEach { type ->
                    val key = "home-${type.storedValue}"
                    val legacy = values[key] as? String
                    val raw = homePrefs.getString(key, null) ?: legacy.orEmpty()
                    CatalogSnapshot.decode(raw, now)?.let { page ->
                        cached[type] = page
                        stamps[type] = org.json.JSONObject(raw).getLong("savedAt")
                        if (!homePrefs.contains(key)) migration.putString(key, raw)
                    }
                    if (legacy != null) oldKeys += key
                }
                // Only remove legacy cache after the separate cache has been committed.
                if (oldKeys.isNotEmpty() && migration.commit()) {
                    prefs.edit().also { edit -> oldKeys.forEach(edit::remove) }.commit()
                }
                Triple(loaded, cached, stamps)
            }
            main.post {
                loading = false
                if (closed) { readyCallbacks.clear(); errorCallbacks.clear(); return@post }
                val ready = readyCallbacks.toList(); readyCallbacks.clear()
                val errors = errorCallbacks.toList(); errorCallbacks.clear()
                result.onSuccess { (loaded, cached, stamps) ->
                    index = loaded; homes.putAll(cached); homeSavedAt.putAll(stamps)
                    ready.forEach { it() }
                }.onFailure { failure -> errors.forEach { it(failure) } }
            }
        }
    }
    private fun state(): LibraryIndex = checkNotNull(index) { "Load the local library before accessing it" }
    private fun put(key: String, value: Any?) { writes.submit(mapOf(key to { value })) }
    private fun persist(vararg keys: String) {
        val state = state()
        val batch = linkedMapOf<String, () -> Any?>()
        keys.forEach { key ->
            // Each lambda captures an immutable snapshot; JSON is built by the serial writer.
            batch[key] = when (key) {
                "favorites" -> state.favorites().let { rows -> { LibraryDisk.series(rows) } }
                "later" -> state.later().let { rows -> { LibraryDisk.series(rows) } }
                "progress" -> state.history().let { rows -> { LibraryDisk.history(rows) } }
                "favoriteUpdates" -> state.updates().let { rows -> { LibraryDisk.updates(rows) } }
                "favoriteGroups" -> state.groupsSnapshot().let { rows -> { LibraryDisk.groups(rows) } }
                "searches" -> state.searches().let { rows -> { SearchHistory.encode(rows) } }
                "watched" -> state.watched().let { ids -> { ids } }
                "hidden" -> state.hidden().let { ids -> { ids } }
                else -> error("Unknown library partition")
            }
        }
        writes.submit(batch)
    }
    fun favorites() = state().favorites()
    fun favorite(id: String) = state().favorite(id)
    fun later() = state().later()
    fun queued(id: String) = state().queued(id)
    fun history(): List<WatchProgress> = state().history()
    fun progress(id: String): WatchProgress? = state().progress(id)
    fun searches() = state().searches()
    fun watched(id: String) = state().watched(id)
    fun hidden() = state().hidden()
    fun favoriteUpdate(id: String) = state().favoriteUpdate(id)
    fun updatedFavorites() = state().updatedFavorites()
    fun updates() = state().updates()
    fun groups() = state().groups()
    fun groupOf(id: String) = state().groupOf(id)
    fun setGroup(id: String, group: String?): Boolean = state().setGroup(id, group).also { if (it) persist("favoriteGroups") }
    fun favoriteLabel(id: String): String = favoriteUpdate(id)?.let {
        val season = if (it.hasNewSeason) "《${it.newSeasonTitle ?: "新季"}》已上线" else ""
        val episodes = if (it.added > 0) "新增 ${it.added} 集 · 更新至 ${it.total} 集" else "更新至 ${it.total} 集"
        listOf(season, episodes).filter { it.isNotEmpty() }.joinToString(" · ")
    } ?: "尚未检查更新"
    fun toggle(series: Series) = state().toggle(series).also { persist("favorites", "favoriteUpdates") }
    fun toggleLater(series: Series) = state().toggleLater(series).also { persist("later") }
    fun save(progress: WatchProgress) { if (state().save(progress)) persist("progress") }
    fun setWatched(id: String, value: Boolean) { if (state().setWatched(id, value)) persist("watched") }
    fun hide(id: String) { state().hide(id); persist("hidden") }
    fun unhideAll() { state().unhideAll(); persist("hidden") }
    fun rememberSearch(query: String, type: ContentType) { state().rememberSearch(query, type); persist("searches") }
    fun clearSearches() { state().clearSearches(); persist("searches") }
    fun removeHistory(id: String) { state().removeHistory(id); persist("progress", "watched") }
    fun clearHistory() { state().clearHistory(); persist("progress", "watched") }
    fun observeFavorite(id: String, count: Int, now: Long, acknowledge: Boolean = false, newSeason: Series? = null) {
        if (state().observeFavorite(id, count, now, acknowledge, newSeason)) persist("favoriteUpdates")
    }
    fun cachedHome(type: ContentType): CatalogPage? {
        state()
        val age = System.currentTimeMillis() - (homeSavedAt[type] ?: return null)
        return homes[type].takeIf { age in 0..7 * 24 * 60 * 60 * 1000L }
    }
    fun cacheHome(type: ContentType, items: List<Series>, hasMore: Boolean) {
        state()
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        val retained = items.take(30).map { it.copy(description = it.description.take(1500)) }
        homes[type] = CatalogPage(retained, hasMore)
        homeSavedAt[type] = now
        homeWrites.submit(mapOf("home-${type.storedValue}" to { CatalogSnapshot.encode(retained, hasMore, now) }))
    }
    var maxQuality: Int
        get() = state().settings.quality
        set(value) { val next = PlaybackQuality.normalize(value); if (next != maxQuality) { state().settings = state().settings.copy(quality = next); put("quality", next) } }
    var autoNext: Boolean
        get() = state().settings.autoNext
        set(value) { if (value != autoNext) { state().settings = state().settings.copy(autoNext = value); put("autoNext", value) } }
    var autoSeason: Boolean
        get() = state().settings.autoSeason
        set(value) { if (value != autoSeason) { state().settings = state().settings.copy(autoSeason = value); put("autoSeason", value) } }
    var danmaku: Boolean
        get() = state().settings.danmaku
        set(value) { if (value != danmaku) { state().settings = state().settings.copy(danmaku = value); put("danmaku", value) } }
    var danmakuOpacity: Int
        get() = state().settings.danmakuOpacity
        set(value) { val next = DanmakuPrefs.opacity(value); if (next != danmakuOpacity) { state().settings = state().settings.copy(danmakuOpacity = next); put("danmakuOpacity", next) } }
    var danmakuFont: Int
        get() = state().settings.danmakuFont
        set(value) { val next = DanmakuPrefs.font(value); if (next != danmakuFont) { state().settings = state().settings.copy(danmakuFont = next); put("danmakuFont", next) } }
    var danmakuSpeed: Int
        get() = state().settings.danmakuSpeed
        set(value) { val next = DanmakuPrefs.speed(value); if (next != danmakuSpeed) { state().settings = state().settings.copy(danmakuSpeed = next); put("danmakuSpeed", next) } }
    var danmakuRows: Int
        get() = state().settings.danmakuRows
        set(value) { val next = DanmakuPrefs.rows(value); if (next != danmakuRows) { state().settings = state().settings.copy(danmakuRows = next); put("danmakuRows", next) } }
    var contentType: ContentType
        get() = state().settings.type
        set(value) { if (value != contentType) { state().settings = state().settings.copy(type = value); put("contentType", value.storedValue) } }
    var playbackSpeed: Float
        get() = state().settings.speed
        set(value) { val next = PlaybackSpeed.normalize(value); if (next != playbackSpeed) { state().settings = state().settings.copy(speed = next); put("playbackSpeed", next) } }
    var frameMode: VideoFrameMode
        get() = state().settings.frame
        set(value) { if (value != frameMode) { state().settings = state().settings.copy(frame = value); put("frameMode", value.name) } }
    var showClock: Boolean
        get() = state().settings.showClock
        set(value) { if (value != showClock) { state().settings = state().settings.copy(showClock = value); put("showClock", value) } }
    /** Player overlays are device-local display preferences, independent of playback and clock visibility. */
    var videoInfoMode: Int
        get() = prefs.getInt("videoInfoMode", 0).coerceIn(0, 2)
        set(value) { prefs.edit().putInt("videoInfoMode", value.coerceIn(0, 2)).apply() }
    var showNetworkSpeed: Boolean
        get() = prefs.getBoolean("showNetworkSpeed", false)
        set(value) { prefs.edit().putBoolean("showNetworkSpeed", value).apply() }
    var theme: String
        get() = prefs.getString("theme", null) ?: "NIGHT"
        set(value) { prefs.edit().putString("theme", value).apply() }
    /** 导航栏「片源」入口是否隐藏：默认隐藏，用户可在设置中开启；随备份一起恢复。 */
    var sourceHidden: Boolean
        get() = state().settings.sourceHidden
        set(value) { if (value != sourceHidden) { state().settings = state().settings.copy(sourceHidden = value); put("sourceHidden", value) } }
    // Per-series intro/outro skips are stored locally and included in exported backups.
    private var marksCache: LinkedHashMap<String, SkipMark>? = null
    private fun marks(): LinkedHashMap<String, SkipMark> =
        marksCache ?: SkipMarks.decode(prefs.getString("skipMarks", null)).also { marksCache = it }
    fun skipMark(id: String): SkipMark? = marks()[id]
    fun setSkipMark(id: String, mark: SkipMark?) {
        val next = SkipMarks.set(marks(), id, mark)
        marksCache = next
        prefs.edit().putString("skipMarks", SkipMarks.encode(next)).apply()
    }
    fun skipMarksSnapshot(): Map<String, SkipMark> = marks().toMap()
    fun restoreSkipMarks(incoming: Map<String, SkipMark>) {
        val normalized = SkipMarks.decode(SkipMarks.encode(incoming))
        marksCache = normalized
        prefs.edit().putString("skipMarks", SkipMarks.encode(normalized)).apply()
    }
    fun snapshot() = state().snapshot(
        // 三方源/观看历史/收藏来自独立存储，任何异常都退化为空列表，不能让备份整体失败
        runCatching { thirdSourcesProvider?.invoke() ?: emptyList() }.getOrDefault(emptyList()),
        runCatching { thirdHistoryProvider?.invoke() ?: emptyList() }.getOrDefault(emptyList()),
        runCatching { thirdFavoritesProvider?.invoke() ?: emptyList() }.getOrDefault(emptyList()))

    /** 由 MainActivity 注入：三方片源配置、观看历史与收藏随主备份一起写入与恢复。 */
    var thirdSourcesProvider: (() -> List<BackupThirdSource>)? = null
    var thirdSourcesRestore: ((List<BackupThirdSource>) -> Unit)? = null
    var thirdHistoryProvider: (() -> List<BackupThirdHistory>)? = null
    var thirdHistoryRestore: ((List<BackupThirdHistory>) -> Unit)? = null
    var thirdFavoritesProvider: (() -> List<BackupThirdFavorite>)? = null
    var thirdFavoritesRestore: ((List<BackupThirdFavorite>) -> Unit)? = null

    fun restore(incoming: BackupData, restoreSettings: Boolean) {
        // 文件恢复以备份为准覆盖本机记录；仅在用户选择保留时沿用当前播放/设备偏好。
        val current = snapshot()
        val restored = incoming.copy(settings = if (restoreSettings) incoming.settings else current.settings)
        index = LibraryIndex(restored, emptyMap())
        persist("favorites", "later", "progress", "watched", "hidden", "searches", "favoriteUpdates", "favoriteGroups")
        // 同样按备份内容恢复三方数据，空列表也代表清空对应本机记录。
        runCatching { thirdSourcesRestore?.invoke(incoming.thirdSources) }
        runCatching { thirdHistoryRestore?.invoke(incoming.thirdHistory) }
        runCatching { thirdFavoritesRestore?.invoke(incoming.thirdFavorites) }
        writes.submit(mapOf("quality" to { restored.settings.quality }, "playbackSpeed" to { restored.settings.speed },
            "autoNext" to { restored.settings.autoNext }, "contentType" to { restored.settings.type.storedValue }, "frameMode" to { restored.settings.frame.name },
            "autoSeason" to { restored.settings.autoSeason }, "danmaku" to { restored.settings.danmaku },
            "danmakuOpacity" to { restored.settings.danmakuOpacity }, "danmakuFont" to { restored.settings.danmakuFont },
            "danmakuSpeed" to { restored.settings.danmakuSpeed }, "danmakuRows" to { restored.settings.danmakuRows }, "showClock" to { restored.settings.showClock },
            "sourceHidden" to { restored.settings.sourceHidden }))
        flush()
    }
    /** Reset only playback preferences; preserve catalog filters and device/interface preferences. */
    fun resetPlaybackPreferences() {
        val defaults = LibraryDisk.decode(emptyMap<String, Any?>()).settings
        maxQuality = defaults.quality
        playbackSpeed = defaults.speed
        autoNext = defaults.autoNext
        autoSeason = defaults.autoSeason
        frameMode = defaults.frame
        danmaku = defaults.danmaku
        danmakuOpacity = defaults.danmakuOpacity
        danmakuFont = defaults.danmakuFont
        danmakuSpeed = defaults.danmakuSpeed
        danmakuRows = defaults.danmakuRows
        marksCache = linkedMapOf()
        prefs.edit().remove("skipMarks").apply()
    }

    /** Restore playback/interface preferences while retaining all user library data. */
    fun resetSettings() {
        val defaults = LibraryDisk.decode(emptyMap<String, Any?>()).settings
        state().settings = defaults
        marksCache = linkedMapOf()
        prefs.edit().remove("theme").remove("skipMarks").remove("videoInfoMode").remove("showNetworkSpeed").apply()
        writes.submit(mapOf(
            "quality" to { defaults.quality }, "playbackSpeed" to { defaults.speed },
            "autoNext" to { defaults.autoNext }, "contentType" to { defaults.type.storedValue },
            "frameMode" to { defaults.frame.name }, "autoSeason" to { defaults.autoSeason },
            "danmaku" to { defaults.danmaku }, "danmakuOpacity" to { defaults.danmakuOpacity },
            "danmakuFont" to { defaults.danmakuFont }, "danmakuSpeed" to { defaults.danmakuSpeed },
            "danmakuRows" to { defaults.danmakuRows }, "showClock" to { defaults.showClock },
            "sourceHidden" to { defaults.sourceHidden }
        ))
    }

    /** Close and wait for queued library writes before an all-data reset removes storage. */
    fun closeAndAwait(timeoutSeconds: Long = 15): Boolean {
        close()
        val loaderDone = loader.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)
        val writesDone = writes.awaitClosed(timeoutSeconds, TimeUnit.SECONDS)
        val homesDone = homeWrites.awaitClosed(timeoutSeconds, TimeUnit.SECONDS)
        synchronized(retiredWrites) { retiredWrites.remove(writes); retiredWrites.remove(homeWrites) }
        return loaderDone && writesDone && homesDone
    }

    /** Requests prompt background commit, without lifecycle-thread disk waits. */
    fun flush() {
        writes.flush(); homeWrites.flush()
    }
    /** Drain pending commits after Activity destruction; abrupt process death is still a boundary. */
    fun close() {
        if (closed) return
        closed = true
        readyCallbacks.clear(); errorCallbacks.clear()
        loader.shutdown()
        synchronized(retiredWrites) {
            writes.close(); homeWrites.close()
            retiredWrites += writes; retiredWrites += homeWrites
        }
    }
    private fun commit(target: SharedPreferences, values: Map<String, () -> Any?>): Boolean {
        val edit = target.edit()
        values.forEach { (key, encode) ->
            when (val value = encode()) {
                null -> edit.remove(key)
                is String -> edit.putString(key, value)
                is Int -> edit.putInt(key, value)
                is Float -> edit.putFloat(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> error("Unsupported library value")
            }
        }
        return edit.commit()
    }
}
