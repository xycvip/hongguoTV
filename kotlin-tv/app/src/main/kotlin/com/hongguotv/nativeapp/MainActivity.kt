// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.net.Network
import android.net.NetworkCapabilities
import android.widget.*
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.ui.PlayerView
import com.hongguotv.core.*
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Search result column owns vertical swipe gestures across the full column,
 * not only on poster cards. Horizontal gestures and taps remain with children.
 */
/** 顶栏四叶接口状态灯：细长光叶朝向同一圆心，颜色分别对应四项接口。 */
internal const val FOOTER_NONE=0
internal const val FOOTER_LOADING=1
internal const val FOOTER_END=2
internal const val FOOTER_ERROR=3
internal const val FOOTER_MORE_HOT=4
internal const val REQUEST_EXPORT_BACKUP=7201
internal const val REQUEST_IMPORT_BACKUP=7202

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity: Activity() {
    internal val searchUiState = SearchUiState()
    internal val theaterUiState = TheaterUiState()
    internal val accountUiState = AccountUiState()
    internal val detailUiState = DetailUiState()
    internal val homeUiState = HomeUiState()
    internal val rankingUiState = RankingUiState()
    internal val playbackUiState = PlaybackUiState()
    internal val debugUiState = DebugUiState()
    internal val commentsUiState = CommentsUiState()
    internal val favoriteUiState = FavoriteUiState()
    internal val startupUiState = StartupUiState()
    internal val networkUiState = NetworkUiState()
    internal val navigationUiState = NavigationUiState()
    internal val backupUiState = BackupUiState()
    internal val apiStatusUiState = ApiStatusUiState()
    internal val danmakuUiState = DanmakuUiState()
    internal val playbackPipelineState = PlaybackPipelineState()
    internal val asyncWorkState = AsyncWorkState()
    // Read the active theme dynamically so the settings page can redraw without recreating the Activity.
    internal val isTelevisionDevice: Boolean by lazy {
        packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
            packageManager.hasSystemFeature("android.hardware.type.television") ||
            (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }
    internal val isPhoneDevice: Boolean get() = !isTelevisionDevice
    // 保存手机播放器方向偏好；离开播放器时仅临时恢复主界面横屏。
    internal var phonePlaybackOrientation: Int = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    internal val bg get() = TvStyle.background
    internal val surface get() = TvStyle.surface
    internal val accent get() = TvStyle.accent
    internal val white get() = TvStyle.text
    internal val muted get() = TvStyle.muted
    internal val main=Handler(Looper.getMainLooper())
    internal val io=Executors.newFixedThreadPool(3)
    internal val platformUiState = PlatformUiState()
    internal var activePlatform by platformUiState::active
    internal val redfruitRepository by lazy { ContentRepository() }
    internal val hippoRepository by lazy { HippoContentRepository(this) }
    internal val repository: ContentRepository
        get() = if (activePlatform == ContentPlatform.HIPPO) hippoRepository else redfruitRepository
    internal val redfruitRandomRecommender by lazy {
        val raw=getSharedPreferences("random_pool", MODE_PRIVATE).getString("pool", null)
        val items=raw?.let { RandomPoolSnapshot.decode(it, System.currentTimeMillis()) } ?: emptyList()
        RandomRecommender(redfruitRepository, items)
    }
    internal val hippoRandomRecommender by lazy {
        val raw=getSharedPreferences("hippo_random_pool", MODE_PRIVATE).getString("pool", null)
        val items=raw?.let { RandomPoolSnapshot.decode(it, System.currentTimeMillis()) } ?: emptyList()
        RandomRecommender(hippoRepository, items)
    }
    internal val randomRecommender: RandomRecommender
        get() = if (activePlatform == ContentPlatform.HIPPO) hippoRandomRecommender else redfruitRandomRecommender
    internal var randomReplenishing by playbackPipelineState::randomReplenishing
    internal var randomPlaybackLaunching by playbackPipelineState::randomPlaybackLaunching
    internal var randomLoadingLabel by playbackPipelineState::randomLoadingLabel
    internal var randomDialog by playbackPipelineState::randomDialog
    internal var exitDialog by playbackPipelineState::exitDialog
    internal var killOnDestroy by playbackPipelineState::killOnDestroy
    @Volatile internal var resettingAllData=false
    internal val danmakuRepo=DanmakuRepository(repository.http)
    internal val networkCleanup=Executors.newSingleThreadExecutor()
    internal val prefetchWorker=Executors.newSingleThreadExecutor()
    internal var prefetchHttp by playbackPipelineState::prefetchHttp
    internal var playbackHttp by playbackPipelineState::playbackHttp
    internal val preparedNext get() = playbackPipelineState.preparedNext
    internal var standbyPlayer by playbackPipelineState::standbyPlayer
    internal var standbyRemote by playbackPipelineState::standbyRemote
    internal var standbyHttp by playbackPipelineState::standbyHttp
    internal var standbyEpisodeId by playbackPipelineState::standbyEpisodeId
    internal var standbyReady by playbackPipelineState::standbyReady
    internal var seamlessHandoffInProgress by playbackPipelineState::seamlessHandoffInProgress
    internal var standbyFallbackEpisodeId by playbackPipelineState::standbyFallbackEpisodeId
    internal var standbyJob by playbackPipelineState::standbyJob
    internal var standbyOutputView by playbackPipelineState::standbyOutputView
    internal var standbyByteCounterEnabled by playbackPipelineState::standbyByteCounterEnabled
    internal var standbyPrepareStartedAt by playbackPipelineState::standbyPrepareStartedAt
    internal var standbyReadyAt by playbackPipelineState::standbyReadyAt
    internal var standbyFirstFrameAt by playbackPipelineState::standbyFirstFrameAt
    internal var standbyPromotionAt by playbackPipelineState::standbyPromotionAt
    internal var standbyAttachedAt by playbackPipelineState::standbyAttachedAt
    internal var standbyVisibleFirstFrameAt by playbackPipelineState::standbyVisibleFirstFrameAt
    internal val seamlessPlaybackEnabled: Boolean
        get() = getSharedPreferences(platformPrefsName("playback-settings-v1"), MODE_PRIVATE).getBoolean("seamless_playback", true)
    internal var prefetchJob by playbackPipelineState::prefetchJob
    internal var prefetchScope by playbackPipelineState::prefetchScope
    internal var prefetchedFor by playbackPipelineState::prefetchedFor
    internal var readySince by playbackPipelineState::readySince
    internal lateinit var artwork: ArtworkLoader
    internal var deferredWork by asyncWorkState::deferredWork
    internal var initialCatalogPending by homeUiState::initialCatalogPending
    internal var restoreStoppedPage by homeUiState::restoreStoppedPage
    internal var pendingDetail by homeUiState::pendingDetail
    internal var detailAutoplay by homeUiState::detailAutoplay
    internal var libraryLoadError by homeUiState::libraryLoadError
    internal var clockView by homeUiState::clockView
    internal var clockTick by homeUiState::clockTick
    internal var videoInfoView: TextView?=null
    internal var networkSpeedView: TextView?=null
    internal var activeVideoFormat by playbackPipelineState::activeVideoFormat
    internal var activeDecoderName by playbackPipelineState::activeDecoderName
    internal val playbackBytes get() = playbackPipelineState.playbackBytes
    internal var debugFile by debugUiState::file
    internal var debugStartPending by debugUiState::startPending
    internal val debugLogExecutor get() = debugUiState.logExecutor
    internal val debugLock get() = debugUiState.lock
    internal var debugOverlayText by debugUiState::overlayText
    internal var debugTick by debugUiState::tick
    internal var debugLastBytes by debugUiState::lastBytes
    internal var debugLastSampleAt by debugUiState::lastSampleAt
    internal var debugCurrentBytesPerSecond by debugUiState::currentBytesPerSecond
    internal var debugPlaybackStartedAt by debugUiState::playbackStartedAt
    internal var debugPlaybackReadyAt by debugUiState::playbackReadyAt
    internal var debugFirstFrameAt by debugUiState::firstFrameAt
    internal var debugSettingButton by debugUiState::settingButton
    internal val debugEnabled get()=getSharedPreferences("debug-mode-v1",MODE_PRIVATE).getBoolean("enabled",false)
    // 手动解锁状态只保留在当前进程；若调试开关保持开启，则由持久化开关决定重启后继续显示。
    // Activity 构造阶段 Context 尚未挂接，不能在属性初始化时读取 SharedPreferences。
    internal var debugSettingRevealedThisSession: Boolean
        get()=DebugUiProcessState.settingRevealedThisSession
        set(value) { DebugUiProcessState.settingRevealedThisSession=value }
    internal var debugRevealTapCount: Int
        get()=DebugUiProcessState.revealTapCount
        set(value) { DebugUiProcessState.revealTapCount=value }
    internal var debugRevealLastTapAt: Long
        get()=DebugUiProcessState.revealLastTapAt
        set(value) { DebugUiProcessState.revealLastTapAt=value }
    internal var lastSpeedBytes by debugUiState::lastSpeedBytes
    internal var lastSpeedAt by debugUiState::lastSpeedAt
    internal var currentSpeedBytesPerSecond by debugUiState::currentSpeedBytesPerSecond
    internal var workScope by asyncWorkState::scope
    internal var workJob by asyncWorkState::job
    internal var playerListener by playbackPipelineState::playerListener
    internal lateinit var playbackTitle: TextView
    internal var directPlayback by playbackPipelineState::directPlayback
    internal var collection by navigationUiState::collection
    internal var collectionReturn by navigationUiState::collectionReturn
    internal var collectionFocus by navigationUiState::collectionFocus
    internal var collectionBack by navigationUiState::collectionBack
    internal val gridColumns=6
    internal val waterfallMaxPages=10
    internal var catalogRecycler: RecyclerView?=null
    internal var catalogAdapter: CatalogAdapter?=null
    internal var loadingMore by navigationUiState::loadingMore
    internal var homePosition by homeUiState::homePosition
    internal var catalogPageKey by navigationUiState::catalogPageKey
    internal val scrollPositions get() = homeUiState.scrollPositions
    internal var prefetchAttemptAt by navigationUiState::prefetchAttemptAt
    internal var prefetchAttempts by navigationUiState::prefetchAttempts
    internal var homeScreen by homeUiState::homeScreen
    internal var selectionPreview by homeUiState::selectionPreview
    internal var homeFromCache by homeUiState::homeFromCache
    internal var homeUpdateCount by homeUiState::homeUpdateCount
    internal var launchStarted=android.os.SystemClock.elapsedRealtime()
    internal val activityStarted=launchStarted
    internal var homeContentLogged by homeUiState::homeContentLogged
    internal var pendingCloudProgress by homeUiState::pendingCloudProgress
    internal var accountContentHost by accountUiState::contentHost
    /** Ignore delayed focus restores from an older account-page render. */
    internal var accountRenderRevision by accountUiState::renderRevision
    internal var renderedAccountAvatarRevision by accountUiState::renderedAvatarRevision
    internal var accountQrAutoStarted by accountUiState::qrAutoStarted
    internal var accountQrImageView by accountUiState::qrImageView
    internal var accountQrPlaceholderView by accountUiState::qrPlaceholderView
    internal var accountQrStatusView by accountUiState::qrStatusView
    internal var accountQrRefreshView by accountUiState::qrRefreshView
    internal var accountApiCheckStarted by accountUiState::apiCheckStarted
    internal var accountApiOnline by accountUiState::apiOnline
    internal var accountApiDotView by accountUiState::apiDotView
    internal var accountApiLabelView by accountUiState::apiLabelView
    internal var accountSection by accountUiState::section
    internal lateinit var cloudAccount: AccountController
    internal lateinit var redfruitLibrary: Library
    internal lateinit var hippoLibrary: Library
    internal val library: Library
        get() = if (activePlatform == ContentPlatform.HIPPO) hippoLibrary else redfruitLibrary
    internal lateinit var tvTools: TvTools
    internal lateinit var redfruitExtras: PlaybackExtras
    internal lateinit var hippoExtras: PlaybackExtras
    internal val extras: PlaybackExtras
        get() = if (activePlatform == ContentPlatform.HIPPO) hippoExtras else redfruitExtras
    internal lateinit var updater: AppUpdater
    internal lateinit var mediaSession: TvMediaSession
    internal val sleepTimer=SleepTimer { android.os.SystemClock.elapsedRealtime() }
    internal val sleepActionMode: Int
        get() = getSharedPreferences(platformPrefsName("playback-settings-v1"), MODE_PRIVATE).getInt("sleep_action_mode", 0).let { if(it in 0..1) it else 0 }

    // 每次启动软件后首次点击时提示一次；不依赖设备开机次数。
    internal var sleepSettingHintShown by playbackUiState::sleepSettingHintShown

    internal var sleepStopped by playbackUiState::sleepStopped
    internal var playerView by playbackUiState::playerView
    internal var danmakuView by danmakuUiState::view
    internal var danmakuLoader by danmakuUiState::loader
    internal val danmakuColorMode: Int
        get() = getSharedPreferences(platformPrefsName("playback-settings-v1"), MODE_PRIVATE)
            .getInt("danmaku_color_mode", DanmakuView.COLOR_MODE_DEFAULT)
            .coerceIn(DanmakuView.COLOR_MODE_DEFAULT, DanmakuView.COLOR_MODE_LAST)

    internal val officialCommentRepository by lazy { OfficialCommentRepository(repository.http) }
    internal var commentPanel by commentsUiState::panel
    internal var commentAnchor by commentsUiState::anchor
    internal var commentCursor by commentsUiState::cursor
    internal var commentGeneration by commentsUiState::generation
    internal var commentListScope by commentsUiState::listScope
    internal val commentReplyCursors get() = commentsUiState.replyCursors
    internal lateinit var favoriteMonitor: FavoriteMonitor
    internal val favoriteBadges get() = favoriteUiState.badges
    internal var favoriteStatus by favoriteUiState::status
    internal var favoriteCheck by favoriteUiState::check
    internal var favoriteGroupFilter by favoriteUiState::groupFilter
    internal var favoriteFilterFirst by favoriteUiState::filterFirst
    internal var favoriteFilterButtons by favoriteUiState::filterButtons
    internal var favoriteFilterFocus by favoriteUiState::filterFocus
    internal var homeUpdates by homeUiState::homeUpdates
    internal val resumeCards get() = playbackUiState.resumeCards
    internal var episodePanel by playbackUiState::episodePanel
    internal val recovery=RecoveryBudget()
    internal var retryPending by playbackUiState::retryPending
    internal var autoRecovery by playbackUiState::autoRecovery
    /** 本次播放连续跳过的不兼容集数：成功后归零，最多自动跳 5 集。 */
    internal var incompatibleSkips by playbackUiState::incompatibleSkips
    internal var startPosition by playbackUiState::startPosition
    internal var outroSkipSeconds by playbackUiState::outroSkipSeconds
    internal var outroTriggered by playbackUiState::outroTriggered
    internal var ffActive by playbackUiState::ffActive
    internal var ffSpeedBefore by playbackUiState::ffSpeedBefore
    internal var retryPosition by playbackUiState::retryPosition
    internal var requestedAutoplay by playbackUiState::requestedAutoplay
    internal var retryAutoplay by playbackUiState::retryAutoplay
    internal var foreground by networkUiState::foreground
    internal lateinit var connectivity: ConnectivityManager
    internal var networkRegistered by networkUiState::networkRegistered
    internal var apiNetworkUnavailable by networkUiState::apiNetworkUnavailable
    internal val networkCallback = createNetworkCallback()

    internal val retryRunnable=Runnable {
        retryPending=false
        if(foreground && screen=="player" && playError && autoRecovery) {
            if(!networkAvailable()) scheduleRecovery()
            else if(recovery.consume()) {
                android.util.Log.i("HongguoTV","Automatic playback recovery ${recovery.attempts}/3 at ${retryPosition}ms")
                playEpisode(episodeIndex,retryPosition,retryAutoplay,recovering=true)
            }
        }
    }
    internal lateinit var root: FrameLayout
    internal var bootSplash by startupUiState::bootSplash
    internal var bootSplashAnimator by startupUiState::bootSplashAnimator
    internal var generation by asyncWorkState::generation
    internal var screen by navigationUiState::screen
    /** 第三方片源页面由主 Activity 托管，跨页面重建保留浏览/播放状态。 */
    internal var thirdSourceScreen: ThirdSourceScreen? = null
    internal var tab by navigationUiState::tab
    internal var page by navigationUiState::page
    internal var query by searchUiState::query
    /** 搜索页默认聚合漫剧与短剧；用户点单一类型后切换为单类搜索。 */
    internal var aggregateSearch by searchUiState::aggregateSearch
    internal var aggregateButton by searchUiState::aggregateButton
    internal var catalog by navigationUiState::catalog
    internal var hasMore by navigationUiState::hasMore
    internal var catalogFocus by navigationUiState::catalogFocus
    internal var ranking by rankingUiState::ranking
    internal var rankBoard by rankingUiState::rankBoard
    internal var rankFocusTag by rankingUiState::rankFocusTag
    // 榜单按枚举顺序循环切换，新增榜单只需在 RankBoard 里加一项
    internal var rankSwitch by rankingUiState::rankSwitch
    internal var rankRefresh by rankingUiState::rankRefresh
    internal var rankSubtitle by rankingUiState::rankSubtitle
    // AI 剧分类是排行榜页内的子视图，不占用首页内容类型页签，也不参与搜索口径
    internal var aiCatalog by rankingUiState::aiCatalog
    internal var aiGenre by rankingUiState::aiGenre
    internal var aiEntry by rankingUiState::aiEntry
    internal var aiBack by rankingUiState::aiBack
    internal var aiGenreButton by rankingUiState::aiGenreButton
    internal var genre by rankingUiState::genre
    internal var genreButton by rankingUiState::genreButton
    internal var genreClear by rankingUiState::genreClear
    internal var theaterFilterHost by theaterUiState::filterHost
    internal var theaterFocusTag by theaterUiState::focusTag
    internal val theaterFiltersByType get() = theaterUiState.filtersByType
    internal var theaterContentType by theaterUiState::contentType
    internal val theaterSelections get() = theaterUiState.selections
    internal val tabState=mutableMapOf<Int,CatalogState>()
    internal val tabFocus get() = navigationUiState.tabFocus
    internal val nav get() = navigationUiState.nav
    internal var searchNavShortcut by navigationUiState::searchNavShortcut
    // 导航焦点按业务 tab ID 索引，不依赖顶部按钮的显示顺序。
    internal val navByTab get() = navigationUiState.navByTab
    /** 顶栏只读接口状态；结果保留在重建后的页面中。 */
    internal val apiStatusViews get() = apiStatusUiState.views
    internal val apiStatusResults get() = apiStatusUiState.results
    internal val apiStatusUpdatedAt get() = apiStatusUiState.updatedAt
    internal val apiStatusElapsedMs get() = apiStatusUiState.elapsedMs
    internal val apiStatusAnimators get() = apiStatusUiState.animators
    internal var catalogNavFrame by apiStatusUiState::catalogNavFrame
    internal var catalogNavFlower by apiStatusUiState::catalogNavFlower
    internal var catalogNavVisible by apiStatusUiState::catalogNavVisible
    internal var apiStatusCheckStarted by apiStatusUiState::checkStarted
    internal val typeButtons get() = homeUiState.typeButtons
    internal val homeShelfLoads get() = homeUiState.homeShelfLoads
    internal val queuedHomeShelfRefresh get() = homeUiState.queuedHomeShelfRefresh
    internal var searchInput by searchUiState::searchInput
    internal var searchButton by searchUiState::searchButton
    internal var searchActions by searchUiState::searchActions
    internal var azFirst by searchUiState::azFirst
    internal var azLastRow by searchUiState::azLastRow
    internal var suggestRow by searchUiState::suggestRow
    internal var suggestFirst by searchUiState::suggestFirst
    internal var suggestTicket by searchUiState::suggestTicket
    internal var suggestVertical by searchUiState::suggestVertical
    internal var suggestFocusIndex by searchUiState::suggestFocusIndex
    internal var searchBody by searchUiState::searchBody
    internal var searchColumns by searchUiState::searchColumns
    internal var phoneSearched by searchUiState::phoneSearched
    internal var keyboardLastLabel by searchUiState::keyboardLastLabel
    internal var keyboardKeys by searchUiState::keyboardKeys
    internal var keyboardRows by searchUiState::keyboardRows
    internal var historyFirst by searchUiState::historyFirst
    internal var historyClear by searchUiState::historyClear
    internal var historyItems by searchUiState::historyItems
    internal var suggestScrollRef by searchUiState::suggestScrollRef
    internal var fullSearchColumns by searchUiState::fullSearchColumns
    internal var lastSuggestWords by searchUiState::lastSuggestWords
    internal var lastSuggestKey by searchUiState::lastSuggestKey
    internal var resultFirstCard by searchUiState::resultFirstCard
    internal var searchFocusPending by searchUiState::searchFocusPending
    internal var searchHotLoading by searchUiState::searchHotLoading
    internal var searchHotLoaded by searchUiState::searchHotLoaded
    internal var refreshHomePending by searchUiState::refreshHomePending
    /** Keep focus on the refresh control while a page is rebuilt for a refresh request. */
    internal var pendingRefreshFocus by searchUiState::pendingRefreshFocus
    internal val pendingSuggest=Runnable { fetchSuggest() }
    internal var historyManage by searchUiState::historyManage
    /** 设置页重建后要恢复焦点的设置项标签前缀。 */
    internal var settingsFocusKey by navigationUiState::settingsFocusKey
    internal var detail by detailUiState::detail
    // Same-name seasons are fetched after the detail page is visible; these views are filled in place.
    internal var detailSeasonLine by detailUiState::seasonLine
    internal var detailSeasonSection by detailUiState::seasonSection
    internal val detailEpisodeButtons get() = detailUiState.episodeButtons
    internal val detailRelatedCards get() = detailUiState.relatedCards
    internal var sameNameLoadingId by detailUiState::sameNameLoadingId

    internal var episodeIndex by detailUiState::episodeIndex
    internal var group by detailUiState::group
    internal var synopsisExpanded by detailUiState::synopsisExpanded
    internal var player by playbackUiState::player
    internal var preloadManager by playbackUiState::preloadManager
    internal var video by playbackUiState::video
    internal var playbackReady by playbackUiState::playbackReady
    internal var pausedForLifecycle by playbackUiState::pausedForLifecycle
    internal lateinit var hud: LinearLayout
    internal lateinit var playbackText: TextView
    internal lateinit var progressBar: ProgressBar
    internal lateinit var controls: LinearLayout
    internal var panel by playbackUiState::panel
    internal var touchTracking by playbackUiState::touchTracking
    internal var touchStartX by playbackUiState::touchStartX
    internal var touchStartY by playbackUiState::touchStartY
    internal var touchSeekStart by playbackUiState::touchSeekStart
    internal var touchSeekTarget by playbackUiState::touchSeekTarget
    internal var touchSeekGesture by playbackUiState::touchSeekGesture
    /** 进度条自身负责拖动定位，避免全屏手势抢走 SeekBar 的触摸事件。 */
    internal var touchOnProgressBar by playbackUiState::touchOnProgressBar
    /** 控制栏区域的触摸交给原生控件；视频画面区域由播放器手势统一识别。 */
    internal var touchOnHud by playbackUiState::touchOnHud
    internal var progressUserSeeking by playbackUiState::progressUserSeeking
    internal var playbackTapFeedbackView: PlaybackTapFeedbackView? = null
    internal var transportPlay by playbackUiState::transportPlay
    internal var sleepButton by playbackUiState::sleepButton
    internal var speedDialog by playbackUiState::speedDialog
    internal var pendingSeek by playbackUiState::pendingSeek
    internal var playError by playbackUiState::playError
    internal var lastSaved by playbackUiState::lastSaved
    internal var quality by playbackUiState::quality
    internal val tick=object: Runnable {
        override fun run() {
            if(!foreground || isDestroyed) return
            if(screen=="player") {
                if(sleepTimer.poll()) stopForSleep()
                if(hud.visibility==View.VISIBLE) updatePlaybackText()
                danmakuLoader?.let { loader -> if(player?.isPlaying==true) { val position=currentPosition(); io.execute { runCatching { loader.loadUpTo(position) } } } }
                maybePrefetch()
                val p=player; val data=detail
                if(p!=null && data!=null && playbackReady && !playError && outroSkipSeconds>0 && !outroTriggered && p.isPlaying) {
                    val remaining=p.duration-p.currentPosition
                    if(p.duration>0 && remaining in 0..outroSkipSeconds*1000L) {
                        outroTriggered=true
                        if(library.autoNext && episodeIndex<data.episodes.lastIndex) {
                            Toast.makeText(this@MainActivity,"已跳过片尾，播放下一集",Toast.LENGTH_SHORT).show()
                            playEpisode(episodeIndex+1)
                        } else if(continueToNextSeason()) { /* season jump in progress */ }
                    }
                }
                val now=android.os.SystemClock.elapsedRealtime()
                val currentEpisodeId=data?.episodes?.getOrNull(episodeIndex)
                if(startupPreviewEpisodeId!=null && currentEpisodeId==startupPreviewEpisodeId) {
                    if(p?.isPlaying==true && playbackReady && !playError) {
                        val previous=startupPreviewLastTickAt
                        if(previous>0L) startupPreviewPlayedMs+=(now-previous).coerceIn(0L,1500L)
                        startupPreviewLastTickAt=now
                        if(startupPreviewPlayedMs>=30_000L && previous>0L && startupPreviewPlayedMs-(now-previous).coerceIn(0L,1500L)<30_000L) {
                            saveProgress(); lastSaved=now
                        }
                    } else startupPreviewLastTickAt=0L
                } else {
                    startupPreviewEpisodeId=null; startupPreviewPlayedMs=0L; startupPreviewLastTickAt=0L
                }
                if(player?.isPlaying==true && now-lastSaved>5000) { saveProgress(); lastSaved=now }
            }
            main.postDelayed(this,1000)
        }
    }
    internal val hideHud=Runnable {
        // 焦点移动本身会在 dispatchKeyEvent 中重置 5 秒计时；
        // 到期后无论焦点停在哪个按钮，都按时收起控制栏。
        if(screen=="player" && commentPanel==null && episodePanel==null && player?.isPlaying==true && !playError) {
            hidePlaybackOverlay()
        }
    }
    internal val seekRunnable=Runnable { pendingSeek?.let { player?.seekTo(it) }; pendingSeek=null }
    internal var phonePlayerOriginalWindowBrightness: Float? = null
    private val touchGestureController by lazy {
        MainActivityTouchGestureController(
            state = playbackUiState,
            main = main,
            isPhonePlayer = { isPhoneDevice && screen == "player" },
            progressBar = { if(::progressBar.isInitialized) progressBar else null },
            hud = { if(::hud.isInitialized) hud else null },
            hasBlockingPanel = { commentPanel != null || episodePanel != null || playError },
            playerDuration = { player?.duration ?: 0L },
            screenWidthPx = { resources.displayMetrics.widthPixels.toFloat() },
            dp = { value -> dp(value).toFloat() },
            currentPosition = { currentPosition() },
            seekRunnable = seekRunnable,
            centerControl = { playbackTapFeedbackView },
            showCenterControl = {
                showPanel()
                showPlaybackTapFeedback(player?.playWhenReady==true)
            },
            showHud = { showHud() },
            hideHud = { hidePlaybackOverlay() },
            updatePlaybackText = { updatePlaybackText() },
            seekTo = { position -> seekTo(position) },
            screenHeightPx = { resources.displayMetrics.heightPixels.toFloat() },
            beginVerticalGesture = { isLeft -> beginPhoneVerticalGesture(isLeft) },
            updateVerticalGesture = { isLeft, start, delta -> updatePhoneVerticalGesture(isLeft, start, delta) },
            finishVerticalGesture = { isLeft, percent -> finishPhoneVerticalGesture(isLeft, percent) },
            changeEpisode = { next ->
                val episodes = detail?.episodes
                val target = episodeIndex + if (next) 1 else -1
                if (episodes != null && target in episodes.indices) {
                    playEpisode(target)
                    Toast.makeText(this@MainActivity, "第 ${target + 1} 集", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        if (next) "已经是最后一集" else "已经是第一集",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            startLongPressSpeed = { startPhoneTouchFastForward() },
            stopLongPressSpeed = { stopPhoneTouchFastForward() }
        )
    }
    /**
     * Android 16：隐藏系统栏，并允许横屏内容延伸到摄像头挖孔区域。
     * 保留旧版 flags 以兼容电视盒子和较旧 Android 版本；窗口重新获得焦点时再次应用。
     */


    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveFullscreen()
    }

    override fun onCreate(state: Bundle?) {
        TvStyle.apply(getSharedPreferences("native-library-v1", Context.MODE_PRIVATE).getString("theme", null))
        super.onCreate(state)
        // 方向选择仅在当前 App 进程内保留；进程重启后字段默认横屏。
        // 调试入口的显示状态仅保存在进程内；调试模式本身仍由持久化开关控制。
        applyDeviceOrientationPolicy()
        applyImmersiveFullscreen()
        platformUiState.active = PlatformPreferenceStore(this).load()
        if (platformUiState.active == ContentPlatform.HIPPO) theaterContentType = ContentType.SHORT
        redfruitLibrary=Library(this)
        hippoLibrary=Library(this,"hippo")
        // 第三方片源独立存储，但源配置、观看历史和收藏必须进入整机备份。
        // ThirdSourceScreen 使用同一 SharedPreferences；整机恢复时三类数据均按备份内容完整替换。
        val thirdStore = ThirdStore(applicationContext)
        redfruitLibrary.thirdSourcesProvider = { thirdStore.backupSources() }
        redfruitLibrary.thirdSourcesRestore = { thirdStore.restoreSources(it) }
        redfruitLibrary.thirdHistoryProvider = { thirdStore.backupHistory() }
        redfruitLibrary.thirdHistoryRestore = { thirdStore.restoreHistory(it) }
        redfruitLibrary.thirdFavoritesProvider = { thirdStore.backupFavorites() }
        redfruitLibrary.thirdFavoritesRestore = { thirdStore.restoreFavorites(it) }
        artwork=ArtworkLoader(this); tvTools=TvTools(this); redfruitExtras=PlaybackExtras(this); hippoExtras=PlaybackExtras(this,"hippo"); root=FrameLayout(this).apply { setBackgroundColor(bg) }; setContentView(root); showBootSplash()
        // 全局焦点变化统一记录，覆盖电视遥控器导航、设置项、列表和弹窗焦点迁移。
        root.viewTreeObserver.addOnGlobalFocusChangeListener { oldFocus,newFocus ->
            if(debugEnabled) {
                fun describeFocus(view:View?):String {
                    if(view==null) return "无焦点"
                    val idName=runCatching {
                        if(view.id==View.NO_ID) "无ID" else resources.getResourceEntryName(view.id)
                    }.getOrDefault("未知ID")
                    return "${view.javaClass.simpleName}(id=$idName)"
                }
                debugLog("焦点变化：${describeFocus(oldFocus)} -> ${describeFocus(newFocus)}；页面=$screen；导航=$tab")
            }
        }
        updater=AppUpdater(this) { foreground && library.isLoaded && screen=="catalog" && !tvTools.showing }
        cloudAccount = AccountController(this) { remote ->
            pendingCloudProgress = remote
            openDetail(remote.series, true)
        }
        cloudAccount.onHistoryChanged = {
            if (activePlatform==ContentPlatform.REDFRUIT && library.isLoaded && !isDestroyed) cloudAccount.syncLocalFavorites(library.favorites())
            // History sync may emit several callbacks while the account page is visible.
            // Do not tear down and rebuild the whole account view tree here: doing so
            // repeatedly changes measured height, scroll position and focus, causing
            // visible screen jitter. The next account-page entry or manual refresh
            // renders the latest cloud data without disturbing the current page.
        }
        cloudAccount.onInlineChanged = {
            main.post {
                if (tab == 6 && screen == "catalog" && !isDestroyed && !cloudAccount.signedIn) {
                    accountQrStatusView?.text = cloudAccount.inlineStatus
                    accountQrRefreshView?.text = if (cloudAccount.inlineBusy) "正在获取…" else "刷新二维码"
                    cloudAccount.inlineQrBitmap?.let { bitmap ->
                        accountQrImageView?.setImageBitmap(bitmap)
                        accountQrImageView?.visibility = View.VISIBLE
                        accountQrPlaceholderView?.visibility = View.GONE
                    } ?: run {
                        accountQrImageView?.visibility = View.GONE
                        accountQrPlaceholderView?.visibility = View.VISIBLE
                        (accountQrPlaceholderView?.getChildAt(0) as? TextView)?.text =
                            if (cloudAccount.inlineBusy) "正在获取二维码…" else "二维码暂不可用"
                    }
                }
            }
        }
        connectivity=getSystemService(ConnectivityManager::class.java)
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback); networkRegistered=true }
        favoriteMonitor=FavoriteMonitor(redfruitLibrary,redfruitRepository) { refreshFavoriteLabels() }
        mediaSession=TvMediaSession(this,{ if(foreground && screen=="player") requestPlayback(true) },{ if(foreground && screen=="player") requestPlayback(false) },{ position -> if(foreground && screen=="player") seekTo(position) },{ direction -> skipEpisode(direction) },{ if(foreground && screen=="player") returnToDetail() })
        loadLibrary()
    }

    internal var launchRandomPlaybackStarted by startupUiState::launchRandomPlaybackStarted
    internal var startupHomeRefreshStarted by startupUiState::startupHomeRefreshStarted
    internal var homeRefreshInFlight by startupUiState::homeRefreshInFlight
    /** 启动全屏推荐期间只保留过场；实际视频首帧出现后按正常播放规则记录进度。 */
    internal var bootAutoPlaybackPending by startupUiState::bootAutoPlaybackPending
    /** 整个开机推荐播放会话都使用静默失败回退；不影响用户手动发起的正常播放。 */
    internal var startupAutoPlaybackActive by startupUiState::startupAutoPlaybackActive
    /** 开机自动推荐只在实际播放满 30 秒后才写入历史；手动播放不受此门槛影响。 */
    internal var startupPreviewEpisodeId by startupUiState::startupPreviewEpisodeId
    internal var startupPreviewPlayedMs by startupUiState::startupPreviewPlayedMs
    internal var startupPreviewLastTickAt by startupUiState::startupPreviewLastTickAt
    /** 开机推荐最多等待 8 秒让内容开始实际播放；开始播放后不再受超时限制。 */
    internal var startupRecommendationExpired by startupUiState::startupRecommendationExpired
    internal val startupRecommendationTimeout=Runnable {
        // 只在等待播放内容期间超时；开始播放后 pending 会被清除，推荐可正常持续播放。
        if(!resettingAllData && bootAutoPlaybackPending) leaveStartupRecommendation()
    }








    /**
     * 使用 TVBox 风格的传统共享存储访问方案：公共根目录/HongGuoTV。
     * 目标 SDK 28 用于兼容旧版存储权限行为；仍需在真实 Android 11+ 电视上验证。
     */


    internal var pendingBackupAction by backupUiState::pendingAction
    internal var pendingBackupAnchor by backupUiState::pendingAnchor
    internal val REQUEST_STORAGE_PERMISSION = 7203
























    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        handleBackupPermissionResult(requestCode, grantResults)
    }



    /** 将备份保存到共享存储根目录/HongGuoTV，卸载应用后文件仍可保留。 */




    /** 使用应用内文件列表读取公共目录中的 JSON，不依赖系统文件选择器。 */






    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        handleBackupActivityResult(requestCode,resultCode,data)
    }


    /** Re-adds the optional overlays after every root rebuild. The clock and network speed are independent. */






    // 外观设置中的视频信息与网速浮层已移除，保留空方法兼容播放器监听回调。













    /** 接口状态只在 Android 已验证可访问互联网时才允许显示成功。 */

















    /** Device-aware buffering: keep low-memory boxes conservative and avoid oversized queues on metered links. */




    /** 渲染方式：0 自动、1 SurfaceView、2 TextureView。默认自动。 */


    /** 自动模式默认使用 SurfaceView；需要兼容性排查时可手动切换 TextureView。 */


    /** 切换输出组件时只替换 PlayerView，不停止 ExoPlayer、不重置播放进度。 */










    /** In-player danmaku preferences; reopens itself after each change so several can be tuned in one visit. */

    /** 弹幕屏蔽词：常用词多选 + 保留用户已添加的自定义词；确认后立即对正在播放的弹幕生效。 */



    /** 本机观看统计：直接按最近观看记录汇总，不额外存储、不产生任何网络请求。 */




    /** 蓝灯闪烁=首次检测；橙灯闪烁=失败后重试；结束后常亮显示真实结果。 */


    /** 每次启动首次进入主页时检测；成功即停止，失败最多重试 3 次，页面切换不重复请求。 */


        /** 接口自检：用户逐项点击，单次串行请求，失败只提示不重试，避免给站点增加并发压力。
     *  状态圆点内嵌在按钮文字前：灰=未检测，橙=检测中，绿=通，红=不通；3 秒内重复点击直接忽略。 */


    /** 仅自定义播放页的上、下、确认和菜单键；左右键固定用于快退/快进。 */





    /** Season finale auto-continues into the next season only when both the master autoplay and the season switch are on. */



    // 供播放控制栏和片头/片尾设置弹窗共同使用。








    /** Starts the official-comment overlay for the current episode; safe to call repeatedly. */

    /** 播放中切换当前剧的弹幕并记住：全局弹幕关闭时先提示打开总开关，避免出现「开了却看不到」。 */


    /** Opens only the ordinary, episode-level comment panel. Video playback is never paused. */












    /** 官方账号页：云端数据独立展示，不覆盖或清空本机收藏、历史。 */


    /** Account page: compact centered QR sign-in; full-width account dashboard after login. */


    /** 返回顶部导航时，统一将当前导航页内的纵向滚动容器复位。 */



    private fun dispatchTouchEventToViews(event: MotionEvent): Boolean = super.dispatchTouchEvent(event)

    /** 手机播放器触控交由独立协调器处理，Activity 只保留系统事件分发入口。 */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        touchGestureController.dispatch(event, ::dispatchTouchEventToViews)

    @Deprecated("TV remote back is handled through the activity")
    override fun onBackPressed() {
        if(screen=="third_source") {
            if(thirdSourceScreen?.onBack()!=true) showCatalog()
            return
        }
        goBack()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(debugEnabled && event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) {
            val keyName=KeyEvent.keyCodeToString(event.keyCode)
            debugLog("遥控器/键盘按键：$keyName；页面=$screen；当前焦点=${currentFocus?.javaClass?.simpleName ?: "无"}")
        }
        if(screen=="third_source") {
            if(event.keyCode==KeyEvent.KEYCODE_BACK) {
                if(event.action==KeyEvent.ACTION_UP) onBackPressed()
                return true
            }
            if(thirdSourceScreen?.onKey(event)==true) return true
            return super.dispatchKeyEvent(event)
        }
        if(screen=="player") {
            val key=event.keyCode
            // 控制栏显示期间，遥控器每次按键都重新开始 5 秒倒计时；
            // 不能因为最初唤起时启动的计时器到期，就在用户操作过程中把控制栏收起。
            if(event.action==KeyEvent.ACTION_DOWN && controls.visibility==View.VISIBLE) {
                resetHudHideTimer()
            }
            if(key==KeyEvent.KEYCODE_BACK) {
                if(event.action==KeyEvent.ACTION_UP && commentPanel?.dismissEditorIfNeeded()!=true) goBack()
                return true
            }
            if(key in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,KeyEvent.KEYCODE_MEDIA_PLAY,KeyEvent.KEYCODE_MEDIA_PAUSE,KeyEvent.KEYCODE_MEDIA_NEXT,KeyEvent.KEYCODE_MEDIA_PREVIOUS,KeyEvent.KEYCODE_MEDIA_STOP,KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,KeyEvent.KEYCODE_MEDIA_REWIND)) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) when(key) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> requestPlayback(true)
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> requestPlayback(false)
                    KeyEvent.KEYCODE_MEDIA_NEXT -> skipEpisode(1)
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> skipEpisode(-1)
                    KeyEvent.KEYCODE_MEDIA_STOP -> returnToDetail()
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(1,0)
                    KeyEvent.KEYCODE_MEDIA_REWIND -> seek(-1,0)
                    else -> togglePlayback()
                }
                return true
            }
            // 评论列表和播放控制条有自己的焦点/点击行为，不能再被全局快进、暂停逻辑截走。
            if(commentPanel!=null || (::controls.isInitialized && controls.visibility==View.VISIBLE)) {
                return super.dispatchKeyEvent(event)
            }
            if(!panel) {
                if(key in listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT)) {
                    if(key==KeyEvent.KEYCODE_DPAD_RIGHT && event.action==KeyEvent.ACTION_UP) {
                        stopFastForward()
                        return true
                    }
                    if(event.action==KeyEvent.ACTION_DOWN) when(key) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> seek(-1,event.repeatCount)
                        KeyEvent.KEYCODE_DPAD_RIGHT -> if(event.repeatCount==0) seek(1,0) else startFastForward()
                    }
                    return true
                }
                val action=when(key) {
                    KeyEvent.KEYCODE_DPAD_UP -> extras.remoteUpAction
                    KeyEvent.KEYCODE_DPAD_DOWN -> extras.remoteDownAction
                    KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER -> extras.remoteConfirmAction
                    KeyEvent.KEYCODE_MENU -> extras.remoteMenuAction
                    else -> null
                }
                if(action!=null) {
                    if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) performRemoteAction(action)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if(!library.isLoaded) return
        when(screen) {
            // 方向变化只调整现有视图尺寸，不重建播放器、不重新请求视频流。
            "player" -> { playerView?.resizeMode=activeFrameResizeMode(); playerView?.requestLayout(); danmakuView?.requestLayout(); hud.requestLayout() }
            "detail" -> if(detail!=null) showDetail(false)
            "third_source" -> openThirdSource()
            else -> showCatalog()
        }
    }
    override fun onResume() {
        super.onResume(); foreground=true; main.removeCallbacks(tick); main.post(tick)
        if(debugEnabled && debugFile==null) startDebugLogging(null)
        debugLog("生命周期：onResume；页面=$screen；调试模式=${if(debugEnabled) "开启" else "关闭"}")
        // A page rebuild can also schedule the clock while resuming; replace any queued copy.
        clockTick?.let { main.removeCallbacks(it); it.run() }
        updater.resume()
        if(library.isLoaded) {
            if(initialCatalogPending) showInitialScreen()
            else if(restoreStoppedPage) {
                restoreStoppedPage=false
                when(screen) {
                    "player" -> playEpisode(episodeIndex,startPosition,autoplay=false)
                    "detail" -> if(detail!=null) showDetail(false) else pendingDetail?.let { openDetail(it,directPlayback,autoplay=false) } ?: showCatalog()
                    "third_source" -> openThirdSource()
                    else -> showCatalog(load=!localCatalog() && catalog.isEmpty() && (tab==0 || tab==2 || (tab==1 && query.isNotBlank())))
                }
            } else {
                val waiting=deferredWork; deferredWork=null; waiting?.apply?.invoke()
            }
            if(screen=="catalog") {
                favoriteMonitor.check()
                if(!apiStatusCheckStarted && tab==0) main.post { runAutomaticApiChecks() }
            }
            if(screen=="player") {
                syncMediaSession()
                attachDebugOverlay()
                if(seamlessPlaybackEnabled && playbackReady && detail!=null && episodeIndex<detail!!.episodes.lastIndex) {
                    prepareStandbyEpisode(episodeIndex+1)
                }
            }
        } else libraryLoadError?.let(::showLibraryError)
    }






    override fun onPause() {
        debugLog("生命周期：onPause；页面=$screen；播放位置=${player?.currentPosition ?: -1}ms")
        updater.pause()
        foreground=false; main.removeCallbacks(tick); clockTick?.let(main::removeCallbacks); cancelRecovery(); cancelPrefetch(); clearStandbyPlayer("应用进入后台"); mediaSession.deactivate()
        sleepTimer.cancel(); favoriteMonitor.stop(); detailAutoplay=false; requestedAutoplay=false; pausedForLifecycle=true
        if(screen=="third_source") thirdSourceScreen?.pause()
        player?.pause(); if(!resettingAllData) { saveProgress(); library.flush() }; super.onPause()
    }
    override fun onStop() {
        // Activity 进入后台后停止四叶灯动画，并使未完成的旧检测批次失效；回到前台时重新检测。
        apiStatusUiState.checkBatchId++
        apiStatusCheckStarted=false
        stopApiStatusAnimations()
        restoreStoppedPage=true; generation++; cancelWork(); tvTools.close()
        if(screen=="player") { if(!resettingAllData) { startPosition=currentPosition(); saveProgress() }; releasePlayer() }
        if(screen=="third_source") thirdSourceScreen?.stop()
        artwork.cancelPage(clearMemory=true); if(!resettingAllData) library.flush(); super.onStop()
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if(::artwork.isInitialized) artwork.trimMemory(level)
        if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) { cancelPrefetch(); standbyFallbackEpisodeId=detail?.episodes?.getOrNull(episodeIndex).orEmpty(); clearStandbyPlayer("系统内存压力") }
    }
    override fun onDestroy() {
        debugLog("生命周期：onDestroy；页面=$screen；正在销毁 Activity")
        apiStatusUiState.checkBatchId++
        stopApiStatusAnimations()
        generation++; cancelWork()
        if(networkRegistered) connectivity.unregisterNetworkCallback(networkCallback)
        favoriteMonitor.destroy(); tvTools.destroy(); updater.destroy(); if(!resettingAllData) saveProgress(); cloudAccount.destroy(); releasePlayer(); thirdSourceScreen?.release(); thirdSourceScreen=null; redfruitLibrary.close(); hippoLibrary.close()
        artwork.close(); releaseCatalogViews(); mediaSession.release(); main.removeCallbacksAndMessages(null)
        io.shutdownNow(); prefetchWorker.shutdownNow(); networkCleanup.shutdown(); debugLogExecutor.shutdown()
        Thread({ listOf(redfruitRepository.http,hippoRepository.http).distinct().forEach { client -> client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() } },"hongguotv-network-cleanup").start()
        super.onDestroy()
        if(killOnDestroy) android.os.Process.killProcess(android.os.Process.myPid())
    }



}
