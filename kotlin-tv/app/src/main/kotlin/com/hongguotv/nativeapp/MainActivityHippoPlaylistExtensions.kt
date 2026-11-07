// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.hongguotv.core.Series

/**
 * 河马排行榜剧单详情：使用全屏页面式布局，避免旧版 AlertDialog 纯文字列表。
 * 剧单成员点击后仍进入现有 1132 目录与 1133 播放流程。
 */
internal fun MainActivity.openHippoPlaylist(playlist: Series) {
    val playlistId = playlist.id.removePrefix("hippo-playlist:")
    if (playlistId.isBlank()) {
        Toast.makeText(this, "剧单编号无效", Toast.LENGTH_SHORT).show()
        return
    }
    Toast.makeText(this, "正在加载剧单：${playlist.title}", Toast.LENGTH_SHORT).show()
    io.execute {
        val result = runCatching { hippoRepository.playlistDetail(playlistId) }
        main.post {
            if (isFinishing || isDestroyed || activePlatform != ContentPlatform.HIPPO) return@post
            result.onSuccess { dramas ->
                if (dramas.isEmpty()) {
                    Toast.makeText(this, "该剧单暂时没有短剧内容", Toast.LENGTH_LONG).show()
                    return@onSuccess
                }
                val dialog = Dialog(this)
                val root = column().apply {
                    setBackgroundColor(bg)
                    setPadding(dp(18), dp(10), dp(18), dp(12))
                }

                // 顶部封面区域：使用剧单封面；1115 未提供时由仓库回退第一部短剧海报。
                val hero = FrameLayout(this)
                val heroImage = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = playlist.title
                }
                loadCover(heroImage, playlist.cover.ifBlank { dramas.firstOrNull()?.cover.orEmpty() })
                hero.addView(heroImage, FrameLayout.LayoutParams(-1, -1))
                hero.addView(View(this).apply {
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(0x33000000, 0xEE000000.toInt())
                    )
                }, FrameLayout.LayoutParams(-1, -1))
                val close = TextView(this).apply {
                    text = "‹ 返回排行榜"
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    isFocusable = true
                    isClickable = true
                    setOnClickListener { dialog.dismiss() }
                    background = GradientDrawable().apply {
                        setColor(0x55000000)
                        cornerRadius = dp(8).toFloat()
                    }
                }
                hero.addView(close, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                    leftMargin = dp(12); topMargin = dp(12)
                })
                val heroText = column().apply {
                    setPadding(dp(18), 0, dp(18), dp(16))
                    gravity = Gravity.BOTTOM
                }
                heroText.addView(text(playlist.title, 24f, Color.WHITE).apply {
                    setTypeface(null, Typeface.BOLD)
                    maxLines = 2
                })
                val subtitle = playlist.description.ifBlank { "河马精选剧单" }
                heroText.addView(text("$subtitle  ·  共 ${dramas.size} 部短剧", 13f, 0xFFE4E4E4.toInt()).apply {
                    setPadding(0, dp(7), 0, 0)
                })
                hero.addView(heroText, FrameLayout.LayoutParams(-1, -1))
                root.addView(hero, lp(-1, dp(190)))

                val section = row().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(2), dp(16), dp(2), dp(9))
                }
                section.addView(text("剧单内容", 18f).apply { setTypeface(null, Typeface.BOLD) }, lp(0, -2).apply { weight = 1f })
                section.addView(text("${dramas.size} 部短剧", 13f, muted))
                root.addView(section)

                val scroll = ScrollView(this).apply {
                    isFillViewport = false
                    clipToPadding = false
                }
                val list = column()
                dramas.forEachIndexed { index, drama ->
                    val card = row().apply {
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(9), dp(8), dp(12), dp(8))
                        background = GradientDrawable().apply {
                            setColor(0xFF20232A.toInt())
                            cornerRadius = dp(9).toFloat()
                        }
                        isFocusable = true
                        isClickable = true
                        setOnClickListener {
                            dialog.dismiss()
                            openDetail(drama)
                        }
                    }
                    val poster = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        contentDescription = drama.title
                    }
                    loadCover(poster, drama.cover)
                    card.addView(poster, lp(dp(86), dp(122)))
                    val info = column().apply {
                        setPadding(dp(13), dp(4), 0, dp(4))
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    info.addView(text(drama.title, 16f, white).apply {
                        setTypeface(null, Typeface.BOLD)
                        maxLines = 2
                    })
                    val meta = drama.badge.ifBlank { "河马短剧" }
                    info.addView(text(meta, 12f, accent).apply { setPadding(0, dp(7), 0, dp(5)) })
                    if (drama.tags.isNotBlank()) info.addView(text(drama.tags, 12f, muted).apply { maxLines = 1 })
                    if (drama.description.isNotBlank()) info.addView(text(drama.description, 12f, muted).apply {
                        maxLines = 2
                        setPadding(0, dp(5), 0, 0)
                    })
                    card.addView(info, lp(0, -1).apply { weight = 1f })
                    list.addView(card, lp(-1, dp(140)))
                    if (index != dramas.lastIndex) list.addView(View(this), lp(-1, dp(8)))
                }
                scroll.addView(list)
                root.addView(scroll, lp(-1, 0).apply { weight = 1f })

                dialog.setContentView(root)
                dialog.setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                        dialog.dismiss()
                        true
                    } else false
                }
                dialog.show()
                dialog.window?.apply {
                    setBackgroundDrawableResource(android.R.color.transparent)
                    setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                    setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                }
                close.nextFocusDownId = (list.getChildAt(0) as? View)?.id ?: View.NO_ID
                list.getChildAt(0)?.requestFocus()
            }.onFailure { error ->
                Toast.makeText(this, "剧单加载失败：${error.message ?: "请稍后重试"}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
