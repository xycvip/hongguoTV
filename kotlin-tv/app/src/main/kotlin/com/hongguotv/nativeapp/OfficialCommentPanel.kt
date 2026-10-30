// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import com.hongguotv.core.OfficialComment
import com.hongguotv.core.OfficialCommentPage

/** Right-side TV panel for ordinary episode comments; never displays time-anchored danmaku. */
internal class OfficialCommentPanel(
    context: Context,
    private val signedIn: Boolean,
    private val listener: Listener,
) : FrameLayout(context) {
    interface Listener {
        fun close()
        fun loadMore()
        fun like(comment: OfficialComment)
        fun reply(comment: OfficialComment)
        fun openReplies(comment: OfficialComment)
        fun send(text: String)
        fun login()
    }

    private val backgroundColor = Color.rgb(20, 24, 31)
    private val surfaceColor = Color.rgb(31, 37, 47)
    private val textColor = Color.rgb(245, 247, 250)
    private val mutedColor = Color.rgb(166, 176, 190)
    private val accentColor = Color.rgb(255, 91, 110)
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply {
        isFillViewport = false
        isVerticalScrollBarEnabled = true
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private val countLabel = label("本集评论", 17f, true)
    private val statusLabel = label("", 13f, false, mutedColor).apply { isFocusable = true }
    private val composerLabel = label("", 12f, false, mutedColor)
    private val input = EditText(context).apply {
        setTextColor(textColor)
        setHintTextColor(mutedColor)
        textSize = 14f
        hint = "说说你的看法……"
        minLines = 1
        maxLines = 3
        isSingleLine = false
        imeOptions = EditorInfo.IME_ACTION_SEND
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = rounded(surfaceColor, 10)
        isFocusable = true
    }
    private val sendButton = action("发送") { listener.send(input.text.toString()) }
    private var canSend = signedIn
    private var loading = false
    private var hasMore = false
    private var currentItems: List<OfficialComment> = emptyList()
    private var currentReplies: Map<String, List<OfficialComment>> = emptyMap()
    private var replyTarget: OfficialComment? = null
    private var draft = ""

    init {
        setBackgroundColor(backgroundColor)
        isFocusable = false
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(14))
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(countLabel, LinearLayout.LayoutParams(0, dp(42), 1f))
        header.addView(action("关闭") { listener.close() })
        page.addView(header)
        page.addView(label("当前播放集的官方评论", 12f, false, mutedColor))
        page.addView(statusLabel)
        page.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(8)
            bottomMargin = dp(10)
        })
        page.addView(composerLabel)
        if (signedIn) {
            val composer = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
            }
            composer.addView(input, LinearLayout.LayoutParams(0, dp(76), 1f))
            composer.addView(sendButton, LinearLayout.LayoutParams(dp(76), dp(52)).apply {
                leftMargin = dp(8)
            })
            page.addView(composer)
            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    listener.send(input.text.toString())
                    true
                } else false
            }
        } else {
            page.addView(action("登录红果账号后发表评论、回复和点赞") { listener.login() })
        }
        addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        showLoading()
    }

    fun setEpisode(title: String, episodeNumber: Int) {
        countLabel.text = "《$title》· 第 $episodeNumber 集评论"
        currentItems = emptyList()
        currentReplies = emptyMap()
        replyTarget = null
        composerLabel.text = ""
        statusLabel.text = "正在加载评论…"
        render()
    }

    fun setPage(page: OfficialCommentPage, append: Boolean, replies: Map<String, List<OfficialComment>> = currentReplies) {
        loading = false
        currentItems = if (append) currentItems + page.items else page.items
        currentReplies = replies
        hasMore = page.hasMore
        statusLabel.text = if (currentItems.isEmpty()) "暂无评论" else "共 ${page.total} 条评论"
        render()
    }

    fun setReplies(commentId: String, rows: List<OfficialComment>) {
        currentReplies = currentReplies + (commentId to rows)
        render()
    }

    fun showLoading(message: String = "正在加载评论…") {
        loading = true
        statusLabel.text = message
        render()
    }

    fun showError(message: String) {
        loading = false
        statusLabel.text = message
        render()
    }

    fun setSending(sending: Boolean) {
        sendButton.isEnabled = !sending
        sendButton.alpha = if (sending) .55f else 1f
        sendButton.text = if (sending) "发送中" else "发送"
    }

    fun keepDraft(value: String) {
        draft = value
        if (signedIn && input.text.toString() != value) input.setText(value)
    }

    fun clearDraft() {
        draft = ""
        if (signedIn) input.setText("")
        replyTarget = null
        composerLabel.text = ""
    }

    fun setReplyTarget(comment: OfficialComment) {
        replyTarget = comment
        composerLabel.text = "回复 @${comment.author}（按返回可关闭评论面板）"
        if (signedIn) {
            input.hint = "回复 @${comment.author}……"
            input.requestFocus()
        }
    }

    fun replyTarget(): OfficialComment? = replyTarget
    fun draft(): String = if (signedIn) input.text.toString() else draft

    fun focusCommentArea() {
        val target = list.getChildAt(0)
        target?.let { firstFocusable(it)?.requestFocus() }
            ?: statusLabel.requestFocus()
    }

    private fun actionFocusFallback() {
        if (signedIn) input.requestFocus()
    }

    private fun firstFocusable(view: View): View? {
        if (view.isFocusable && view.visibility == View.VISIBLE) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) firstFocusable(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun render() {
        list.removeAllViews()
        if (currentItems.isEmpty() && !loading && statusLabel.text.isNullOrBlank()) {
            statusLabel.text = "暂无评论"
        }
        currentItems.forEach { comment ->
            list.addView(commentCard(comment, false))
            currentReplies[comment.id]?.forEach { reply ->
                list.addView(commentCard(reply, true))
            }
        }
        if (hasMore) {
            list.addView(action(if (loading) "加载中…" else "加载更多评论") {
                if (!loading) listener.loadMore()
            })
        }
        if (currentItems.isEmpty() && !loading) {
            list.addView(label("还没有人发表本集评论", 14f, false, mutedColor).apply {
                setPadding(dp(8), dp(22), dp(8), dp(22))
            })
        }
    }

    private fun commentCard(comment: OfficialComment, isReply: Boolean): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (isReply) 22 else 10), dp(10), dp(8), dp(9))
            background = rounded(if (isReply) backgroundColor else surfaceColor, 10)
            isFocusable = false
        }
        val authorLine = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        authorLine.addView(label(comment.author, 13f, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (comment.createdAt > 0) authorLine.addView(label(timeText(comment.createdAt), 11f, false, mutedColor))
        card.addView(authorLine)
        card.addView(label(comment.text, 14f, false, textColor).apply {
            setPadding(0, dp(6), 0, dp(8))
        })
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (!isReply) {
            actions.addView(action((if (comment.liked) "♥ " else "♡ ") + comment.likes) {
                listener.like(comment)
            })
            actions.addView(action("回复") { listener.reply(comment) })
            if (comment.replies > 0) {
                actions.addView(action("查看回复（${comment.replies}）") { listener.openReplies(comment) })
            }
        } else {
            actions.addView(label("回复", 11f, false, mutedColor))
        }
        card.addView(actions)
        return card
    }

    private fun label(value: String, size: Float, bold: Boolean, color: Int = textColor) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            isFocusable = false
        }

    private fun action(value: String, click: () -> Unit) =
        TextView(context).apply {
            text = value
            textSize = 13f
            setTextColor(textColor)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            minHeight = dp(42)
            isFocusable = true
            isClickable = true
            background = rounded(surfaceColor, 9)
            setOnFocusChangeListener { view, focused ->
                view.background = rounded(if (focused) Color.rgb(55, 75, 100) else surfaceColor, 9)
            }
            setOnClickListener { click() }
        }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun timeText(seconds: Long): String {
        val delta = (System.currentTimeMillis() / 1000 - seconds).coerceAtLeast(0)
        return when {
            delta < 60 -> "刚刚"
            delta < 3600 -> "${delta / 60}分钟前"
            delta < 86400 -> "${delta / 3600}小时前"
            delta < 86400L * 30 -> "${delta / 86400}天前"
            else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date(seconds * 1000))
        }
    }
}
