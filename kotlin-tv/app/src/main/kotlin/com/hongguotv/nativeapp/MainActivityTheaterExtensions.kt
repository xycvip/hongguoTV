// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import com.hongguotv.core.*

    internal fun MainActivity.renderTheaterFilters(host:LinearLayout) {
        // 重建前优先读取真正持有焦点的选项；仅有点击记录时才使用旧记录。
        host.removeAllViews()
        val types=row().apply { id=View.generateViewId(); setPadding(0,dp(6),0,dp(8)) }
        addButton(types,"全部体裁",theaterContentType==null).apply {
            tag="theater-filter:type:all"
            setOnClickListener {
                theaterFocusTag=tag.toString()
                if(theaterContentType!=null) { theaterContentType=null; theaterSelections.clear(); page=1; catalogFocus=""; tabState.remove(7); showCatalog(load=true) }
            }
        }
        listOf(ContentType.SHORT to "真人剧",ContentType.COMIC to "漫剧",ContentType.AI to "AI剧").forEach { (type,label) ->
            addButton(types,label,theaterContentType==type).apply {
                tag="theater-filter:type:${type.name}"
                setOnClickListener {
                    theaterFocusTag=tag.toString()
                    if(theaterContentType!=type) { theaterContentType=type; theaterSelections.clear(); page=1; catalogFocus=""; tabState.remove(7); showCatalog(load=true) }
                }
            }
        }
        if(theaterSelections.isNotEmpty()) {
            addButton(types,"清除筛选").apply {
                tag="theater-filter:clear"
                setOnClickListener {
                    theaterFocusTag=tag.toString()
                    theaterSelections.clear(); page=1; catalogFocus=""; tabState.remove(7); showCatalog(load=true)
                }
            }
        }
        // 河马剧场也必须提供内容类型入口，不能依赖服务端默认 checkedFlag（常见默认值为漫剧）。
        host.addView(types)
        val groups=theaterFiltersByType[theaterFilterKey(theaterContentType)].orEmpty().filterNot { it.type=="genre" }
        if(groups.isEmpty()) host.addView(text("正在读取${activePlatform.label}官方筛选条件…",13f,muted).apply { setPadding(0,dp(5),0,dp(10)) })
        else groups.forEach { group ->
            val line=row().apply { gravity=Gravity.CENTER_VERTICAL; setPadding(0,dp(2),0,dp(2)) }
            val scroll=VerticalFriendlyHorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; clipToPadding=false }
            val choices=row().apply { gravity=Gravity.CENTER_VERTICAL }
            val selected=theaterSelections[group.type]
            val allLabel=if(group.label.startsWith("全部")) group.label else "全部${group.label}"
            addButton(choices,allLabel,selected.isNullOrBlank()).apply {
                tag="theater-filter:${group.type}:all"
                setOnClickListener {
                    theaterFocusTag=tag.toString()
                    if(theaterSelections.remove(group.type)!=null) { page=1; catalogFocus=""; tabState.remove(7); showCatalog(load=true) }
                }
            }
            group.options.forEach { option ->
                addButton(choices,option.label,selected==option.id).apply {
                    tag="theater-filter:${group.type}:${option.id}"
                    setOnClickListener {
                        theaterFocusTag=tag.toString()
                        if(selected!=option.id) {
                            if(group.type in setOf("category_dim_theme","category_dim_role","category_dim_epoch","creation_status")) {
                                listOf("category_dim_theme","category_dim_role","category_dim_epoch","creation_status").forEach { theaterSelections.remove(it) }
                            }
                            if(group.type=="channelGroupId") theaterSelections.remove("channelId")
                            theaterSelections[group.type]=option.id; page=1; catalogFocus=""; tabState.remove(7); showCatalog(load=true)
                        }
                    }
                }
            }
            scroll.addView(choices,ViewGroup.LayoutParams(-2,dp(40)))
            line.addView(scroll,lp(0,dp(40)).apply { weight=1f })
            host.addView(line)
        }

    }
/**
 * 在筛选头与海报网格合并进同一个 RecyclerView 后重新连线。
 * 必须在 ConcatAdapter 安装完成后执行，此时筛选头和首排海报才有真实屏幕坐标。
 */
internal fun MainActivity.wireTheaterFilterFocus(host:LinearLayout,recycler:androidx.recyclerview.widget.RecyclerView) {
    var wired=false
    lateinit var layoutListener:View.OnLayoutChangeListener

    fun connectAfterLayout() {
        if(wired || !recycler.isAttachedToWindow || !host.isAttachedToWindow ||
            recycler.width<=0 || recycler.height<=0 || host.width<=0 || host.height<=0) return

        val grid=recycler.layoutManager as? androidx.recyclerview.widget.GridLayoutManager ?: return
        fun collect(view:View,out:MutableList<View>) {
            if(view.tag?.toString()?.startsWith("theater-filter:")==true && view.isFocusable) out+=view
            if(view is ViewGroup) for(index in 0 until view.childCount) collect(view.getChildAt(index),out)
        }
        val rows=(0 until host.childCount).map { index ->
            mutableListOf<View>().also { collect(host.getChildAt(index),it) }
        }.filter { it.isNotEmpty() }
        if(rows.isEmpty()) return

        fun visibleCenterX(view:View):Float? {
            if(!view.isAttachedToWindow || view.width<=0 || view.height<=0) return null
            val rect=android.graphics.Rect()
            if(!view.getGlobalVisibleRect(rect) || rect.width()<=0 || rect.height()<=0) return null
            return (rect.left+rect.right)/2f
        }
        fun nearest(items:List<View>,x:Float):View? =
            items.mapNotNull { item -> visibleCenterX(item)?.let { item to it } }
                .minByOrNull { kotlin.math.abs(it.second-x) }?.first

        rows.forEachIndexed { index,items ->
            val previous=rows.getOrNull(index-1).orEmpty()
            val next=rows.getOrNull(index+1).orEmpty()
            items.forEachIndexed { itemIndex,item ->
                val x=visibleCenterX(item) ?: return@forEachIndexed
                item.nextFocusLeftId=items.getOrNull(itemIndex-1)?.id ?: item.id
                item.nextFocusRightId=items.getOrNull(itemIndex+1)?.id ?: item.id
                item.nextFocusUpId=nearest(previous,x)?.id ?: navForTab().id
                item.nextFocusDownId=nearest(next,x)?.id ?: recycler.id
            }
        }

        // ConcatAdapter 的 position 0 是完整筛选 Header，海报从 position 1 开始。
        // 首排卡片可能尚未 attach；持续监听其 attach，确保出现时立即补上向上焦点。
        val lastRow=rows.last()
        val columns=grid.spanCount
        fun wirePoster(view:View) {
            val position=recycler.getChildAdapterPosition(view)
            if(position !in 1..columns) return
            val x=visibleCenterX(view) ?: return
            view.nextFocusUpId=nearest(lastRow,x)?.id ?: navForTab().id
        }
        for(index in 0 until recycler.childCount) wirePoster(recycler.getChildAt(index))
        recycler.addOnChildAttachStateChangeListener(object:androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view:View) {
                view.post { if(view.isAttachedToWindow) wirePoster(view) }
            }
            override fun onChildViewDetachedFromWindow(view:View) = Unit
        })

        wired=true
        recycler.removeOnLayoutChangeListener(layoutListener)
        host.removeOnLayoutChangeListener(layoutListener)
    }

    layoutListener=View.OnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> connectAfterLayout() }
    recycler.post {
        connectAfterLayout()
        if(!wired) {
            recycler.addOnLayoutChangeListener(layoutListener)
            host.addOnLayoutChangeListener(layoutListener)
            // 再排一次，覆盖 RecyclerView 首次布局已完成但 Header 子树稍后完成测量的情况。
            recycler.post { connectAfterLayout() }
        }
    }
}

internal fun MainActivity.theaterFilterKey(type:ContentType?)=type?.name ?: "ALL"
internal fun MainActivity.theaterFilterSummary()=theaterSelections.entries.joinToString(" · ") { (key,value) ->
    theaterFiltersByType[theaterFilterKey(theaterContentType)].orEmpty().firstOrNull { it.type==key }?.options?.firstOrNull { it.id==value }?.label ?: value
}
