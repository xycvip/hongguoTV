// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

// 账户界面与状态协调：连接登录、云端记录及页面刷新回调。

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.widget.Toast
import com.hongguotv.core.*
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Local storage is encrypted with Android Keystore; explicit user backups export this payload as plaintext. */
private class AccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("cloud_account_secure", Context.MODE_PRIVATE)
    private val alias = "hongguotv.cloud.account.v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            alias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }
            .generateKey()
    }

    fun read(): AccountCredentials? {
        val raw = prefs.getString("session", null) ?: return null
        val bytes = Base64.decode(raw, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return AccountCredentials.fromJson(
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        )
    }

    fun save(credentials: AccountCredentials) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val raw = cipher.iv + cipher.doFinal(credentials.toJson().toByteArray())
        check(
            prefs.edit().putString("session", Base64.encodeToString(raw, Base64.NO_WRAP)).commit()
        ) {
            "保存登录态失败"
        }
    }

    fun clear() {
        check(prefs.edit().clear().commit()) { "清除登录态失败" }
    }
}

internal data class FavoriteState(
    val rows: List<CloudFavorite> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val message: String = "尚未读取账户收藏",
)

internal data class ReservationState(
    val rows: List<CloudReservation> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val message: String = "尚未读取预约记录",
)

/**
 * Cloud history is kept separate from guest records; only explicit playback creates local history.
 */
class AccountController(private val activity: Activity, private val play: (CloudProgress) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val http =
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    private val client = CloudAccountClient(http)
    private val store = AccountStore(activity)
    @Volatile private var generation = 0
    @Volatile private var closed = false
    private var credentials: AccountCredentials? = null
    private var profile: CloudProfile? = null
    private var history = emptyList<CloudProgress>()
    private var historyLoaded = false
    private val acceptedReservations = mutableSetOf<String>()
    private var favorites = FavoriteState()
    private val unconfirmedFavorites = mutableSetOf<String>()
    private var localFavoriteSyncRunning = false
    private var reservations = emptyMap<Boolean, ReservationState>()
    private var status = "未登录"
    private var busy = false
    private var lastUpload = 0L
    private var lastEpisode = ""
    private var pending: Pair<CloudProgress, Int>? = null
    private var lastProgress: Pair<CloudProgress, Int>? = null
    private var draining = false
    private var dialog: AlertDialog? = null
    private var inlineQrLogin: WebLogin? = null
    private var inlineQrDeadline = 0L
    private var inlineQrToken = 0
    val inlineQrBitmap: android.graphics.Bitmap? get() = qrBitmap
    private var qrBitmap: android.graphics.Bitmap? = null
    val inlineStatus: String get() = status
    val inlineBusy: Boolean get() = busy
    var onHistoryChanged: (() -> Unit)? = null

    internal fun recommendationCredentials() = credentials?.takeIf { profile != null }

    internal val recommendationIdentity
        get() = "$generation:${profile?.id.orEmpty()}"

    val signedIn
        get() = credentials != null

    val accountLabel
        get() = profile?.nickname ?: if (signedIn) "红果账号" else "未登录"

    val accountAvatarUrl
        get() = profile?.avatarUrl.orEmpty()

    /** Increments whenever account profile data is fetched, even when the avatar URL is unchanged. */
    var accountProfileRevision = 0L
        private set

    val historyStatus
        get() = status

    val historyLoading
        get() = busy

    val historyReady
        get() = signedIn && historyLoaded

    val historyScope
        get() = generation

    fun historyRows(): List<CloudProgress> = if (signedIn) history.toList() else emptyList()

    /** Read-only connectivity check for the signed-in account API. */
    fun checkConnection(done: (Boolean, String) -> Unit) {
        val account = credentials
        if (account == null || closed) {
            done(false, "未登录")
            return
        }
        val token = generation
        worker.execute {
            val result = runCatching { client.profile(account) }
            main.post {
                if (closed || token != generation) return@post
                result.fold(
                    { done(true, "连接成功") },
                    { done(false, safeError(it)) },
                )
            }
        }
    }

    fun refreshHistory() {
        refresh(false)
    }

    internal fun reservationState(online: Boolean): ReservationState =
        if (signedIn) reservations[online] ?: ReservationState()
        else ReservationState(message = "未登录")

    fun reservationLabel(work: RelatedWork): String =
        when {
            !signedIn -> "登录后预约"
            reservationState(false).rows.any {
                it.series.id == work.series.id || it.series.id == "reservation:${work.series.id}"
            } -> "已预约"
            work.series.id in acceptedReservations -> "刷新预约状态"
            else -> "预约"
        }

    fun reserve(work: RelatedWork, done: (String) -> Unit) {
        val account =
            credentials
                ?: run {
                    show()
                    return
                }
        if (busy) {
            toast("账号操作进行中，请稍候")
            return
        }
        if (reservationLabel(work) == "已预约") {
            done("该作品已在当前账号的预约列表中")
            return
        }
        if (work.series.id in acceptedReservations) {
            refreshReservations()
            done("正在刷新预约列表，请稍后核对；未显示前暂不能确认预约生效。")
            return
        }
        task(
            { client.reserve(account, work) },
            {
                acceptedReservations += work.series.id
                changed()
                done("红果已接受预约请求，尚未在账号列表确认。请刷新预约状态，或在手机红果核对是否新增。")
            },
            { done("预约未完成：${safeError(it)}") },
        )
    }

    internal fun favoriteState(): FavoriteState =
        if (signedIn) favorites else FavoriteState(message = "未登录")

    fun accountFavorite(id: String) = signedIn && favorites.rows.any { it.series.id == id }

    fun favoriteNeedsRefresh(id: String) = id in unconfirmedFavorites

    fun favoriteActionLabel(id: String): String =
        when {
            !signedIn -> "登录后加入账户收藏"
            favorites.loading -> "账户收藏处理中…"
            favoriteNeedsRefresh(id) -> "刷新账户收藏状态"
            accountFavorite(id) -> "取消账户收藏"
            else -> "加入账户收藏"
        }

    fun changeFavorite(series: Series, add: Boolean, done: (String) -> Unit) {
        val account =
            credentials
                ?: run {
                    show()
                    return
                }
        if (closed || favorites.loading) {
            done("账户收藏正在处理，请稍候")
            return
        }
        if (favoriteNeedsRefresh(series.id)) {
            refreshFavorites()
            done("上次操作尚未确认，已刷新账户收藏，请核对后再操作。")
            return
        }
        val token = generation
        favorites = favorites.copy(loading = true, message = "正在核对并更新账户收藏…")
        changed()
        worker.execute {
            var attempted = false
            val result = runCatching {
                check(!closed && token == generation)
                val before = client.favorites(account)
                if (before.any { it.series.id == series.id } == add) before to false
                else {
                    check(!closed && token == generation)
                    attempted = true
                    client.setFavorite(account, series.id, add)
                    check(!closed && token == generation)
                    client.favorites(account) to true
                }
            }
            main.post {
                if (closed || token != generation) return@post
                val message =
                    result.fold(
                        { (rows, wrote) ->
                            val confirmed = rows.any { it.series.id == series.id } == add
                            if (confirmed) unconfirmedFavorites.remove(series.id)
                            else unconfirmedFavorites.add(series.id)
                            favorites =
                                FavoriteState(
                                    rows,
                                    loaded = true,
                                    message =
                                        if (confirmed) "已核对账户收藏，共读取 ${rows.size} 部"
                                        else "请求已提交，列表尚未确认，请刷新或在手机核对",
                                )
                            if (!confirmed) "请求已提交，但账户列表尚未确认生效。请刷新或在手机红果核对，不要连续重复提交。"
                            else if (wrote) if (add) "已加入账户收藏，并已在账户列表确认" else "已取消账户收藏，并已从本次读取的列表移除"
                            else if (add) "这部剧已在账户收藏中，无需重复添加" else "本次读取的账户列表中已没有这部剧，无需重复取消"
                        },
                        { error ->
                            if (attempted) unconfirmedFavorites.add(series.id)
                            favorites =
                                favorites.copy(
                                    loading = false,
                                    message =
                                        if (attempted) "收藏操作未确认，请刷新或在手机核对"
                                        else "读取失败：${safeError(error)}",
                                )
                            if (attempted) "收藏操作未确认：${safeError(error)}。请先刷新或在手机核对。"
                            else "未修改账户收藏：${safeError(error)}"
                        },
                    )
                changed()
                done(message)
            }
        }
    }

    fun refreshFavorites() {
        val account = credentials ?: return
        if (closed || favorites.loading) return
        val token = generation
        favorites = favorites.copy(loading = true, message = "正在读取账户收藏…")
        changed()
        worker.execute {
            if (closed || token != generation) return@execute
            val result = runCatching { client.favorites(account) }
            main.post {
                if (closed || token != generation) return@post
                favorites =
                    result.fold(
                        {
                            unconfirmedFavorites.clear()
                            FavoriteState(it, loaded = true, message = "已读取 ${it.size} 部账户收藏")
                        },
                        { favorites.copy(loading = false, message = "读取失败：${safeError(it)}") },
                    )
                changed()
            }
        }
    }

    /** 只把本机收藏补充到红果账户；绝不删除或覆盖云端已有收藏。 */
    fun syncLocalFavorites(localRows: List<Series>) {
        val account = credentials ?: return
        if (closed || localFavoriteSyncRunning || favorites.loading || localRows.isEmpty()) return
        val token = generation
        localFavoriteSyncRunning = true
        worker.execute {
            val result = runCatching {
                val existing = client.favorites(account).mapTo(mutableSetOf()) { it.series.id }
                var added = false
                localRows.distinctBy { it.id }.take(500).forEach { series ->
                    if (series.id.matches(Regex("[0-9]{1,30}")) && series.id !in existing) {
                        check(!closed && token == generation)
                        client.setFavorite(account, series.id, true)
                        existing.add(series.id)
                        added = true
                    }
                }
                added
            }
            main.post {
                if (closed || token != generation) {
                    localFavoriteSyncRunning = false
                    return@post
                }
                localFavoriteSyncRunning = false
                result.onSuccess { if (it) refreshFavorites() }
            }
        }
    }

    fun refreshReservations() {
        val account = credentials ?: return
        if (closed || reservations.values.any { it.loading }) return
        val token = generation
        reservations =
            listOf(true, false).associateWith {
                reservationState(it).copy(loading = true, message = "正在读取预约记录…")
            }
        changed()
        // Independent results: failure in one category must not masquerade as an empty list.
        worker.execute {
            for (online in listOf(true, false)) {
                if (closed || token != generation) return@execute
                val result = runCatching { client.reservations(account, online) }
                main.post {
                    if (closed || token != generation) return@post
                    val before = reservationState(online)
                    reservations =
                        reservations +
                            (online to
                                result.fold(
                                    {
                                        ReservationState(
                                            it,
                                            loaded = true,
                                            message = "已读取 ${it.size} 部预约记录",
                                        )
                                    },
                                    {
                                        before.copy(
                                            loading = false,
                                            message = "读取失败：${safeError(it)}",
                                        )
                                    },
                                ))
                    changed()
                }
            }
        }
    }

    private fun changed() {
        onHistoryChanged?.invoke()
    }

    val showing
        get() = dialog?.isShowing == true

    init {
        val token = generation
        worker.execute {
            val saved = runCatching { store.read() }
            main.post {
                if (closed || token != generation) return@post
                credentials = saved.getOrNull()
                status =
                    if (credentials != null) "登录态已恢复，打开账号页面刷新云端记录"
                    else if (saved.isFailure) "登录态无法读取，请重新登录" else "未登录"
                changed()
                if (credentials != null) refresh(false)
            }
        }
    }

    private fun toast(value: String) {
        if (!closed) Toast.makeText(activity, value, Toast.LENGTH_LONG).show()
    }

    private fun safeError(error: Throwable) =
        when (error) {
            is java.net.SocketTimeoutException -> "账号接口连接超时，请稍后重试"
            is java.net.UnknownHostException -> "无法连接账号接口，请检查网络"
            else ->
                error.message.orEmpty().replace(Regex("https?://\\S+"), "[地址]").take(150).ifBlank {
                    "账号操作失败"
                }
        }

    fun progress(id: String): CloudProgress? =
        if (credentials != null) history.firstOrNull { it.series.id == id } else null

    private fun showDialog(next: AlertDialog) {
        dialog?.dismiss()
        dialog = next
        next.setOnDismissListener { if (dialog === next) dialog = null }
        next.show()
    }

    fun show() {
        if (closed) return
        if (busy) {
            toast("账号操作进行中，请稍候")
            return
        }
        val signed = credentials != null
        val choices =
            if (signed) arrayOf("云端继续观看（最近 50 部）", "刷新账号和云端记录", "退出红果账号")
            else arrayOf("使用红果 App 扫码登录")
        val next = TvDialog.choose(
            activity,
            profile?.let { "红果账户 · ${it.nickname}" } ?: "红果账户",
            choices.toList(),
            { index ->
                if (!signed) qrLogin()
                else when (index) {
                    0 -> showHistory()
                    1 -> refresh(true)
                    2 -> logout()
                }
            },
            extra = "同步状态",
            onExtra = {
                dialog?.dismiss()
                TvDialog.info(
                    activity,
                    "账号同步状态",
                    "$status\n\n登录后，电视播放的进度会自动上传。手机记录可从「云端继续观看」打开。平台接口可能变化，首次使用请对照手机核对集数和时间。\n本机历史与云端记录分别保留；退出登录会停止上传。"
                ).show()
            }
        )
        showDialog(next)
    }

    private fun validateAccount(web: AccountCredentials): Pair<AccountCredentials, CloudProfile> {
        // QR login has no registered device identity, so initialize one only when needed.
        val next = if (web.deviceId.isNotBlank() && web.installId.isNotBlank()) {
            web
        } else {
            val device = client.registerDevice() ?: throw java.io.IOException("账号设备初始化失败")
            web.copy(
                deviceId = device.deviceId,
                installId = device.installId,
                device = mapOf("device_id" to device.deviceId, "iid" to device.installId),
            )
        }
        val account = client.profile(next)
        return next to account
    }

    private fun acceptAccount(next: AccountCredentials, account: CloudProfile, inline: Boolean = false) {
        credentials = next
        profile = account
        accountProfileRevision++
        history = emptyList()
        historyLoaded = false
        favorites = FavoriteState()
        unconfirmedFavorites.clear()
        reservations = emptyMap()
        acceptedReservations.clear()
        generation++
        status = "已登录，正在读取云端记录"
        lastUpload = 0
        lastEpisode = ""
        lastProgress = null
        pending = null
        draining = false
        changed()
        toast("已登录 ${account.nickname}")
        refresh(!inline)
    }

    /** Inline account screen APIs: QR and phone login stay inside the shared page, never a dialog. */
    fun startQrInline() {
        if (closed || busy) return
        generation++
        val token = generation
        inlineQrToken = token
        val login = WebLogin(http)
        inlineQrLogin = login
        qrBitmap = null
        busy = true
        status = "正在获取二维码…"
        changed()
        worker.execute {
            val result = runCatching { login.qr() }
            main.post {
                if (closed || token != generation) return@post
                result.fold(
                    { qr ->
                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(qr.image, 0, qr.image.size)
                        if (bitmap == null) {
                            busy = false
                            status = "二维码无法显示，请刷新重试"
                            changed()
                            return@fold
                        }
                        qrBitmap = bitmap
                        inlineQrDeadline = android.os.SystemClock.elapsedRealtime() + qr.expiresIn.coerceAtLeast(1) * 1000L
                        busy = false
                        status = "请使用手机红果 App 扫码，并在手机上确认登录"
                        changed()
                        scheduleInlineQrPoll(token)
                    },
                    {
                        busy = false
                        status = safeError(it)
                        changed()
                    }
                )
            }
        }
    }

    private fun scheduleInlineQrPoll(token: Int) {
        main.postDelayed({
            if (closed || token != generation || token != inlineQrToken || inlineQrLogin == null) return@postDelayed
            if (android.os.SystemClock.elapsedRealtime() >= inlineQrDeadline) {
                qrBitmap = null
                inlineQrLogin?.cancel()
                inlineQrLogin = null
                status = "二维码已过期，请点击刷新二维码"
                changed()
                return@postDelayed
            }
            val login = inlineQrLogin ?: return@postDelayed
            worker.execute {
                val result = runCatching { login.poll() }
                if (closed || token != generation) return@execute
                if (result.isFailure) {
                    val error = result.exceptionOrNull()
                    main.post {
                        if (closed || token != generation) return@post
                        if (error?.message.orEmpty().contains("过期")) {
                            qrBitmap = null
                            inlineQrLogin = null
                            status = "二维码已过期，请点击刷新二维码"
                            changed()
                        } else {
                            scheduleInlineQrPoll(token)
                        }
                    }
                    return@execute
                }
                if (result.getOrDefault(false)) {
                    val credentialsResult = runCatching { validateAccount(login.credentials()) }
                    main.post {
                        if (closed || token != generation) return@post
                        busy = false
                        inlineQrLogin = null
                        qrBitmap = null
                        credentialsResult.fold(
                            { acceptAccount(it.first, it.second, inline = true) },
                            { status = safeError(it); changed() }
                        )
                    }
                } else scheduleInlineQrPoll(token)
            }
        }, 1800L)
    }

    fun logoutInline() {
        if (busy) return
        logout()
        qrBitmap = null
        inlineQrLogin?.cancel()
        inlineQrLogin = null
        changed()
    }

    fun loginByQr() = qrLogin()

    fun backupJson(): String? = credentials?.toJson()

    fun restoreJson(raw: String, callback: (Boolean, String) -> Unit) {
        worker.execute {
            val result = runCatching {
                val saved = AccountCredentials.fromJson(raw)
                val checked = validateAccount(saved)
                store.save(checked.first)
                checked
            }
            main.post {
                if (closed) return@post
                result.onSuccess { pair ->
                    acceptAccount(pair.first, pair.second, inline = true)
                    callback(true, "账户已恢复，正在同步云端数据")
                }.onFailure { error ->
                    callback(false, "账户恢复失败或凭据已失效：${safeError(error)}")
                }
            }
        }
    }

    private fun qrLogin() {
        val login = WebLogin(http)
        task(
            { login.qr() },
            { qr ->
                val bitmap =
                    android.graphics.BitmapFactory.decodeByteArray(qr.image, 0, qr.image.size)
                if (bitmap == null) {
                    toast("登录二维码无法显示，请重试")
                    return@task
                }
                val density = activity.resources.displayMetrics.density
                fun dp(value: Int) = (value * density).toInt()
                val metrics = activity.resources.displayMetrics
                val maxQrWidth = (metrics.widthPixels - dp(88)).coerceAtLeast(dp(96))
                val maxQrHeight = (metrics.heightPixels - dp(230)).coerceAtLeast(dp(96))
                val size = minOf(dp(300), maxQrWidth, maxQrHeight)
                val image =
                    android.widget.ImageView(activity).apply {
                        setImageBitmap(bitmap)
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        adjustViewBounds = true
                        setBackgroundColor(android.graphics.Color.WHITE)
                    }
                val panel = android.widget.LinearLayout(activity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    addView(TvDialog.title(activity, "红果官方扫码登录"))
                    addView(image, android.widget.LinearLayout.LayoutParams(size, size).apply {
                        gravity = android.view.Gravity.CENTER_HORIZONTAL
                        bottomMargin = (12 * density).toInt()
                    })
                    addView(TvDialog.body(activity, "用手机红果 App：我的 → 右上角菜单 → 扫一扫\n扫码后请在手机确认登录。"))
                }
                val actions = android.widget.LinearLayout(activity).apply { gravity = android.view.Gravity.END }
                val cancelButton = TvDialog.capsuleButton(activity, "取消") {}
                actions.addView(cancelButton)
                panel.addView(actions)
                val qrDialog = AlertDialog.Builder(activity).setView(TvDialog.card(activity, panel)).create()
                val phoneLayout = activity.resources.configuration.smallestScreenWidthDp < 600
                TvDialog.styleWindow(
                    activity,
                    qrDialog,
                    if (phoneLayout) 0.94f else 0.62f,
                    if (phoneLayout) 520 else 620
                ) { cancelButton.requestFocus() }
                showDialog(qrDialog)
                cancelButton.setOnClickListener { qrDialog.dismiss() }
                val current = dialog ?: return@task
                val token = generation
                val deadline =
                    android.os.SystemClock.elapsedRealtime() + qr.expiresIn.coerceIn(1, 300) * 1000L
                var cancelled = false
                busy = true
                status = "等待手机确认扫码登录"
                current.setOnDismissListener {
                    cancelled = true
                    login.cancel()
                    if (dialog === current) dialog = null
                    if (token == generation) {
                        busy = false
                        status = "已取消扫码登录"
                    }
                }
                // Android dispatches OnDismiss through a Handler. Finish the QR operation now;
                // a delayed dismissal must not keep the save task busy or cancel its successor.
                fun closeQr() {
                    cancelled = true
                    current.setOnDismissListener(null)
                    login.cancel()
                    if (token == generation) busy = false
                    if (dialog === current) dialog = null
                    current.dismiss()
                }
                fun poll() {
                    if (cancelled || closed || token != generation) return
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                        closeQr()
                        status = "二维码已过期，请重新扫码"
                        showDialog(TvDialog.confirm(activity, "二维码已过期", "请刷新二维码，再用手机红果 App 扫码确认。", action = { qrLogin() }))
                        return
                    }
                    worker.execute {
                        val result = runCatching {
                            if (login.poll()) validateAccount(login.credentials()) else null
                        }
                        main.post {
                            if (cancelled || closed || token != generation) return@post
                            result
                                .onSuccess { account ->
                                    if (account != null) {
                                        closeQr()
                                        status = "扫码已确认，正在保存登录状态"
                                        task(
                                            {
                                                store.save(account.first)
                                                account
                                            },
                                            { acceptAccount(it.first, it.second) },
                                            { failure ->
                                                showDialog(
                                                    TvDialog.confirm(activity, "登录状态保存失败", safeError(failure), action = { qrLogin() })
                                                )
                                            },
                                        )
                                    } else main.postDelayed({ poll() }, 2000)
                                }
                                .onFailure {
                                    closeQr()
                                    status = safeError(it)
                                    showDialog(
                                        TvDialog.confirm(activity, "扫码登录未完成", status, action = { qrLogin() })
                                    )
                                }
                        }
                    }
                }
                main.postDelayed({ poll() }, 1500)
            },
        )
    }

    private fun <T> task(
        action: () -> T,
        success: (T) -> Unit,
        failure: (Throwable) -> Unit = { toast(safeError(it)) },
    ) {
        if (busy || closed) return
        busy = true
        val token = generation
        worker.execute {
            if (closed || token != generation) return@execute
            val result = runCatching(action)
            main.post {
                if (closed || token != generation) return@post
                busy = false
                result.onSuccess(success).onFailure {
                    status = safeError(it)
                    failure(it)
                }
            }
        }
    }

    private fun refresh(showResult: Boolean) {
        val account = credentials ?: return
        if (busy || closed) return
        status = "正在读取账号观看历史…"
        task(
            { client.profile(account) to client.history(account) },
            { (user, rows) ->
                profile = user
                accountProfileRevision++
                historyLoaded = true
                history =
                    rows.distinctBy { it.series.id }.sortedByDescending { it.updatedAt }.take(50)
                status = "已读取 ${rows.size} 部云端观看记录"
                changed()
                if (showResult) {
                    toast(status)
                    show()
                }
            },
            {
                changed()
                toast(safeError(it))
            },
        )
        changed()
    }

    private fun showHistory() {
        if (history.isEmpty()) {
            toast("暂无已读取的云端记录，请先刷新")
            return
        }
        val snapshot = history
        showDialog(
            TvDialog.choose(
                activity,
                "云端继续观看",
                snapshot.map { "${it.series.title} · 第 ${it.episodeIndex+1} 集 · ${it.position/1000} 秒" },
                { index -> play(snapshot[index]) }
            )
        )
    }

    private fun logout() {
        generation++
        busy = false
        credentials = null
        profile = null
        history = emptyList()
        historyLoaded = false
        favorites = FavoriteState()
        unconfirmedFavorites.clear()
        reservations = emptyMap()
        acceptedReservations.clear()
        pending = null
        lastProgress = null
        draining = false
        http.dispatcher.cancelAll()
        status = "已退出账号，自动同步已停止"
        changed()
        worker.execute {
            val result = runCatching { store.clear() }
            main.post { toast(if (result.isSuccess) status else "清除登录态失败，请重试退出") }
        }
    }

    fun upload(progress: CloudProgress, episodes: Int, force: Boolean) {
        val account = credentials?.takeIf { profile != null } ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && progress.episodeId == lastEpisode && now - lastUpload < 30000) return
        lastUpload = now
        lastEpisode = progress.episodeId
        history =
            (listOf(progress.copy(episodeCount = episodes)) +
                    history.filterNot { it.series.id == progress.series.id })
                .take(50)
        changed()
        pending = progress to episodes
        lastProgress = pending
        if (draining) return
        draining = true
        drain(account, generation)
    }

    private fun drain(account: AccountCredentials, token: Int) {
        val next = pending
        if (closed || token != generation) return
        if (next == null) {
            draining = false
            return
        }
        pending = null
        worker.execute {
            val result = runCatching {
                if (!closed && token == generation) client.upload(account, next.first, next.second)
            }
            main.post {
                if (closed) return@post
                if (token == generation)
                    status =
                        if (result.isSuccess) "播放进度已上传（请在手机端核对续播）"
                        else "进度上传失败：${safeError(result.exceptionOrNull()!!)}"
                drain(account, token)
            }
        }
    }

    fun destroy() {
        val account = credentials
        val final = lastProgress
        onHistoryChanged = null
        closed = true
        generation++
        pending = null
        inlineQrLogin?.cancel()
        dialog?.dismiss()
        http.dispatcher.cancelAll()
        worker.execute {
            if (account != null && final != null)
                runCatching { client.upload(account, final.first, final.second) }
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
        worker.shutdown()
    }
}
