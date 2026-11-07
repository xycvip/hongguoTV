// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.view.View
import android.widget.Toast
import com.hongguotv.core.*

/** 在两套平台上下文间切换；页面快照、Library、Repository 与门户身份各自隔离。 */
internal fun MainActivity.switchContentPlatform() {
    val previous = activePlatform
    val next = previous.other()
    if (screen == "player") {
        saveProgress()
        library.flush()
        releasePlayer()
        stopDanmaku()
    } else {
        library.flush()
    }
    cancelWork()
    cancelRecovery()
    cancelPrefetch()
    main.removeCallbacks(startupRecommendationTimeout)
    bootAutoPlaybackPending = false
    startupAutoPlaybackActive = false
    startupRecommendationExpired = false
    startupPreviewEpisodeId = null
    initialCatalogPending = false
    randomReplenishing = false
    randomPlaybackLaunching = false
    startupHomeRefreshStarted = false
    homeRefreshInFlight = false
    randomLoadingLabel = null
    favoriteMonitor.stop()

    platformUiState.snapshots[previous] = PlatformCatalogSnapshot(
        tab = tab, page = page, catalog = catalog.toList(), hasMore = hasMore,
        ranking = ranking, rankBoard = rankBoard, genre = genre, catalogFocus = catalogFocus,
        query = query, aggregateSearch = aggregateSearch, theaterContentType = theaterContentType,
        theaterSelections = theaterSelections.toMap(), theaterFocusTag = theaterFocusTag,
        rankFocusTag = rankFocusTag, tabState = tabState.toMap(), tabFocus = tabFocus.toMap()
    )

    activePlatform = next
    PlatformPreferenceStore(this).save(next)
    leaveCollection()
    tabState.clear()
    tabFocus.clear()
    theaterSelections.clear()
    val saved = platformUiState.snapshots[next]
    if (saved != null) {
        tab = saved.tab
        page = saved.page
        catalog = saved.catalog
        hasMore = saved.hasMore
        ranking = saved.ranking
        rankBoard = saved.rankBoard
        genre = saved.genre
        catalogFocus = saved.catalogFocus
        query = saved.query
        aggregateSearch = saved.aggregateSearch
        theaterContentType = saved.theaterContentType
        theaterSelections.putAll(saved.theaterSelections)
        theaterFocusTag = saved.theaterFocusTag.orEmpty()
        rankFocusTag = saved.rankFocusTag
        tabState.putAll(saved.tabState)
        tabFocus.putAll(saved.tabFocus)
    } else {
        tab = 0
        page = 1
        catalog = emptyList()
        hasMore = false
        ranking = null
        rankBoard = RankBoard.TOTAL
        rankFocusTag = null
        genre = null
        catalogFocus = ""
    }

    // 搜索、剧场筛选及焦点状态属于各自平台；不把上一平台的临时控件状态带过去。
    if (saved == null) query = ""
    if (saved == null) aggregateSearch = true
    phoneSearched = false
    lastSuggestWords = emptyList()
    lastSuggestKey = ""
    suggestFocusIndex = -1
    keyboardLastLabel = null
    searchHotLoaded = false
    searchHotLoading = false
    if (saved == null) {
        theaterFocusTag = ""
        theaterContentType = if (next == ContentPlatform.HIPPO) ContentType.SHORT else null
    }
    theaterFiltersByType.clear()
    aiCatalog = false
    aiGenre = null
    collection = null
    detail = null
    directPlayback = false
    screen = "catalog"
    releaseCatalogViews()

    val target = activePlatform
    library.loadAsync(onFailure = { problem ->
        if (!isDestroyed && activePlatform == target) {
            Toast.makeText(this, "${target.label}平台本地数据读取失败：${problem.message}", Toast.LENGTH_LONG).show()
        }
    }) {
        if (isDestroyed || activePlatform != target) return@loadAsync
        if (target == ContentPlatform.REDFRUIT) {
            cloudAccount.syncLocalFavorites(library.favorites())
            favoriteMonitor.check()
        } else {
            library.contentType = ContentType.SHORT
        }
        val shouldLoad = saved == null || saved.catalog.isEmpty() || saved.tab == 0 || saved.tab == 7
        showCatalog(load = shouldLoad)
        if (!shouldLoad) navForTab().post { if (activePlatform == target && screen == "catalog") navForTab().requestFocus() }
        Toast.makeText(this, "已切换到${target.label}平台", Toast.LENGTH_SHORT).show()
    }
}
