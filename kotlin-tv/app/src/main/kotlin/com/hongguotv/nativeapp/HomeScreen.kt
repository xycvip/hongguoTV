// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.*
import com.hongguotv.core.Series

/** Lets the vertical page container arbitrate gestures; this view only handles horizontal drags. */
class VerticalFriendlyHorizontalScrollView(context: Context): HorizontalScrollView(context) {
    private val touchSlop=ViewConfiguration.get(context).scaledTouchSlop
    private var downX=0f
    private var downY=0f

    override fun onInterceptTouchEvent(event: android.view.MotionEvent): Boolean {
        when(event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX=event.x
                downY=event.y
                // Clear any stale disallow-intercept request before a new gesture.
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx=kotlin.math.abs(event.x-downX)
                val dy=kotlin.math.abs(event.y-downY)
                if(dy>touchSlop && dy>dx*1.15f) {
                    // Do not claim vertical gestures: let the outer page ScrollView intercept.
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return false
                }
                // Intentionally do not disallow parent interception for horizontal gestures.
                // The vertical ScrollView naturally ignores predominantly horizontal movement,
                // while this HorizontalScrollView still handles and scrolls its own shelf.
            }
            android.view.MotionEvent.ACTION_UP,android.view.MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.onInterceptTouchEvent(event)
    }
}

/** Fixed text slots keep D-pad movement from resizing the content below the preview. */
class SeriesPreview(context: Context): LinearLayout(context) {
    private fun label(size: Float,color: Int)=TextView(context).apply { textSize=size; setTextColor(color); includeFontPadding=false }
    private val title=label(27f,TvStyle.text).apply { minLines=1; maxLines=1; ellipsize=TextUtils.TruncateAt.END; typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) }
    private val meta=label(12f,TvStyle.muted).apply { minLines=1; maxLines=1; ellipsize=TextUtils.TruncateAt.END }
    init {
        // 不再占用页面顶部的剧名预览区域，避免删除提示文字后留下整块空白。
        orientation=VERTICAL
        visibility=GONE
    }
    fun show(series: Series?,status: String) {
        // 未选中海报时保持信息区为空；选中海报后仅显示对应剧名和简介信息。
        val nextTitle=series?.title ?: ""
        val nextMeta=if(series==null) "" else listOf(status,series.badge,series.tags).flatMap { it.split('·') }.map(String::trim).filter { it.isNotBlank() && !it.all(Char::isDigit) }.distinct().joinToString("  /  ")
        if(title.text.toString()!=nextTitle) title.text=nextTitle
        if(meta.text.toString()!=nextMeta) meta.text=nextMeta
    }
}

/** Bounded horizontal shelves with stable D-pad selection and per-row scroll positions. */
class HomeScreen(
    context: Context,
    private val top: View,
    private val loadCover: (ImageView,String)->Unit,
    private val selected: (Series,String)->Unit,
    private val open: (Series,Boolean)->Unit,
    private val actions: (Series,View)->Unit,
    private val remember: (String)->Unit
): LinearLayout(context) {
    data class Entry(val series: Series,val label: String,val resume: Boolean=false,val progress: Int?=null)
    data class Shelf(val key: String,val title: String,val entries: List<Entry>,val more: (() -> Unit)?=null)
    data class Position(val focus: String,val shelf: String?,val column: Int,val row: Int,val vertical: Int,val horizontal: Map<String,Int>)
    private data class Card(val key: String,val view: LinearLayout,val image: ImageView?,val title: TextView,val label: TextView,val progress: ProgressBar?,var entry: Entry?,var pending: Boolean=false)
    private data class Row(val key: String,val block: LinearLayout,val horizontal: HorizontalScrollView,val cards: List<Card>)
    private val accent=TvStyle.accent
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private fun shape(focus: Boolean)=TvStyle.card(context,focus)
    private fun label(value: String,size: Float,color: Int=TvStyle.text)=TextView(context).apply { text=value; textSize=size; setTextColor(color); includeFontPadding=false }
    private val scroll=ScrollView(context).apply { isVerticalScrollBarEnabled=false; clipToPadding=false }
    private val contents=LinearLayout(context).apply { orientation=VERTICAL }
    private val rows=mutableListOf<Row>()
    private val cards=linkedMapOf<String,Card>()
    private var shelves=emptyList<Shelf>()
    private var footerMore: (() -> Unit)?=null
    private var moreButton: View?=null
    private var lastFocus=""
    private var lastRow: String?=null
    private var restoring=false
    private var revision=0
    val refresh=label("正在更新热门…",14f,TvStyle.muted).apply { id=View.generateViewId(); tag="home:refresh"; isFocusable=true; isFocusableInTouchMode=true; gravity=Gravity.CENTER; minHeight=dp(44); setPadding(dp(16),dp(10),dp(16),dp(10)); background=shape(false); setOnFocusChangeListener { _,f -> background=shape(f); setTextColor(if(f) TvStyle.text else TvStyle.muted); if(f) lastFocus=tag.toString() } }
    init { orientation=VERTICAL; scroll.addView(contents); addView(scroll,LayoutParams(-1,0,1f)) }

    fun capturePosition(): Position {
        val key=findFocus()?.tag?.toString()?.takeIf { it in cards || it=="home:refresh" || it=="home:more" } ?: lastFocus
        val row=rows.indexOfFirst { item -> item.cards.any { it.key==key } }
        val column=rows.getOrNull(row)?.cards?.indexOfFirst { it.key==key } ?: 0
        return Position(key,rows.getOrNull(row)?.key,column.coerceAtLeast(0),row.coerceAtLeast(0),scroll.scrollY,rows.associate { it.key to it.horizontal.scrollX })
    }

    /** Rebuild only this bounded shelf area, preserving the old card or its nearest neighbour. */
    fun updateShelves(next: List<Shelf>,requestFocus: Boolean=hasFocus()) {
        val position=capturePosition()
        render(next,position.focus,requestFocus,footerMore,position)
    }

    /** Update an existing card without replacing views or moving focus. Structural changes use updateShelves. */
    fun updateEntry(shelfKey: String,entry: Entry): Boolean {
        val card=cards["$shelfKey:${entry.series.id}"] ?: return false
        if(card.entry?.series?.cover!=entry.series.cover) { card.image?.setImageDrawable(null) }
        card.entry=entry; card.title.text=entry.series.title; card.label.text=entry.label
        card.progress?.apply { progress=entry.progress ?: 0; visibility=if(entry.progress==null) INVISIBLE else VISIBLE }
        card.view.contentDescription="${shelves.firstOrNull { it.key==shelfKey }?.title.orEmpty()}，${entry.series.title}，${entry.label}"
        shelves=shelves.map { shelf -> if(shelf.key==shelfKey) shelf.copy(entries=shelf.entries.map { if(it.series.id==entry.series.id) entry else it }) else shelf }
        if(lastFocus==card.key) refreshSelection()
        loadNearbySelection()
        return true
    }

    fun refreshSelection() { cards[lastFocus]?.entry?.let { selected(it.series,it.label) } }

    /** Retry visible cover requests after the owner cancels work for a page or lifecycle change. */
    fun reloadArtwork() {
        if (restoring) return
        // 刷新首页时重新请求当前已展示的所有货架封面，避免仅当前焦点附近有图。
        rows.forEach { row -> row.cards.forEach(::requestCover) }
    }

    fun render(shelves: List<Shelf>,focus: String,requestFocus: Boolean,more: (() -> Unit)?,position: Position?=null) {
        val previous=position ?: if(rows.isNotEmpty()) capturePosition() else null
        this.shelves=shelves; footerMore=more; revision++; val ticket=revision; restoring=true
        contents.removeAllViews(); rows.clear(); cards.clear(); moreButton=null; lastRow=null
        shelves.filter { it.entries.isNotEmpty() || it.more!=null }.forEach { shelf ->
            val block=LinearLayout(context).apply { orientation=VERTICAL }
            // 随机推荐区保留居中的操作卡片，不再额外显示重复的栏目标题。
            if(shelf.key!="random") {
                val heading=LinearLayout(context).apply { orientation=HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(2),dp(4),dp(2),dp(9)) }
                heading.addView(View(context).apply { background=TvStyle.shape(context,accent,radius=2) },LayoutParams(dp(3),dp(14)).apply { rightMargin=dp(8) })
                heading.addView(label(shelf.title,16f).apply { setTypeface(null,Typeface.BOLD) },LayoutParams(0,-2,1f))
                heading.addView(label("左右选剧  ·  上下切换分区",10f,TvStyle.muted))
                block.addView(heading)
            }
            val horizontal=VerticalFriendlyHorizontalScrollView(context).apply { isHorizontalScrollBarEnabled=false; clipToPadding=false; setPadding(dp(2),dp(2),dp(2),dp(2)); isFillViewport=shelf.key=="random" }
            val line=LinearLayout(context).apply { orientation=HORIZONTAL; gravity=if(shelf.key=="random") Gravity.CENTER else Gravity.TOP }
            horizontal.addView(line, if(shelf.key=="random") LayoutParams(-1,-2) else LayoutParams(-2,-2)); block.addView(horizontal)
            val rowCards=mutableListOf<Card>()
            fun addCard(entry: Entry?,moreAction: (() -> Unit)?=null) {
                val width=if(shelf.key=="random") dp(320)
                    else ((resources.displayMetrics.widthPixels*.9f-dp(70))/6).toInt().coerceAtLeast(dp(100))
                val key=if(entry!=null) "${shelf.key}:${entry.series.id}" else "${shelf.key}:more"
                val view=LinearLayout(context).apply { orientation=VERTICAL; id=View.generateViewId(); tag=key; isFocusable=true; isFocusableInTouchMode=true; setPadding(dp(4),dp(4),dp(4),dp(8)); background=shape(false) }
                val image=if(entry!=null) ImageView(context).apply { scaleType=ImageView.ScaleType.FIT_CENTER; importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO } else null
                val posterHeight=if(shelf.key=="random") dp(76) else ((width-dp(8))/TvStyle.POSTER_ASPECT).toInt()
                if(image!=null) {
                    view.addView(image,LayoutParams(-1,posterHeight))
                } else {
                    // “查看全部”使用完整的探索卡片，不再是空白海报框里孤零零的箭头。
                    val moreTile=LinearLayout(context).apply {
                        orientation=VERTICAL
                        gravity=Gravity.CENTER
                        setPadding(dp(8),dp(10),dp(8),dp(10))
                        background=android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                            intArrayOf(TvStyle.surface,TvStyle.background)
                        ).apply {
                            cornerRadius=dp(8).toFloat()
                            setStroke(dp(1),TvStyle.outline)
                        }
                    }
                    if(shelf.key=="random") {
                        moreTile.orientation=HORIZONTAL
                        moreTile.gravity=Gravity.CENTER
                        moreTile.addView(label("✦",25f,accent).apply {
                            gravity=Gravity.CENTER
                            setPadding(0,0,dp(14),0)
                        })
                        moreTile.addView(label("不知道看什么·点一下",16f,TvStyle.text).apply {
                            gravity=Gravity.CENTER
                            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
                        })
                        moreTile.addView(label("  ›",20f,accent).apply { gravity=Gravity.CENTER })
                    } else {
                        moreTile.addView(label("✦",30f,accent).apply {
                            gravity=Gravity.CENTER
                            setPadding(0,0,0,dp(8))
                        })
                        moreTile.addView(label("探索更多",14f,TvStyle.text).apply {
                            gravity=Gravity.CENTER
                            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
                        })
                        moreTile.addView(label("发现精彩剧集  ›",10f,TvStyle.muted).apply {
                            gravity=Gravity.CENTER
                            setPadding(0,dp(6),0,0)
                        })
                    }
                    view.addView(moreTile,LayoutParams(-1,posterHeight))
                }
                val title=label(entry?.series?.title ?: "查看全部",14f).apply { minLines=2; maxLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(5),dp(3),0) }
                val badge=label(entry?.label ?: shelf.title,12f,TvStyle.muted).apply { minLines=1; maxLines=1; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(2),0,0) }
                val progress=ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal).apply { max=100; progress=entry?.progress ?: 0; visibility=if(entry?.progress==null) INVISIBLE else VISIBLE; progressTintList=android.content.res.ColorStateList.valueOf(accent) }
                if(shelf.key!="random") { view.addView(title); view.addView(badge); view.addView(progress,LayoutParams(-1,dp(3)).apply { topMargin=dp(4) }) }
                val card=Card(key,view,image,title,badge,progress,entry)
                view.contentDescription=entry?.let { "${shelf.title}，${it.series.title}，${it.label}" } ?: "查看全部${shelf.title}"
                view.setOnClickListener { lastFocus=key; remember(key); card.entry?.let { open(it.series,it.resume) } ?: moreAction?.invoke() }
                if(entry!=null) {
                    view.setOnLongClickListener { lastFocus=key; remember(key); card.entry?.let { actions(it.series,view) }; true }
                    view.setOnKeyListener { _,code,event -> if(code==KeyEvent.KEYCODE_MENU) { if(event.action==KeyEvent.ACTION_UP) { lastFocus=key; remember(key); card.entry?.let { actions(it.series,view) } }; true } else false }
                }
                view.setOnFocusChangeListener { _,focused ->
                    view.background=shape(focused)
                    if(focused) {
                        lastFocus=key; remember(key); card.entry?.let { selected(it.series,it.label) }
                        if(!restoring) {
                            loadNearbySelection()
                            if(lastRow!=shelf.key) scroll.post { if(revision==ticket && view.hasFocus()) reveal(block) }
                        }
                        lastRow=shelf.key
                    }
                }
                line.addView(view,LayoutParams(width,if(shelf.key=="random") dp(104) else -1).apply { rightMargin=dp(14) }); rowCards+=card; cards[key]=card
            }
            // 所有片单先展示内容，“查看全部”统一放在对应栏目末尾。
            shelf.entries.forEach { addCard(it) }
            shelf.more?.let { action -> addCard(null,action) }
            contents.addView(block,LayoutParams(-1,-2).apply { bottomMargin=dp(16) }); rows+=Row(shelf.key,block,horizontal,rowCards)
        }
        if(cards.isEmpty()) contents.addView(label("还没有本机记录，热门剧加载后可选择观看。",15f).apply { setPadding(0,dp(16),0,dp(16)) })
        // 刷新按钮由首页顶部快捷操作栏承载；底部不再重复放置“刷新热门/更多热门”。
        moreButton=null
        rows.forEachIndexed { i,row -> row.cards.forEachIndexed { j,card ->
            card.view.nextFocusLeftId=row.cards[(j-1).coerceAtLeast(0)].view.id; card.view.nextFocusRightId=row.cards[(j+1).coerceAtMost(row.cards.lastIndex)].view.id
            card.view.nextFocusUpId=if(i==0) top.id else rows[i-1].cards[j.coerceAtMost(rows[i-1].cards.lastIndex)].view.id
            card.view.nextFocusDownId=if(i==rows.lastIndex) card.view.id else rows[i+1].cards[j.coerceAtMost(rows[i+1].cards.lastIndex)].view.id
        } }
        top.nextFocusDownId=firstId()
        val preferred=focus.ifBlank { previous?.focus.orEmpty() }
        val target=target(preferred,previous)
        if(requestFocus) target.requestFocus()
        post layout@{
            if(revision!=ticket) return@layout
            fitArtwork()
            post restore@{
                if(revision!=ticket) return@restore
                if(previous!=null) restoreOffsets(previous)
                restoring=false
                if(requestFocus && previous==null) rows.firstOrNull { row -> row.cards.any { it.view===target } }?.let { revealSelection(it) }
                loadNearbySelection()
            }
        }
    }

    fun restorePosition(position: Position,requestFocus: Boolean=true) {
        restoring=true
        if(requestFocus) target(position.focus,position).requestFocus()
        post { restoreOffsets(position); restoring=false; loadNearbySelection() }
    }

    private fun target(key: String,position: Position?): View {
        if(key=="home:refresh") return top
        if(key=="home:more") moreButton?.let { return it }
        cards[key]?.let { return it.view }
        if(position!=null) {
            val row=rows.firstOrNull { it.key==position.shelf } ?: rows.getOrNull(position.row.coerceAtMost(rows.lastIndex))
            row?.cards?.getOrNull(position.column.coerceAtMost(row.cards.lastIndex))?.let { return it.view }
        }
        return cards.values.firstOrNull()?.view ?: refresh
    }
    private fun restoreOffsets(position: Position) {
        rows.forEach { row -> row.horizontal.scrollTo(position.horizontal[row.key] ?: 0,0) }
        scroll.scrollTo(0,position.vertical)
        // A removed row can leave the nearest surviving card outside the old viewport.
        rows.firstOrNull { row -> row.cards.any { it.view.hasFocus() } }?.let(::revealSelection)
    }
    private fun revealSelection(row: Row) {
        row.cards.firstOrNull { it.view.hasFocus() }?.view?.let { card ->
            val horizontal=row.horizontal
            val left=horizontal.scrollX
            val right=left+horizontal.width-horizontal.paddingRight
            val target=when { card.left<left -> card.left; card.right>right -> card.right-horizontal.width+horizontal.paddingRight; else -> left }
            if(target!=left) horizontal.scrollTo(target.coerceAtLeast(0),0)
        }
        reveal(row.block,false)
    }
    private fun reveal(block: View,smooth: Boolean=true) {
        val target=if(smooth) block.top else when { block.top<scroll.scrollY -> block.top; block.bottom>scroll.scrollY+scroll.height -> if(block.height>=scroll.height) block.top else block.bottom-scroll.height; else -> scroll.scrollY }.coerceAtLeast(0)
        if(target!=scroll.scrollY) { if(smooth) scroll.smoothScrollTo(0,target) else scroll.scrollTo(0,target) }
    }
    private fun loadNearbySelection() {
        if(restoring) return
        val row=rows.indexOfFirst { it.cards.any { card -> card.key==lastFocus } }.coerceAtLeast(0)
        val column=rows.getOrNull(row)?.cards?.indexOfFirst { it.key==lastFocus }?.coerceAtLeast(0) ?: 0
        rows.getOrNull(row)?.cards?.let { items -> for(index in (column-1).coerceAtLeast(0)..(column+6).coerceAtMost(items.lastIndex)) requestCover(items[index]) }
        rows.getOrNull(row+1)?.cards?.take(6)?.forEach(::requestCover)
    }
    private fun requestCover(card: Card) {
        val image=card.image ?: return
        if(card.pending || card.entry?.series?.cover.isNullOrBlank()) return
        val ticket=revision
        fun start() {
            card.pending=false
            if(ticket!=revision || !image.isAttachedToWindow || image.width<=0 || image.height<=0) return
            loadCover(image,card.entry!!.series.cover)
        }
        if(image.isLaidOut && !image.isLayoutRequested) start()
        else {
            card.pending=true
            image.addOnLayoutChangeListener(object: View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View,left: Int,top: Int,right: Int,bottom: Int,oldLeft: Int,oldTop: Int,oldRight: Int,oldBottom: Int) {
                    image.removeOnLayoutChangeListener(this); start()
                }
            })
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post { reloadArtwork() } }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) { super.onSizeChanged(w,h,oldw,oldh); if(w!=oldw || h!=oldh) post { fitArtwork(); loadNearbySelection() } }
    private fun fitArtwork() {
        if(height<=0) return
        rows.forEach { row ->
            // 随机推荐操作卡片使用独立固定尺寸，不参与海报高度自适应，避免被按海报宽高比拉成方形。
            if(row.key=="random") return@forEach
            val textHeight=row.cards.maxOf { item -> item.view.paddingTop+item.view.paddingBottom+(1 until item.view.childCount).sumOf { child ->
                val view=item.view.getChildAt(child); val params=view.layoutParams as LayoutParams
                view.measuredHeight+params.topMargin+params.bottomMargin
            } }
            val desired=((row.cards.first().view.width-dp(8))/TvStyle.POSTER_ASPECT).toInt()
            val imageHeight=minOf(desired,(height-row.block.getChildAt(0).measuredHeight-textHeight-dp(8)).coerceAtLeast(dp(48)))
            row.cards.forEach { card -> card.view.getChildAt(0).let { artwork -> if(artwork.layoutParams.height!=imageHeight) artwork.layoutParams=artwork.layoutParams.apply { height=imageHeight } } }
        }
    }
    fun firstId()=rows.firstOrNull()?.cards?.firstOrNull()?.view?.id ?: top.id
}
